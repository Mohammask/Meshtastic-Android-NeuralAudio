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
package org.meshtastic.core.model.util

/**
 * Regex matching voice note representations such as: `[Voice Note: 0:01 | /data/user/0/cache/audio.wt]` or `[Voice
 * Note: 0:01]`
 */
private val VOICE_NOTE_PATTERN = Regex("""\[Voice Note:\s*([^|\]]+)(?:\|\s*[^\]]+)?\]""", RegexOption.IGNORE_CASE)

/**
 * Regex matching live PTT representations such as: `[Live PTT: 0:01 | /data/user/0/cache/ptt.wt]` or `[Live PTT: 0:01]`
 */
private val LIVE_PTT_PATTERN = Regex("""\[Live PTT:\s*([^|\]]+)(?:\|\s*[^\]]+)?\]""", RegexOption.IGNORE_CASE)

/** Regex matching 1-bit image representations such as: `[1-bit Image: /data/user/0/cache/img.png]` */
private val IMAGE_PATTERN = Regex("""\[1-bit Image:\s*[^\]]+\]""", RegexOption.IGNORE_CASE)

/**
 * Sanitizes voice note or media payload markers from raw text by completely stripping any internal filesystem path,
 * keeping the tag intact for internal parsing (e.g. `[Voice Note: 0:01]`).
 */
fun sanitizeVoiceNoteText(text: String): String = when {
    (text.startsWith("[Voice Note:", ignoreCase = true) || text.startsWith("[Live PTT:", ignoreCase = true)) &&
        text.contains("|") &&
        text.contains("]") -> {
        val prefix = text.substringBefore("|").trim()
        val suffix = text.substringAfter("]")
        "$prefix]$suffix"
    }

    text.startsWith("[1-bit Image:", ignoreCase = true) && text.contains("]") -> {
        val caption = text.substringAfter("]").trim()
        if (caption.isNotEmpty()) "[1-bit Image] $caption" else "[1-bit Image]"
    }

    else -> text
}

/**
 * Formats a duration string to ensure M:SS format (e.g. "0:03", "1:15"). If the input is already in "M:SS" format, it
 * is returned trimmed. If the input is in seconds (e.g. "15"), it is converted to "0:15".
 */
fun formatDurationSnippet(durationStr: String): String {
    val trimmed = durationStr.trim()
    return if (trimmed.contains(":")) {
        trimmed
    } else {
        val totalSec = trimmed.toIntOrNull() ?: return trimmed
        val m = totalSec / 60
        val s = totalSec % 60
        "$m:${s.toString().padStart(2, '0')}"
    }
}

/**
 * Sanitizes and formats message text for display in conversation lists, channel cards, reply snippets, quote headers,
 * and notifications.
 *
 * Strips internal storage paths (such as `/data/user/0/...`) and replaces raw voice note tags with a clean
 * human-readable preview:
 * - `[Voice Note: 0:01 | /path/to/file.wt]` -> `🎤 Voice note (0:01)`
 * - `[Voice Note: 0:01]` -> `🎤 Voice note (0:01)`
 * - `[Live PTT: 0:02 | /path/to/file.wt]` -> `📻 Live PTT (0:02)`
 * - `[1-bit Image: /path/to/img.png]` -> `📷 Image`
 */
fun formatMessageSnippet(text: String?): String {
    if (text.isNullOrEmpty()) return ""

    var result = text

    // Format Voice Notes: [Voice Note: 0:01 | /path...] -> 🎤 Voice note (0:01)
    if (result.contains("[Voice Note:", ignoreCase = true)) {
        result =
            VOICE_NOTE_PATTERN.replace(result) { match ->
                val duration = formatDurationSnippet(match.groupValues[1])
                "🎤 Voice note ($duration)"
            }
    }

    // Format Live PTT: [Live PTT: 0:01 | /path...] -> 📻 Live PTT (0:01)
    if (result.contains("[Live PTT:", ignoreCase = true)) {
        result =
            LIVE_PTT_PATTERN.replace(result) { match ->
                val duration = formatDurationSnippet(match.groupValues[1])
                "📻 Live PTT ($duration)"
            }
    }

    // Format 1-bit Image: [1-bit Image: /path...] -> 📷 Image
    if (result.contains("[1-bit Image:", ignoreCase = true)) {
        result = IMAGE_PATTERN.replace(result) { "📷 Image" }.trim()
    }

    return result
}
