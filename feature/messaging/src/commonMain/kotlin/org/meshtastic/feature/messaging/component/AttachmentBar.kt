/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.feature.messaging.component

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.vectorResource
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.model.media.ActionTargetMode
import org.meshtastic.core.repository.UiPrefs
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.ic_mic
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Send

const val MAX_VOICE_NOTE_SECONDS = 30

/**
 * Smart Action Bar providing:
 * 1. [📻 Live Walkie-Talkie] Synchronous PTT Session Toggle.
 * 2. Neural Voice Note Recording (WavTokenizer 24kHz / Codec 2).
 * 3. Dynamic UI Lockout during radio transmission/reception.
 */
@Composable
fun AttachmentBar(
    enabled: Boolean,
    selectedMode: ActionTargetMode = ActionTargetMode.P2P,
    codecName: String = UiPrefs.CODEC_WAVTOKENIZER,
    isSendingVoiceNote: Boolean = false,
    isProcessingVoiceNote: Boolean = false,
    isReceivingVoiceNote: Boolean = false,
    outboundMediaProgress: Pair<Int, Int>? = null,
    inboundMediaProgress: Any? = null,
    isRadioBusy: Boolean = false,
    onModeChanged: (ActionTargetMode) -> Unit = {},
    onOpenLivePttScreen: () -> Unit = {},
    onSelectImage: (filePath: String) -> Unit = {},
    onAudioRecorded: (durationSeconds: Int, audioBytes: ByteArray?) -> Unit = { _, _ -> },
    onTriggerScreenshot: () -> Unit = {},
    onRecordingStateChanged: (isRecording: Boolean, seconds: Int, onCancel: () -> Unit, onStop: () -> Unit) -> Unit =
        { _, _, _, _ ->
        },
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    val audioPermissionRequester = rememberAudioPermissionRequester { granted ->
        if (granted) {
            onOpenLivePttScreen()
        }
    }

    var isAwaitingTransmission by remember { mutableStateOf(false) }

    LaunchedEffect(isProcessingVoiceNote, isSendingVoiceNote, outboundMediaProgress) {
        if (isProcessingVoiceNote || isSendingVoiceNote || outboundMediaProgress != null) {
            isAwaitingTransmission = false
        }
    }

    LaunchedEffect(isAwaitingTransmission) {
        if (isAwaitingTransmission) {
            delay(3000L)
            isAwaitingTransmission = false
        }
    }

    val radioBusyForRecorder =
        isRadioBusy ||
            isSendingVoiceNote ||
            isProcessingVoiceNote ||
            outboundMediaProgress != null ||
            isReceivingVoiceNote ||
            (inboundMediaProgress != null) ||
            isAwaitingTransmission

    val audioRecorder =
        rememberAudioRecorderLauncher(isSending = radioBusyForRecorder, onAudioRecorded = onAudioRecorded)

    val isRecordingVoiceNote = audioRecorder.isRecording || isAwaitingTransmission

    val effectiveRadioBusy =
        isRadioBusy ||
            isRecordingVoiceNote ||
            isSendingVoiceNote ||
            isProcessingVoiceNote ||
            outboundMediaProgress != null ||
            isReceivingVoiceNote ||
            (inboundMediaProgress != null)

    // Notify parent about recording state changes so the input text field can be replaced in-place
    LaunchedEffect(audioRecorder.isRecording, audioRecorder.recordingSeconds) {
        onRecordingStateChanged(
            audioRecorder.isRecording,
            audioRecorder.recordingSeconds,
            {
                isAwaitingTransmission = false
                audioRecorder.cancelRecording()
            },
            { audioRecorder.stopRecording() },
        )
    }

    // Half-duplex lockout: If remote chunks arrive while local recording is active, abort immediately
    LaunchedEffect(isReceivingVoiceNote, inboundMediaProgress) {
        if ((isReceivingVoiceNote || inboundMediaProgress != null) && audioRecorder.isRecording) {
            isAwaitingTransmission = false
            audioRecorder.cancelRecording()
        }
    }

    Column(modifier = modifier.fillMaxWidth().animateContentSize()) {
        // Action Bar & Media Affordances
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // [📻 Live Walkie-Talkie] Button launching real PTT Call interface with permission check
            Surface(
                modifier =
                Modifier.clip(RoundedCornerShape(20.dp)).clickable(
                    enabled = enabled && selectedMode.allowsAudio && !effectiveRadioBusy,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    audioPermissionRequester()
                },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = "📻 Walkie-Talkie",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Controls with Dynamic Lockout & Tap-to-Record Tooltip Guard
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Voice PTT Button: Hold to record, slide to cancel, release to send (WhatsApp/Telegram style)
                val audioEnabled = enabled && selectedMode.allowsAudio && !effectiveRadioBusy
                var isSlideCancelled by remember { mutableStateOf(false) }
                val cancelThresholdPx = with(LocalDensity.current) { 60.dp.toPx() }

                var isHoldingMic by remember { mutableStateOf(false) }
                val micScale by
                    animateFloatAsState(
                        targetValue = if (isHoldingMic) 1.3f else 1.0f,
                        animationSpec = tween(150),
                        label = "mic_scale",
                    )

                LaunchedEffect(audioRecorder.isRecording) {
                    if (!audioRecorder.isRecording) {
                        isHoldingMic = false
                    }
                }

                var showHoldTooltip by remember { mutableStateOf(false) }
                var holdTooltipKey by remember { mutableStateOf(0) }
                var holdTooltipText by remember { mutableStateOf("Hold to record 🎙️") }

                LaunchedEffect(holdTooltipKey) {
                    if (holdTooltipKey > 0) {
                        showHoldTooltip = true
                        delay(2000L)
                        showHoldTooltip = false
                    }
                }

                val currentAudioEnabled by rememberUpdatedState(audioEnabled)
                val currentEffectiveRadioBusy by rememberUpdatedState(effectiveRadioBusy)
                val currentIsReceivingVoiceNote by rememberUpdatedState(isReceivingVoiceNote)
                val currentIsSendingVoiceNote by rememberUpdatedState(isSendingVoiceNote)
                val currentIsProcessingVoiceNote by rememberUpdatedState(isProcessingVoiceNote)
                val currentInboundMediaProgress by rememberUpdatedState(inboundMediaProgress)
                val currentOutboundMediaProgress by rememberUpdatedState(outboundMediaProgress)
                val currentIsAwaitingTransmission by rememberUpdatedState(isAwaitingTransmission)

                Box(modifier = Modifier.wrapContentSize(), contentAlignment = Alignment.Center) {
                    // Unclipped floating tooltip hint above the mic button using Compose Popup
                    if (showHoldTooltip) {
                        Popup(
                            alignment = Alignment.TopCenter,
                            offset = IntOffset(x = 0, y = with(LocalDensity.current) { (-44).dp.roundToPx() }),
                            onDismissRequest = { showHoldTooltip = false },
                            properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.inverseSurface,
                                tonalElevation = 6.dp,
                                shadowElevation = 8.dp,
                            ) {
                                Text(
                                    text = holdTooltipText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    Box(
                        modifier =
                        Modifier.size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isHoldingMic || audioRecorder.isRecording) {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (!currentAudioEnabled) {
                                        if (currentIsReceivingVoiceNote || currentInboundMediaProgress != null) {
                                            holdTooltipText = "Channel busy: Receiving audio 📡"
                                            holdTooltipKey++
                                        } else if (
                                            currentIsSendingVoiceNote || currentOutboundMediaProgress != null
                                        ) {
                                            holdTooltipText = "Sending voice note... 📤"
                                            holdTooltipKey++
                                        } else if (currentIsProcessingVoiceNote || currentIsAwaitingTransmission) {
                                            holdTooltipText = "Processing voice note... ⚙️"
                                            holdTooltipKey++
                                        } else if (currentEffectiveRadioBusy) {
                                            holdTooltipText = "Radio busy... ⏳"
                                            holdTooltipKey++
                                        }
                                        return@awaitEachGesture
                                    }

                                    val pressStartTime = nowMillis
                                    try {
                                        isHoldingMic = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        isSlideCancelled = false
                                        audioRecorder.startRecording()

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            if (change == null || !change.pressed) {
                                                val pressDuration = nowMillis - pressStartTime
                                                if (isSlideCancelled) {
                                                    // Cancelled via slide-to-cancel
                                                } else if (pressDuration < 800L) {
                                                    // Quick tap guard: immediately cancel and discard brief blip (<
                                                    // 800ms)
                                                    audioRecorder.cancelRecording()
                                                    holdTooltipText = "Hold to record 🎙️"
                                                    holdTooltipKey++
                                                } else {
                                                    isAwaitingTransmission = true
                                                    audioRecorder.stopRecording()
                                                }
                                                break
                                            }
                                            val dragX = change.position.x - down.position.x
                                            if (dragX < -cancelThresholdPx && !isSlideCancelled) {
                                                isSlideCancelled = true
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                audioRecorder.cancelRecording()
                                                break
                                            }
                                        }
                                    } finally {
                                        isHoldingMic = false
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isProcessingVoiceNote || isAwaitingTransmission) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(
                                imageVector = vectorResource(Res.drawable.ic_mic),
                                contentDescription = "Hold to record, release to send, slide left to cancel",
                                tint =
                                when {
                                    effectiveRadioBusy && !isHoldingMic && !audioRecorder.isRecording ->
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

                                    !audioEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)

                                    isHoldingMic || audioRecorder.isRecording -> MaterialTheme.colorScheme.error

                                    else -> MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(22.dp).scale(micScale),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Animated audio waveform bars indicating active microphone capture. */
@Composable
fun SubtleAudioWaveform(isRecording: Boolean, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val phase1 by
        infiniteTransition.animateFloat(
            initialValue = 6f,
            targetValue = 20f,
            animationSpec = infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "w1",
        )
    val phase2 by
        infiniteTransition.animateFloat(
            initialValue = 16f,
            targetValue = 8f,
            animationSpec = infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "w2",
        )
    val phase3 by
        infiniteTransition.animateFloat(
            initialValue = 8f,
            targetValue = 24f,
            animationSpec = infiniteRepeatable(tween(450, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "w3",
        )
    val phase4 by
        infiniteTransition.animateFloat(
            initialValue = 18f,
            targetValue = 10f,
            animationSpec = infiniteRepeatable(tween(380, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "w4",
        )
    val phase5 by
        infiniteTransition.animateFloat(
            initialValue = 10f,
            targetValue = 22f,
            animationSpec = infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "w5",
        )

    val heights = listOf(phase1, phase2, phase3, phase4, phase5, phase2, phase4)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        heights.forEach { h ->
            Box(
                modifier =
                Modifier.width(3.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)),
            )
        }
    }
}

/** WhatsApp/Telegram-style inline voice note recording bar that replaces the text input field in-place. */
@Composable
fun InlineRecordingBar(recordingSeconds: Int, onCancel: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "rec_dot_pulse")
    val dotAlpha by
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
            label = "dot_alpha",
        )

    Surface(
        modifier = modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: 🔴 Pulsing recording dot + clean horizontal timer + subtle waveform
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier =
                    Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFEF4444).copy(alpha = dotAlpha)),
                )
                val mins = recordingSeconds / 60
                val secs = recordingSeconds % 60
                Text(
                    text = "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(4.dp))
                SubtleAudioWaveform(isRecording = true)
            }

            // Center / Right: "‹ Slide to cancel 🗑️" indicator + stop/send action
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier =
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .clickable { onCancel() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "‹ Slide to cancel 🗑️",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }

                IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = MeshtasticIcons.Send,
                        contentDescription = "Send voice note",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
