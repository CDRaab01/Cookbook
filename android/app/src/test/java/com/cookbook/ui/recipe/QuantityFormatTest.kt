package com.cookbook.ui.recipe

import org.junit.Test
import kotlin.test.assertEquals

class QuantityFormatTest {

    @Test
    fun `whole numbers render plainly`() {
        assertEquals("2", humanQuantity(2.0))
        assertEquals("0", humanQuantity(0.0))
        assertEquals("10", humanQuantity(10.0))
    }

    @Test
    fun `common fractions render as glyphs`() {
        assertEquals("½", humanQuantity(0.5))
        assertEquals("¼", humanQuantity(0.25))
        assertEquals("¾", humanQuantity(0.75))
        assertEquals("⅓", humanQuantity(1.0 / 3))
        assertEquals("⅔", humanQuantity(2.0 / 3))
        assertEquals("⅛", humanQuantity(0.125))
    }

    @Test
    fun `whole plus fraction combines`() {
        assertEquals("1½", humanQuantity(1.5))
        assertEquals("2¾", humanQuantity(2.75))
        assertEquals("1⅜", humanQuantity(1.375))
    }

    @Test
    fun `scaled ugly decimals snap to the intended fraction`() {
        // A ⅓-cup ingredient doubled = 0.6667; must read "⅔", not "0.67".
        assertEquals("⅔", humanQuantity((1.0 / 3) * 2))
        // ¾ tripled = 2.25 → "2¼".
        assertEquals("2¼", humanQuantity(0.75 * 3))
    }

    @Test
    fun `near-whole values round to the whole`() {
        assertEquals("3", humanQuantity(2.98))
        assertEquals("2", humanQuantity(2.02))
    }
}
