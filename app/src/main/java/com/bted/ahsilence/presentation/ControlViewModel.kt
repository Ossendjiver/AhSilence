package com.bted.ahsilence.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bted.ahsilence.core.constants.AudioConstants
import com.bted.ahsilence.core.di.AudioEngineLocator
import com.bted.ahsilence.data.calibration.CalibrationStore
import com.bted.ahsilence.domain.model.AcousticState
import com.bted.ahsilence.domain.model.CalibrationStatus
import com.bted.ahsilence.domain.port.AudioEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ControlViewModel : ViewModel() {
    private val engine: AudioEngine = AudioEngineLocator.engine
    private val _state = MutableStateFlow(AcousticState())
    val state: StateFlow<AcousticState> = _state.asStateFlow()
    private var startupStarted = false

    init {
        viewModelScope.launch {
            while (true) {
                _state.update { it.copy(diagnostics = engine.diagnostics()) }
                delay(500)
            }
        }
    }

    fun beginStartup() {
        if (startupStarted) return
        startupStarted = true
        viewModelScope.launch(Dispatchers.IO) {
            val frequency = engine.captureAndAnalyzeEnv(AudioConstants.AMBIENT_SCAN_DURATION_SECONDS)
            _state.update { it.copy(detectedFrequencyHz = frequency) }

            val saved = CalibrationStore.load()
            if (saved != null) {
                engine.applyCalibration(saved)
                _state.update {
                    it.copy(
                        calibrationStatus = CalibrationStatus.READY,
                        calibrationProfile = saved,
                        calibrationMessage = "Saved calibration loaded",
                        startupComplete = true
                    )
                }
            } else {
                runCalibration(firstRun = true)
            }
        }
    }

    fun recalibrate() {
        viewModelScope.launch(Dispatchers.IO) {
            engine.stop()
            _state.update { it.copy(isEmitting = false) }
            runCalibration(firstRun = false)
        }
    }

    private suspend fun runCalibration(firstRun: Boolean) {
        _state.update {
            it.copy(
                calibrationStatus = CalibrationStatus.CALIBRATING,
                calibrationMessage = if (firstRun) "First-run audio path calibration…" else "Recalibrating audio path…"
            )
        }
        val result = engine.calibrate()
        if (result.success && result.profile != null) {
            CalibrationStore.save(result.profile)
            engine.applyCalibration(result.profile)
            _state.update {
                it.copy(
                    calibrationStatus = CalibrationStatus.READY,
                    calibrationProfile = result.profile,
                    calibrationMessage = "Calibration complete",
                    startupComplete = true,
                    diagnostics = engine.diagnostics()
                )
            }
        } else {
            _state.update {
                it.copy(
                    calibrationStatus = CalibrationStatus.FAILED,
                    calibrationMessage = result.message.ifBlank { "Calibration failed" },
                    startupComplete = true,
                    diagnostics = engine.diagnostics()
                )
            }
        }
    }

    fun updateAmplitude(percentage: Float) {
        _state.update { it.copy(amplitudePercentage = percentage) }
        engine.updateParameters(percentage, _state.value.phaseDegrees)
    }

    fun updatePhase(degrees: Float) {
        _state.update { it.copy(phaseDegrees = degrees) }
        engine.updateParameters(_state.value.amplitudePercentage, degrees)
    }

    fun canStart(): Boolean = _state.value.calibrationStatus == CalibrationStatus.READY

    fun markStarted() {
        _state.update { it.copy(isEmitting = true) }
    }

    fun markStopped() {
        engine.stop()
        _state.update { it.copy(isEmitting = false, diagnostics = engine.diagnostics()) }
    }

    override fun onCleared() {
        engine.stop()
        engine.release()
        super.onCleared()
    }
}
