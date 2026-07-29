package com.cookbook.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Family mode (household sharing) — the single sharing surface, Settings → Family. Mirrors the
 * Magpie precedent; the server API is identical (GET /household, POST /household/members, …).
 */
@Serializable
data class HouseholdMemberOut(
    @SerialName("user_id") val userId: String,
    val name: String,
    val email: String,
    @SerialName("is_owner") val isOwner: Boolean,
    // "active" or "pending" — a pending member was invited but hasn't accepted yet.
    val status: String = "active",
)

@Serializable
data class HouseholdOut(
    val members: List<HouseholdMemberOut>,
    @SerialName("you_are_owner") val youAreOwner: Boolean,
    // True once the household is actually shared (more than one ACTIVE member).
    val shared: Boolean,
    // How many of YOUR recipes are still private — drives the "share all my recipes" prompt.
    // Defaulted so an older server (which omits the field) simply shows no prompt.
    @SerialName("unshared_recipe_count") val unsharedRecipeCount: Int = 0,
)

/** Result of the bulk "share all my recipes" opt-in: how many just became family recipes. */
@Serializable
data class ShareAllOut(
    @SerialName("shared_count") val sharedCount: Int,
)

@Serializable
data class AddMemberRequest(val email: String)

/** A household invite awaiting this user's response (the API returns null when there is none). */
@Serializable
data class InviteOut(
    @SerialName("household_id") val householdId: String,
    @SerialName("owner_name") val ownerName: String,
    @SerialName("owner_email") val ownerEmail: String,
)
