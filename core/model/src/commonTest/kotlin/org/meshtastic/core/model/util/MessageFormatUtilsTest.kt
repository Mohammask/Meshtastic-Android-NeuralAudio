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

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageFormatUtilsTest {

    @Test
    fun `formatMessageSnippet formats voice note with local path`() {
        val raw = "[Voice Note: 0:01 | /data/user/0/com.geeksville.mesh.fdroid.debug/cache/audio_1727800000.wt]"
        val result = formatMessageSnippet(raw)
        assertEquals("🎤 Voice note (0:01)", result)
    }

    @Test
    fun `formatMessageSnippet formats voice note without path`() {
        val raw = "[Voice Note: 0:03]"
        val result = formatMessageSnippet(raw)
        assertEquals("🎤 Voice note (0:03)", result)
    }

    @Test
    fun `formatMessageSnippet formats voice note with minutes and seconds`() {
        val raw = "[Voice Note: 1:25 | /data/user/0/cache/voice.wt]"
        val result = formatMessageSnippet(raw)
        assertEquals("🎤 Voice note (1:25)", result)
    }

    @Test
    fun `formatMessageSnippet formats voice note with raw seconds`() {
        val raw = "[Voice Note: 15 | /cache/voice.wt]"
        val result = formatMessageSnippet(raw)
        assertEquals("🎤 Voice note (0:15)", result)
    }

    @Test
    fun `formatMessageSnippet formats voice note with sender prefix`() {
        val raw = "Alice: [Voice Note: 0:03 | /data/user/0/cache/voice.wt]"
        val result = formatMessageSnippet(raw)
        assertEquals("Alice: 🎤 Voice note (0:03)", result)
    }

    @Test
    fun `formatMessageSnippet formats live ptt with path`() {
        val raw = "[Live PTT: 0:05 | /data/user/0/cache/ptt_123.wt]"
        val result = formatMessageSnippet(raw)
        assertEquals("📻 Live PTT (0:05)", result)
    }

    @Test
    fun `formatMessageSnippet formats live ptt without path`() {
        val raw = "[Live PTT: 0:02]"
        val result = formatMessageSnippet(raw)
        assertEquals("📻 Live PTT (0:02)", result)
    }

    @Test
    fun `formatMessageSnippet formats 1-bit image`() {
        val raw = "[1-bit Image: /data/user/0/cache/img_123.png]"
        val result = formatMessageSnippet(raw)
        assertEquals("📷 Image", result)
    }

    @Test
    fun `formatMessageSnippet leaves normal text unchanged`() {
        val raw = "Hello from the mesh!"
        val result = formatMessageSnippet(raw)
        assertEquals("Hello from the mesh!", result)
    }

    @Test
    fun `formatMessageSnippet handles null and empty`() {
        assertEquals("", formatMessageSnippet(null))
        assertEquals("", formatMessageSnippet(""))
    }

    @Test
    fun `sanitizeVoiceNoteText strips local path keeping tag`() {
        val rawVoiceNote = "[Voice Note: 0:01 | /data/user/0/com.geeksville.mesh.fdroid.debug/cache/audio.wt]"
        assertEquals("[Voice Note: 0:01]", sanitizeVoiceNoteText(rawVoiceNote))

        val rawLivePtt = "[Live PTT: 0:04 | /data/user/0/cache/ptt.wt]"
        assertEquals("[Live PTT: 0:04]", sanitizeVoiceNoteText(rawLivePtt))

        val normal = "Regular message"
        assertEquals("Regular message", sanitizeVoiceNoteText(normal))
    }
}
