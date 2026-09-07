package com.bted.ahsilence

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.bted.ahsilence.core.constants.AudioConstants
import com.bted.ahsilence.core.di.AudioEngineLocator
import com.bted.ahsilence.data.service.ActiveHumService
import com.bted.ahsilence.presentation.ControlViewModel
import com.bted.ahsilence.ui.screen.DashboardScreen
import com.bted.ahsilence.ui.theme.AhSilenceTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ControlViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (audioGranted) viewModel.beginStartup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AudioEngineLocator.initialize(applicationContext)
        requestRequiredPermissions()

        setContent {
            AhSilenceTheme {
                val uiState by viewModel.state.collectAsState()
                DashboardScreen(
                    state = uiState,
                    onPhaseChanged = viewModel::updatePhase,
                    onAmplitudeChanged = viewModel::updateAmplitude,
                    onRecalibrate = viewModel::recalibrate,
                    onTogglePower = {
                        if (uiState.isEmitting) {
                            stopAcousticService()
                            viewModel.markStopped()
                        } else if (viewModel.canStart()) {
                            startAcousticService()
                            viewModel.markStarted()
                        }
                    }
                )
            }
        }
    }

    private fun startAcousticService() {
        val intent = Intent(this, ActiveHumService::class.java).apply { action = AudioConstants.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopAcousticService() {
        val intent = Intent(this, ActiveHumService::class.java).apply { action = AudioConstants.ACTION_STOP }
        startService(intent)
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            viewModel.beginStartup()
        }
    }
}
