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
    suspend fun getPlan(start: String, end: String): List<PlanEntryOut>
    suspend fun addEntry(date: String, slot: String, recipeId: String?, note: String?): PlanEntryOut
    suspend fun setEaten(id: String, eaten: Boolean): PlanEntryOut
    suspend fun deleteEntry(id: String)
    suspend fun sendToList(start: String, end: String): PlanToListResult
}

class PlanRepositoryImpl @Inject constructor(
    private val api: ApiService,
) : PlanRepository {

    override suspend fun getPlan(start: String, end: String): List<PlanEntryOut> =
        api.getPlan(start, end)

    override suspend fun addEntry(
        date: String,
        slot: String,
        recipeId: String?,
        note: String?,
    ): PlanEntryOut = api.createPlanEntry(PlanEntryCreateRequest(date, slot, recipeId, note))

    override suspend fun setEaten(id: String, eaten: Boolean): PlanEntryOut =
        api.updatePlanEntry(id, PlanEntryUpdateRequest(eaten))

    override suspend fun deleteEntry(id: String) = api.deletePlanEntry(id)

    override suspend fun sendToList(start: String, end: String): PlanToListResult =
        api.planToList(PlanToListRequest(start, end))
}
