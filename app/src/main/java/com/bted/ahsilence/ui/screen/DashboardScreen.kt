package com.bted.ahsilence.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bted.ahsilence.domain.model.AcousticState
import com.bted.ahsilence.domain.model.CalibrationStatus
import com.bted.ahsilence.ui.component.CalibratorRing
import com.bted.ahsilence.ui.component.GainSlider

@Composable
fun DashboardScreen(
    state: AcousticState,
    onPhaseChanged: (Float) -> Unit,
    onAmplitudeChanged: (Float) -> Unit,
    onTogglePower: () -> Unit,
    onRecalibrate: () -> Unit
) {
    var isProMode by remember { mutableStateOf(false) }
    val bg = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val border = MaterialTheme.colorScheme.surfaceVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = MaterialTheme.colorScheme.onBackground
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier.fillMaxSize().background(bg).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = when (state.calibrationStatus) {
                    CalibrationStatus.CALIBRATING -> "CALIBRATING"
                    CalibrationStatus.READY -> if (state.isEmitting) "SILENCING ACTIVE" else "READY"
                    CalibrationStatus.FAILED -> "CALIBRATION FAILED"
                    CalibrationStatus.REQUIRED -> "CALIBRATION REQUIRED"
                },
                color = if (state.calibrationStatus == CalibrationStatus.READY) accent else muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = if (isProMode) "PRO" else "PRO",
                color = if (isProMode) accent else muted,
                modifier = Modifier.clickable { isProMode = !isProMode }.padding(6.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (state.detectedFrequencyHz > 0f) String.format("%.1f", state.detectedFrequencyHz) else "--",
                color = text,
                fontSize = 58.sp,
                fontWeight = FontWeight.Light
            )
            Text(" Hz", color = muted, fontSize = 20.sp, modifier = Modifier.padding(bottom = 9.dp))
        }

        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(surface).border(1.dp, border, RoundedCornerShape(12.dp)).padding(14.dp)
        ) {
            Column {
                Text(state.calibrationMessage, color = text, fontSize = 13.sp)
                val p = state.calibrationProfile
                if (p != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Path ${String.format("%.1f", p.delayMs)} ms · quality ${String.format("%.0f", p.quality * 100)}%",
                        color = muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.calibrationStatus == CalibrationStatus.CALIBRATING) "CALIBRATION IN PROGRESS" else "RECALIBRATE",
                    color = if (state.calibrationStatus == CalibrationStatus.CALIBRATING) muted else accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = if (state.calibrationStatus == CalibrationStatus.CALIBRATING) Modifier else Modifier.clickable { onRecalibrate() }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxHeight().weight(0.35f).clip(RoundedCornerShape(12.dp))
                    .background(surface).border(1.dp, border, RoundedCornerShape(12.dp)).padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("GAIN", color = muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    GainSlider(state.amplitudePercentage, onAmplitudeChanged, Modifier.weight(1f))
                    Text("${state.amplitudePercentage.toInt()}%", color = text, fontSize = 12.sp)
                }
            }
            Box(
                modifier = Modifier.fillMaxHeight().weight(0.65f).clip(RoundedCornerShape(12.dp))
                    .background(surface).border(1.dp, border, RoundedCornerShape(12.dp)).padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PHASE TRIM ${state.phaseDegrees.toInt()}°", color = muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    CalibratorRing(state.phaseDegrees, onPhaseChanged, Modifier.fillMaxWidth())
                }
            }
        }

        if (isProMode) {
            Spacer(Modifier.height(12.dp))
            val d = state.diagnostics
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(surface).border(1.dp, border, RoundedCornerShape(12.dp)).padding(12.dp)
            ) {
                Column {
                    Text("DIAGNOSTICS", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("DSP: ${if (d.running) "RUNNING" else "STOPPED"} · ${d.sampleRate} Hz · ${d.performanceMode} · ${d.sharingMode}", color = text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Output: ${d.outputRoute} [${d.outputDeviceId}]", color = muted, fontSize = 11.sp)
                    Text("Input: ${d.inputRoute} [${d.inputDeviceId}]", color = muted, fontSize = 11.sp)
                    Text("I/O RMS: ${String.format("%.4f", d.inputRms)} / ${String.format("%.4f", d.outputRms)} · XRuns ${d.xRunCount}", color = muted, fontSize = 11.sp)
                    Text("Cal path: ${String.format("%.1f", d.calibrationDelayMs)} ms · ${String.format("%.0f", d.calibrationQuality * 100)}%", color = muted, fontSize = 11.sp)
                    if (d.lastError != "None") Text("Last error: ${d.lastError}", color = Color.Red, fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        val canStart = state.calibrationStatus == CalibrationStatus.READY
        Box(
            modifier = Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(8.dp))
                .background(if (state.isEmitting) accent.copy(alpha = 0.1f) else Color.Transparent)
                .border(BorderStroke(1.dp, if (state.isEmitting) accent else border), RoundedCornerShape(8.dp))
                .clickable(enabled = state.isEmitting || canStart) { onTogglePower() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    state.isEmitting -> "STOP"
                    !canStart -> "CALIBRATE TO ENABLE"
                    else -> "START SILENCE"
                },
                color = if (state.isEmitting || canStart) accent else muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )
        }
    }
}
