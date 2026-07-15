package com.cookbook.data.repository

import com.cookbook.data.remote.ApiService
import com.cookbook.data.remote.PlanEntryCreateRequest
import com.cookbook.data.remote.PlanEntryOut
import com.cookbook.data.remote.PlanEntryUpdateRequest
import com.cookbook.data.remote.PlanToListRequest
import com.cookbook.data.remote.PlanToListResult
import javax.inject.Inject

/** Meal-plan operations (v0.3). No offline mirror — the planner is a light-touch calendar,
 * not the in-store-critical shopping list, so it's fine to require connectivity. */
interface PlanRepository {
    /** [listId] null = your own default list; a shared list's id plans that household's meals. */
    suspend fun getPlan(start: String, end: String, listId: String? = null): List<PlanEntryOut>
    suspend fun addEntry(
        date: String,
        slot: String,
        recipeId: String?,
        note: String?,
        listId: String? = null,
        scale: Double = 1.0,
    ): PlanEntryOut
    /** Confirm (or un-confirm) that you ate this, at [servings] — per-user; a recipe confirmation
     * logs to your Plate diary at that portion. [servings] is ignored when un-eating. */
    suspend fun setEaten(id: String, eaten: Boolean, servings: Double = 1.0): PlanEntryOut
    suspend fun deleteEntry(id: String)
    suspend fun sendToList(start: String, end: String, listId: String? = null): PlanToListResult
}

class PlanRepositoryImpl @Inject constructor(
    private val api: ApiService,
) : PlanRepository {

    override suspend fun getPlan(start: String, end: String, listId: String?): List<PlanEntryOut> =
        api.getPlan(start, end, listId)

    override suspend fun addEntry(
        date: String,
        slot: String,
        recipeId: String?,
        note: String?,
        listId: String?,
        scale: Double,
    ): PlanEntryOut =
        api.createPlanEntry(PlanEntryCreateRequest(date, slot, recipeId, note, scale), listId)

    override suspend fun setEaten(id: String, eaten: Boolean, servings: Double): PlanEntryOut =
        api.updatePlanEntry(id, PlanEntryUpdateRequest(eaten, servings))

    override suspend fun deleteEntry(id: String) = api.deletePlanEntry(id)

    override suspend fun sendToList(start: String, end: String, listId: String?): PlanToListResult =
        api.planToList(PlanToListRequest(start, end, listId = listId))
}
