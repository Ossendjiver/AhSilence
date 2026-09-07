package com.bted.ahsilence.domain.model

enum class CalibrationStatus {
    REQUIRED,
    CALIBRATING,
    READY,
    FAILED
}

data class CalibrationProfile(
    val delaySamples: Int,
    val pathGain: Float,
    val quality: Float,
    val sampleRate: Int,
    val outputDeviceId: Int,
    val inputDeviceId: Int,
    val calibratedAtEpochMs: Long
) {
    val delayMs: Float
        get() = if (sampleRate > 0) delaySamples * 1000f / sampleRate else 0f
}

data class CalibrationResult(
    val success: Boolean,
    val profile: CalibrationProfile? = null,
    val message: String = ""
)

data class EngineDiagnostics(
    val running: Boolean = false,
    val outputRoute: String = "Not opened",
    val inputRoute: String = "Not opened",
    val outputDeviceId: Int = 0,
    val inputDeviceId: Int = 0,
    val sampleRate: Int = 0,
    val performanceMode: String = "Unknown",
    val sharingMode: String = "Unknown",
    val framesPerBurst: Int = 0,
    val xRunCount: Int = 0,
    val inputRms: Float = 0f,
    val outputRms: Float = 0f,
    val calibrationDelayMs: Float = 0f,
    val calibrationQuality: Float = 0f,
    val lastError: String = "None"
)
