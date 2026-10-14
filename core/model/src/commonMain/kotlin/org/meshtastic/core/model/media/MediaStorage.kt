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
package org.meshtastic.core.model.media

import co.touchlab.kermit.Logger
import kotlinx.datetime.Clock
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

object MediaStorage {
    /**
     * Optional custom base directory override path. Initialized at startup on Android to Context.cacheDir /
     * "meshtastic_media".
     */
    var customBaseDirectory: Path? = null

    fun resolveBaseDirectory(): Path {
        customBaseDirectory?.let {
            return it
        }

        // Try reflection to locate ContextServices.app.cacheDir without compile-time Android coupling in commonMain
        try {
            val clazz = Class.forName("org.meshtastic.core.common.ContextServices")
            val appProp = clazz.getMethod("getApp").invoke(null)
            if (appProp != null) {
                val cacheDirMethod = appProp.javaClass.getMethod("getCacheDir")
                val cacheDir = cacheDirMethod.invoke(appProp) as? java.io.File
                if (cacheDir != null) {
                    val mediaDir = java.io.File(cacheDir, "meshtastic_media")
                    return mediaDir.absolutePath.toPath()
                }
            }
        } catch (_: Throwable) {
            // Not Android runtime or ContextServices not initialized
        }

        return FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "meshtastic_media"
    }

    private var fileCounter = 0L
    private val counterLock = Any()

    private fun nextCounter(): Long = synchronized(counterLock) { ++fileCounter }

    fun saveAudioPayload(payload: ByteArray, extension: String): String = try {
        val baseDir = resolveBaseDirectory()
        try {
            FileSystem.SYSTEM.createDirectories(baseDir)
        } catch (e: Exception) {
            Logger.w(e) { "MediaStorage: Failed to create directory $baseDir" }
        }
        val fileName = "voice_${Clock.System.now().toEpochMilliseconds()}_${nextCounter()}.$extension"
        val filePath = baseDir / fileName
        FileSystem.SYSTEM.write(filePath) { write(payload) }
        filePath.toString()
    } catch (e: Exception) {
        Logger.e(e) { "MediaStorage: Failed to write audio payload to primary path, attempting fallback" }
        try {
            val fallbackDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "meshtastic_media"
            FileSystem.SYSTEM.createDirectories(fallbackDir)
            val fileName = "voice_${Clock.System.now().toEpochMilliseconds()}_${nextCounter()}.$extension"
            val fallbackPath = fallbackDir / fileName
            FileSystem.SYSTEM.write(fallbackPath) { write(payload) }
            fallbackPath.toString()
        } catch (e2: Exception) {
            Logger.e(e2) { "MediaStorage: Critical error saving audio payload" }
            ""
        }
    }
}
