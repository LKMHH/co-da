package com.coda.workbench.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.coda.workbench.data.repository.FaultDraftRepository
import java.time.Clock

@Database(
    entities = [
        DeviceEntity::class,
        DeviceAliasEntity::class,
        FaultRecordEntity::class,
        FaultProcessingEntity::class,
        WorkLogEntity::class,
        HandoverItemEntity::class,
        AttendanceEntity::class,
        MonthlyShiftPlanEntity::class,
        ShiftSlotEntity::class,
        BackupImportLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class CodaDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun deviceAliasDao(): DeviceAliasDao
    abstract fun faultRecordDao(): FaultRecordDao
    abstract fun faultProcessingDao(): FaultProcessingDao
    abstract fun workLogDao(): WorkLogDao
    abstract fun handoverItemDao(): HandoverItemDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun monthlyShiftPlanDao(): MonthlyShiftPlanDao
    abstract fun shiftSlotDao(): ShiftSlotDao
    abstract fun backupImportLogDao(): BackupImportLogDao

    fun faultDraftRepository(clock: Clock = Clock.systemUTC()): FaultDraftRepository =
        FaultDraftRepository(this, clock)

    companion object {
        const val DATABASE_NAME = "coda.db"

        fun build(context: Context): CodaDatabase =
            Room.databaseBuilder(context, CodaDatabase::class.java, DATABASE_NAME)
                .addCallback(CodaDatabaseCallback())
                .build()
    }
}

class CodaDatabaseCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        DatabaseSql.recreateWorkLogWithCheck(db)
        DatabaseSql.ensureSchemaIndexes(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        DatabaseSql.ensureSchemaIndexes(db)
    }
}

object DatabaseSql {
    fun ensureAutoHandoverIndex(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS ux_handover_auto_source
            ON handover_item(originType, sourceType, sourceId)
            WHERE originType = 'AUTO_FAULT_PROCESSING'
              AND sourceType IS NOT NULL
              AND sourceId IS NOT NULL
            """.trimIndent(),
        )
    }

    fun ensureSchemaIndexes(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ux_work_log_source ON work_log(sourceType, sourceId)")
        ensureAutoHandoverIndex(db)
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ux_attendance_current ON attendance(isCurrent) WHERE isCurrent = 1")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fault_record_device_reported ON fault_record(deviceId, reportedAt DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fault_processing_fault_created ON fault_processing(faultId, createdAt ASC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_work_log_date_updated ON work_log(workDate, updatedAt DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_work_log_attendance_updated ON work_log(attendanceId, updatedAt DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_handover_status_due ON handover_item(status, dueAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_attendance_start_end ON attendance(startAt, endAt)")
    }

    fun recreateWorkLogWithCheck(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS work_log_checked (
                id TEXT NOT NULL PRIMARY KEY,
                kind TEXT NOT NULL,
                content TEXT NOT NULL,
                workDate TEXT NOT NULL,
                attendanceId TEXT,
                attendanceKindSnapshot TEXT,
                attendanceStartAt INTEGER,
                attendanceEndAt INTEGER,
                productionGroupSnapshot TEXT,
                shiftIdSnapshot TEXT,
                shiftBusinessDateSnapshot TEXT,
                shiftTypeSnapshot TEXT,
                shiftStartAtSnapshot INTEGER,
                shiftEndAtSnapshot INTEGER,
                isShiftChangeSnapshot INTEGER,
                workResult TEXT,
                deviceId TEXT,
                area TEXT,
                deviceNameSnapshot TEXT,
                processingStartedAt INTEGER,
                processingEndedAt INTEGER,
                processedAt INTEGER,
                restoreResult TEXT,
                arrangementSource TEXT,
                sourceType TEXT,
                sourceId TEXT,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                voidedAt INTEGER,
                CHECK (
                    (kind = 'MANUAL' AND sourceType IS NULL AND sourceId IS NULL)
                    OR
                    (kind = 'FAULT_DERIVED'
                        AND sourceType = 'FAULT_PROCESSING'
                        AND sourceId IS NOT NULL)
                )
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO work_log_checked (
                id, kind, content, workDate, attendanceId, attendanceKindSnapshot,
                attendanceStartAt, attendanceEndAt, productionGroupSnapshot, shiftIdSnapshot,
                shiftBusinessDateSnapshot, shiftTypeSnapshot, shiftStartAtSnapshot,
                shiftEndAtSnapshot, isShiftChangeSnapshot, workResult, deviceId, area,
                deviceNameSnapshot, processingStartedAt, processingEndedAt, processedAt,
                restoreResult, arrangementSource, sourceType, sourceId, status, createdAt,
                updatedAt, voidedAt
            )
            SELECT
                id, kind, content, workDate, attendanceId, attendanceKindSnapshot,
                attendanceStartAt, attendanceEndAt, productionGroupSnapshot, shiftIdSnapshot,
                shiftBusinessDateSnapshot, shiftTypeSnapshot, shiftStartAtSnapshot,
                shiftEndAtSnapshot, isShiftChangeSnapshot, workResult, deviceId, area,
                deviceNameSnapshot, processingStartedAt, processingEndedAt, processedAt,
                restoreResult, arrangementSource, sourceType, sourceId, status, createdAt,
                updatedAt, voidedAt
            FROM work_log
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE work_log")
        db.execSQL("ALTER TABLE work_log_checked RENAME TO work_log")
    }

    fun createAllTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS device (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                normalizedName TEXT NOT NULL,
                isActive INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS device_alias (
                id TEXT NOT NULL PRIMARY KEY,
                deviceId TEXT NOT NULL,
                alias TEXT NOT NULL,
                normalizedAlias TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY(deviceId) REFERENCES device(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS fault_record (
                id TEXT NOT NULL PRIMARY KEY,
                deviceId TEXT NOT NULL,
                deviceNameSnapshot TEXT NOT NULL,
                reportedAt INTEGER NOT NULL,
                symptom TEXT NOT NULL,
                lifecycleStatus TEXT NOT NULL,
                lastProcessingId TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                voidedAt INTEGER,
                FOREIGN KEY(deviceId) REFERENCES device(id) ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS fault_processing (
                id TEXT NOT NULL PRIMARY KEY,
                faultId TEXT NOT NULL,
                progressStatus TEXT NOT NULL,
                restoreResult TEXT,
                startedAt INTEGER,
                endedAt INTEGER,
                checkResult TEXT,
                initialJudgement TEXT,
                rootCause TEXT,
                measures TEXT,
                verification TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                completedAt INTEGER,
                voidedAt INTEGER,
                FOREIGN KEY(faultId) REFERENCES fault_record(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS work_log (
                id TEXT NOT NULL PRIMARY KEY,
                kind TEXT NOT NULL,
                content TEXT NOT NULL,
                workDate TEXT NOT NULL,
                attendanceId TEXT,
                attendanceKindSnapshot TEXT,
                attendanceStartAt INTEGER,
                attendanceEndAt INTEGER,
                productionGroupSnapshot TEXT,
                shiftIdSnapshot TEXT,
                shiftBusinessDateSnapshot TEXT,
                shiftTypeSnapshot TEXT,
                shiftStartAtSnapshot INTEGER,
                shiftEndAtSnapshot INTEGER,
                isShiftChangeSnapshot INTEGER,
                workResult TEXT,
                deviceId TEXT,
                area TEXT,
                deviceNameSnapshot TEXT,
                processingStartedAt INTEGER,
                processingEndedAt INTEGER,
                processedAt INTEGER,
                restoreResult TEXT,
                arrangementSource TEXT,
                sourceType TEXT,
                sourceId TEXT,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                voidedAt INTEGER,
                CHECK (
                    (kind = 'MANUAL' AND sourceType IS NULL AND sourceId IS NULL)
                    OR
                    (kind = 'FAULT_DERIVED'
                        AND sourceType = 'FAULT_PROCESSING'
                        AND sourceId IS NOT NULL)
                )
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS handover_item (
                id TEXT NOT NULL PRIMARY KEY,
                summary TEXT NOT NULL,
                status TEXT NOT NULL,
                nextAction TEXT NOT NULL,
                dueKind TEXT NOT NULL,
                dueAt INTEGER,
                originType TEXT NOT NULL,
                sourceType TEXT,
                sourceId TEXT,
                handoverGroup TEXT,
                potentialHazardNote TEXT,
                lastOverdueNoticeDate TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                completedAt INTEGER,
                voidedAt INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS attendance (
                id TEXT NOT NULL PRIMARY KEY,
                businessDate TEXT NOT NULL,
                kind TEXT NOT NULL,
                startAt INTEGER NOT NULL,
                endAt INTEGER,
                productionGroup TEXT,
                shiftId TEXT,
                shiftBusinessDate TEXT,
                shiftType TEXT,
                shiftStartAt INTEGER,
                shiftEndAt INTEGER,
                isShiftChange INTEGER NOT NULL,
                isCurrent INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS monthly_shift_plan (
                id TEXT NOT NULL PRIMARY KEY,
                businessMonth TEXT NOT NULL,
                groupADayStart INTEGER NOT NULL,
                groupBDayStart INTEGER NOT NULL,
                confirmedAt INTEGER,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS shift_slot (
                id TEXT NOT NULL PRIMARY KEY,
                planId TEXT NOT NULL,
                businessDate TEXT NOT NULL,
                `group` TEXT NOT NULL,
                shiftType TEXT NOT NULL,
                startAt INTEGER NOT NULL,
                endAt INTEGER NOT NULL,
                isShiftChange INTEGER NOT NULL,
                source TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY(planId) REFERENCES monthly_shift_plan(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS backup_import_log (
                id TEXT NOT NULL PRIMARY KEY,
                startedAt INTEGER NOT NULL,
                endedAt INTEGER,
                fileSha256 TEXT NOT NULL,
                result TEXT NOT NULL,
                countsJson TEXT,
                errorMessage TEXT
            )
            """.trimIndent(),
        )
    }
}
