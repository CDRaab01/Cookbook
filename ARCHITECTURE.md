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

Alembic 0001–0007, migrate-on-boot. 21 pytest files (~260 tests); CI runs ruff **and**
`ruff format --check`. Local recipe (CLAUDE.md): scratch DB inside the live cookbook-db container,
`DATABASE_URL` on **127.0.0.1:5434**, `DB_NULLPOOL=true` (conftest sets NullPool; bcrypt dropped
to 4 rounds tests-only). One env-dependent local-only failure when the live `.env` has
`SUITE_JWKS_URL` set; green in CI.

## Android (`android/`, package `com.cookbook`)

Standard suite MVVM. Feature packages:

- `ui/shopping/` — the core surface: category-grouped checklist, tap-to-check (optimistic,
  offline-queued), checked items sweep to a dimmed bottom, clear-checked, list switcher
  (multiple named lists; the default = the oldest list), autocomplete + category recall from
  `item_history`.
- `ui/recipe/` — book/detail/editor (servings rescaler is display-only math), cook events
  ("Made it"), share/duplicate; `RecipeDraftStore` receives photo/URL-import drafts.
- `ui/discover/` — Spoonacular search + preview bottom sheet + import; share-from-browser URL
  import via `SharedIntentStore` (ACTION_SEND).
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
`checked`). Room is a mirror — destructive rebuild on schema change is acceptable. The
grocery-store flow must survive airplane mode end-to-end; treat any regression there as P0.

## Invariants

1. **Merge math is server-side and singular** (`lists/merge.py`) — no client-side merging, no
   second implementation.
2. **Shopping list works offline and without AI.** AI features degrade to absence.
3. **AI output is a user-confirmed draft** (`RecipeDraftStore` / `PantryConfirmScreen` pattern).
4. Pantry is a thin presence model (items + staples) — **not** quantity inventory; re-litigated
   and rejected.
5. Ingredients are free text; nutrition coupling only via the Plate integration seam.
6. The release workflow checks out the sibling **Pulse** repo — keep that step when editing CI.

## Where to make common changes

- **List/merge behavior**: `app/lists/merge.py` + its table-driven tests; nothing else merges.
- **New AI surface**: extend `services/ai/` (prompt module + parser reuse), return a draft,
  never persist server-side.
- **New screen**: `ui/<feature>/` + ViewModel + route; Pulse components only.
- **Plate contract changes**: coordinate with Plate (its provider surfaces) and commit contract
  fixtures both sides (`Dragonfly/CROSS-APP.md` rules).
