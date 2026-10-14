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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.meshtastic.core.ui.component.AutoLinkText

private val CODE_BLOCK_REGEX = Regex("```(?:([a-zA-Z0-9_-]+)?\\r?\\n)?([\\s\\S]*?)```")
private val BULLET_ITEM_REGEX = Regex("^[ \t]*[-*+][ \t]+(.*)$")
private val NUMBERED_ITEM_REGEX = Regex("^[ \t]*(\\d+)\\.[ \t]+(.*)$")

private sealed interface ContentSegment {
    data class TextSegment(val content: String) : ContentSegment

    data class CodeSegment(val code: String, val language: String?) : ContentSegment
}

private sealed interface TextLine {
    data class Paragraph(val text: String) : TextLine

    data class BulletItem(val text: String) : TextLine

    data class NumberedItem(val number: String, val text: String) : TextLine
}

private fun parseContentSegments(raw: String): List<ContentSegment> {
    if (!raw.contains("```")) {
        return listOf(ContentSegment.TextSegment(raw))
    }
    val segments = mutableListOf<ContentSegment>()
    var lastIndex = 0

    for (match in CODE_BLOCK_REGEX.findAll(raw)) {
        if (match.range.first > lastIndex) {
            val textBefore = raw.substring(lastIndex, match.range.first)
            if (textBefore.isNotEmpty()) {
                segments.add(ContentSegment.TextSegment(textBefore))
            }
        }
        val language = match.groupValues[1].takeIf { it.isNotBlank() }
        val code = match.groupValues[2]
        segments.add(ContentSegment.CodeSegment(code.trimEnd(), language))
        lastIndex = match.range.last + 1
    }

    if (lastIndex < raw.length) {
        val remaining = raw.substring(lastIndex)
        if (remaining.isNotEmpty()) {
            segments.add(ContentSegment.TextSegment(remaining))
        }
    }
    return segments
}

private fun parseTextLines(text: String): List<TextLine> {
    val rawLines = text.split("\n")
    return rawLines.map { line ->
        val bulletMatch = BULLET_ITEM_REGEX.matchEntire(line)
        if (bulletMatch != null) {
            return@map TextLine.BulletItem(bulletMatch.groupValues[1])
        }
        val numberedMatch = NUMBERED_ITEM_REGEX.matchEntire(line)
        if (numberedMatch != null) {
            return@map TextLine.NumberedItem(numberedMatch.groupValues[1], numberedMatch.groupValues[2])
        }
        TextLine.Paragraph(line)
    }
}

/**
 * Smart markdown renderer for message bubbles supporting:
 * - Bold, Italics, Strikethrough, Inline Code, Links, Mentions
 * - Bullet lists (* / -) and Numbered lists (1., 2.)
 * - Styled Code Blocks (```) with language header and Copy button
 * - Typewriter streaming animation for PC/AI responses
 */
@Composable
fun SmartMessageContent(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    mentionName: ((String) -> String?)? = null,
    onMentionClick: ((String) -> Unit)? = null,
    isStreaming: Boolean = false,
) {
    if (isStreaming) {
        TypewriterText(fullText = text) { displayedText, _ ->
            RenderParsedContent(
                text = displayedText,
                style = style,
                color = color,
                mentionName = mentionName,
                onMentionClick = onMentionClick,
                modifier = modifier,
            )
        }
    } else {
        RenderParsedContent(
            text = text,
            style = style,
            color = color,
            mentionName = mentionName,
            onMentionClick = onMentionClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun RenderParsedContent(
    text: String,
    style: TextStyle,
    color: Color,
    mentionName: ((String) -> String?)?,
    onMentionClick: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val segments = remember(text) { parseContentSegments(text) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (segment in segments) {
            when (segment) {
                is ContentSegment.CodeSegment -> {
                    CodeBlockView(
                        code = segment.code,
                        language = segment.language,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                is ContentSegment.TextSegment -> {
                    val lines = remember(segment.content) { parseTextLines(segment.content) }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (line in lines) {
                            when (line) {
                                is TextLine.Paragraph -> {
                                    if (line.text.isNotEmpty()) {
                                        AutoLinkText(
                                            text = line.text,
                                            style = style,
                                            color = color,
                                            mentionName = mentionName,
                                            onMentionClick = onMentionClick,
                                        )
                                    }
                                }

                                is TextLine.BulletItem -> {
                                    Row(
                                        modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(text = "•", style = style, color = color)
                                        AutoLinkText(
                                            text = line.text,
                                            style = style,
                                            color = color,
                                            mentionName = mentionName,
                                            onMentionClick = onMentionClick,
                                        )
                                    }
                                }

                                is TextLine.NumberedItem -> {
                                    Row(
                                        modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(text = "${line.number}.", style = style, color = color)
                                        AutoLinkText(
                                            text = line.text,
                                            style = style,
                                            color = color,
                                            mentionName = mentionName,
                                            onMentionClick = onMentionClick,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
