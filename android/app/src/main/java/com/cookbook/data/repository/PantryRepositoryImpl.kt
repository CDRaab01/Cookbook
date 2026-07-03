package com.cookbook.data.repository

import com.cookbook.data.remote.ApiService
import com.cookbook.data.remote.PantryConfirmRequest
import com.cookbook.data.remote.PantryItemCreateRequest
import com.cookbook.data.remote.PantryItemOut
import com.cookbook.data.remote.PantryItemUpdateRequest
import com.cookbook.data.remote.PantryScanDraftOut
import com.cookbook.data.remote.PantrySuggestionsOut
import com.cookbook.data.remote.StaplesOut
import com.cookbook.data.remote.StaplesPutRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server-only, no Room mirror (the meal-planner precedent): the pantry isn't in-store-critical,
 * and a stale local copy would quietly suggest recipes the fridge can't back up.
 */
@Singleton
class PantryRepositoryImpl @Inject constructor(
    private val api: ApiService,
) : PantryRepository {

    override suspend fun getPantry(): List<PantryItemOut> = api.getPantry()

    override suspend fun addItem(name: String, category: String?): PantryItemOut =
        api.addPantryItem(PantryItemCreateRequest(name = name, category = category))

    override suspend fun updateItem(id: String, name: String?, category: String?): PantryItemOut =
        api.updatePantryItem(id, PantryItemUpdateRequest(name = name, category = category))

    override suspend fun deleteItem(id: String) = api.deletePantryItem(id)

    override suspend fun scanPhoto(
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
    ): PantryScanDraftOut {
        val body = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
        val part = MultipartBody.Part.createFormData("photo", fileName, body)
        return api.scanPantry(part)
    }

    override suspend fun confirmItems(req: PantryConfirmRequest): List<PantryItemOut> =
        api.confirmPantryItems(req)

    override suspend fun getStaples(): StaplesOut = api.getStaples()

    override suspend fun putStaples(staples: List<String>): StaplesOut =
        api.putStaples(StaplesPutRequest(staples))

    override suspend fun getSuggestions(maxMissing: Int): PantrySuggestionsOut =
        api.getPantrySuggestions(maxMissing)
}
