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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.vectorResource
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.ic_mic

/** Push-To-Talk (PTT) tactical state machine. */
enum class PttUiState {
    IDLE,
    TRANSMITTING,
    RECEIVING,
    PLAYING,
    COOLDOWN,
}

/**
 * Full-Screen Push-To-Talk (PTT) Room Interface.
 *
 * Implements a true Walkie-Talkie experience with tactical floor control and dynamic presence indicators:
 * - "Waiting for peer... 🟡 (Alone in room)" when 1 user is present.
 * - "Peer Active 🟢 ([Peer Name] joined)" when peer has joined the room.
 * - 5 explicit tactical states:
 *     - IDLE: "🟢 Channel Clear (Hold to Talk)" - button enabled.
 *     - TRANSMITTING: "🔴 Transmitting (TX Chunk X/Y)" - sender button locked in send mode.
 *     - RECEIVING: "🔒 [User] is Speaking..." - remote button completely disabled.
 *     - PLAYING: "🔊 Playing Audio..." - remote button remains disabled until sound finishes.
 *     - COOLDOWN: Brief 300ms post-transmission settle window before returning to IDLE.
 */
@Composable
fun LivePttCallScreen(
    contactName: String,
    isReceiving: Boolean,
    receivingSpeakerName: String?,
    isBothActive: Boolean = false,
    activeUsersCount: Int = 1,
    activePeersCount: Int = 0,
    codecName: String = "WavTokenizer (480 bps)",
    isFloorRejected: Boolean = false,
    lastPeerJoinedName: String? = null,
    isPlayingAudio: Boolean = false,
    isSending: Boolean = false,
    onDismiss: () -> Unit,
    onEnterScreen: () -> Unit = {},
    onExitScreen: () -> Unit = {},
    onRequestFloor: () -> Unit = {},
    onReleaseFloor: () -> Unit = {},
    onSendPttAudio: (durationSeconds: Int, audioBytes: ByteArray?) -> Unit,
    modifier: Modifier = Modifier,
    txProgress: Pair<Int, Int>? = null,
    rxProgress: Pair<Int, Int>? = null,
) {
    var isHolding by remember { mutableStateOf(false) }
    var isPressing by remember { mutableStateOf(false) }
    var showQuickTapHint by remember { mutableStateOf(false) }
    var quickTapHintKey by remember { mutableStateOf(0) }
    var showNoPeersHint by remember { mutableStateOf(false) }
    var noPeersHintKey by remember { mutableStateOf(0) }
    var isCooldown by remember { mutableStateOf(false) }
    var wasSending by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    var isAwaitingTransmission by remember { mutableStateOf(false) }

    val recorder = rememberAudioRecorderLauncher { duration, bytes ->
        if (bytes != null && duration > 0) {
            onSendPttAudio(duration, bytes)
        } else {
            // Nothing usable was captured (released before the mic was ready, capture failed, ...). Without this the
            // floor stayed reserved forever: no audio was sent, so nothing ever released it, and peers stayed locked.
            isAwaitingTransmission = false
            onReleaseFloor()
        }
    }

    LaunchedEffect(Unit) { onEnterScreen() }

    DisposableEffect(Unit) { onDispose { onExitScreen() } }

    LaunchedEffect(quickTapHintKey) {
        if (quickTapHintKey > 0) {
            showQuickTapHint = true
            delay(2000L)
            showQuickTapHint = false
        }
    }

    LaunchedEffect(noPeersHintKey) {
        if (noPeersHintKey > 0) {
            showNoPeersHint = true
            delay(2000L)
            showNoPeersHint = false
        }
    }

    // 300ms post-transmission settle window before returning to IDLE
    LaunchedEffect(isSending) {
        if (isSending) {
            isAwaitingTransmission = false
        }
        if (wasSending && !isSending) {
            isCooldown = true
            delay(300L)
            isCooldown = false
        }
        wasSending = isSending
    }

    LaunchedEffect(isAwaitingTransmission) {
        if (isAwaitingTransmission) {
            delay(5000L)
            isAwaitingTransmission = false
        }
    }

    // Cancel active recording immediately if remote peer took the floor or won collision tie-breaker
    LaunchedEffect(isFloorRejected, isReceiving) {
        if ((isFloorRejected || isReceiving) && (isHolding || isPressing)) {
            isHolding = false
            isPressing = false
            isAwaitingTransmission = false
            recorder.cancelRecording()
        }
    }

    val isSoloLocked = activePeersCount == 0

    // Explicit tactical state machine
    val pttUiState: PttUiState =
        when {
            isHolding || isSending || isAwaitingTransmission -> PttUiState.TRANSMITTING
            isPlayingAudio -> PttUiState.PLAYING
            isReceiving -> PttUiState.RECEIVING
            isCooldown -> PttUiState.COOLDOWN
            else -> PttUiState.IDLE
        }

    val isPttDisabled = pttUiState != PttUiState.IDLE || isFloorRejected

    val infiniteTransition = rememberInfiniteTransition(label = "ptt_pulse")
    val pulseScale by
        infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
            label = "button_pulse",
        )

    Surface(
        modifier = modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        color = Color(0xFF0F172A), // Dark tactical slate background
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // TOP BAR: Navigation, Room Header, & Codec Chip
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                        Text(
                            text = "✕",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "MESH WALKIE-TALKIE",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            color = Color(0xFF38BDF8), // Cyan-400 accent
                        )
                        Text(
                            text = contactName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1E293B),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                    ) {
                        Text(
                            text = codecName,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                // Top Incoming Stream Indicator when receiving or playing audio
                if (isReceiving || isPlayingAudio) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1E293B),
                        border =
                        androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isPlayingAudio) Color(0xFF10B981) else Color(0xFFF59E0B),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier =
                                Modifier.size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isPlayingAudio) Color(0xFF10B981) else Color(0xFFF59E0B)),
                            )
                            Text(
                                text =
                                if (isPlayingAudio) {
                                    "  🔊 PLAYING STREAM: ${receivingSpeakerName ?: contactName}"
                                } else {
                                    "  📡 INCOMING STREAM: ${receivingSpeakerName ?: contactName}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isPlayingAudio) Color(0xFFA7F3D0) else Color(0xFFFDE68A),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // PRESENCE STATUS BANNER
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (activePeersCount > 0) Color(0xFF064E3B) else Color(0xFF1E293B),
                    border =
                    androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = if (activePeersCount > 0) Color(0xFF10B981) else Color(0xFF475569),
                    ),
                    modifier = Modifier.fillMaxWidth(0.9f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        val peerDisplay = lastPeerJoinedName ?: contactName
                        val peerCountText =
                            if (activePeersCount == 1) {
                                "1 Peer Online ($peerDisplay)"
                            } else {
                                "$activePeersCount Peers Online"
                            }
                        Text(
                            text =
                            if (activePeersCount > 0) {
                                "🟢 $peerCountText - Channel Clear"
                            } else {
                                "⚪ Waiting for peers in room..."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (activePeersCount > 0) Color(0xFF6EE7B7) else Color(0xFF94A3B8),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // CENTER: Live Audio Status & Giant "HOLD TO TALK" Button
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                // Status Prompt
                val statusText =
                    when {
                        showNoPeersHint -> "⚠️ No peers listening"

                        showQuickTapHint -> "Hold to talk 📻"

                        isFloorRejected -> "⚠️ PRECEDENCE COLLISION: PEER TRANSMITTING"

                        pttUiState == PttUiState.TRANSMITTING -> {
                            if (txProgress != null) {
                                "🔴 Transmitting (TX Chunk ${txProgress.first}/${txProgress.second})"
                            } else if (isSending && !isHolding) {
                                "Waiting for transmission to complete..."
                            } else {
                                "🔴 Transmitting (Talking... 0:${recorder.recordingSeconds.toString().padStart(
                                    2,
                                    '0',
                                )})"
                            }
                        }

                        pttUiState == PttUiState.PLAYING -> "🔊 Playing Audio..."

                        pttUiState == PttUiState.RECEIVING -> "🔒 ${receivingSpeakerName ?: contactName} is Speaking..."

                        pttUiState == PttUiState.COOLDOWN -> "⏳ Channel Settling..."

                        activePeersCount == 0 -> "⚪ Waiting for peers in room..."

                        else ->
                            "🟢 $activePeersCount Peer${if (activePeersCount == 1) "" else "s"} Online - Channel Clear (Hold to Talk)"
                    }
                val statusColor =
                    when {
                        showNoPeersHint -> Color(0xFFFBBF24)
                        showQuickTapHint -> Color(0xFFFBBF24)
                        isFloorRejected -> Color(0xFFEF4444)
                        pttUiState == PttUiState.TRANSMITTING -> Color(0xFFEF4444)
                        pttUiState == PttUiState.PLAYING -> Color(0xFFF59E0B)
                        pttUiState == PttUiState.RECEIVING -> Color(0xFFF87171)
                        pttUiState == PttUiState.COOLDOWN -> Color(0xFF94A3B8)
                        activePeersCount == 0 -> Color(0xFF94A3B8)
                        else -> Color(0xFF10B981)
                    }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    color = statusColor,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Giant Circular PTT Button: Distinct Visual States
                val buttonBrush =
                    when {
                        isSoloLocked -> Brush.radialGradient(listOf(Color(0xFF334155), Color(0xFF1E293B)))

                        isFloorRejected -> Brush.radialGradient(listOf(Color(0xFFDC2626), Color(0xFF7F1D1D)))

                        pttUiState == PttUiState.TRANSMITTING ->
                            Brush.radialGradient(listOf(Color(0xFFEF4444), Color(0xFF991B1B)))

                        pttUiState == PttUiState.PLAYING ->
                            Brush.radialGradient(listOf(Color(0xFFD97706), Color(0xFF78350F)))

                        pttUiState == PttUiState.RECEIVING ->
                            Brush.radialGradient(listOf(Color(0xFFDC2626), Color(0xFF7F1D1D)))

                        pttUiState == PttUiState.COOLDOWN ->
                            Brush.radialGradient(listOf(Color(0xFF334155), Color(0xFF1E293B)))

                        else -> Brush.radialGradient(listOf(Color(0xFF059669), Color(0xFF064E3B))) // Green glow
                    }

                val borderColor =
                    when {
                        isSoloLocked -> Color(0xFF475569)
                        isFloorRejected -> Color(0xFFEF4444)
                        pttUiState == PttUiState.TRANSMITTING -> Color.White
                        pttUiState == PttUiState.PLAYING -> Color(0xFFF59E0B)
                        pttUiState == PttUiState.RECEIVING -> Color(0xFFEF4444)
                        pttUiState == PttUiState.COOLDOWN -> Color(0xFF64748B)
                        else -> Color(0xFF10B981) // Green glow
                    }

                val borderWidth =
                    when {
                        pttUiState == PttUiState.TRANSMITTING -> 5.dp
                        isFloorRejected -> 4.dp
                        pttUiState == PttUiState.RECEIVING || pttUiState == PttUiState.PLAYING -> 4.dp
                        else -> 3.dp
                    }

                val contentModifier =
                    if (pttUiState == PttUiState.TRANSMITTING) {
                        Modifier.scale(pulseScale)
                    } else {
                        Modifier
                    }

                val currentIsFloorRejected by rememberUpdatedState(isFloorRejected)
                val currentActivePeersCount by rememberUpdatedState(activePeersCount)
                val currentIsReceiving by rememberUpdatedState(isReceiving)
                val currentIsPlayingAudio by rememberUpdatedState(isPlayingAudio)
                val currentIsSending by rememberUpdatedState(isSending)
                val currentIsCooldown by rememberUpdatedState(isCooldown)
                val currentIsAwaitingTransmission by rememberUpdatedState(isAwaitingTransmission)
                val currentPttUiState by rememberUpdatedState(pttUiState)
                val currentIsHolding by rememberUpdatedState(isHolding)
                val currentIsPressing by rememberUpdatedState(isPressing)
                val currentOnRequestFloor by rememberUpdatedState(onRequestFloor)
                val currentOnReleaseFloor by rememberUpdatedState(onReleaseFloor)
                val currentRecorder by rememberUpdatedState(recorder)

                Box(
                    modifier =
                    Modifier.size(220.dp)
                        .clip(CircleShape)
                        .background(buttonBrush)
                        .border(width = borderWidth, color = borderColor, shape = CircleShape)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()

                                    // If channel is busy or awaiting transmission/settling, ignore touch
                                    if (
                                        currentIsFloorRejected ||
                                        currentIsReceiving ||
                                        currentIsPlayingAudio ||
                                        currentIsSending ||
                                        currentIsAwaitingTransmission ||
                                        currentIsCooldown ||
                                        currentIsHolding ||
                                        currentIsPressing ||
                                        currentPttUiState != PttUiState.IDLE
                                    ) {
                                        continue
                                    }

                                    val pressStartTime = nowMillis
                                    var shouldCancel = false
                                    try {
                                        isPressing = true
                                        isHolding = true
                                        showQuickTapHint = false
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        // Floor lease renewal (keepalive) is owned by the ViewModel: it must
                                        // survive
                                        // the finger lifting, because encode + transmit happen after release.
                                        currentOnRequestFloor()
                                        currentRecorder.startRecording()

                                        // Track ONLY the pointer that went down. A release of some other pointer (a
                                        // second finger) must not end this press, and a missing/cancelled pointer
                                        // (system gesture, focus loss) counts as release.
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            event.changes.forEach { it.consume() }
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            if (change == null || !change.pressed) break
                                        }
                                    } catch (e: CancellationException) {
                                        shouldCancel = true
                                        throw e
                                    } finally {
                                        isPressing = false
                                        isHolding = false
                                        val pressDuration = nowMillis - pressStartTime
                                        if (
                                            shouldCancel ||
                                            pressDuration < 300L ||
                                            currentIsFloorRejected ||
                                            currentIsReceiving
                                        ) {
                                            isAwaitingTransmission = false
                                            currentRecorder.cancelRecording()
                                            currentOnReleaseFloor()
                                            if (
                                                pressDuration < 300L &&
                                                !shouldCancel &&
                                                !currentIsFloorRejected &&
                                                !currentIsReceiving
                                            ) {
                                                quickTapHintKey++
                                            }
                                        } else {
                                            isAwaitingTransmission = true
                                            currentRecorder.stopRecording()
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = contentModifier,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.ic_mic),
                            contentDescription = if (isSoloLocked) "PTT Locked" else "Hold to Talk",
                            tint =
                            when {
                                isSoloLocked -> Color(0xFF64748B)
                                pttUiState == PttUiState.COOLDOWN -> Color(0xFF94A3B8)
                                else -> Color.White
                            },
                            modifier = Modifier.size(56.dp),
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text =
                            when {
                                showNoPeersHint -> "NO PEERS"

                                isSoloLocked -> "WAITING FOR PEERS ⚪"

                                isFloorRejected -> "FLOOR BUSY ⚠️"

                                pttUiState == PttUiState.TRANSMITTING -> {
                                    if (isSending && !isHolding) "SENDING RADIO..." else "TRANSMITTING 🔴"
                                }

                                pttUiState == PttUiState.PLAYING -> "PLAYING AUDIO 🔊"

                                pttUiState == PttUiState.RECEIVING -> "PEER IS SPEAKING 🔒"

                                pttUiState == PttUiState.COOLDOWN -> "CHANNEL SETTLING"

                                else -> "HOLD TO TALK"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = if (isSoloLocked) Color(0xFF94A3B8) else Color.White,
                            textAlign = TextAlign.Center,
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text =
                            when {
                                showNoPeersHint -> "(No peers listening)"

                                isSoloLocked -> "⚪ Waiting for peers in room..."

                                isFloorRejected -> "(Precedence collision)"

                                pttUiState == PttUiState.TRANSMITTING -> {
                                    if (isSending && !isHolding) {
                                        "Waiting for transmission to complete..."
                                    } else {
                                        "(Talking... 0:${recorder.recordingSeconds.toString().padStart(2, '0')})"
                                    }
                                }

                                pttUiState == PttUiState.PLAYING -> "(Acoustic drain lockout)"

                                pttUiState == PttUiState.RECEIVING -> "(Microphone locked)"

                                pttUiState == PttUiState.COOLDOWN -> "(Wait 300ms...)"

                                else -> "🟢 Channel Clear (Hold to Talk)"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (isSoloLocked) Color(0xFF64748B) else Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                // Tactical Live PTT Chunk Counter HUD
                if (txProgress != null || rxProgress != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF1E293B),
                        border =
                        androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (txProgress != null) Color(0xFF38BDF8) else Color(0xFFF59E0B),
                        ),
                        modifier = Modifier.fillMaxWidth(0.85f),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val hudText =
                                when {
                                    txProgress != null -> "📡 TX: Chunk ${txProgress.first} / ${txProgress.second}"
                                    rxProgress != null -> "🔊 RX: Chunk ${rxProgress.first} / ${rxProgress.second}"
                                    else -> ""
                                }
                            val hudColor = if (txProgress != null) Color(0xFF38BDF8) else Color(0xFFFCD34D)
                            val progressFrac =
                                when {
                                    txProgress != null && txProgress.second > 0 ->
                                        (txProgress.first.toFloat() / txProgress.second).coerceIn(0f, 1f)

                                    rxProgress != null && rxProgress.second > 0 ->
                                        (rxProgress.first.toFloat() / rxProgress.second).coerceIn(0f, 1f)

                                    else -> 0f
                                }
                            Text(
                                text = hudText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp,
                                color = hudColor,
                            )
                            LinearProgressIndicator(
                                progress = { progressFrac },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = hudColor,
                                trackColor = Color(0xFF334155),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text =
                    when {
                        pttUiState == PttUiState.RECEIVING || pttUiState == PttUiState.PLAYING ->
                            "Microphone locked while peer is speaking or audio is playing"

                        isSending -> "Waiting for transmission to complete..."

                        pttUiState == PttUiState.COOLDOWN -> "Channel clearing, please wait..."

                        else -> "Release button to broadcast voice over mesh"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                )
            }

            // BOTTOM: Exit Room Control
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth(0.65f),
                ) {
                    Text(
                        text = "Exit PTT Room",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
