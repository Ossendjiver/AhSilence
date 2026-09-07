package com.bted.ahsilence.core.di

import android.content.Context
import com.bted.ahsilence.data.calibration.CalibrationStore
import com.bted.ahsilence.data.engine.NativeAudioDSP
import com.bted.ahsilence.domain.port.AudioEngine

object AudioEngineLocator {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        CalibrationStore.initialize(context)
    }

    val engine: AudioEngine by lazy {
        NativeAudioDSP(requireNotNull(appContext) { "AudioEngineLocator.initialize(context) must be called first" })
    }
}
