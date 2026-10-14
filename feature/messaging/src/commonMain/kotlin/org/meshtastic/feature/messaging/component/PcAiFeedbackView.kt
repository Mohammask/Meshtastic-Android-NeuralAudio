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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import org.meshtastic.core.ui.icon.ExpandLess
import org.meshtastic.core.ui.icon.ExpandMore
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Serial

@Composable
private fun CancelWaitingButton(onCancel: () -> Unit) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onCancel() },
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = "Cancel",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Typewriter effect component that progressively streams incoming text character-by-character with a terminal-style
 * blinking cursor.
 */
@Composable
fun TypewriterText(
    fullText: String,
    modifier: Modifier = Modifier,
    charDelayMillis: Long = 18L,
    cursorChar: String = "▋",
    onAnimationFinished: () -> Unit = {},
    content: @Composable (displayedText: String, isFinished: Boolean) -> Unit,
) {
    var displayedLength by remember(fullText) { mutableIntStateOf(0) }
    var isFinished by remember(fullText) { mutableStateOf(false) }

    LaunchedEffect(fullText) {
        displayedLength = 0
        isFinished = false
        for (i in 1..fullText.length) {
            displayedLength = i
            delay(charDelayMillis)
        }
        isFinished = true
        onAnimationFinished()
    }

    val visibleText = fullText.take(displayedLength)
    val displayTextWithCursor =
        if (!isFinished) {
            "$visibleText$cursorChar"
        } else {
            visibleText
        }

    Box(modifier = modifier) { content(displayTextWithCursor, isFinished) }
}

/**
 * High-framerate animated "Thinking..." indicator for PC backend / AI command processing. Features 3 staggered wave
 * dots and an animated pulse.
 */
@Composable
fun ThinkingAnimation(
    modifier: Modifier = Modifier,
    dotSize: Dp = 6.dp,
    dotColor: Color = MaterialTheme.colorScheme.primary,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinkingDots")

    val dot1Scale by
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dot1",
        )

    val dot2Scale by
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 600, delayMillis = 200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dot2",
        )

    val dot3Scale by
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 600, delayMillis = 400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dot3",
        )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(dotSize).scale(dot1Scale).clip(CircleShape).background(dotColor))
        Box(modifier = Modifier.size(dotSize).scale(dot2Scale).clip(CircleShape).background(dotColor))
        Box(modifier = Modifier.size(dotSize).scale(dot3Scale).clip(CircleShape).background(dotColor))
    }
}

/** Compact Thinking Bubble shown while waiting for PC/AI backend responses. */
@Composable
fun ThinkingBubble(modifier: Modifier = Modifier, label: String = "PC / AI Thinking...") {
    Surface(
        modifier = modifier.padding(vertical = 4.dp),
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = MeshtasticIcons.Serial,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ThinkingAnimation(dotColor = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Progress indicator shown exclusively when awaiting remote screen capture from PC Gateway. */
@Composable
fun ScreenshotProgressView(
    modifier: Modifier = Modifier,
    label: String = "Capturing Remote Screen (Multi-Monitor)...",
    awaitingSinceTimestamp: Long = 0L,
    onCancel: (() -> Unit)? = null,
) {
    var elapsedSeconds by remember(awaitingSinceTimestamp) { mutableIntStateOf(0) }
    LaunchedEffect(awaitingSinceTimestamp) {
        if (awaitingSinceTimestamp > 0L) {
            while (true) {
                val elapsed = ((Clock.System.now().toEpochMilliseconds() - awaitingSinceTimestamp) / 1000L).toInt()
                elapsedSeconds = elapsed
                delay(1000L)
            }
        } else {
            elapsedSeconds = 0
        }
    }
    val showCancel = elapsedSeconds >= 10 && onCancel != null

    Surface(
        modifier = modifier.padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "📸", fontSize = 16.sp)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ThinkingAnimation(dotColor = MaterialTheme.colorScheme.primary)
            if (showCancel) {
                Spacer(modifier = Modifier.width(8.dp))
                CancelWaitingButton(onCancel = onCancel)
            }
        }
    }
}

/** Terminal execution indicator and output view for WSL 2.0 / PowerShell execution. */
@Composable
fun TerminalExecutionView(
    command: String,
    output: String,
    isExecuting: Boolean,
    modifier: Modifier = Modifier,
    awaitingSinceTimestamp: Long = 0L,
    onCancel: (() -> Unit)? = null,
) {
    var elapsedSeconds by remember(awaitingSinceTimestamp) { mutableIntStateOf(0) }
    LaunchedEffect(awaitingSinceTimestamp) {
        if (awaitingSinceTimestamp > 0L) {
            while (true) {
                val elapsed = ((Clock.System.now().toEpochMilliseconds() - awaitingSinceTimestamp) / 1000L).toInt()
                elapsedSeconds = elapsed
                delay(1000L)
            }
        } else {
            elapsedSeconds = 0
        }
    }
    val showCancel = elapsedSeconds >= 10 && onCancel != null

    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "⌨️", fontSize = 14.sp)
                Text(
                    text = if (isExecuting) "Running: $command" else "Command Finished",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (isExecuting) {
                    ThinkingAnimation(dotColor = MaterialTheme.colorScheme.primary)
                }
                if (isExecuting && showCancel) {
                    Spacer(modifier = Modifier.weight(1f, fill = false))
                    CancelWaitingButton(onCancel = onCancel)
                }
            }

            if (output.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Expandable "Chain of Thought" Accordion UI.
 *
 * Modern LLM models (e.g. Gemini 2.0 Thinking, DeepSeek, Ollama) stream a "thought" chain before the final response.
 * This component presents:
 * 1. A collapsible header with live status and thinking indicator.
 * 2. An expandable accordion containing the streaming internal thoughts (monospaced/italicized).
 * 3. The final response rendered below via [TypewriterText] and markdown formatting.
 */
@Composable
fun ExpandableThinkingView(
    thinkingThoughts: String,
    finalResponse: String,
    isThinking: Boolean,
    isStreamingResponse: Boolean,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    awaitingSinceTimestamp: Long = 0L,
    onCancel: (() -> Unit)? = null,
) {
    var isExpanded by remember { mutableStateOf(initiallyExpanded) }
    var elapsedSeconds by remember(awaitingSinceTimestamp) { mutableIntStateOf(0) }
    LaunchedEffect(awaitingSinceTimestamp) {
        if (awaitingSinceTimestamp > 0L) {
            while (true) {
                val elapsed = ((Clock.System.now().toEpochMilliseconds() - awaitingSinceTimestamp) / 1000L).toInt()
                elapsedSeconds = elapsed
                delay(1000L)
            }
        } else {
            elapsedSeconds = 0
        }
    }
    val showCancel = elapsedSeconds >= 10 && onCancel != null

    // Automatically expand while thinking, auto-collapse when final response starts
    LaunchedEffect(isThinking, isStreamingResponse) {
        if (isThinking && thinkingThoughts.isNotBlank()) {
            isExpanded = true
        } else if (isStreamingResponse && finalResponse.isNotBlank()) {
            isExpanded = false
        }
    }

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).animateContentSize()) {
        // Collapsible Accordion Header
        if (thinkingThoughts.isNotBlank() || isThinking) {
            Surface(
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { isExpanded = !isExpanded },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = "🧠", fontSize = 14.sp)
                        Text(
                            text =
                            if (isThinking) {
                                "Thinking Process..."
                            } else {
                                "Thought Chain (${thinkingThoughts.length} chars)"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isThinking) {
                            ThinkingAnimation(dotSize = 5.dp, dotColor = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isThinking && showCancel) {
                            CancelWaitingButton(onCancel = onCancel)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Icon(
                            imageVector = if (isExpanded) MeshtasticIcons.ExpandLess else MeshtasticIcons.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse Thoughts" else "Expand Thoughts",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Accordion Body: The Thoughts Content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = thinkingThoughts,
                            style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontStyle = FontStyle.Italic,
                                lineHeight = 18.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }

        // Final Response Content
        if (finalResponse.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            if (isStreamingResponse) {
                TypewriterText(fullText = finalResponse) { text, _ ->
                    SmartMessageContent(text = text, modifier = Modifier.fillMaxWidth())
                }
            } else {
                SmartMessageContent(text = finalResponse, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
