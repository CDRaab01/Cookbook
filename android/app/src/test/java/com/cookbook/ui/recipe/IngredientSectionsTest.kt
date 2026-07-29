package com.cookbook.ui.recipe

import com.cookbook.data.remote.IngredientOut
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IngredientSectionsTest {

    private fun ingredient(
        name: String,
        section: String? = null,
        quantity: Double? = null,
        unit: String? = null,
        note: String? = null,
    ) = IngredientOut(
        id = name,
        order = 0,
        name = name,
        quantity = quantity,
        unit = unit,
        section = section,
        note = note,
    )

    @Test
    fun `an ungrouped recipe gets no headings at all`() {
        val rows = ingredientRows(listOf(ingredient("Flour"), ingredient("Sugar")))
        assertEquals(listOf(null, null), rows.map { it.first })
        assertEquals(listOf("Flour", "Sugar"), rows.map { it.second.name })
    }

    @Test
    fun `a heading is emitted once per run, not per ingredient`() {
        val rows = ingredientRows(
            listOf(
                ingredient("Lime juice", "Steak Marinade"),
                ingredient("Ground cumin", "Steak Marinade"),
                ingredient("Skirt steak", "Fajitas"),
                ingredient("White onions", "Fajitas"),
            ),
        )
        assertEquals(listOf("Steak Marinade", null, "Fajitas", null), rows.map { it.first })
    }

    @Test
    fun `order is the recipe's own — sections never reorder anything`() {
        val ingredients = listOf(
            ingredient("Lime juice", "Marinade"),
            ingredient("Skirt steak", "Fajitas"),
            ingredient("Ground cumin", "Marinade"),
        )
        val rows = ingredientRows(ingredients)
        assertEquals(ingredients.map { it.name }, rows.map { it.second.name })
        // A section that resumes after an interruption is a new run, not a merge.
        assertEquals(listOf("Marinade", "Fajitas", "Marinade"), rows.map { it.first })
    }

    @Test
    fun `going back to no section stops the heading without starting a new one`() {
        val rows = ingredientRows(
            listOf(
                ingredient("Lime juice", "Marinade"),
                ingredient("Tortillas", null),
                ingredient("Salsa", null),
            ),
        )
        assertEquals(listOf("Marinade", null, null), rows.map { it.first })
    }

    @Test
    fun `a blank section is treated as no section`() {
        val rows = ingredientRows(listOf(ingredient("Flour", "   "), ingredient("Sugar", "")))
        assertEquals(listOf(null, null), rows.map { it.first })
    }

    @Test
    fun `a note that just restates the row is hidden`() {
        // What imports actually store: the source's whole line, duplicating the row beneath it.
        val ing = ingredient(
            name = "pineapple juice (no sugar added)",
            quantity = 0.25,
            unit = "cup",
            note = "¼ cup pineapple juice (no sugar added)",
        )
        assertFalse(noteAddsDetail(ing.note!!, ing))
    }

    @Test
    fun `unit spelling and punctuation differences do not make a note look new`() {
        val ing = ingredient("olive oil", quantity = 3.0, unit = "tbsp", note = "3 Tablespoons olive oil")
        assertFalse(noteAddsDetail(ing.note!!, ing))
    }

    @Test
    fun `a note that adds real detail is kept`() {
        val ing = ingredient("Salt", note = "to taste")
        assertTrue(noteAddsDetail(ing.note!!, ing))

        val prep = ingredient("Chicken breast", quantity = 2.0, unit = "lb", note = "2 lb chicken breast, pounded flat")
        assertTrue(noteAddsDetail(prep.note!!, prep))
    }

    @Test
    fun `a blank note is never shown`() {
        val ing = ingredient("Flour", note = "   ")
        assertFalse(noteAddsDetail(ing.note!!, ing))
    }
}
