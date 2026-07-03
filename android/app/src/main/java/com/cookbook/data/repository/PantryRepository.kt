package com.cookbook.data.repository

import com.cookbook.data.remote.PantryConfirmRequest
import com.cookbook.data.remote.PantryItemOut
import com.cookbook.data.remote.PantryScanDraftOut
import com.cookbook.data.remote.PantrySuggestionsOut
import com.cookbook.data.remote.StaplesOut

interface PantryRepository {
    suspend fun getPantry(): List<PantryItemOut>
    suspend fun addItem(name: String, category: String?): PantryItemOut
    suspend fun updateItem(id: String, name: String?, category: String?): PantryItemOut
    suspend fun deleteItem(id: String)
    suspend fun scanPhoto(bytes: ByteArray, mimeType: String, fileName: String): PantryScanDraftOut
    suspend fun confirmItems(req: PantryConfirmRequest): List<PantryItemOut>
    suspend fun getStaples(): StaplesOut
    suspend fun putStaples(staples: List<String>): StaplesOut
    suspend fun getSuggestions(maxMissing: Int = 2): PantrySuggestionsOut
}
