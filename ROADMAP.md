# ROADMAP.md — Cookbook (departing-engineer assessment, 2026-07-03)

**Update 2026-07-03: the AI round shipped as v0.4.0 pantry scan** (photo → confirm → pantry +
staples → "what can I make" suggestions — see CLAUDE.md v0.4.0). It followed the ground rules
below, which remain the contract for any future AI work here.

## Road to 1.0 (suite pivot, 2026-07-13)

The suite entered its **1.0 polish round** (host-level ROADMAP3, C:\Code): every app must pass a
shared bar — onboarding, designed empty/loading/error states, motion/celebration + dark/light
parity, defined offline behavior, no dead settings, an on-device pass, gating screenshot
baselines, icon quality, truthful docs — with `versionName` 1.0.0 as the round's **last** commit.

Cookbook's 1.0 slate (all already named in this file; the pivot promotes them):

1. **Household sharing (family mode)** (Non-AI #1 below) — the headline. ✓ **DONE 2026-07-16.**
   Shipped bigger than the "share a list" sketch: Cookbook moved to a **household** managed in
   **Settings → Family** (invite by email, member roster, owner-removes / member-leaves via new
   `/household` endpoints) and the per-list `ShareSheet` was retired. Recipes gained a `shared`
   flag — a **family** recipe is visible AND editable to the whole household (fully
   collaborative; delete + the share toggle stay creator-only via `is_owner`), private recipes
   stay the creator's; `POST /recipes/{id}/share` toggles it and the recipe list splits into
   **Family** vs **Yours**. Shopping lists + meal plans are reachable by household co-members
   (resolution through `household_service.household_member_ids`; legacy per-list `ListMember`
   still works). Migration `0014` (households, household_members, recipes.shared). This is the
   feature that makes the suite read as multi-user and professional.
2. ✓ **Custom/reorderable aisles** (Non-AI #2) — DONE 2026-07-15. Settings → "Edit aisle order"
   reorders the store categories (move up/down + reset); the order persists in DataStore
   (`AppPreferences.aisleOrder`, reconciled against the canonical set) and the shopping list
   groups items in that walk order. Client-only; 5 unit tests on the reconcile logic.
3. **Designed empty states** across screens (the Phase-8 leftover) — partially DONE 2026-07-16:
   a designed empty state now shows when a recipe search/filter matches nothing. Remaining:
   sweep the other screens + celebration on list-complete/cook-complete via the Pulse
   primitives as host Tier P lands them.
4. ✓ **Record the Roborazzi baselines** (Non-AI #4) — DONE 2026-07-16. Baselines recorded
   (light + dark) for the five previously-uncaptured screens: recipe list, recipe detail,
   shopping list, pantry, discover (10 PNGs under `android/app/screenshots/`). Home was already
   captured in v0.2.
5. **Still open — the 1.0 gate.** Version 0.4.0 → **1.0.0** at the gate (versionName is still
   `0.4.0`); the airplane-mode on-device pass happens in the same round (the offline
   shopping-list promise is the product — prove it on the phone). These two are what remain for
   1.0.

**Gap review 2026-07-14 (host ROADMAP3 additions — what a Paprika/AnyList user would expect):**

6. **Recipe scaling** — ✓ **mostly DONE 2026-07-15.** Quantities now render as human cooking
   fractions everywhere via `ui/recipe/QuantityFormat.humanQuantity` ("1½ cups", not "1.5"; snaps
   scaled decimals like ⅓×2 → ⅔), used by detail, cook mode, discover, and shopping-list rows.
   **Cook mode now respects the chosen scale** — the detail rescaler's servings ride a nav arg
   into cook mode (scaled ingredient sheet + a "Scaled for N servings" note). **Also DONE:
   per-entry `plan→list` scale** — each planned recipe carries its own cooking `scale` (migration
   `0013`) that scales its shopping-list contribution and composes with the batch's global scale,
   so "chili at 2× for leftovers, tacos at 1×" works — end to end: a ×N stepper in the plan's
   add-recipe dialog sets each entry's scale, which flows through plan→list.
7. **Keep-screen-on in cook mode** — verify; add `FLAG_KEEP_SCREEN_ON` if absent. Wet hands,
   dark screen is the classic kitchen failure.
8. ✓ **Post-cook rating** — DONE 2026-07-15. "I made this" now opens a 1–5 "would make again"
   star pick (Skip rates nothing); `cook_events.rating` (migration `0012`, backward-compatible)
   and the recipe detail/list surface `avg_rating` (★ N.N). *Still open:* a free-text per-cook
   note, and wiring rating into the "what can I make" ranking.

## Ground rules for the incoming AI round (read before building)

1. **Reuse the existing seam.** `app/services/ai/` already holds the LM Studio vision pipeline
   (ported from Plate for photo import): strict-JSON prompts, forgiving parser, transport
   failures → clean 5xx, content failures → low-confidence draft. New AI features extend that
   module — don't grow a second AI stack. `LM_STUDIO_BASE_URL` via
   `host.docker.internal:1234/v1` in the container, and remember the LM Studio instance is
   shared with Spotter/Plate/kidbot (model swaps are suite-wide events).
2. **The Spotter guardrail model is the contract** (its CLAUDE.md "AI Guardrails" section):
   server-side prompts only, Pydantic-validated structured output, user chat untrusted,
   AI output is always a **user-confirmed draft** — the photo-import → `RecipeDraftStore` →
   editor flow is the house pattern for "AI proposes, user commits". No autonomous writes.
3. **The shopping list must never depend on AI.** Offline-first in a grocery store is the
   product's core promise; AI features are server-side enhancements that degrade to absence,
   never a dependency in the add/check/sync path.
4. **Remaining AI candidates** (pantry-aware "what can I make" shipped in v0.4.0):
   meal-planner autofill ("plan my week from favorites + what's not been made lately", riding
   `cook_events` + `POST /plan/to-list`), ingredient-line cleanup on import (dedupe/normalize —
   extends the merge module's mission), and cook-mode Q&A (substitutions, scaling) with the
   recipe as trusted context. A natural v0.4 follow-on: suggestions that *combine* pantry
   coverage with the meal planner ("plan my week from what I have").

## Cross-app work (approved 2026-07-03 — see Dragonfly/CROSS-APP.md for the full design)

- ✓ **Meal plan → Plate — SHIPPED 2026-07-11** (federated-awareness Link E): `GET
  /cross-app/plan?date=` (slot+name only), consumed by Plate's coach as trusted context.
- ✓ **Macro-aware suggestions — SHIPPED 2026-07-11** (Link F): recipe nutrition carries
  `fits_today: bool|None` from Plate's `/cross-app/remaining`; suggestions work with the flag
  unset, as specced. **Also shipped (Link D consumer, 2026-07-12):** "$Y on groceries · via
  Magpie" on the shopping list from Magpie's `/cross-app/summary`; absent ⇒ hidden.
- **Digest range read** (item 4): a cook-events/plan range endpoint for the future suite
  digest.
- Adopt the contract-fixture rule (CROSS-APP.md infra): consume Plate's committed fixtures
  for the surfaces Cookbook mocks, and commit fixtures for Cookbook's own provider surfaces.

## Non-AI work (valuable regardless of the AI round)

1. ✓ **Household sharing** — DONE 2026-07-16 (see "Road to 1.0" #1 above). Shipped as a
   household (Settings → Family, invite by email) rather than the smallest per-list sketch:
   family recipes are shared+collaborative, private recipes stay yours, and lists + plans are
   reachable by co-members. Suite SSO (accounts linked by email) is the identity foundation it
   builds on. The last "personal-app" limitation is closed.
2. **Custom/reorderable aisles** — deferred from v0.3; category recall (`item_history`) solved
   "where does milk go", this solves "what order do I walk the store". Per-list category order,
   drag-to-reorder (Hawksnest's Customize has the drag-and-drop precedent).
3. ~~Pantry model (thin)~~ — **shipped in v0.4.0** as pantry items + confirmed staples (still
   deliberately NOT quantity inventory — keep it that way). Follow-on worth doing:
   recently-bought inference (checked-off shopping history → "probably in the pantry" hints).
4. ✓ **Record the Roborazzi baselines** — DONE 2026-07-16. The five uncaptured screens (recipe
   list, recipe detail, shopping list, pantry, discover) now have light+dark baselines under
   `android/app/screenshots/`; Home was captured in v0.2.
5. **Camera-captured recipe photos** (vs web URLs) — deferred from v0.3; pairs naturally with
   photo import since the capture UX is the same.

## Explicitly not worth it

- Price tracking / store APIs — rejected in v1, still right: fragile scrapers, low value.
- Full pantry inventory with quantities — see above; thin staples model or nothing.
- Public recipe sharing/community — this is a household tool.
