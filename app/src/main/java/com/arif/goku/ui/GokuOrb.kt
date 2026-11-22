package com.arif.goku.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arif.goku.voice.GokuVoiceManager
import com.arif.goku.voice.VoiceEngineState
import com.example.R
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.CrimsonEmergency
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.GokuAmber
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PlasmaBlue

/**
 * Animated Jarvis-style central Goku Energy Orb with rotating HUD rings,
 * live mic RMS reactivity, and 6-second Continuous Listening countdown arc.
 */
@Composable
fun GokuOrb(
    voiceState: VoiceEngineState,
    isThinking: Boolean,
    audioLevel: Float,
    continuousSecondsLeft: Int,
    emergencyActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    orbSize: Dp = 186.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "goku_orb_transition")

    val outerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isThinking) 2400 else 11000,
                easing = LinearEasing
            )
        ),
        label = "outer_rotation"
    )

    val innerRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isThinking) 1900 else 7500,
                easing = LinearEasing
            )
        ),
        label = "inner_rotation"
    )

    val idleBreath by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_breath"
    )

    val animatedAudio by animateFloatAsState(
        targetValue = audioLevel.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 350f),
        label = "audio_level"
    )

    val primaryGlow = when {
        emergencyActive -> CrimsonEmergency
        isThinking -> PlasmaBlue
        voiceState == VoiceEngineState.SPEAKING -> GokuAmber
        voiceState == VoiceEngineState.CONTINUOUS_LISTENING -> EmeraldOnline
        voiceState == VoiceEngineState.LISTENING -> ArcCyan
        else -> ArcCyan
    }

    val secondaryGlow = when {
        emergencyActive -> GokuAmber
        voiceState == VoiceEngineState.SPEAKING -> ArcCyan
        voiceState == VoiceEngineState.CONTINUOUS_LISTENING -> ArcCyan
        isThinking -> ArcCyan
        else -> GokuAmber
    }

    val orbDescription = stringResource(R.string.cd_goku_orb)
    val stateLabel = when {
        isThinking -> stringResource(R.string.orb_thinking)
        voiceState == VoiceEngineState.SPEAKING -> stringResource(R.string.orb_speaking)
        voiceState == VoiceEngineState.CONTINUOUS_LISTENING ->
            "Continuous Mic • ${continuousSecondsLeft}s"
        voiceState == VoiceEngineState.LISTENING -> stringResource(R.string.orb_listening)
        else -> stringResource(R.string.orb_tap_to_talk)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(orbSize)
                .clip(CircleShape)
                .semantics { contentDescription = "$orbDescription — $stateLabel" }
                .testTag("goku_orb_button")
                .clickable(
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, color = primaryGlow),
                    onClick = onClick
                )
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = size.minDimension / 2f
                val pulseBoost = if (voiceState == VoiceEngineState.LISTENING ||
                    voiceState == VoiceEngineState.SPEAKING
                ) {
                    1f + animatedAudio * 0.12f
                } else {
                    idleBreath
                }

                // Ambient radial plasma aura
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = 0.34f),
                            secondaryGlow.copy(alpha = 0.14f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = maxRadius * pulseBoost
                    ),
                    radius = maxRadius,
                    center = center
                )

                // Outer rotating segmented HUD arc ring
                rotate(degrees = outerRotation, pivot = center) {
                    val arcRadius = maxRadius * 0.87f
                    val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
                    val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
                    val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)

                    for (i in 0 until 3) {
                        drawArc(
                            color = primaryGlow.copy(alpha = 0.82f),
                            startAngle = i * 120f + 12f,
                            sweepAngle = 72f,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = stroke
                        )
                    }
                }

                // Counter-rotating inner energy ring
                rotate(degrees = innerRotation, pivot = center) {
                    val innerRadius = maxRadius * 0.74f
                    val innerTopLeft = Offset(center.x - innerRadius, center.y - innerRadius)
                    val innerSize = Size(innerRadius * 2f, innerRadius * 2f)
                    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)

                    drawArc(
                        color = secondaryGlow.copy(alpha = 0.75f),
                        startAngle = 20f,
                        sweepAngle = 125f,
                        useCenter = false,
                        topLeft = innerTopLeft,
                        size = innerSize,
                        style = stroke
                    )
                    drawArc(
                        color = secondaryGlow.copy(alpha = 0.55f),
                        startAngle = 205f,
                        sweepAngle = 110f,
                        useCenter = false,
                        topLeft = innerTopLeft,
                        size = innerSize,
                        style = stroke
                    )
                }

                // 6-second continuous listening countdown ring
                if (voiceState == VoiceEngineState.CONTINUOUS_LISTENING && continuousSecondsLeft > 0) {
                    val fraction = (continuousSecondsLeft.toFloat() / GokuVoiceManager.CONTINUOUS_WINDOW_SECONDS)
                        .coerceIn(0f, 1f)
                    val cdRadius = maxRadius * 0.95f
                    drawArc(
                        color = EmeraldOnline,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        topLeft = Offset(center.x - cdRadius, center.y - cdRadius),
                        size = Size(cdRadius * 2f, cdRadius * 2f),
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Core dark reactor sphere + glowing rim
                val coreRadius = maxRadius * 0.57f * (0.96f + animatedAudio * 0.08f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = 0.36f),
                            ObsidianVoid.copy(alpha = 0.94f)
                        ),
                        center = center,
                        radius = coreRadius
                    ),
                    radius = coreRadius,
                    center = center
                )
                drawCircle(
                    color = primaryGlow.copy(alpha = 0.9f),
                    radius = coreRadius,
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }

            // Center adaptive icon
            val centerIcon = when {
                isThinking -> Icons.Filled.Psychology
                voiceState == VoiceEngineState.SPEAKING -> Icons.Filled.VolumeUp
                voiceState == VoiceEngineState.LISTENING ||
                    voiceState == VoiceEngineState.CONTINUOUS_LISTENING -> Icons.Filled.GraphicEq
                else -> Icons.Filled.Mic
            }

            Icon(
                imageVector = centerIcon,
                contentDescription = null,
                tint = primaryGlow,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = stateLabel.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = primaryGlow
        )
    }
}
