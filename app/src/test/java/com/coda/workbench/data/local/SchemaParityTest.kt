package com.coda.workbench.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * schema 事实来源守护（技术稿 §3.3 口径）：
 * schemas/1.json 只包含 Room 注解声明的 DDL；
 * 部分唯一索引、CHECK 约束与回调索引由 [DatabaseSql.INDEX_DDL_STATEMENTS] 持有，
 * 任何 Migration 必须重放该清单。
 */
class SchemaParityTest {

    @Test
    fun `callback index ddl is idempotent`() {
        DatabaseSql.INDEX_DDL_STATEMENTS.forEach { stmt ->
            assertTrue("回调 DDL 必须幂等（IF NOT EXISTS）：$stmt", stmt.contains("IF NOT EXISTS"))
        }
    }

    @Test
    fun `callback ddl contains unique and partial and covering indexes`() {
        val ddl = DatabaseSql.INDEX_DDL_STATEMENTS.joinToString("\n")
        assertTrue("缺少 ux_work_log_source 唯一索引", ddl.contains("ux_work_log_source"))
        assertTrue("缺少 ux_handover_auto_source 部分唯一索引", ddl.contains("ux_handover_auto_source"))
        assertTrue("缺少 ux_attendance_current 部分唯一索引", ddl.contains("ux_attendance_current"))
        assertTrue("缺少 idx_attendance_current_start 覆盖索引", ddl.contains("idx_attendance_current_start"))
    }

    @Test
    fun `exported schema does not duplicate callback owned ddl`() {
        val schema = File("schemas/com.coda.workbench.data.local.CodaDatabase/1.json")
        assertTrue("schemas/1.json 不存在（先执行一次编译以导出 schema）", schema.exists())
        val text = schema.readText()
        // 这些 DDL 由回调持有：Room 注解无法表达部分索引与 CHECK。
        // 若未来改为注解 + 数据库版本升级，需同步更新本测试与技术稿 §3.3。
        assertFalse("ux_work_log_source 不应出现在 1.json（回调持有）", text.contains("ux_work_log_source"))
        assertFalse("ux_handover_auto_source 不应出现在 1.json（回调持有）", text.contains("ux_handover_auto_source"))
        assertFalse("ux_attendance_current 不应出现在 1.json（回调持有）", text.contains("ux_attendance_current"))
        assertFalse("idx_attendance_current_start 不应出现在 1.json（回调持有）", text.contains("idx_attendance_current_start"))
    }
}
