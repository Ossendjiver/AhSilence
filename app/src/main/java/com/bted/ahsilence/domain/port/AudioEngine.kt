package com.bted.ahsilence.domain.port

import com.bted.ahsilence.domain.model.CalibrationProfile
import com.bted.ahsilence.domain.model.CalibrationResult
import com.bted.ahsilence.domain.model.EngineDiagnostics

interface AudioEngine {
    suspend fun captureAndAnalyzeEnv(durationSeconds: Int): Float
    suspend fun calibrate(): CalibrationResult
    fun applyCalibration(profile: CalibrationProfile)
    fun startAntiNoiseEmission(): Boolean
    fun updateParameters(amplitudePercentage: Float, phaseDegrees: Float)
    fun diagnostics(): EngineDiagnostics
    fun stop()
    fun release() {}
}
