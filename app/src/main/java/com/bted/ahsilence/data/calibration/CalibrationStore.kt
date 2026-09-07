package com.bted.ahsilence.data.calibration

import android.content.Context
import com.bted.ahsilence.domain.model.CalibrationProfile

object CalibrationStore {
    private const val PREFS = "ahsilence_calibration"
    private const val KEY_VALID = "valid"
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun load(): CalibrationProfile? {
        if (!::appContext.isInitialized) return null
        val p = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_VALID, false)) return null
        return CalibrationProfile(
            delaySamples = p.getInt("delaySamples", 0),
            pathGain = p.getFloat("pathGain", 1f),
            quality = p.getFloat("quality", 0f),
            sampleRate = p.getInt("sampleRate", 0),
            outputDeviceId = p.getInt("outputDeviceId", 0),
            inputDeviceId = p.getInt("inputDeviceId", 0),
            calibratedAtEpochMs = p.getLong("calibratedAt", 0L)
        )
    }

    fun save(profile: CalibrationProfile) {
        if (!::appContext.isInitialized) return
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VALID, true)
            .putInt("delaySamples", profile.delaySamples)
            .putFloat("pathGain", profile.pathGain)
            .putFloat("quality", profile.quality)
            .putInt("sampleRate", profile.sampleRate)
            .putInt("outputDeviceId", profile.outputDeviceId)
            .putInt("inputDeviceId", profile.inputDeviceId)
            .putLong("calibratedAt", profile.calibratedAtEpochMs)
            .apply()
    }

    fun clear() {
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
