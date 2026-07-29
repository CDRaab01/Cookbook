package com.cookbook.ui.recipe

import com.cookbook.data.remote.IngredientOut

/**
 * Pair each ingredient with the section heading to render above it, or null for "no heading here".
 *
 * A recipe's sections ("Steak Marinade", "Fajitas") are stored denormalized — one value per
 * ingredient — and are contiguous runs in the recipe's own order, never a sort key. So a heading
 * is emitted exactly when the section *changes*, and the ingredient order is left alone.
 *
 * A recipe with no sections yields every heading as null, i.e. a plain flat list.
 *
 * Pure, so the run logic can be tested without Compose, and shared by the detail screen, cook
 * mode and share-as-text rather than re-derived three times.
 */
fun ingredientRows(ingredients: List<IngredientOut>): List<Pair<String?, IngredientOut>> {
    var previous: String? = null
    return ingredients.map { ingredient ->
        val section = ingredient.section?.takeIf { it.isNotBlank() }
        val heading = if (section != null && section != previous) section else null
        previous = section
        heading to ingredient
    }
}

/**
 * Whether an ingredient's note says anything the row isn't already showing.
 *
 * Importers store the source's full line as the note ("¼ cup pineapple juice (no sugar added)"),
 * which on a row that already renders "¼ cup" and "pineapple juice (no sugar added)" is pure
 * duplication — it doubled the height of every imported recipe for nothing. Compared on letters
 * and digits only, so punctuation, casing, unit spelling ("2 tablespoons" vs "2 tbsp") and the
 * fraction glyph can't make identical text look different.
 */
fun noteAddsDetail(note: String, ingredient: IngredientOut): Boolean {
    if (note.isBlank()) return false
    val noteKey = comparisonKey(note)
    if (noteKey.isEmpty()) return false
    val nameKey = comparisonKey(ingredient.name)
    // The note restates the name and adds only the amount that's already in the quantity column.
    return !noteKey.endsWith(nameKey) || nameKey.isEmpty()
}

private fun comparisonKey(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
