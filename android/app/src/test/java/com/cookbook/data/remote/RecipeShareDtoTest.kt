package com.cookbook.data.remote

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Family-mode share wire-format: the toggle request body and the `shared` / `is_owner` fields the
 * server adds to the recipe DTOs. Pure serialization — no network — so the mapping is locked down.
 */
class RecipeShareDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `share request serializes the shared flag`() {
        assertEquals(
            """{"shared":true}""",
            json.encodeToString(RecipeShareRequest.serializer(), RecipeShareRequest(shared = true)),
        )
        assertEquals(
            """{"shared":false}""",
            json.encodeToString(RecipeShareRequest.serializer(), RecipeShareRequest(shared = false)),
        )
    }

    @Test
    fun `summary maps snake_case is_owner and shared`() {
        val payload = """
            {"id":"r1","name":"Grandma's Chili","servings":4,
             "ingredient_count":5,"step_count":3,"shared":true,"is_owner":false}
        """.trimIndent()

        val out = json.decodeFromString(RecipeSummaryOut.serializer(), payload)

        assertTrue(out.shared)
        assertFalse(out.isOwner)
    }

    @Test
    fun `detail maps shared and is_owner, defaulting to private-and-owned when absent`() {
        val shared = """
            {"id":"r1","name":"Family Lasagna","servings":6,"source":"manual",
             "created_at":"2026-07-16T00:00:00","shared":true,"is_owner":true,
             "steps":[],"ingredients":[]}
        """.trimIndent()
        val out = json.decodeFromString(RecipeOut.serializer(), shared)
        assertTrue(out.shared)
        assertTrue(out.isOwner)

        // Older server payloads omit the fields entirely — must read as your private recipe.
        val legacy = """
            {"id":"r2","name":"Solo Oats","servings":1,"source":"manual",
             "created_at":"2026-07-16T00:00:00","steps":[],"ingredients":[]}
        """.trimIndent()
        val legacyOut = json.decodeFromString(RecipeOut.serializer(), legacy)
        assertFalse(legacyOut.shared)
        assertTrue(legacyOut.isOwner)
    }
}
