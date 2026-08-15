package com.coda.workbench

import com.coda.workbench.core.rules.TextRules
import kotlin.test.Test
import kotlin.test.assertEquals

/** M6 同义词扩展新增行为（TextRules.expandedTerms 增量）：多词查询、回波弱扩展、原文恒保留。 */
class TextRulesSearchExpansionTest {
    @Test
    fun echoWeakExpandsWithOriginalRetained() {
        assertEquals(
            setOf("回波弱", "有效回波偏弱"),
            TextRules.expandedTerms("回波弱"),
        )
    }

    @Test
    fun multiWordQueryContainingSynonymKeyExpandsVariants() {
        assertEquals(
            setOf("一号雷达 信号弱", "一号雷达 信号强度偏弱"),
            TextRules.expandedTerms("一号雷达 信号弱"),
        )
    }

    @Test
    fun unknownQueryKeepsOriginalOnly() {
        assertEquals(setOf("配电箱"), TextRules.expandedTerms(" 配电箱 "))
    }

    @Test
    fun originalQueryIsAlwaysIncludedWhenKeyRepeats() {
        // replace 替换全部出现处，结果 = 原文 + 全量替换变体
        assertEquals(
            setOf("信号弱 信号弱", "信号强度偏弱 信号强度偏弱"),
            TextRules.expandedTerms("信号弱 信号弱"),
        )
    }
}
