package com.example.security

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages private unverified file quarantine outside FileProvider paths.
 * - Unverified files live inside filesDir/quarantine/ (not shared in res/xml/file_paths.xml).
 * - Automatically removes abandoned stale files and prevents duplicate concurrent processing.
 */
class DownloadQuarantineManager(private val context: Context) {

    val quarantineDir: File
        get() = File(context.filesDir, "quarantine").apply { mkdirs() }

    val downloadsDir: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    private val activeKeys = ConcurrentHashMap.newKeySet<String>()

    fun tryAcquireProcessKey(key: String): Boolean = activeKeys.add(key)

    fun releaseProcessKey(key: String) {
        activeKeys.remove(key)
    }

    fun isConfinedWithin(file: File, dir: File): Boolean {
        val canonicalRoot = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        val canonicalTarget = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val prefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separatorChar
        return canonicalTarget.path.startsWith(prefix)
    }

    fun allocateQuarantineFile(downloadId: Long, sanitizedName: String): File {
        val safeStem = sanitizedName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        return File(quarantineDir, "q_${downloadId}_$safeStem.part")
    }

    fun secureDelete(file: File?) {
        if (file == null || !file.exists()) return
        val allowed = isConfinedWithin(file, quarantineDir) ||
            isConfinedWithin(file, downloadsDir) ||
            isConfinedWithin(file, context.cacheDir)
        if (!allowed) return
        runCatching {
            // Overwrite first block before unlinking temporary sensitive/unverified data
            if (file.isFile && file.length() > 0L) {
                val overwriteLen = minOf(file.length(), 4096L).toInt()
                java.io.RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(0)
                    raf.write(ByteArray(overwriteLen))
                }
            }
            file.delete()
        }.onFailure {
            runCatching { file.delete() }
        }
    }

    fun promoteToDestination(quarantinedFile: File, targetFile: File): Result<File> {
        return runCatching {
            require(isConfinedWithin(quarantinedFile, quarantineDir)) {
                "Source file is outside quarantine directory"
            }
            require(isConfinedWithin(targetFile, downloadsDir)) {
                "Destination path is outside downloads directory"
            }
            targetFile.parentFile?.mkdirs()
            if (quarantinedFile.renameTo(targetFile)) {
                targetFile
            } else {
                quarantinedFile.inputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            output.write(buf, 0, r)
                        }
                    }
                }
                secureDelete(quarantinedFile)
                targetFile
            }
        }
    }

    fun cleanupAbandonedFiles(maxAgeMillis: Long = 24 * 60 * 60 * 1000L, activePaths: Set<String> = emptySet()): Int {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        var cleaned = 0
        quarantineDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in activePaths && file.lastModified() < cutoff) {
                secureDelete(file)
                cleaned++
            }
        }
        return cleaned
    }
}
