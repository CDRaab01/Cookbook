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
  (`Measure(quantity, unit)`) — same canonical unit sums, mixed units sit side by side.
  `canonical_unit` normalizes spellings everywhere. Non-purchasables (water) are filtered at
  add-recipe. **Cooking-only units (tsp/tbsp/cup/pinch/dash) never land on the buy list**
  (`buyable_measures`/`is_buyable_measure`, v0.8) — "2 tbsp oil" says how to cook, not what to buy;
  the amount is dropped (the item stays), while store units (lb/oz/g/can/bag…) and bare counts are
  kept. The filter is applied at the single storage choke point, `shopping_service._store_measures`
  (migration `0020` backfilled the existing list). Exhaustively table-driven-tested; **clients
  never merge independently** — every path into a list (recipe add, plan-to-list, manual add, undo rebuild)
  goes through this module.
- **`lists/categorize.py`** — store-category guesser (fallback behind `item_history` recall).
  v0.7: the aisle set widened from 7 food-only buckets to 13 store aisles (adds deli, snacks,
  beverages, household, personal, baby — `STORE_CATEGORIES` in `models/recipe.py`, mirrored by
  the Android `DEFAULT_AISLE_ORDER`), and matching became **word-boundary + longest-wins** instead
  of naive substring: "milk collector" → baby (not dairy via the word "milk"), "eggplant" isn't
  *egg*, "chipotle" isn't *chip*. Keywords are matched against both the raw and merge-normalized
  name with a tolerant trailing plural. Migration `0019` did a **one-time re-sort** of existing
  `shopping_list_items` + `item_history` into the wider set, but only where the stored category
  equaled the *old* guesser's output (auto-assigned) — manual picks were left untouched.
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

### The recipe/shopping boundary (v0.9) — a layer rule, not a preference

`recipe_ingredients` carries two fields that look similar and must never be conflated:

- **`section`** — the recipe's own heading for a run of ingredients ("Steak Marinade", "Fajitas").
  *Recipe presentation.* Contiguous runs in `order`, never a sort key. Nothing about merging,
  categorizing or shopping reads it.
- **`category`** — the store aisle the item is *bought* in. *Shopping-list routing.* It must never
  be rendered as recipe structure: grouping a recipe's ingredients by aisle scrambles the recipe
  (a spice files under Meat & Seafood) and destroys the grouping the instructions refer to.

So: the recipe detail screen, cook mode and share-as-text render **source order + `section`**; the
shopping list and pantry render **`category`** in the user's aisle order. `ui/recipe/
IngredientSections.kt::ingredientRows` is the one shared helper for the former; `util/AisleOrder.kt`
(`DEFAULT_AISLE_ORDER`, `categoryLabel`) owns the latter — it lives there, not in a recipe screen,
precisely so the two don't drift back together.

Sections are recovered on import by **`recipes_ext/ingredient_groups.py`** (schema.org has no
ingredient-group vocabulary): inline heading entries inside `recipeIngredient` first, then a
regex scrape of the page's own markup (WP Recipe Maker / Tasty Recipes / Mediavine Create and
anything with the same heading-then-`<li>` shape), aligned back onto the JSON-LD lines by
normalized text. **Every step declines rather than guesses** — below a match-confidence gate, or
if the assigned sections aren't contiguous, it returns "no sections" and the import is exactly
what it was before the module existed. Spoonacular has no group data and is deliberately left flat.

Category *routing* precedence on the bulk paths (`add_recipe`, `plan_to_list`) is
**your `item_history` → the recipe's stored value → keyword guess**, batched into one query via
`shopping_service.remembered_categories`. History outranks the recipe on purpose: the recipe's
category is nearly always a machine guess from import time, so one correction in the aisle
(written back by `update_item`) sticks for every future recipe mentioning that item. This is
inverted from `add_item`, where the client's `category` is a choice being made right now.

### Store routing (v0.11) — two layers, so the category vocabulary stays portable

The 13 `STORE_CATEGORIES` are a **portable** vocabulary: recipes, `item_history` and the keyword
guesser all speak it, and an item keeps its category no matter which store you're standing in.
A real store has real aisles ("Aisle 12 — Baking") and two Meijers don't agree with each other, so
a store profile is layered *on top of* the categories rather than replacing them:

- **`store_aisles`** — the store's own ordered walk. Each aisle claims zero or more canonical
  categories; a category claimed twice resolves to the first aisle in walk order, and one no aisle
  claims falls to a client-rendered "Unsorted" section at the end. Nothing is ever dropped.
- **`store_placements`** — the per-item exception ("peanut butter is aisle 5 at *this* Meijer"),
  keyed on `normalize_name` and therefore sharing a key space with `item_history`. Overrides the
  category mapping.

The split of ownership matters: a **placement is a fact about the store**, so it is
household-shared like the store itself; `item_history` remains one user's preference. A placement
deliberately never rewrites the item's canonical `category` — where a thing sits in one store says
nothing about the next.

`POST /stores` with no `aisles` seeds one aisle per category in canonical order, so selecting a
brand-new store reproduces exactly the grouping the user already had — a store can never make the
list worse before it's been edited. `PUT /stores/{id}/aisles` is a **full replace preserving rows
the payload identifies by id**, which is what lets a reorder/rename keep the placements learned by
walking the store; only an aisle actually removed loses them (DB cascade). Because those writes go
through `db.add`/`db.delete` plus that cascade rather than through the ORM collections,
`store_service._reload` must use `populate_existing=True` — the session is `expire_on_commit=False`,
so the identity map would otherwise return pre-write collections.

`ItemOut.key` (= `normalize_name(name)`, computed server-side) is how the client looks up "which
aisle is this item in at this store" with a plain map get. It exists so Kotlin never re-implements
the normalizer and drifts from the merge module — the "clients display, never compute" rule.

Client-side, all of the routing lives in the pure `util/StoreRouting.kt::groupForStore`, which the
shopping screen renders straight into sections. No store selected reproduces the v0.7 category
grouping exactly; a store selected resolves each item by **placement → first aisle claiming its
category → trailing "Unsorted"**, and empty aisles are omitted. Two properties are table-tested and
worth keeping: nothing is ever dropped (an item with no home is still an item you have to buy), and
a **default-seeded store renders identically to the category grouping** — so selecting a store can
never make the list worse before it's been edited. Which store is selected is a **client DataStore**
preference (`pref_selected_store_id`), per-device like the pinned list: two household members can be
standing in different stores at once even though the store *profiles* are shared.

Stores are cached in Room (schema **v7**) because aisle routing is only useful inside the store,
which is exactly where the signal is worst. Store mutations are otherwise online-only; the single
exception is `pending_placements`, since moving an item to the aisle you actually found it in is an
in-store action. That queue is drained poison-row-safely (a rejected row is dropped, never allowed
to wedge the backlog — the v0.5 lesson). `StoreRepositoryImpl` carries a small, deliberately private
`normalizeKeyForCacheOnly` used *only* so an optimistic placement matches before the server's real
row arrives; the server owns the key space.

### Domain map

| Domain | Router | Service | Models |
|---|---|---|---|
| Auth/users | `auth.py`, `users.py`, `suite_auth.py` | `auth_service`, `suite_auth` | `User` |
| Recipes (CRUD, notes, tags, favorites, cook events) | `recipes.py` | `recipe_service` | `Recipe` (+steps/ingredients), `CookEvent`, tags |
| Discovery/import | `recipes.py` | `recipe_discovery_service` | — (`recipes_ext/`: `spoonacular.py` + `jsonld.py` URL parser w/ SSRF guard) |
| Shopping lists | `lists.py` | `shopping_service` | `ShoppingList`, `ShoppingListItem`, `ItemHistory` |
| Stores / aisle routing (v0.11) | `stores.py` | `store_service` | `Store`, `StoreAisle`, `StorePlacement` |
| Meal planner | `plan.py` | `plan_service` | `MealPlanEntry` |
| Pantry (v0.4 AI round) | `pantry.py` | `pantry_service` (+ `services/ai/`) | `PantryItem`, `PantryStaple` |
| Household / family sharing | `household.py` (+ `POST /recipes/share-all`) | `household_service`, `recipe_service.{share_all_own_recipes,count_unshared_own_recipes}` | `Household`, `HouseholdMember` (+ `recipes.shared`) |
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

Since v0.11 there is also a **text** seam, `services/ai/text.py::chat_text` — same host, same model
(`lm_studio_model`, `google/gemma-4-e4b`), same error taxonomy as `_chat_vision`, but no image,
`temperature=0` and a mandatory `max_tokens` (an unbounded completion from a local model turns a
200 ms classification into a 30 s one).

> **Size `max_tokens` for reasoning + answer.** gemma-4 is a reasoning model: it spends hidden
> `reasoning_content` tokens that count against the *same* budget and emits **no content at all**
> until it's finished thinking. Measured on this host — Organize (10 items): 597 reasoning then 296
> of answer; store layout: 932 reasoning then 169. A budget sized for the visible answer returns
> `finish_reason: "length"` with an **empty string**, which every parser here correctly reports as
> "unreadable" — so the feature degrades silently and reads as a dumb model rather than a small
> number. Store layout shipped briefly at 900 and fell back to the default order 100% of the time.
> `chat_text` now logs a loud warning on that exact signature. Single-word classification is the
> one prompt simple enough that the model doesn't reason at all (3 tokens, 0 reasoning). The fence-stripping / widest-`{...}`-span salvage both
vision prompt modules had privately is now `services/ai/jsonish.py::parse_object`, shared.

**Background aisle classification** (`services/classification_service.py`) is the one surface here
that writes without a user confirming, and it is the suite's documented exception to the
drafts-only rule — a category is *metadata*, not user-visible AI content, and the failure mode is
"unfiled", never "wrong data committed" (Remnant's note classifier established it). It runs as a
FastAPI `BackgroundTasks` job **after** the response on its own `AsyncSessionLocal`, only for items
the deterministic chain (history → keyword guesser) left NULL. Guardrails:

- Writes only `shopping_list_items.category`, only where it is still NULL.
- **Never writes `item_history`** — history is where *you* file things and outranks the guesser for
  every future recipe; a machine guess must not become "remembered".
- Re-checks the name under the write, so a rename landing during the model call can't be
  overwritten by a label computed for the old text.
- The parser returns `None` rather than falling back to `other`: unfiled is honest and stays
  eligible for a retry, whereas `other` looks like a decision and stops reconsideration.
- Every add re-queues *everything* unfiled (capped at 15), so a row stranded while LM Studio was
  down heals on the next add — no polling loop, no migration.

**"Organize list"** (`services/organize_service.py`, `POST /lists/{id}/organize` 10/min +
`/organize/apply`) is the same capability as a *draft*, and the split is the point. The draft asks
the model which unchecked items are mis-filed and **saves nothing**; apply writes only the moves
the user accepted, makes no model call at all (so it works with the sidecar down, which matters
when a review screen has been sitting open), and **does** write `item_history` — accepting a
suggestion is a decision about where *you* file that item, which is exactly what distinguishes it
from background classification. `parse_organize` treats the names that were sent as a whitelist: a
name the model invented or garbled is dropped, never fuzzy-matched, because guessing which row was
meant is how the wrong item moves. `None` (unreadable) and `[]` (nothing to do) are different
outcomes and the client says different things about them.

**"Suggest layout"** (`POST /stores/suggest-layout`, 5/min) drafts a store's aisles from the chain
name so setting one up isn't a dozen-plus rows of typing before it's worth anything. It saves
nothing; the client opens the draft in the aisle editor. `parse_layout` guarantees the draft is
*usable*, not correct: names clamped, invented categories dropped, a category claimed twice kept by
the first aisle to claim it, and **every category the model forgot swept into a trailing aisle** —
otherwise it would have no aisle to route to and its items would land in "Unsorted", reading as a
bug in the layout the user just saved. An unreachable or unreadable model returns the canonical
walk order flagged `low_confidence` rather than an error: adding a store must not depend on AI
either. Expect generic output — the model knows "Meijer" as world knowledge, not the floor plan of
the Maysville Rd one, so edit-before-save is the intended workflow.

House rules (ROADMAP "ground rules"): extend this module, don't grow a second AI stack; the
Spotter guardrail model is the contract; **the shopping list must never depend on AI** — AI
degrades to absence, never blocks add/check/sync. That invariant is why classification is
post-response: the add path's latency and result are exactly what they were before it existed.

### Migrations & tests

Alembic 0001–0017, migrate-on-boot (0008 plan-eaten, 0009 list-members, 0010 plan-list-id,
0011 meal-confirmations, 0012 cook-rating, 0013 plan-entry-scale, 0014 household-sharing,
0015 household-member-status, 0016 item-history-trigram, 0017 item-link-url —
`shopping_list_items.link_url`, Text, first-link-wins on merge; item names are capped at 255
with a clean 422, never a DB 500; 0018 link-preview-and-recall — `shopping_list_items.image_url`
+ `item_history.link_url`/`image_url` for thumbnails and "buy again"; 0019/0020/0022 category and
cooking-measure re-sorts; 0021 ingredient sections; **0023 stores/aisles/placements**). ~548 pytest
tests; CI runs ruff **and** `ruff format --check`, pinned to **0.4.4**, scoped to `app` only.

**Local recipe (2026-07-31 — the older "`127.0.0.1:5434`" instruction is wrong and will fail with
`InvalidPasswordError`):** `cookbook-db-1` publishes **no host port**, so nothing on the host can
reach it. Run the suite in a throwaway container on the compose network instead — the prod image
has no pytest and its entrypoint ignores a passed command, hence `--entrypoint sh`:

```bash
PW=$(docker exec cookbook-db-1 sh -c 'echo "$POSTGRES_PASSWORD"')   # root .env rotated it
docker exec cookbook-db-1 createdb -U cookbook cookbook_scratch
docker run --rm --network cookbook_default -v "C:/Code/Cookbook/server:/w" -w /w \
  -e DATABASE_URL="postgresql+asyncpg://cookbook:${PW}@db:5432/cookbook_scratch" \
  -e DB_NULLPOOL=true -e SECRET_KEY=x --entrypoint sh cookbook-server \
  -c "pip install -q pytest pytest-asyncio; python -m pytest -q"
```

conftest sets NullPool and drops bcrypt to 4 rounds (tests only). Running from a **git worktree**
also sidesteps the ~8 env-dependent failures (`test_suite_auth`, `test_plate_*`, `test_pantry`) —
those only appear when the live `server/.env` is present and supplies `SUITE_JWKS_URL` /
`PLATE_BASE_URL`; they are green in CI either way.

**Use a fresh scratch DB per run.** `test_suite_auth.py` registers fixed emails
(`brandnew@example.com`) and asserts a starting count of zero, so re-running against a scratch DB
that already has them fails two tests for reasons that have nothing to do with your change.

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
  **Bulk opt-in:** the book shows a dismissible prompt when you're in an actually-shared household
  and still have private recipes (`HouseholdOut.unshared_recipe_count` > 0 && `shared`), offering
  `POST /recipes/share-all`. Dismissal is a one-way DataStore flag
  (`AppPreferences.shareAllNudgeDismissed`) — "not now" means never again on that device, and the
  action itself stays in Settings → Family.
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
8. **Recipe sharing is opt-in per recipe, and only its creator may opt it in.** Joining a household
   shares lists and plans at once, but a recipe stays private until its creator flips `shared` —
   `POST /recipes/share-all` is the bulk form of that same choice and is filtered to the caller's
   own rows, so it can never share a co-member's cookbook for them. There is deliberately no bulk
   *un*-share, and no server path that shares another user's recipes.

## Where to make common changes

- **List/merge behavior**: `app/lists/merge.py` + its table-driven tests; nothing else merges.
- **New AI surface**: extend `services/ai/` (prompt module + parser reuse), return a draft,
  never persist server-side.
- **New screen**: `ui/<feature>/` + ViewModel + route; Pulse components only.
- **Plate contract changes**: coordinate with Plate (its provider surfaces) and commit contract
  fixtures both sides (`Dragonfly/CROSS-APP.md` rules).
