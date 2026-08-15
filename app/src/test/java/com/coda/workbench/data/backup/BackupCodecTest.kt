package com.coda.workbench.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupCodecTest {
    @Test
    fun dataRoundTripPreservesEveryField() {
        val data = sampleData()

        val json = BackupCodec.encodeData(data)
        val decoded = BackupCodec.decodeData(json)

        assertEquals(data, decoded)
    }

    @Test
    fun zipRoundTripWithManifestValidationPasses() {
        val data = sampleData()
        val dataJson = BackupCodec.encodeData(data)
        val manifest = BackupManifest(
            format = BackupCodec.FORMAT_NAME,
            formatVersion = BackupCodec.FORMAT_VERSION,
            minReaderVersion = BackupCodec.MIN_READER_VERSION,
            createdAt = 1L,
            appVersion = "0.1.0",
            counts = BackupCounts().of(data),
            dataSha256 = BackupCodec.sha256Hex(dataJson.toByteArray(StandardCharsets.UTF_8)),
        )
        val zip = BackupCodec.zipBytes(BackupCodec.encodeManifest(manifest), dataJson)

        val (parsedManifest, parsedData) = BackupCodec.unzip(zip)

        assertEquals(manifest, parsedManifest)
        assertEquals(data, parsedData)
    }

    @Test
    fun wrongDataHashIsRejected() {
        val data = sampleData()
        val dataJson = BackupCodec.encodeData(data)
        val manifest = BackupManifest(
            format = BackupCodec.FORMAT_NAME,
            formatVersion = BackupCodec.FORMAT_VERSION,
            minReaderVersion = BackupCodec.MIN_READER_VERSION,
            createdAt = 1L,
            appVersion = "0.1.0",
            counts = BackupCounts().of(data),
            dataSha256 = BackupCodec.sha256Hex("different".toByteArray()),
        )
        val zip = BackupCodec.zipBytes(BackupCodec.encodeManifest(manifest), dataJson)

        assertFailsWith<BackupValidationException> { BackupCodec.unzip(zip) }
    }

    @Test
    fun unknownZipEntryIsRejected() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupCodec.MANIFEST_ENTRY))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupCodec.DATA_ENTRY))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("virus.exe"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }

        assertFailsWith<BackupValidationException> { BackupCodec.unzip(out.toByteArray()) }
    }

    @Test
    fun invalidEnumIsRejected() {
        val data = sampleData().copy(
            faults = listOf(sampleData().faults.first().copy(lifecycleStatus = "BROKEN")),
        )
        assertFailsWith<BackupValidationException> { BackupCodec.validate(data) }
    }

    @Test
    fun missingForeignKeyIsRejected() {
        val data = sampleData().copy(
            deviceAliases = listOf(sampleData().deviceAliases.first().copy(deviceId = "ghost-device")),
        )
        assertFailsWith<BackupValidationException> { BackupCodec.validate(data) }
    }

    @Test
    fun derivedWorkLogWithoutSourceIsRejected() {
        val data = sampleData().copy(
            workLogs = listOf(sampleData().workLogs.first().copy(kind = "FAULT_DERIVED", sourceType = null, sourceId = null)),
        )
        assertFailsWith<BackupValidationException> { BackupCodec.validate(data) }
    }

    @Test
    fun manualWorkLogWithSourceIsRejected() {
        val data = sampleData().copy(
            workLogs = listOf(sampleData().workLogs.first().copy(sourceType = "FAULT_PROCESSING", sourceId = "p1")),
        )
        assertFailsWith<BackupValidationException> { BackupCodec.validate(data) }
    }

    @Test
    fun duplicateIdsAreRejected() {
        val data = sampleData().copy(
            devices = listOf(sampleData().devices.first(), sampleData().devices.first().copy(name = "改名")),
        )
        assertFailsWith<BackupValidationException> { BackupCodec.validate(data) }
    }

    @Test
    fun multipleCurrentAttendancesAreRejected() {
        val attendance = sampleData().attendance.first()
        val data = sampleData().copy(
            attendance = listOf(attendance, attendance.copy(id = "att-2", businessDate = "2026-08-15")),
        )
        assertFailsWith<BackupValidationException> { BackupCodec.validate(data) }
    }

    @Test
    fun wrongCountsInManifestAreRejected() {
        val data = sampleData()
        val dataJson = BackupCodec.encodeData(data)
        val manifest = BackupManifest(
            format = BackupCodec.FORMAT_NAME,
            formatVersion = BackupCodec.FORMAT_VERSION,
            minReaderVersion = BackupCodec.MIN_READER_VERSION,
            createdAt = 1L,
            appVersion = "0.1.0",
            counts = BackupCounts(devices = 999),
            dataSha256 = BackupCodec.sha256Hex(dataJson.toByteArray(StandardCharsets.UTF_8)),
        )
        val zip = BackupCodec.zipBytes(BackupCodec.encodeManifest(manifest), dataJson)

        assertFailsWith<BackupValidationException> { BackupCodec.unzip(zip) }
    }

    @Test
    fun newerReaderVersionIsRejected() {
        val json = """{"format":"coda-backup","formatVersion":1,"minReaderVersion":99,"dataSha256":"x"}"""
        assertFailsWith<BackupValidationException> { BackupCodec.decodeManifest(json) }
    }

    @Test
    fun emptyValidationPassesForEmptyBackup() {
        val empty = BackupData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        BackupCodec.validate(empty)
        assertTrue(BackupCounts().of(empty).devices == 0)
    }

    private fun sampleData(): BackupData = BackupData(
        devices = listOf(BackupDevice("d1", "1号雷达", "1号雷达", true, 1L, 2L)),
        deviceAliases = listOf(BackupDeviceAlias("a1", "d1", "雷达1", "雷达1", 1L, 2L)),
        faults = listOf(
            BackupFault("f1", "d1", "1号雷达", 1_000L, "信号弱", "OPEN", "p1", 1L, 2L, null),
            BackupFault("f2", "d1", "1号雷达", 2_000L, "已作废故障", "VOIDED", null, 1L, 2L, 3L),
        ),
        processings = listOf(
            BackupProcessing("p1", "f1", "DRAFT", null, null, null, "检查结果", null, null, null, null, 1L, 2L, null, null),
        ),
        workLogs = listOf(
            BackupWorkLog(
                id = "w1", kind = "MANUAL", content = "巡检", workDate = "2026-08-14",
                attendanceId = "att-1", attendanceKindSnapshot = "NORMAL", attendanceStartAt = 1L,
                attendanceEndAt = 2L, productionGroupSnapshot = null, shiftIdSnapshot = null,
                shiftBusinessDateSnapshot = null, shiftTypeSnapshot = null, shiftStartAtSnapshot = null,
                shiftEndAtSnapshot = null, isShiftChangeSnapshot = false, workResult = "正常",
                deviceId = "d1", area = null, deviceNameSnapshot = null, processingStartedAt = null,
                processingEndedAt = null, processedAt = null, restoreResult = null,
                arrangementSource = "MANUAL", sourceType = null, sourceId = null,
                status = "ACTIVE", createdAt = 1L, updatedAt = 2L, voidedAt = null,
            ),
        ),
        handoverItems = listOf(
            BackupHandoverItem(
                id = "h1", summary = "待跟进", status = "PENDING_HANDOVER", nextAction = "查线",
                dueKind = "NONE", dueAt = null, originType = "MANUAL", sourceType = null, sourceId = null,
                handoverGroup = null, potentialHazardNote = null, lastOverdueNoticeDate = null,
                createdAt = 1L, updatedAt = 2L, completedAt = null, voidedAt = null,
            ),
        ),
        attendance = listOf(
            BackupAttendance(
                id = "att-1", businessDate = "2026-08-14", kind = "NORMAL", startAt = 1L, endAt = 2L,
                productionGroup = null, shiftId = null, shiftBusinessDate = null, shiftType = null,
                shiftStartAt = null, shiftEndAt = null, isShiftChange = false, isCurrent = true,
                createdAt = 1L, updatedAt = 2L,
            ),
        ),
        shiftPlans = listOf(BackupShiftPlan("sp1", "2026-08", 1, 16, 1L, 1L, 2L)),
        shiftSlots = listOf(
            BackupShiftSlot("s1", "sp1", "2026-08-14", "A", "DAY", 1L, 2L, false, "SUGGESTED", 1L, 2L),
        ),
    )
}
