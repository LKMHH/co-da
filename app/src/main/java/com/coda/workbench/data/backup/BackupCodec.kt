package com.coda.workbench.data.backup

import com.coda.workbench.data.local.AttendanceEntity
import com.coda.workbench.data.local.DeviceAliasEntity
import com.coda.workbench.data.local.DeviceEntity
import com.coda.workbench.data.local.FaultProcessingEntity
import com.coda.workbench.data.local.FaultRecordEntity
import com.coda.workbench.data.local.HandoverItemEntity
import com.coda.workbench.data.local.MonthlyShiftPlanEntity
import com.coda.workbench.data.local.ShiftSlotEntity
import com.coda.workbench.data.local.WorkLogEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupValidationException(message: String) : Exception(message)

/**
 * M7 备份编解码与校验（技术稿 §10）：
 * - ZIP 仅含 manifest.json + data.json，单包大小上限 64MB，条目去重；
 * - manifest 校验 format/formatVersion/minReaderVersion/dataSha256/counts；
 * - data 校验 UUID 非空、数组内唯一、枚举值、外键关系、work_log 来源约束、当前出勤唯一。
 */
object BackupCodec {
    const val MANIFEST_ENTRY = "manifest.json"
    const val DATA_ENTRY = "data.json"
    const val FORMAT_NAME = "coda-backup"
    const val FORMAT_VERSION = 1
    const val MIN_READER_VERSION = 1
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    private val ALLOWED = mapOf(
        "lifecycleStatus" to setOf("OPEN", "CLOSED", "VOIDED"),
        "progressStatus" to setOf("DRAFT", "IN_PROGRESS", "PENDING_VERIFICATION", "ENDED", "CANCELED"),
        "restoreResult" to setOf("RESTORED", "TEMPORARY", "PARTIAL", "NOT_RESTORED", "UNKNOWN"),
        "workLogKind" to setOf("MANUAL", "FAULT_DERIVED"),
        "workLogStatus" to setOf("ACTIVE", "VOIDED"),
        "handoverStatus" to setOf("PENDING_HANDOVER", "HANDED_OVER", "IN_PROGRESS", "COMPLETED", "CANCELED"),
        "dueKind" to setOf("NONE", "END_OF_TODAY", "NEXT_SHIFT", "SPECIFIC"),
        "attendanceKind" to setOf("NORMAL", "TOP_DAY", "TOP_NIGHT", "CUSTOM"),
        "group" to setOf("A", "B"),
        "shiftType" to setOf("DAY", "NIGHT"),
        "slotSource" to setOf("SUGGESTED", "MANUAL"),
    )

    // ---- ZIP ----

    fun zipBytes(manifestJson: String, dataJson: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifestJson.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DATA_ENTRY))
            zip.write(dataJson.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    fun unzip(bytes: ByteArray): Pair<BackupManifest, BackupData> {
        val entries = readZip(bytes)
        val manifestBytes = entries[MANIFEST_ENTRY]
            ?: throw BackupValidationException("备份文件缺少必要内容")
        val dataJsonBytes = entries[DATA_ENTRY]
            ?: throw BackupValidationException("备份文件缺少必要内容")
        val manifest = decodeManifest(manifestBytes.toString(StandardCharsets.UTF_8))
        val dataJson = dataJsonBytes.toString(StandardCharsets.UTF_8)
        // 先校验 SHA-256 再解析 DTO（技术稿 §10.3 顺序；哈希对象为原始字节）
        if (sha256Hex(dataJsonBytes) != manifest.dataSha256) {
            throw BackupValidationException("备份文件校验失败，文件可能已损坏")
        }
        val data = decodeData(dataJson)
        if (manifest.counts != manifest.counts.of(data)) {
            throw BackupValidationException("备份文件记录数量与清单不一致")
        }
        validate(data)
        return manifest to data
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        var total = 0L
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(BufferedInputStream(bytes.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name != MANIFEST_ENTRY && entry.name != DATA_ENTRY) {
                    throw BackupValidationException("备份文件格式不受支持")
                }
                if (entries.containsKey(entry.name)) {
                    throw BackupValidationException("备份文件包含重复条目")
                }
                val buffer = ByteArray(8192)
                val entryBytes = ByteArrayOutputStream()
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_TOTAL_BYTES) throw BackupValidationException("备份文件过大")
                    entryBytes.write(buffer, 0, n)
                }
                entries[entry.name] = entryBytes.toByteArray()
            }
        }
        if (MANIFEST_ENTRY !in entries || DATA_ENTRY !in entries) {
            throw BackupValidationException("备份文件缺少必要内容")
        }
        return entries
    }

    // ---- 哈希 ----

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // ---- manifest ----

    fun encodeManifest(manifest: BackupManifest): String = JSONObject().apply {
        put("format", manifest.format)
        put("formatVersion", manifest.formatVersion)
        put("minReaderVersion", manifest.minReaderVersion)
        put("createdAt", manifest.createdAt)
        put("appVersion", manifest.appVersion)
        put("counts", JSONObject().apply {
            put("devices", manifest.counts.devices)
            put("deviceAliases", manifest.counts.deviceAliases)
            put("faults", manifest.counts.faults)
            put("processings", manifest.counts.processings)
            put("workLogs", manifest.counts.workLogs)
            put("handoverItems", manifest.counts.handoverItems)
            put("attendance", manifest.counts.attendance)
            put("shiftPlans", manifest.counts.shiftPlans)
            put("shiftSlots", manifest.counts.shiftSlots)
        })
        put("dataSha256", manifest.dataSha256)
    }.toString()

    fun decodeManifest(json: String): BackupManifest {
        val obj = runCatching { JSONObject(json) }.getOrElse { throw BackupValidationException("备份文件格式不受支持") }
        val format = obj.optString("format")
        if (format != FORMAT_NAME) throw BackupValidationException("备份文件格式不受支持")
        if (obj.optInt("formatVersion") != FORMAT_VERSION) throw BackupValidationException("备份文件版本不受支持")
        if (obj.optInt("minReaderVersion") > MIN_READER_VERSION) throw BackupValidationException("备份内容与当前版本不兼容")
        val counts = obj.optJSONObject("counts")
            ?: throw BackupValidationException("备份文件缺少数量清单")
        return BackupManifest(
            format = format,
            formatVersion = obj.optInt("formatVersion"),
            minReaderVersion = obj.optInt("minReaderVersion"),
            createdAt = obj.optLong("createdAt"),
            appVersion = obj.optString("appVersion"),
            counts = BackupCounts(
                devices = counts.optInt("devices"),
                deviceAliases = counts.optInt("deviceAliases"),
                faults = counts.optInt("faults"),
                processings = counts.optInt("processings"),
                workLogs = counts.optInt("workLogs"),
                handoverItems = counts.optInt("handoverItems"),
                attendance = counts.optInt("attendance"),
                shiftPlans = counts.optInt("shiftPlans"),
                shiftSlots = counts.optInt("shiftSlots"),
            ),
            dataSha256 = obj.optString("dataSha256"),
        )
    }

    // ---- data 编码 ----

    fun encodeData(data: BackupData): String = JSONObject().apply {
        put("devices", JSONArray().apply { data.devices.forEach { put(it.toJson()) } })
        put("deviceAliases", JSONArray().apply { data.deviceAliases.forEach { put(it.toJson()) } })
        put("faults", JSONArray().apply { data.faults.forEach { put(it.toJson()) } })
        put("processings", JSONArray().apply { data.processings.forEach { put(it.toJson()) } })
        put("workLogs", JSONArray().apply { data.workLogs.forEach { put(it.toJson()) } })
        put("handoverItems", JSONArray().apply { data.handoverItems.forEach { put(it.toJson()) } })
        put("attendance", JSONArray().apply { data.attendance.forEach { put(it.toJson()) } })
        put("shiftPlans", JSONArray().apply { data.shiftPlans.forEach { put(it.toJson()) } })
        put("shiftSlots", JSONArray().apply { data.shiftSlots.forEach { put(it.toJson()) } })
    }.toString()

    fun decodeData(json: String): BackupData {
        val obj = runCatching { JSONObject(json) }.getOrElse { throw BackupValidationException("备份内容解析失败") }
        fun array(name: String): JSONArray = obj.optJSONArray(name)
            ?: throw BackupValidationException("备份内容缺少 $name")
        return BackupData(
            devices = array("devices").mapObjects { deviceFromJson(it) },
            deviceAliases = array("deviceAliases").mapObjects { deviceAliasFromJson(it) },
            faults = array("faults").mapObjects { faultFromJson(it) },
            processings = array("processings").mapObjects { processingFromJson(it) },
            workLogs = array("workLogs").mapObjects { workLogFromJson(it) },
            handoverItems = array("handoverItems").mapObjects { handoverFromJson(it) },
            attendance = array("attendance").mapObjects { attendanceFromJson(it) },
            shiftPlans = array("shiftPlans").mapObjects { shiftPlanFromJson(it) },
            shiftSlots = array("shiftSlots").mapObjects { shiftSlotFromJson(it) },
        )
    }

    private inline fun <reified T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> {
        val result = ArrayList<T>(length())
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: throw BackupValidationException("备份内容存在无效条目")
            result += block(item)
        }
        return result
    }

    // ---- 结构校验 ----

    fun validate(data: BackupData) {
        requireUniqueIds(data.devices.map { it.id }, "设备")
        requireUniqueIds(data.deviceAliases.map { it.id }, "设备别名")
        requireUniqueIds(data.faults.map { it.id }, "故障")
        requireUniqueIds(data.processings.map { it.id }, "处理记录")
        requireUniqueIds(data.workLogs.map { it.id }, "工作记录")
        requireUniqueIds(data.handoverItems.map { it.id }, "交接事项")
        requireUniqueIds(data.attendance.map { it.id }, "出勤")
        requireUniqueIds(data.shiftPlans.map { it.id }, "排班月份")
        requireUniqueIds(data.shiftSlots.map { it.id }, "班次")

        val deviceIds = data.devices.map { it.id }.toSet()
        val faultIds = data.faults.map { it.id }.toSet()
        val processingIds = data.processings.map { it.id }.toSet()
        val attendanceIds = data.attendance.map { it.id }.toSet()
        val planIds = data.shiftPlans.map { it.id }.toSet()

        data.deviceAliases.forEach { alias ->
            if (alias.deviceId !in deviceIds) throw BackupValidationException("备份内容引用不存在的设备")
        }
        data.faults.forEach { fault ->
            if (fault.deviceId !in deviceIds) throw BackupValidationException("备份内容引用不存在的设备")
            if (fault.lifecycleStatus !in ALLOWED.getValue("lifecycleStatus")) throw BackupValidationException("备份内容存在无效状态")
            fault.lastProcessingId?.let { if (it !in processingIds) throw BackupValidationException("备份内容引用不存在的处理记录") }
        }
        data.processings.forEach { processing ->
            if (processing.faultId !in faultIds) throw BackupValidationException("备份内容引用不存在的故障")
            if (processing.progressStatus !in ALLOWED.getValue("progressStatus")) throw BackupValidationException("备份内容存在无效状态")
            processing.restoreResult?.let { if (it !in ALLOWED.getValue("restoreResult")) throw BackupValidationException("备份内容存在无效结果") }
        }
        data.workLogs.forEach { log ->
            if (log.kind !in ALLOWED.getValue("workLogKind")) throw BackupValidationException("备份内容存在无效工作类型")
            if (log.status !in ALLOWED.getValue("workLogStatus")) throw BackupValidationException("备份内容存在无效状态")
            if (log.kind == "MANUAL" && (log.sourceType != null || log.sourceId != null)) {
                throw BackupValidationException("备份内容存在无效工作来源")
            }
            if (log.kind == "FAULT_DERIVED" && (log.sourceType != "FAULT_PROCESSING" || log.sourceId !in processingIds)) {
                throw BackupValidationException("备份内容存在无效工作来源")
            }
            log.deviceId?.let { if (it !in deviceIds) throw BackupValidationException("备份内容引用不存在的设备") }
            log.attendanceId?.let { if (it !in attendanceIds) throw BackupValidationException("备份内容引用不存在的出勤") }
        }
        val derivedSources = data.workLogs
            .filter { it.kind == "FAULT_DERIVED" }
            .map { it.sourceType to it.sourceId }
        if (derivedSources.distinct().size != derivedSources.size) {
            throw BackupValidationException("备份内容存在重复工作来源")
        }
        data.handoverItems.forEach { item ->
            if (item.status !in ALLOWED.getValue("handoverStatus")) throw BackupValidationException("备份内容存在无效状态")
            if (item.dueKind !in ALLOWED.getValue("dueKind")) throw BackupValidationException("备份内容存在无效期限")
            if (item.originType == "AUTO_FAULT_PROCESSING" &&
                (item.sourceType != "FAULT_PROCESSING" || item.sourceId !in processingIds)
            ) {
                throw BackupValidationException("备份内容存在无效交接来源")
            }
        }
        val autoHandoverSources = data.handoverItems
            .filter { it.originType == "AUTO_FAULT_PROCESSING" }
            .map { it.sourceType to it.sourceId }
        if (autoHandoverSources.distinct().size != autoHandoverSources.size) {
            throw BackupValidationException("备份内容存在重复交接来源")
        }
        data.attendance.forEach { attendance ->
            if (attendance.kind !in ALLOWED.getValue("attendanceKind")) throw BackupValidationException("备份内容存在无效出勤类型")
            attendance.productionGroup?.let { if (it !in ALLOWED.getValue("group")) throw BackupValidationException("备份内容存在无效班组") }
            attendance.shiftType?.let { if (it !in ALLOWED.getValue("shiftType")) throw BackupValidationException("备份内容存在无效班别") }
            if (attendance.startAt >= (attendance.endAt ?: Long.MAX_VALUE)) throw BackupValidationException("备份内容存在无效出勤时间")
        }
        data.shiftPlans.forEach { plan ->
            if (plan.businessMonth.isBlank()) throw BackupValidationException("备份内容存在无效排班月份")
        }
        if (data.shiftPlans.map { it.businessMonth }.distinct().size != data.shiftPlans.size) {
            throw BackupValidationException("备份内容存在重复排班月份")
        }
        data.shiftSlots.forEach { slot ->
            if (slot.planId !in planIds) throw BackupValidationException("备份内容引用不存在的排班月份")
            if (slot.group !in ALLOWED.getValue("group")) throw BackupValidationException("备份内容存在无效班组")
            if (slot.shiftType !in ALLOWED.getValue("shiftType")) throw BackupValidationException("备份内容存在无效班别")
            if (slot.source !in ALLOWED.getValue("slotSource")) throw BackupValidationException("备份内容存在无效班次来源")
            if (slot.startAt >= slot.endAt) throw BackupValidationException("备份内容存在无效班次时间")
        }
        val slotKeys = data.shiftSlots.map { Triple(it.businessDate, it.group, it.shiftType) }
        if (slotKeys.distinct().size != slotKeys.size) throw BackupValidationException("备份内容存在重复班次")
        if (data.attendance.count { it.isCurrent } > 1) throw BackupValidationException("备份内容存在多条当前出勤")
    }

    private fun requireUniqueIds(ids: List<String>, label: String) {
        ids.forEach { if (it.isBlank()) throw BackupValidationException("备份内容存在空 $label 标识") }
        if (ids.distinct().size != ids.size) throw BackupValidationException("备份内容存在重复$label")
    }

    // ---- JSON 助手 ----

    private fun JSONObject.putNullable(name: String, value: String?) {
        if (value != null) put(name, value)
    }

    private fun JSONObject.putNullable(name: String, value: Long?) {
        if (value != null) put(name, value)
    }

    private fun JSONObject.putNullable(name: String, value: Boolean?) {
        if (value != null) put(name, value)
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (has(name) && !isNull(name)) optString(name) else null

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null

    private fun JSONObject.optNullableBoolean(name: String): Boolean? =
        if (has(name) && !isNull(name)) optBoolean(name) else null

    // ---- DTO -> JSON ----

    private fun BackupDevice.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("normalizedName", normalizedName)
        put("isActive", isActive); put("createdAt", createdAt); put("updatedAt", updatedAt)
    }

    private fun BackupDeviceAlias.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("deviceId", deviceId); put("alias", alias)
        put("normalizedAlias", normalizedAlias); put("createdAt", createdAt); put("updatedAt", updatedAt)
    }

    private fun BackupFault.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("deviceId", deviceId); put("deviceNameSnapshot", deviceNameSnapshot)
        put("reportedAt", reportedAt); put("symptom", symptom); put("lifecycleStatus", lifecycleStatus)
        putNullable("lastProcessingId", lastProcessingId)
        put("createdAt", createdAt); put("updatedAt", updatedAt); putNullable("voidedAt", voidedAt)
    }

    private fun BackupProcessing.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("faultId", faultId); put("progressStatus", progressStatus)
        putNullable("restoreResult", restoreResult)
        putNullable("startedAt", startedAt); putNullable("endedAt", endedAt)
        putNullable("checkResult", checkResult); putNullable("initialJudgement", initialJudgement)
        putNullable("rootCause", rootCause); putNullable("measures", measures)
        putNullable("verification", verification)
        put("createdAt", createdAt); put("updatedAt", updatedAt)
        putNullable("completedAt", completedAt); putNullable("voidedAt", voidedAt)
    }

    private fun BackupWorkLog.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind); put("content", content); put("workDate", workDate)
        putNullable("attendanceId", attendanceId); putNullable("attendanceKindSnapshot", attendanceKindSnapshot)
        putNullable("attendanceStartAt", attendanceStartAt); putNullable("attendanceEndAt", attendanceEndAt)
        putNullable("productionGroupSnapshot", productionGroupSnapshot)
        putNullable("shiftIdSnapshot", shiftIdSnapshot)
        putNullable("shiftBusinessDateSnapshot", shiftBusinessDateSnapshot)
        putNullable("shiftTypeSnapshot", shiftTypeSnapshot)
        putNullable("shiftStartAtSnapshot", shiftStartAtSnapshot)
        putNullable("shiftEndAtSnapshot", shiftEndAtSnapshot)
        putNullable("isShiftChangeSnapshot", isShiftChangeSnapshot)
        putNullable("workResult", workResult); putNullable("deviceId", deviceId); putNullable("area", area)
        putNullable("deviceNameSnapshot", deviceNameSnapshot)
        putNullable("processingStartedAt", processingStartedAt)
        putNullable("processingEndedAt", processingEndedAt)
        putNullable("processedAt", processedAt); putNullable("restoreResult", restoreResult)
        putNullable("arrangementSource", arrangementSource)
        putNullable("sourceType", sourceType); putNullable("sourceId", sourceId)
        put("status", status); put("createdAt", createdAt); put("updatedAt", updatedAt)
        putNullable("voidedAt", voidedAt)
    }

    private fun BackupHandoverItem.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("summary", summary); put("status", status); put("nextAction", nextAction)
        put("dueKind", dueKind); putNullable("dueAt", dueAt)
        put("originType", originType); putNullable("sourceType", sourceType); putNullable("sourceId", sourceId)
        putNullable("handoverGroup", handoverGroup)
        putNullable("potentialHazardNote", potentialHazardNote)
        putNullable("lastOverdueNoticeDate", lastOverdueNoticeDate)
        put("createdAt", createdAt); put("updatedAt", updatedAt)
        putNullable("completedAt", completedAt); putNullable("voidedAt", voidedAt)
    }

    private fun BackupAttendance.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("businessDate", businessDate); put("kind", kind)
        put("startAt", startAt); putNullable("endAt", endAt)
        putNullable("productionGroup", productionGroup); putNullable("shiftId", shiftId)
        putNullable("shiftBusinessDate", shiftBusinessDate); putNullable("shiftType", shiftType)
        putNullable("shiftStartAt", shiftStartAt); putNullable("shiftEndAt", shiftEndAt)
        put("isShiftChange", isShiftChange); put("isCurrent", isCurrent)
        put("createdAt", createdAt); put("updatedAt", updatedAt)
    }

    private fun BackupShiftPlan.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("businessMonth", businessMonth)
        put("groupADayStart", groupADayStart); put("groupBDayStart", groupBDayStart)
        putNullable("confirmedAt", confirmedAt)
        put("createdAt", createdAt); put("updatedAt", updatedAt)
    }

    private fun BackupShiftSlot.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("planId", planId); put("businessDate", businessDate)
        put("group", group); put("shiftType", shiftType)
        put("startAt", startAt); put("endAt", endAt); put("isShiftChange", isShiftChange)
        put("source", source); put("createdAt", createdAt); put("updatedAt", updatedAt)
    }

    // ---- JSON -> DTO ----

    private fun deviceFromJson(obj: JSONObject): BackupDevice = BackupDevice(
        id = obj.optString("id"), name = obj.optString("name"), normalizedName = obj.optString("normalizedName"),
        isActive = obj.optBoolean("isActive"), createdAt = obj.optLong("createdAt"), updatedAt = obj.optLong("updatedAt"),
    )

    private fun deviceAliasFromJson(obj: JSONObject): BackupDeviceAlias = BackupDeviceAlias(
        id = obj.optString("id"), deviceId = obj.optString("deviceId"),
        alias = obj.optString("alias"), normalizedAlias = obj.optString("normalizedAlias"),
        createdAt = obj.optLong("createdAt"), updatedAt = obj.optLong("updatedAt"),
    )

    private fun faultFromJson(obj: JSONObject): BackupFault = BackupFault(
        id = obj.optString("id"), deviceId = obj.optString("deviceId"),
        deviceNameSnapshot = obj.optString("deviceNameSnapshot"),
        reportedAt = obj.optLong("reportedAt"), symptom = obj.optString("symptom"),
        lifecycleStatus = obj.optString("lifecycleStatus"),
        lastProcessingId = obj.optNullableString("lastProcessingId"),
        createdAt = obj.optLong("createdAt"), updatedAt = obj.optLong("updatedAt"),
        voidedAt = obj.optNullableLong("voidedAt"),
    )

    private fun processingFromJson(obj: JSONObject): BackupProcessing = BackupProcessing(
        id = obj.optString("id"), faultId = obj.optString("faultId"),
        progressStatus = obj.optString("progressStatus"),
        restoreResult = obj.optNullableString("restoreResult"),
        startedAt = obj.optNullableLong("startedAt"), endedAt = obj.optNullableLong("endedAt"),
        checkResult = obj.optNullableString("checkResult"),
        initialJudgement = obj.optNullableString("initialJudgement"),
        rootCause = obj.optNullableString("rootCause"),
        measures = obj.optNullableString("measures"),
        verification = obj.optNullableString("verification"),
        createdAt = obj.optLong("createdAt"), updatedAt = obj.optLong("updatedAt"),
        completedAt = obj.optNullableLong("completedAt"), voidedAt = obj.optNullableLong("voidedAt"),
    )

    private fun workLogFromJson(obj: JSONObject): BackupWorkLog = BackupWorkLog(
        id = obj.optString("id"), kind = obj.optString("kind"),
        content = obj.optString("content"), workDate = obj.optString("workDate"),
        attendanceId = obj.optNullableString("attendanceId"),
        attendanceKindSnapshot = obj.optNullableString("attendanceKindSnapshot"),
        attendanceStartAt = obj.optNullableLong("attendanceStartAt"),
        attendanceEndAt = obj.optNullableLong("attendanceEndAt"),
        productionGroupSnapshot = obj.optNullableString("productionGroupSnapshot"),
        shiftIdSnapshot = obj.optNullableString("shiftIdSnapshot"),
        shiftBusinessDateSnapshot = obj.optNullableString("shiftBusinessDateSnapshot"),
        shiftTypeSnapshot = obj.optNullableString("shiftTypeSnapshot"),
        shiftStartAtSnapshot = obj.optNullableLong("shiftStartAtSnapshot"),
        shiftEndAtSnapshot = obj.optNullableLong("shiftEndAtSnapshot"),
        isShiftChangeSnapshot = obj.optNullableBoolean("isShiftChangeSnapshot"),
        workResult = obj.optNullableString("workResult"),
        deviceId = obj.optNullableString("deviceId"),
        area = obj.optNullableString("area"),
        deviceNameSnapshot = obj.optNullableString("deviceNameSnapshot"),
        processingStartedAt = obj.optNullableLong("processingStartedAt"),
        processingEndedAt = obj.optNullableLong("processingEndedAt"),
        processedAt = obj.optNullableLong("processedAt"),
        restoreResult = obj.optNullableString("restoreResult"),
        arrangementSource = obj.optNullableString("arrangementSource"),
        sourceType = obj.optNullableString("sourceType"),
        sourceId = obj.optNullableString("sourceId"),
        status = obj.optString("status"),
        createdAt = obj.optLong("createdAt"), updatedAt = obj.optLong("updatedAt"),
        voidedAt = obj.optNullableLong("voidedAt"),
    )

    private fun handoverFromJson(obj: JSONObject): BackupHandoverItem = BackupHandoverItem(
        id = obj.optString("id"), summary = obj.optString("summary"), status = obj.optString("status"),
        nextAction = obj.optString("nextAction"), dueKind = obj.optString("dueKind"),
        dueAt = obj.optNullableLong("dueAt"), originType = obj.optString("originType"),
        sourceType = obj.optNullableString("sourceType"), sourceId = obj.optNullableString("sourceId"),
        handoverGroup = obj.optNullableString("handoverGroup"),
        potentialHazardNote = obj.optNullableString("potentialHazardNote"),
        lastOverdueNoticeDate = obj.optNullableString("lastOverdueNoticeDate"),
        createdAt = obj.optLong("createdAt"), updatedAt = obj.optLong("updatedAt"),
        completedAt = obj.optNullableLong("completedAt"), voidedAt = obj.optNullableLong("voidedAt"),
    )

    private fun attendanceFromJson(obj: JSONObject): BackupAttendance = BackupAttendance(
        id = obj.optString("id"), businessDate = obj.optString("businessDate"), kind = obj.optString("kind"),
        startAt = obj.optLong("startAt"), endAt = obj.optNullableLong("endAt"),
        productionGroup = obj.optNullableString("productionGroup"),
        shiftId = obj.optNullableString("shiftId"),
        shiftBusinessDate = obj.optNullableString("shiftBusinessDate"),
        shiftType = obj.optNullableString("shiftType"),
        shiftStartAt = obj.optNullableLong("shiftStartAt"),
        shiftEndAt = obj.optNullableLong("shiftEndAt"),
        isShiftChange = obj.optBoolean("isShiftChange"), isCurrent = obj.optBoolean("isCurrent"),
        createdAt = obj.optLong("createdAt"), updatedAt = obj.optLong("updatedAt"),
    )

    private fun shiftPlanFromJson(obj: JSONObject): BackupShiftPlan = BackupShiftPlan(
        id = obj.optString("id"), businessMonth = obj.optString("businessMonth"),
        groupADayStart = obj.optInt("groupADayStart"), groupBDayStart = obj.optInt("groupBDayStart"),
        confirmedAt = obj.optNullableLong("confirmedAt"),
        createdAt = obj.optLong("createdAt"), updatedAt = obj.optLong("updatedAt"),
    )

    private fun shiftSlotFromJson(obj: JSONObject): BackupShiftSlot = BackupShiftSlot(
        id = obj.optString("id"), planId = obj.optString("planId"),
        businessDate = obj.optString("businessDate"), group = obj.optString("group"),
        shiftType = obj.optString("shiftType"),
        startAt = obj.optLong("startAt"), endAt = obj.optLong("endAt"),
        isShiftChange = obj.optBoolean("isShiftChange"), source = obj.optString("source"),
        createdAt = obj.optLong("createdAt"), updatedAt = obj.optLong("updatedAt"),
    )
}

// ---- Entity <-> DTO ----

fun DeviceEntity.toBackupDto(): BackupDevice = BackupDevice(id, name, normalizedName, isActive, createdAt, updatedAt)
fun BackupDevice.toEntity(): DeviceEntity = DeviceEntity(id, name, normalizedName, isActive, createdAt, updatedAt)

fun DeviceAliasEntity.toBackupDto(): BackupDeviceAlias = BackupDeviceAlias(id, deviceId, alias, normalizedAlias, createdAt, updatedAt)
fun BackupDeviceAlias.toEntity(): DeviceAliasEntity = DeviceAliasEntity(id, deviceId, alias, normalizedAlias, createdAt, updatedAt)

fun FaultRecordEntity.toBackupDto(): BackupFault =
    BackupFault(id, deviceId, deviceNameSnapshot, reportedAt, symptom, lifecycleStatus, lastProcessingId, createdAt, updatedAt, voidedAt)
fun BackupFault.toEntity(): FaultRecordEntity =
    FaultRecordEntity(id, deviceId, deviceNameSnapshot, reportedAt, symptom, lifecycleStatus, lastProcessingId, createdAt, updatedAt, voidedAt)

fun FaultProcessingEntity.toBackupDto(): BackupProcessing = BackupProcessing(
    id, faultId, progressStatus, restoreResult, startedAt, endedAt, checkResult, initialJudgement,
    rootCause, measures, verification, createdAt, updatedAt, completedAt, voidedAt,
)
fun BackupProcessing.toEntity(): FaultProcessingEntity = FaultProcessingEntity(
    id, faultId, progressStatus, restoreResult, startedAt, endedAt, checkResult, initialJudgement,
    rootCause, measures, verification, createdAt, updatedAt, completedAt, voidedAt,
)

fun WorkLogEntity.toBackupDto(): BackupWorkLog = BackupWorkLog(
    id, kind, content, workDate, attendanceId, attendanceKindSnapshot, attendanceStartAt, attendanceEndAt,
    productionGroupSnapshot, shiftIdSnapshot, shiftBusinessDateSnapshot, shiftTypeSnapshot,
    shiftStartAtSnapshot, shiftEndAtSnapshot, isShiftChangeSnapshot, workResult, deviceId, area,
    deviceNameSnapshot, processingStartedAt, processingEndedAt, processedAt, restoreResult,
    arrangementSource, sourceType, sourceId, status, createdAt, updatedAt, voidedAt,
)
fun BackupWorkLog.toEntity(): WorkLogEntity = WorkLogEntity(
    id, kind, content, workDate, attendanceId, attendanceKindSnapshot, attendanceStartAt, attendanceEndAt,
    productionGroupSnapshot, shiftIdSnapshot, shiftBusinessDateSnapshot, shiftTypeSnapshot,
    shiftStartAtSnapshot, shiftEndAtSnapshot, isShiftChangeSnapshot, workResult, deviceId, area,
    deviceNameSnapshot, processingStartedAt, processingEndedAt, processedAt, restoreResult,
    arrangementSource, sourceType, sourceId, status, createdAt, updatedAt, voidedAt,
)

fun HandoverItemEntity.toBackupDto(): BackupHandoverItem = BackupHandoverItem(
    id, summary, status, nextAction, dueKind, dueAt, originType, sourceType, sourceId,
    handoverGroup, potentialHazardNote, lastOverdueNoticeDate, createdAt, updatedAt, completedAt, voidedAt,
)
fun BackupHandoverItem.toEntity(): HandoverItemEntity = HandoverItemEntity(
    id, summary, status, nextAction, dueKind, dueAt, originType, sourceType, sourceId,
    handoverGroup, potentialHazardNote, lastOverdueNoticeDate, createdAt, updatedAt, completedAt, voidedAt,
)

fun AttendanceEntity.toBackupDto(): BackupAttendance = BackupAttendance(
    id, businessDate, kind, startAt, endAt, productionGroup, shiftId, shiftBusinessDate, shiftType,
    shiftStartAt, shiftEndAt, isShiftChange, isCurrent, createdAt, updatedAt,
)
fun BackupAttendance.toEntity(): AttendanceEntity = AttendanceEntity(
    id, businessDate, kind, startAt, endAt, productionGroup, shiftId, shiftBusinessDate, shiftType,
    shiftStartAt, shiftEndAt, isShiftChange, isCurrent, createdAt, updatedAt,
)

fun MonthlyShiftPlanEntity.toBackupDto(): BackupShiftPlan =
    BackupShiftPlan(id, businessMonth, groupADayStart, groupBDayStart, confirmedAt, createdAt, updatedAt)
fun BackupShiftPlan.toEntity(): MonthlyShiftPlanEntity =
    MonthlyShiftPlanEntity(id, businessMonth, groupADayStart, groupBDayStart, confirmedAt, createdAt, updatedAt)

fun ShiftSlotEntity.toBackupDto(): BackupShiftSlot =
    BackupShiftSlot(id, planId, businessDate, group, shiftType, startAt, endAt, isShiftChange, source, createdAt, updatedAt)
fun BackupShiftSlot.toEntity(): ShiftSlotEntity =
    ShiftSlotEntity(id, planId, businessDate, group, shiftType, startAt, endAt, isShiftChange, source, createdAt, updatedAt)
