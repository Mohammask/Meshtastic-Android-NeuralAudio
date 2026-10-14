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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import org.meshtastic.core.ui.icon.Close
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.PlayArrow
import org.meshtastic.feature.messaging.audio.AudioPlayer

/** Renders a voice note audio bubble with real hardware playback controls, duration, and progress. */
@Composable
fun AudioAttachmentBubble(mediaPath: String?, modifier: Modifier = Modifier, durationSeconds: Int = 15) {
    val globalIsPlaying by AudioPlayer.isPlaying.collectAsState()
    val activePath by AudioPlayer.activeMediaPath.collectAsState()
    val isPlayingThis = globalIsPlaying && activePath != null && activePath == mediaPath
    val isPreparingThis = !globalIsPlaying && activePath != null && activePath == mediaPath

    var playbackProgress by remember { mutableFloatStateOf(0f) }

    val codecLabel =
        remember(mediaPath) {
            when {
                mediaPath?.endsWith(".wt") == true -> "WavTokenizer (480 bps)"
                mediaPath?.endsWith(".wav") == true -> "Salvaged Voice (PCM)"
                else -> "Codec 2 (700 bps)"
            }
        }

    LaunchedEffect(isPlayingThis) {
        if (isPlayingThis) {
            val totalMs = (durationSeconds.coerceAtLeast(1) * 1000L)
            val startTime = Clock.System.now().toEpochMilliseconds()
            while (isPlayingThis) {
                val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
                playbackProgress = (elapsed.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
                if (playbackProgress >= 1f) break
                delay(50L)
            }
        } else {
            playbackProgress = 0f
        }
    }

    Surface(
        modifier = modifier.width(260.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Play / Pause button
            IconButton(
                onClick = {
                    if (isPlayingThis || isPreparingThis) {
                        AudioPlayer.stop()
                    } else {
                        AudioPlayer.play(mediaPath)
                    }
                },
                modifier = Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            ) {
                if (isPreparingThis) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = if (isPlayingThis) MeshtasticIcons.Close else MeshtasticIcons.PlayArrow,
                        contentDescription = if (isPlayingThis) "Stop voice note" else "Play voice note",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Voice Note",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val minutes = durationSeconds / 60
                    val seconds = durationSeconds % 60
                    val durationFormatted = "$minutes:${seconds.toString().padStart(2, '0')}"
                    Text(
                        text = durationFormatted,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Playback progress bar
                LinearProgressIndicator(
                    progress = { playbackProgress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )

                val sizeText =
                    remember(mediaPath, durationSeconds) {
                        val file = mediaPath?.let { java.io.File(it) }
                        val isWav = mediaPath?.endsWith(".wav") == true
                        val bytes =
                            if (file != null && file.exists() && file.length() > 0 && !isWav) {
                                file.length()
                            } else {
                                val isWt = mediaPath?.endsWith(".wt") == true || isWav
                                if (isWt) {
                                    (durationSeconds * 60L).coerceAtLeast(60L)
                                } else {
                                    ((durationSeconds * 700L) / 8L).coerceAtLeast(87L)
                                }
                            }
                        if (bytes >= 1024) "${(bytes / 1024.0).let { (it * 10).toInt() / 10.0 }} kB" else "$bytes B"
                    }

                Text(
                    text = "$codecLabel • $sizeText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}
