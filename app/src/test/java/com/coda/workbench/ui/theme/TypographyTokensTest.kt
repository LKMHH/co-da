package com.coda.workbench.ui.theme

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 冻结字体层级守护：与视觉稿 v1.1 §1.2 保持一致（T13）。
 */
class TypographyTokensTest {

    @Test
    fun `headlineMedium is page title 20 28 semibold`() {
        val t = CodaTypography.headlineMedium
        assertEquals(20f, t.fontSize.value)
        assertEquals(28f, t.lineHeight.value)
        assertEquals(FontWeight.SemiBold, t.fontWeight)
        assertEquals(0f, t.letterSpacing.value)
    }

    @Test
    fun `headlineSmall and titleLarge are record title 18 24 semibold`() {
        for (t in listOf(CodaTypography.headlineSmall, CodaTypography.titleLarge)) {
            assertEquals(18f, t.fontSize.value)
            assertEquals(24f, t.lineHeight.value)
            assertEquals(FontWeight.SemiBold, t.fontWeight)
        }
    }

    @Test
    fun `titleMedium is section title 16 22 semibold`() {
        val t = CodaTypography.titleMedium
        assertEquals(16f, t.fontSize.value)
        assertEquals(22f, t.lineHeight.value)
        assertEquals(FontWeight.SemiBold, t.fontWeight)
    }

    @Test
    fun `bodyLarge is content 16 24 regular`() {
        val t = CodaTypography.bodyLarge
        assertEquals(16f, t.fontSize.value)
        assertEquals(24f, t.lineHeight.value)
        assertEquals(FontWeight.Normal, t.fontWeight)
    }

    @Test
    fun `bodyMedium is aux 14 20 regular`() {
        for (t in listOf(CodaTypography.bodyMedium, CodaTypography.bodySmall)) {
            assertEquals(14f, t.fontSize.value)
            assertEquals(20f, t.lineHeight.value)
            assertEquals(FontWeight.Normal, t.fontWeight)
        }
    }

    @Test
    fun `labelLarge is button 15 semibold and labelMedium is status 14 semibold`() {
        assertEquals(15f, CodaTypography.labelLarge.fontSize.value)
        assertEquals(FontWeight.SemiBold, CodaTypography.labelLarge.fontWeight)
        assertEquals(14f, CodaTypography.labelMedium.fontSize.value)
        assertEquals(20f, CodaTypography.labelMedium.lineHeight.value)
        assertEquals(FontWeight.SemiBold, CodaTypography.labelMedium.fontWeight)
    }
}
