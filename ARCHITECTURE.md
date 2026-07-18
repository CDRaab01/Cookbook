# ARCHITECTURE.md — Cookbook (software-level)

How this codebase is organized and why. Suite-level context: `C:\Code\ARCHITECTURE.md`. Working
instructions + version history: [CLAUDE.md](CLAUDE.md). Backlog: [ROADMAP.md](ROADMAP.md).

Cookbook is the **newest app and the template**: it carries the suite's current best conventions
(Pulse composite build from day one, NullPool conftest, compose layout, release.yml, SSO pilot).
**New apps copy Cookbook, not Spotter.** Its product promise: the shopping list must work with
zero signal in a grocery store.

## System shape

```
Android (Kotlin/Compose, offline-first list) ⇄ FastAPI :8003 ⇄ Postgres :5434
                                                   │
                                                   ├→ LM Studio :1234 (photo import + pantry scan vision)
                                                   ├→ Spoonacular (discovery, find-by-ingredients)
                                                   └→ Plate (nutrition breakdown, log-to-diary, one-time migration)
```

## Server (`server/`)

### Layers

Standard suite layering (`routers/` → `services/` → `models/`, Pydantic at the boundary) plus the
pure domain package **`app/lists/`** — the app's kernel:

- **`lists/merge.py`** — shopping-list merge math. Merge identity is the **normalized name only**
  (casefold/trim/singularize-lite); amounts aggregate into a `measures` JSON column
  (`Measure(quantity, unit)`) — same canonical unit sums, mixed units sit side by side
  ("2 tbsp + 2 tsp"). `canonical_unit` normalizes spellings everywhere. Non-purchasables (water)
  are filtered at add-recipe. Exhaustively table-driven-tested; **clients never merge
  independently** — every path into a list (recipe add, plan-to-list, manual add, undo rebuild)
  goes through this module.
- **`lists/categorize.py`** — store-category guesser (fallback behind `item_history` recall).
  v0.7: the aisle set widened from 7 food-only buckets to 13 store aisles (adds deli, snacks,
  beverages, household, personal, baby — `STORE_CATEGORIES` in `models/recipe.py`, mirrored by
  the Android `DEFAULT_AISLE_ORDER`), and matching became **word-boundary + longest-wins** instead
  of naive substring: "milk collector" → baby (not dairy via the word "milk"), "eggplant" isn't
  *egg*, "chipotle" isn't *chip*. Keywords are matched against both the raw and merge-normalized
  name with a tolerant trailing plural.
- **`lists/link_items.py`** — pasted-product-link splitting (v0.5): `split_link` pulls the first
  URL out of add-bar text; `name_from_url` derives a readable slug-based fallback name. The
  service layer pairs it with **`services/link_title_service.py`** — a best-effort
  `resolve_link_preview` that returns a `LinkPreview(title, image_url)` from one guarded fetch
  (JSON-LD `Product.name`/`Product.image` → `og:title`/`og:image` → `<title>`, behind the shared
  SSRF guard `services/url_guard.py`; never raises). A link add gets a human title **and a
  thumbnail** (v0.6). URL-derived names never enter `item_history` (no SKU-title autocomplete
  pollution), and category recall/guessing only ever sees the cleaned name.
- **"Buy again" recall (v0.6):** `item_history` remembers the link + thumbnail from *typed* adds
  (`recall_link`), so re-adding an item by name ("milk collector") re-attaches both with no
  re-paste — while URL-only adds still stay out of history entirely.
- **`lists/pantry_match.py`** — pantry↔recipe ingredient matching (token-set subset in either
  direction, descriptor stopwords, staples logic). Documented looseness ("milk" ⊆ "coconut milk")
  is a decision, not a bug.

### Domain map

| Domain | Router | Service | Models |
|---|---|---|---|
| Auth/users | `auth.py`, `users.py`, `suite_auth.py` | `auth_service`, `suite_auth` | `User` |
| Recipes (CRUD, notes, tags, favorites, cook events) | `recipes.py` | `recipe_service` | `Recipe` (+steps/ingredients), `CookEvent`, tags |
| Discovery/import | `recipes.py` | `recipe_discovery_service` | — (`recipes_ext/`: `spoonacular.py` + `jsonld.py` URL parser w/ SSRF guard) |
| Shopping lists | `lists.py` | `shopping_service` | `ShoppingList`, `ShoppingListItem`, `ItemHistory` |
| Meal planner | `plan.py` | `plan_service` | `MealPlanEntry` |
| Pantry (v0.4 AI round) | `pantry.py` | `pantry_service` (+ `services/ai/`) | `PantryItem`, `PantryStaple` |
| Household / family sharing | `household.py` | `household_service` | `Household`, `HouseholdMember` (+ `recipes.shared`) |
| Plate integration | `migrate.py` + recipe endpoints | `plate_migration_service`, `plate_nutrition_service`, `cross_app_token` | — |
| Export | `export.py` | `export_service` | generic dump |

### AI (`app/services/ai/`) — ported from Plate, one stack only

LM Studio vision pipeline: strict-JSON prompts, forgiving parser (fence-stripping, widest-object
salvage), transport failures → clean 5xx, content failures → **low-confidence draft**, never an
error. Two surfaces, same contract:
- **Photo import** (`POST /recipes/import-photo`) — recipe card/page photo → draft → client's
  recipe editor via `RecipeDraftStore`; the normal create endpoint commits after user review.
- **Pantry scan** (`POST /pantry/scan`) — fridge photo → candidate list → `PantryConfirmScreen`
  → `POST /pantry/confirm`. Nothing persists from the scan itself.

House rules (ROADMAP "ground rules"): extend this module, don't grow a second AI stack; the
Spotter guardrail model is the contract; **the shopping list must never depend on AI** — AI
degrades to absence, never blocks add/check/sync.

### Migrations & tests

Alembic 0001–0017, migrate-on-boot (0008 plan-eaten, 0009 list-members, 0010 plan-list-id,
0011 meal-confirmations, 0012 cook-rating, 0013 plan-entry-scale, 0014 household-sharing,
0015 household-member-status, 0016 item-history-trigram, 0017 item-link-url —
`shopping_list_items.link_url`, Text, first-link-wins on merge; item names are capped at 255
with a clean 422, never a DB 500; 0018 link-preview-and-recall — `shopping_list_items.image_url`
+ `item_history.link_url`/`image_url` for thumbnails and "buy again"). ~347 pytest tests; CI runs ruff **and** `ruff format --check`. Local recipe (CLAUDE.md): scratch DB inside the live cookbook-db container,
`DATABASE_URL` on **127.0.0.1:5434**, `DB_NULLPOOL=true` (conftest sets NullPool; bcrypt dropped
to 4 rounds tests-only). One env-dependent local-only failure when the live `.env` has
`SUITE_JWKS_URL` set; green in CI.

## Android (`android/`, package `com.cookbook`)

Standard suite MVVM. Feature packages:

- `ui/shopping/` — the core surface: category-grouped checklist, tap-to-check (optimistic,
  offline-queued), checked items sweep to a dimmed bottom, clear-checked, list switcher
  (multiple named lists; the default = the oldest list), autocomplete + category recall from
  `item_history` (substring first, then pg_trgm fuzzy/similar-spelling matches). The home-screen
  Glance widget mirrors the same list and taps to check off (`widget/ShoppingWidget.kt`).
  **Link items (v0.5/v0.6):** a product URL pasted into the add bar becomes a titled row with a
  tappable domain chip (opens the browser), a **product thumbnail** (Coil `AsyncImage` off the
  server's `image_url`), and a **−/＋ count stepper** ("×2" — a distinct product you buy N of).
  `util/LinkText.kt` mirrors the server split for the optimistic/offline row only — the server's
  parse (title + image) is authoritative on reconcile. Grouping coerces null *and unknown*
  categories into "Other" so no item can be counted yet unrendered.
- `ui/recipe/` — book/detail/editor (servings rescaler is display-only math), cook events
  ("Made it"), share/duplicate; `RecipeDraftStore` receives photo/URL-import drafts. **Family
  mode:** the list splits into **Family** (`shared==true`, household-wide) and **Yours**
  (`shared==false`) sections with a family badge on shared cards; recipe detail carries the
  creator-only "Share with family" / "Make private" toggle (`POST /recipes/{id}/share`), and
  `is_owner` gates both that toggle and Delete (a co-member viewing a family recipe sees neither).
- `ui/settings/` — server URL, Plate migration, pantry-staples/aisle-order editors, and
  **Settings → Family** — the single household-sharing surface: invite by email, member roster
  (owner badge, **pending** badge on unaccepted invites), owner-removes / member-leaves
  (`/household` endpoints). This replaced the old per-list `ShareSheet` (retired — sharing is
  household-wide now, not per shopping list). **Consented invites:** an invite is created
  `status="pending"` and shares nothing until the invitee accepts (`household_member_ids` /
  `household_owner_id` count only `active` members); the invitee sees the invite via `GET
  /household/invite` and responds with `POST /household/{accept,decline}` (migration 0015).
- `ui/discover/` — Spoonacular search + preview bottom sheet + import; share-from-browser URLs
  arrive via `SharedIntentStore` (ACTION_SEND) into a nav-host **chooser** ("Import as recipe" /
  "Add to shopping list") — the shopping branch funnels the raw text through the normal add path
  (server-side link split); Discover keeps its direct pre-filled import when already open.
- `ui/cook/` — cook mode: step-at-a-time, screen-awake, duration-detected timers
  (elapsedRealtime-anchored per the suite drift-free rule).
- `ui/plan/` — weekly meal planner; `POST /plan/to-list` funnels a week of dinners through the
  same merge module. No offline mirror (deliberate — not in-store-critical).
- `ui/pantry/` — pantry list/edit, camera+gallery scan (both paths downscale to ≤1600px JPEG via
  `util/ImageBytes.kt` — camera captures blow the 8 MB cap otherwise), confirm flow, suggestions,
  staples editor (`PantryDraftStore` idiom).
- `widget/` — Glance home-screen widget reading the Room mirror via a Hilt EntryPoint,
  tap-to-check-off.
- `ui/theme/CookbookTheme.kt` — Pulse semantics: Cookbook **leads amber** (heat); recovery green
  = checked/done; blue/violet are supporting channels.

### Offline model

The active shopping list (and a recipes read cache) mirrors into Room; check-offs and edits are
optimistic local writes queued for reconnect sync (dirty rows push **full state**, not just
`checked`). The grocery-store flow must survive airplane mode end-to-end; treat any regression
there as P0. The error-cause discipline everywhere in the data layer: **`IOException` =
unreachable ⇒ degrade to local state; `retrofit2.HttpException` = the server refused ⇒ error
loudly** — the two are never conflated. A refusal also **undoes the optimistic write**: a
rejected online add deletes its local row, and `syncPending` drops any rejected pending row and
keeps draining (**server wins**, uniform with the recipe-op queue) — a refused row kept locally
would be a permanent ghost only its own phone can see, and rethrowing mid-drain used to let one
poison row wedge the whole backlog.

**Staleness is surfaced, never silent.** Both recipe cache tables carry a `cachedAtMs`
capture-time stamp written on every successful fetch; `RecipeRepositoryImpl.listRecipes` /
`getRecipe` return `Stale<T>` (`value` + nullable `asOfMs` — null = fresh, non-null = served
from cache, captured then). The recipe list + detail screens render Pulse's `StaleBanner`
("Offline — as of h:mm a", amber `heat` channel) off that stamp. Rows cached before stamping
existed (`cachedAtMs == 0`, migration default) surface as null — no honest timestamp to show.
The Shopping screen has its own banner off `ShoppingRepository.offline` (a `StateFlow` flipped
by any unreachable round-trip, cleared by any successful reconcile) reading **"Offline —
changes will sync"** — deliberately *not* an "as of" stamp, because the local queue is
authoritative there, not stale.

**Recipe favorites are the book's one offline-capable write.** `setFavorite` flips the cached
JSON blobs optimistically (heart responds instantly); an unreachable server enqueues a row in
`pending_recipe_ops` (recipeId/opType/boolValue/createdAtMs — op-shaped so future op kinds fit
without a schema change); a server rejection reverts the blobs and rethrows.
`syncPendingRecipeOps()` drains the queue in order on reconnect (`NetworkSyncObserver`, after
the shopping sync): success deletes the op and refreshes the blobs from the response; a
rejection drops the op and re-pulls truth (**server wins**; a 404 purges the cached detail); a
renewed outage stops and keeps the backlog.

**Migration policy changed (Room v4):** the old "Room is a mirror — destructive rebuild is
acceptable" stance is retired. `shopping_items` carries unpushed offline queue rows
(dirty/tombstoned/serverless) and `pending_recipe_ops` is a write queue outright — a
destructive rebuild would silently drop user writes. Schema bumps now ship real migrations
(`CookbookDatabase.MIGRATION_3_4` is the first); `fallbackToDestructiveMigration()` remains
registered only as a last-resort backstop for version jumps no migration covers.

**Deliberately online-only** (no offline path, by design): recipe create/edit/delete/import,
pantry, and the meal planner — none are in-store-critical, and offline editing would grow a
merge story the app doesn't need (add-recipe-to-list also stays online because merge math is
server-side, invariant 1). Their failure paths name the outage plainly — an `IOException`
surfaces as **"Can't reach the Cookbook server"** (`util/ErrorMessages.kt`) instead of a raw
socket message; server rejections keep their own messages.

## Invariants

1. **Merge math is server-side and singular** (`lists/merge.py`) — no client-side merging, no
   second implementation.
2. **Shopping list works offline and without AI.** AI features degrade to absence.
3. **AI output is a user-confirmed draft** (`RecipeDraftStore` / `PantryConfirmScreen` pattern).
4. Pantry is a thin presence model (items + staples) — **not** quantity inventory; re-litigated
   and rejected.
5. Ingredients are free text; nutrition coupling only via the Plate integration seam.
6. The release workflow checks out the sibling **Pulse** repo — keep that step when editing CI.
7. **Sharing is household-wide, one surface** (Settings → Family / `/household`). Recipes/lists/
   plans are shared via household membership, not per-object ACLs; the client never uses the
   legacy per-list member endpoints (server still accepts them).

## Where to make common changes

- **List/merge behavior**: `app/lists/merge.py` + its table-driven tests; nothing else merges.
- **New AI surface**: extend `services/ai/` (prompt module + parser reuse), return a draft,
  never persist server-side.
- **New screen**: `ui/<feature>/` + ViewModel + route; Pulse components only.
- **Plate contract changes**: coordinate with Plate (its provider surfaces) and commit contract
  fixtures both sides (`Dragonfly/CROSS-APP.md` rules).
