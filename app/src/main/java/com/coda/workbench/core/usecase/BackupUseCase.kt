package com.coda.workbench.core.usecase

import com.coda.workbench.data.backup.BackupCodec
import com.coda.workbench.data.backup.BackupCounts
import com.coda.workbench.data.backup.BackupExportResult
import com.coda.workbench.data.backup.BackupManifest
import com.coda.workbench.data.backup.BackupPreview
import com.coda.workbench.data.backup.BackupValidationException
import com.coda.workbench.data.backup.RestoreResult
import com.coda.workbench.data.repository.BackupRepository
import com.coda.workbench.platform.BackupDestination
import com.coda.workbench.platform.BackupFileStore
import com.coda.workbench.platform.BackupSource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock

/** 启动恢复回滚结果：interrupted=检测到 PREPARED 中断；rolledBack=是否成功用安全备份回滚。 */
data class RestoreRecoveryState(
    val interrupted: Boolean,
    val rolledBack: Boolean,
)

/**
 * M7 备份与恢复（技术稿 §5 BackupUseCase 契约的等价异常式实现，错误用中文异常抛出）：
 * - export：快照 → JSON/ZIP → SHA-256 → 临时文件校验 → 写入目标；
 * - inspect：复制到临时目录 → ZIP/manifest/哈希/DTO 全量校验 → 数量预览（不改动本机数据）；
 * - replace：校验 → 安全备份（保留 3 份）→ journal=PREPARED → 单事务替换 → journal=COMMITTED → 删除 journal，
 *   事务后重建通知调度（失败不回滚已完成替换）；
 * - recoverInterruptedRestore：启动时看到 PREPARED 无条件按 journal 用安全备份回滚，返回是否发生过中断。
 */
class BackupUseCase(
    private val repository: BackupRepository,
    private val fileStore: BackupFileStore,
    private val clock: Clock,
    private val appVersion: String,
    private val notificationTrigger: ShiftScheduleNotificationTrigger = NoOpShiftScheduleNotificationTrigger,
) {
    suspend fun export(destination: BackupDestination): BackupExportResult {
        val data = repository.snapshot()
        BackupCodec.validate(data)
        val dataJson = BackupCodec.encodeData(data)
        val sha256 = BackupCodec.sha256Hex(dataJson.toByteArray(Charsets.UTF_8))
        val manifest = BackupManifest(
            format = BackupCodec.FORMAT_NAME,
            formatVersion = BackupCodec.FORMAT_VERSION,
            minReaderVersion = BackupCodec.MIN_READER_VERSION,
            createdAt = clock.millis(),
            appVersion = appVersion,
            counts = BackupCounts().of(data),
            dataSha256 = sha256,
        )
        val zipBytes = BackupCodec.zipBytes(BackupCodec.encodeManifest(manifest), dataJson)

        // 先在临时文件自校验，再写入用户选定位置（技术稿 §10.2）
        val temp = fileStore.createTempFile()
        try {
            temp.writeBytes(zipBytes)
            BackupCodec.unzip(temp.readBytes())
            destination.openOutputStream().use { out -> temp.inputStream().use { it.copyTo(out) } }
        } finally {
            temp.delete()
        }
        return BackupExportResult(counts = manifest.counts, dataSha256 = sha256)
    }

    suspend fun inspect(source: BackupSource): BackupPreview {
        val bytes = readSourceToTemp(source)
        val (manifest, data) = BackupCodec.unzip(bytes)
        // 安全备份在用户确认前完成（技术稿 §10.3）；用户取消只留下可清理的安全备份（保留 3 份自动轮换）
        val safetyFile = fileStore.writeSafetyBackup(buildCurrentSafetyZip())
        return BackupPreview(counts = manifest.counts, data = data, safetyFile = safetyFile)
    }

    suspend fun replace(source: BackupSource, safetyFile: java.io.File): RestoreResult {
        val bytes = readSourceToTemp(source)
        val (manifest, data) = BackupCodec.unzip(bytes)
        val startedAt = clock.millis()

        // 1. journal = PREPARED（Room 事务提交与否不改变启动回滚判定；安全备份已在 inspect 完成）
        fileStore.writeJournal(PREPARED, safetyFile)

        // 2. 单事务替换
        var success = false
        try {
            repository.apply(data)
            success = true
        } catch (e: Exception) {
            repository.logImport(
                fileSha256 = manifest.dataSha256,
                result = "FAILURE",
                counts = manifest.counts,
                errorMessage = e.message,
                startedAt = startedAt,
                endedAt = clock.millis(),
            )
            throw e
        } finally {
            if (success) {
                fileStore.writeJournal(COMMITTED, safetyFile)
                fileStore.clearJournal()
                fileStore.clearTemp()
            }
            // 失败：journal 保持 PREPARED，下次启动无条件回滚
        }

        // 3. 事务提交后重建通知调度；调度失败不影响恢复结果
        runCatching { notificationTrigger.reconcileAll() }

        repository.logImport(
            fileSha256 = manifest.dataSha256,
            result = "SUCCESS",
            counts = manifest.counts,
            errorMessage = null,
            startedAt = startedAt,
            endedAt = clock.millis(),
        )
        return RestoreResult(counts = manifest.counts)
    }

    /** 启动恢复：看到 PREPARED 一律按安全备份回滚（不区分事务此前是否已提交）。 */
    suspend fun recoverInterruptedRestore(): RestoreRecoveryState {
        val journal = fileStore.readJournal() ?: return RestoreRecoveryState(interrupted = false, rolledBack = false)
        if (journal.phase != PREPARED) {
            fileStore.clearJournal()
            return RestoreRecoveryState(interrupted = false, rolledBack = false)
        }
        val safetyFile = fileStore.safetyFile(journal.safetyFile)
        var rolledBack = false
        if (safetyFile.exists()) {
            rolledBack = runCatching {
                val (_, data) = BackupCodec.unzip(safetyFile.readBytes())
                repository.apply(data)
            }.isSuccess
        }
        if (rolledBack) {
            fileStore.clearJournal()
        }
        // 回滚失败保留 journal，下次启动重试；UI 按 rolledBack 区分文案
        runCatching { notificationTrigger.reconcileAll() }
        return RestoreRecoveryState(interrupted = true, rolledBack = rolledBack)
    }

    private suspend fun buildCurrentSafetyZip(): ByteArray {
        val data = repository.snapshot()
        BackupCodec.validate(data)
        val dataJson = BackupCodec.encodeData(data)
        val manifest = BackupManifest(
            format = BackupCodec.FORMAT_NAME,
            formatVersion = BackupCodec.FORMAT_VERSION,
            minReaderVersion = BackupCodec.MIN_READER_VERSION,
            createdAt = clock.millis(),
            appVersion = appVersion,
            counts = BackupCounts().of(data),
            dataSha256 = BackupCodec.sha256Hex(dataJson.toByteArray(Charsets.UTF_8)),
        )
        return BackupCodec.zipBytes(BackupCodec.encodeManifest(manifest), dataJson)
    }

    private suspend fun readSourceToTemp(source: BackupSource): ByteArray {
        val out = ByteArrayOutputStream()
        source.openInputStream().use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > BackupCodec.MAX_TOTAL_BYTES) throw BackupValidationException("备份文件过大")
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    companion object {
        const val PREPARED = "PREPARED"
        const val COMMITTED = "COMMITTED"
    }
}
