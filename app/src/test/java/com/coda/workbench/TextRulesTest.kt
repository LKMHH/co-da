package com.coda.workbench

import com.coda.workbench.core.rules.TextRules
import kotlin.test.Test
import kotlin.test.assertEquals

class TextRulesTest {
    @Test
    fun normalizationUsesNfkcTrimAndWhitespaceCollapse() {
        assertEquals("信号弱", TextRules.normalize("  信号弱\n"))
        assertEquals("ABC 123", TextRules.normalize(" ＡＢＣ　１２３ "))
    }

    @Test
    fun knownSynonymExpandsWithoutDroppingOriginalQuery() {
        assertEquals(
            setOf("信号弱", "信号强度偏弱"),
            TextRules.expandedTerms(" 信号弱 "),
        )
    }

    @Test
    fun unknownSynonymStillUsesNormalizedOriginalQuery() {
        assertEquals(setOf("配电箱"), TextRules.expandedTerms(" 配电箱 "))
    }

    @Test
    fun likeWildcardsAndEscapeCharacterAreEscaped() {
        assertEquals("A\\%B\\_C\\\\D", TextRules.escapeLike("A%B_C\\D"))
        assertEquals("%A\\%B\\_C\\\\D%", TextRules.containsLikePattern(" A%B_C\\D "))
    }
}
