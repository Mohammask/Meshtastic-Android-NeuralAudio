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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Displays a 1-bit compressed image attachment (photo or screenshot). Preserves native resolution with sharp
 * (FilterQuality.None) pixels.
 */
@Composable
fun ImageAttachmentBubble(
    mediaPath: String?,
    modifier: Modifier = Modifier,
    caption: String? = null,
    onClick: () -> Unit = {},
) {
    var isExpanded by remember { mutableStateOf(false) }

    val imageModel =
        remember(mediaPath) {
            when {
                mediaPath.isNullOrBlank() -> null

                mediaPath.startsWith("http://") ||
                    mediaPath.startsWith("https://") ||
                    mediaPath.startsWith("file://") ||
                    mediaPath.startsWith("content://") -> mediaPath

                else -> "file://$mediaPath"
            }
        }

    Surface(
        modifier =
        modifier.widthIn(min = 180.dp, max = 280.dp).clip(RoundedCornerShape(12.dp)).clickable {
            isExpanded = !isExpanded
            onClick()
        },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(max = if (isExpanded) 400.dp else 220.dp),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = caption ?: "1-bit Compressed Image",
                    filterQuality = FilterQuality.None, // Crisp 1-bit pixels, no bilinear blurring
                    contentScale = if (isExpanded) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!caption.isNullOrBlank()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "1-bit Image (Lossless RLE)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
