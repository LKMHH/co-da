package com.coda.workbench.platform

import android.content.Context
import org.json.JSONObject
import java.io.File

data class RestoreJournal(
    val phase: String,
    val safetyFile: String,
    val createdAt: Long,
)

/**
 * M7 备份文件存取（技术稿 §10.2/§10.3）：
 * - 临时目录在 cache；安全备份在 files/backups/safety（默认保留最近 3 份）；
 * - restore-journal.json 记录 PREPARED/COMMITTED 阶段与安全备份文件名，进程中断后启动回滚判定。
 */
class BackupFileStore(context: Context) {
    private val tempDir = File(context.cacheDir, "backup-tmp")
    private val backupsDir = File(context.filesDir, "backups")
    private val safetyDir = File(backupsDir, "safety")
    private val journalFile = File(backupsDir, "restore-journal.json")

    companion object {
        const val SAFETY_KEEP = 3
    }

    fun createTempFile(): File {
        tempDir.mkdirs()
        return File(tempDir, "import-${System.currentTimeMillis()}.coda-backup")
    }

    fun clearTemp() {
        tempDir.listFiles()?.forEach { it.delete() }
    }

    fun writeSafetyBackup(bytes: ByteArray): File {
        safetyDir.mkdirs()
        val file = File(safetyDir, "safety-${System.currentTimeMillis()}.coda-backup")
        file.writeBytes(bytes)
        rotateSafetyBackups()
        return file
    }

    fun safetyFile(name: String): File = File(safetyDir, name)

    private fun rotateSafetyBackups() {
        val files = safetyDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(SAFETY_KEEP).forEach { it.delete() }
    }

    fun writeJournal(phase: String, safetyFile: File) {
        backupsDir.mkdirs()
        val json = JSONObject().apply {
            put("phase", phase)
            put("safetyFile", safetyFile.name)
            put("createdAt", System.currentTimeMillis())
        }.toString()
        // 原子写入：先写临时文件再重命名，避免中途被杀留下损坏 journal（技术稿 §10.3）
        val temp = File(backupsDir, "restore-journal.tmp")
        temp.writeText(json)
        if (!temp.renameTo(journalFile)) {
            journalFile.writeText(json)
            temp.delete()
        }
    }

    fun readJournal(): RestoreJournal? {
        if (!journalFile.exists()) return null
        return runCatching {
            val obj = JSONObject(journalFile.readText())
            RestoreJournal(
                phase = obj.optString("phase"),
                safetyFile = obj.optString("safetyFile"),
                createdAt = obj.optLong("createdAt"),
            )
        }.getOrNull()
    }

    fun clearJournal() {
        journalFile.delete()
    }
}
