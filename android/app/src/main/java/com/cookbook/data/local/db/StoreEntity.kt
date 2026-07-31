package com.cookbook.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local cache of the store profiles and their floor plans (v0.11).
 *
 * Read-only mirror, not a queue: the server owns stores, and every mutation here is online-only
 * (the one exception is the placement queue, [PendingPlacementEntity]). It exists because the
 * whole point of aisle routing is being *inside the store*, which is exactly where the signal is
 * worst — a list that falls back to plain category grouping the moment the bars drop would defeat
 * the feature. Refreshed wholesale on every successful fetch.
 */
@Entity(tableName = "stores")
data class StoreEntity(
    @PrimaryKey val id: String,
    val name: String,
    val label: String? = null,
    val isOwner: Boolean = true,
    val order: Int = 0,
)

@Entity(tableName = "store_aisles")
data class StoreAisleEntity(
    @PrimaryKey val id: String,
    val storeId: String,
    val order: Int,
    val name: String,
    /** Comma-joined canonical category keys; empty string = a placement-only aisle. */
    val categories: String,
)

@Entity(tableName = "store_placements")
data class StorePlacementEntity(
    @PrimaryKey val id: String,
    val storeId: String,
    val aisleId: String,
    /** The server's `normalize_name` key — matches `ShoppingItemOut.key`. */
    val key: String,
    val name: String,
)

/**
 * A "move this item to that aisle" that hasn't reached the server yet.
 *
 * Moving an item to the aisle you actually found it in is an *in-store* action, so it has to
 * survive no signal — the one store mutation that is queued rather than online-only. Drained by
 * the same network observer that drains the shopping queue, and poison-row-safe in the same way
 * (a row the server rejects is dropped so it can't wedge every future sync — the v0.5 lesson).
 */
@Entity(tableName = "pending_placements")
data class PendingPlacementEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val storeId: String,
    val aisleId: String,
    val itemName: String,
    val createdAtMs: Long,
)
