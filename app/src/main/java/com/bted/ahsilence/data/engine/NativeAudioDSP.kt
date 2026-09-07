package com.bted.ahsilence.data.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.bted.ahsilence.core.logging.AppLogger
import com.bted.ahsilence.domain.model.CalibrationProfile
import com.bted.ahsilence.domain.model.CalibrationResult
import com.bted.ahsilence.domain.model.EngineDiagnostics
import com.bted.ahsilence.domain.port.AudioEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class NativeAudioDSP(context: Context) : AudioEngine {

    private companion object {
        init { System.loadLibrary("ancengine") }
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val preferredSampleRate = 48000
    private val fftBufferSize = 8192
    private val fftEngine = FastFourier(fftBufferSize, preferredSampleRate)
    private var nativeHandle: Long = 0L
    @Volatile private var lastError: String = "None"

    @SuppressLint("MissingPermission")
    override suspend fun captureAndAnalyzeEnv(durationSeconds: Int): Float = withContext(Dispatchers.IO) {
        ensureEngineCreated()
        val minBufferSize = AudioRecord.getMinBufferSize(
            preferredSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            preferredSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize.coerceAtLeast(fftBufferSize * 2)
        )
        val readBuffer = ShortArray(fftBufferSize)
        record.startRecording()
        delay(durationSeconds * 1000L)
        var read = 0
        var zeroReadCount = 0
        while (read < fftBufferSize) {
            val result = record.read(readBuffer, read, fftBufferSize - read)
            when {
                result < 0 -> { lastError = "Microphone read failed ($result)"; break }
                result == 0 -> {
                    zeroReadCount++
                    if (zeroReadCount > 50) { lastError = "Microphone returned no audio"; break }
                }
                else -> { read += result; zeroReadCount = 0 }
            }
        }
        record.stop()
        record.release()
        val detectedFrequency = fftEngine.extractDominantFrequency(readBuffer)
        nativeSetFrequency(nativeHandle, detectedFrequency)
        detectedFrequency
    }

    override suspend fun calibrate(): CalibrationResult = withContext(Dispatchers.IO) {
        ensureEngineCreated()
        nativeStop(nativeHandle)
        if (!nativeStart(nativeHandle)) {
            lastError = "Could not open full-duplex audio stream for calibration"
            return@withContext CalibrationResult(false, message = lastError)
        }
        nativeBeginCalibration(nativeHandle)
        delay(1250L)
        val values = nativeFinishCalibration(nativeHandle)
        nativeStop(nativeHandle)
        val success = values.getOrNull(0) == 1f
        val profile = if (success) {
            CalibrationProfile(
                delaySamples = values[1].toInt(),
                pathGain = values[2],
                quality = values[3],
                sampleRate = values[4].toInt(),
                outputDeviceId = values[5].toInt(),
                inputDeviceId = values[6].toInt(),
                calibratedAtEpochMs = System.currentTimeMillis()
            )
        } else null
        if (!success) lastError = "Calibration probe was not detected clearly enough"
        CalibrationResult(success, profile, if (success) "Calibration complete" else lastError)
    }

    override fun applyCalibration(profile: CalibrationProfile) {
        ensureEngineCreated()
        nativeApplyCalibration(nativeHandle, profile.delaySamples, profile.pathGain, profile.quality)
    }

    override fun startAntiNoiseEmission(): Boolean {
        ensureEngineCreated()
        val started = nativeStart(nativeHandle)
        if (!started) lastError = "Native audio stream failed to start"
        return started
    }

    override fun updateParameters(amplitudePercentage: Float, phaseDegrees: Float) {
        if (nativeHandle != 0L) nativeUpdateParameters(nativeHandle, amplitudePercentage, phaseDegrees)
    }

    override fun diagnostics(): EngineDiagnostics {
        if (nativeHandle == 0L) return EngineDiagnostics(lastError = lastError)
        val d = nativeDiagnostics(nativeHandle)
        val outputId = d.getOrElse(6) { 0f }.toInt()
        val inputId = d.getOrElse(7) { 0f }.toInt()
        return EngineDiagnostics(
            running = d.getOrElse(0) { 0f } == 1f,
            sampleRate = d.getOrElse(1) { 0f }.toInt(),
            performanceMode = when (d.getOrElse(2) { 0f }.toInt()) {
                12 -> "Low latency"
                11 -> "Power saving"
                else -> "None/default"
            },
            sharingMode = if (d.getOrElse(3) { 1f }.toInt() == 0) "Exclusive" else "Shared",
            framesPerBurst = d.getOrElse(4) { 0f }.toInt(),
            xRunCount = d.getOrElse(5) { 0f }.toInt(),
            outputDeviceId = outputId,
            inputDeviceId = inputId,
            outputRoute = deviceName(outputId, false),
            inputRoute = deviceName(inputId, true),
            inputRms = d.getOrElse(8) { 0f },
            outputRms = d.getOrElse(9) { 0f },
            calibrationDelayMs = d.getOrElse(10) { 0f },
            calibrationQuality = d.getOrElse(11) { 0f },
            lastError = lastError
        )
    }

    override fun stop() {
        if (nativeHandle != 0L) nativeStop(nativeHandle)
    }

    override fun release() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private fun deviceName(id: Int, input: Boolean): String {
        if (id == 0) return "Unknown/default"
        val flag = if (input) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
        val device = audioManager.getDevices(flag).firstOrNull { it.id == id }
        return device?.let { "${typeName(it.type)}${it.productName?.let { name -> " · $name" } ?: ""}" }
            ?: "Device $id"
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
        else -> "Audio device"
    }

    private fun ensureEngineCreated() {
        if (nativeHandle == 0L) nativeHandle = nativeCreate(preferredSampleRate)
    }

    private external fun nativeCreate(sampleRate: Int): Long
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeSetFrequency(handle: Long, frequencyHz: Float)
    private external fun nativeUpdateParameters(handle: Long, gainPercent: Float, phaseDegrees: Float)
    private external fun nativeBeginCalibration(handle: Long)
    private external fun nativeFinishCalibration(handle: Long): FloatArray
    private external fun nativeApplyCalibration(handle: Long, delaySamples: Int, gain: Float, quality: Float)
    private external fun nativeDiagnostics(handle: Long): FloatArray
    private external fun nativeDestroy(handle: Long)
}
