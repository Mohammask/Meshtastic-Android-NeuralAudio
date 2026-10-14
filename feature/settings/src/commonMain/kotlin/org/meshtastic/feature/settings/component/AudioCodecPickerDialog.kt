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
@file:Suppress("MatchingDeclarationName")

package org.meshtastic.feature.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.meshtastic.core.repository.UiPrefs
import org.meshtastic.core.ui.component.MeshtasticDialog

data class AudioCodecOption(val id: String, val title: String, val description: String)

val AUDIO_CODEC_OPTIONS =
    listOf(
        AudioCodecOption(
            id = UiPrefs.CODEC_WAVTOKENIZER,
            title = "WavTokenizer (900 bps) - Recommended",
            description =
            "SOTA 75 tokens/s on-device neural ONNX acoustic model (~3.3 KB / 30s) with natural voice quality and 12-bit bitstream packing.",
        ),
        AudioCodecOption(
            id = UiPrefs.CODEC_CODEC2_1400,
            title = "Codec 2 (1400 bps) - Clear Voice",
            description =
            "Official David Rowe C vocoder with crystal-clear consonant phonemes and formant tracking (175 B/s, ~5.2 KB / 30s).",
        ),
        AudioCodecOption(
            id = UiPrefs.CODEC_CODEC2_700,
            title = "Codec 2 (700 bps) - Low Bitrate",
            description = "Official David Rowe C vocoder with ultra-compact frames (87.5 B/s, ~2.6 KB / 30s).",
        ),
    )

/** Dialog for selecting audio codec preference between WavTokenizer (480 bps) and Codec 2 (700 bps). */
@Composable
fun AudioCodecPickerDialog(
    current: String,
    onClickCodec: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MeshtasticDialog(
        modifier = modifier,
        title = "Audio Codec Selection",
        onDismiss = onDismiss,
        text = {
            Column {
                Text(
                    text = "Select compression codec for Live PTT Walkie-Talkie and voice notes over the LoRa mesh.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Column(modifier = Modifier.selectableGroup()) {
                    AUDIO_CODEC_OPTIONS.forEach { option ->
                        val selected = option.id == current
                        Row(
                            modifier =
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        onClickCodec(option.id)
                                        onDismiss()
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = option.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}
