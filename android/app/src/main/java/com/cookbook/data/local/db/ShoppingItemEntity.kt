package com.cookbook.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local mirror of one shopping-list row (CLAUDE.md §7 Phase 4). The server is the source of
 * truth; this row exists so the in-store checklist works with zero signal.
 *
 * Sync model is state-based, not an op log:
 *  - [serverId] null ⇒ the row was added offline and needs a POST.
 *  - [dirty] ⇒ local state (checked, usually) hasn't been pushed yet.
 *  - [deleted] ⇒ tombstone; kept until the DELETE lands, then purged.
 * Reconciliation (see OfflineFirstShoppingRepository) replaces all clean rows with the server's
 * list and preserves dirty/tombstoned ones until their push succeeds.
 */
@Entity(tableName = "shopping_items")
data class ShoppingItemEntity(
    @PrimaryKey val localId: String,
    val serverId: String?,
    // Which list this row belongs to (server list id) — v0.3 multiple named lists.
    val listId: String? = null,
    val name: String,
    val quantity: Double?,
    val unit: String?,
    // JSON-encoded List<MeasureOut> — the aggregated amounts the row displays (v0.2.1).
    val measuresJson: String? = null,
    val category: String?,
    // Product-page URL for a pasted-link item (v0.5); the name is a clean human title.
    val linkUrl: String? = null,
    // Product thumbnail for a link item (v0.6).
    val imageUrl: String? = null,
    val checked: Boolean,
    val recipeId: String?,
    val order: Int,
    val dirty: Boolean = false,
    val deleted: Boolean = false,
)
