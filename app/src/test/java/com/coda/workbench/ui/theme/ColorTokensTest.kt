package com.coda.workbench.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 冻结色值守护：与视觉稿 v1.1 §1.1 / UI 稿 §3.1 保持一致（T12）。
 */
class ColorTokensTest {

    @Test
    fun `semantic tokens match frozen spec`() {
        assertEquals(Color(0xFFF6F7F9), CodaSurfacePage)
        assertEquals(Color(0xFFFFFFFF), CodaSurfaceContent)
        assertEquals(Color(0xFF18232D), CodaTextPrimary)
        assertEquals(Color(0xFF64717D), CodaTextSecondary)
        assertEquals(Color(0xFF1F5E8C), CodaActionPrimary)
        assertEquals(Color(0xFF2F7D4A), CodaStatusSuccess)
        assertEquals(Color(0xFFB4691D), CodaStatusAttention)
        assertEquals(Color(0xFFB3433A), CodaStatusDanger)
        assertEquals(Color(0xFF356B83), CodaStatusInfo)
        assertEquals(Color(0xFFD8DEE4), CodaBorderDefault)
        assertEquals(Color(0xFF1F5E8C), CodaFocusRing)
    }
}
