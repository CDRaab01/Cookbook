# CLAUDE.md — "Cookbook"

> A recipe tracker that doubles as a grocery shopping checklist, extracted from
> Plate's half-built recipe feature into its own first-class app. Third app in the
> ecosystem alongside **Spotter** (fitness) and **Plate** (nutrition). Same stack,
> same conventions, same PULSE design language — but PULSE is consumed as a
> **shared library** here, not copy-pasted (see §3).

---

## 0. Read this first

This file is the source of truth for the build. Work **phase by phase**; do not
start a later phase before the earlier one's exit criteria (tests green, CI green)
are met. When a decision is ambiguous, **match Spotter/Plate's existing choice** —
inspect those repos (`C:\Code\Spotter`, `C:\Code\Plate`) and mirror their patterns.
Plate is the closer template (it went through this exact process against Spotter).

Before writing code in any phase: restate the phase goal, list the files you'll
touch, flag any assumption, then proceed.

**Decisions locked 2026-07-01 (user-confirmed):** name is **Cookbook**
(`com.cookbook`); visual identity is **warm/amber-led** (see §3); the two
Plate-gated phases (§7 Phases 6 & 7) are **confirmed in scope** for this build.

---

## 1. Product summary

Cookbook answers two questions: *"what can I make?"* and *"what do I need to buy?"*

1. **Recipe book** — full CRUD on recipes: name, description, servings,
   prep/cook time, ordered instruction steps, tags, and an ordered ingredient
   list (free-text name + quantity + unit + optional store category).
2. **Shopping list** — a persistent checklist. Tap **"Add to list"** on any
   recipe(s) and the ingredients autofill; duplicate ingredients across recipes
   merge (quantities summed when units match). Manual one-off items too
   ("paper towels"). Check items off in the store; checked items sweep to the
   bottom; "clear checked" when done.
3. **Recipe discovery/import** — search external recipes (Spoonacular, ported
   from Plate's `recipes_ext/`) and import them as editable Cookbook recipes.
4. **Offline-first shopping** — the list MUST work with zero signal in a grocery
   store. Room local-first, background sync on reconnect (Spotter workout-mode
   precedent).
5. **Plate integration** — nutrition breakdown per recipe and "log this recipe
   to Plate's diary", via the established cross-app JWT pattern.
6. **Real user accounts**, same auth approach as Spotter/Plate.

Explicitly **not** v1: pantry inventory tracking, meal-plan calendar, sharing
lists between users, price tracking. A weekly meal planner that feeds the list
is a natural post-v1 phase.

---

## 2. Stack & ecosystem decisions (already made — do not relitigate)

- **Client:** Android, Kotlin, Jetpack Compose, MVVM + repository, Room +
  Retrofit — mirror Plate's client architecture exactly.
- **Backend:** Python FastAPI, SQLAlchemy 2.0 async + Alembic, Postgres, same
  layout as Plate (`app/routers|services|models|schemas`), same lint/test tooling.
- **Own backend, own DB, own users table.** The ecosystem stays
  one-app-one-backend (deploy blast radius, per-repo rollback, guardrail
  isolation). Cross-app needs use `CROSS_APP_SECRET`-signed JWTs carrying the
  user's email, exactly like Spotter's `GET /workouts` for Plate. **No shared
  monolith backend.**
- **Deployment:** Docker Compose (`db`, `server`, optional `cloudflared` behind
  the `tunnel` profile), migrations on boot, `GET /health` + `GET /version`
  (unauthenticated), self-hosted GitHub Actions runner redeploy — clone
  Spotter's `deploy/` setup.
- **App-manager readiness:** a future ecosystem hub app will check `/version`
  and serve APK updates. Cookbook must uphold the conventions it will rely on:
  `/version` reporting `{name, version, commit, built_at}`, and CI publishing a
  release APK artifact per tag.
- **AI:** none in v1. If a coach/import-parser is added later it follows
  Spotter's guardrail model (server-side prompts, validated structured output,
  user-confirmed writes only).

---

## 3. PULSE as a shared library (Phase 0 deliverable)

PULSE currently exists as duplicated `ui/theme/` + `ui/components/` files in
Spotter and Plate. Cookbook is where it becomes a library:

- New repo `C:\Code\Pulse` → Android library module **`pulse-ui`**: the theme
  tokens (`Pulse.kt`, `DataType.kt`, `Motion.kt`, `Shape.kt`, `Type.kt`, fonts —
  **static per-weight font instances, never variable fonts**) and the generic
  components (`PanelCard`, `PulseButton`, `DataText`/`TickerNumber`,
  `ProgressRing`, `Sparkline`, `HeatBar`, `SectionHeader`, `StatTile`,
  `CelebrationPulse`). App-specific channel *semantics* (which hue means what)
  stay configurable per app; Cookbook gets its own channel mapping.
- **Cookbook channel mapping (decided): warm/amber-led.** The streak
  orange→amber family (energyGradient) is Cookbook's hero/primary-action
  channel — cooking = heat. Recovery green = checked-off/done states (list
  items, completed steps). Electric blue and violet remain supporting data
  channels (counts, provenance accents). Hue values themselves stay the shared
  PULSE palette; only the *semantics* are Cookbook-specific.
- Source the extraction from **Plate's copy** (most recently exercised), diff
  against Spotter's for divergence, and flag any drift found.
- Consumption: start with a Gradle **composite build / included build** from the
  sibling checkout (simplest on a single dev machine); publishing to GitHub
  Packages can come later. Cookbook consumes `pulse-ui` from day one.
- **Do not migrate Spotter/Plate onto the library in this project.** That's a
  separate, later task per app — note drift, don't fix it here.

---

## 4. Data model (backend)

- `users` — id, email, name, password hash, reset-token fields (clone Plate's).
- `recipes` — id, user_id, name, description, servings, prep_minutes,
  cook_minutes, source (`manual` | `imported` | `plate`), source_id (nullable),
  image_url (nullable), created_at.
- `recipe_steps` — recipe_id, order, text. (Ordered children, cascade delete —
  Spotter's `ProgramDay` pattern.)
- `recipe_ingredients` — recipe_id, order, name (free text), quantity (nullable
  numeric), unit (nullable, normalized lowercase), category (nullable enum-ish
  string: `produce|meat|dairy|bakery|frozen|pantry|other`), note (nullable),
  plate_food_id (nullable UUID, **unused until the Plate phase**).
- `shopping_lists` — id, user_id, name, created_at. v1 UI uses one default list
  per user ("Groceries"), but the schema supports several from the start.
- `shopping_list_items` — id, list_id, name, quantity, unit, category, checked
  (bool), checked_at (nullable), recipe_id (nullable — provenance for "from
  Chicken Parm"), order, created_at.
- `recipe_tags` / `tags` — deferred until a phase needs them.

**Ingredients are free text in v1.** No foods-table coupling — the shopping list
doesn't need nutrition, and this is exactly the coupling being escaped from
Plate. Nutrition arrives in the Plate integration phase via `plate_food_id`.

**Merge math lives in one backend module** (`app/lists/merge.py` or similar),
pure and exhaustively unit-tested (the `nutrition/` precedent): normalize name
(casefold, trim, singularize-lite) + unit → same item ⇒ sum quantities;
unit mismatch ⇒ separate line items. Clients never merge independently.

---

## 5. External data source: Spoonacular

Port Plate's `recipes_ext/` (base + spoonacular client) and
`recipe_discovery_service` here — this becomes their canonical home.

- `SPOONACULAR_API_KEY` server-side env only; endpoint returns 503 when unset
  (Plate's existing behavior). Rate-limit discovery (30/min, matching Plate).
- Import maps external ingredients into free-text `recipe_ingredients` rows
  (name/quantity/unit) — **no** food-record creation, unlike Plate's importer.
- Plate's own recipe feature (and its Spoonacular usage) is left untouched until
  the migration phase; both may run in parallel during transition.

---

## 6. Feature flows

**Add recipe to list:** recipe detail → "Add to list" (optionally scaled by
servings multiplier) → backend merges ingredients into the default list (§4
merge rules) → list screen shows new/updated items grouped by category, with
per-item provenance. Adding the same recipe twice warns and offers
re-add/skip.

**In-store checklist:** list screen groups by category (store-aisle order),
unchecked first; tap toggles checked (optimistic, offline-queued); checked
items collapse to a dimmed bottom section; "Clear checked" bulk-deletes them.
All list mutations work offline against Room and sync on reconnect
(dedupe-safe, Spotter cardio-repo precedent).

**Discovery/import:** search → results (title/image/time/servings) → import →
lands in the recipe book flagged `imported`, immediately editable.

**Migration from Plate (one-time, per user):** a Cookbook backend command/endpoint
reads Plate's recipes via a small read-only cross-app endpoint added to Plate
(`GET /recipes/export`, cross-app JWT — mirror of Spotter's `/workouts`
pattern) and imports them as `source='plate'` recipes with free-text
ingredients derived from the food names + quantities. **Confirmed in scope
2026-07-01** (includes the Plate-side change).

**Plate integration:** two directions, both cross-app JWT by email —
(a) Cookbook asks Plate to resolve an ingredient → food match for a
per-recipe nutrition breakdown; (b) "send to Plate diary" from Cookbook.
**Confirmed in scope 2026-07-01**; design the exact contract when the phase
starts and keep both sides' cross-app surfaces isolated from session auth.

---

## 7. Build phases (each ends with green tests + green CI)

**Phase 0 — Pulse extraction + scaffolding + CI/CD**
- Create `Pulse` repo, extract `pulse-ui` per §3. Create Cookbook repo mirroring
  Plate's structure; Android skeleton consumes `pulse-ui`; FastAPI skeleton with
  `/health` + `/version`; same linters/formatters/test runners; GitHub Actions
  CI (lint + tests + assembleDebug) both sides.
- Exit: empty app builds using library theme; CI green; trivial passing tests.

**Phase 1 — Accounts & data model**
- Auth cloned from Plate; migrations for all §4 tables.
- Exit: register/login works; schema migrates cleanly; repo-layer tests pass.

**Phase 2 — Recipe book (CRUD)**
- Server CRUD + Android screens: recipe list, detail (ingredients + steps),
  create/edit. PULSE-styled from the library.
- Exit: full recipe lifecycle on device; router + VM tests green.

**Phase 3 — Shopping list core**
- Lists/items endpoints, merge module (exhaustive table-driven tests),
  "Add to list" flow, checklist UI with category grouping + check-off +
  clear-checked.
- Exit: recipe → list → shop flow works end-to-end; merge math fully tested.

**Phase 4 — Offline-first sync**
- Room mirror for the active list (+ recipes read cache), optimistic check-off,
  reconnect sync (network callback, dedupe-safe), airplane-mode manual test.
- Exit: full in-store flow works offline and reconciles cleanly on reconnect.

**Phase 5 — Discovery & import (Spoonacular)**
- Port `recipes_ext`, discovery + import endpoints/screens, attribution where
  required by Spoonacular's terms.
- Exit: search → import → edit → add-to-list works; external API mocked in tests.

**Phase 6 — Plate recipe migration** *(confirmed in scope)*
- Plate-side export endpoint + Cookbook-side import; decide Plate recipe-UI
  deprecation (likely: Plate keeps quick-log of *its* saved meals until the
  integration phase replaces it).
- Exit: existing Plate recipes appear in Cookbook, verified against live data.

**Phase 7 — Plate nutrition/logging integration** *(confirmed in scope)*
- Cross-app both directions per §6; per-recipe macro breakdown; log-to-diary.
- Exit: a Cookbook recipe shows macros and can land in Plate's diary; integration
  tested with a stubbed Plate.

**Phase 8 — Polish & release**
- Weekly "what am I making" quick-picks feeding the list, staples/quick-add,
  empty states, deploy pipeline live (runner + redeploy scripts), tagged APK
  artifact, README.
- Exit: v1 feature-complete, deployed, CI/CD green end-to-end.

---

## 8. Testing & CI

- **Backend:** table-driven unit tests for merge math; router tests against a
  test DB; external APIs (Spoonacular, Plate) always mocked in CI. pytest +
  ruff, same config as Plate.
- **Android:** VM + repository/sync unit tests; Roborazzi screenshot baselines
  (dark + light) like Spotter/Plate.
- **CI:** every PR — lint, format-check, unit tests both sides, assembleDebug;
  block merge on red. **CD:** self-hosted runner redeploy on green `main`,
  manual `workflow_dispatch` with `ref` as rollback (clone Spotter's).
- No secrets in repo. Spoonacular key, DB creds, `CROSS_APP_SECRET` via env.

---

## 9. Conventions & guardrails

- Match Plate/Spotter code style, package naming (`com.cookbook`), commit style,
  PR scoping. One phase per PR-sized chunk; restate assumptions before coding.
- Merge/scaling math centralized and pure; clients display, never compute.
- Cross-app surfaces are deliberately separate from user-session auth and
  disabled (401) when their secret is unset — Spotter's `get_cross_app_user`
  is the reference implementation.
- Known local gotcha: Plate's server pytest has a pre-existing event-loop issue
  when run locally on this machine — if the cloned test config inherits it,
  validate via CI / smoke script, don't chase it as a Cookbook bug.
- If this file conflicts with how Plate/Spotter actually do something,
  **the existing apps win** — flag the conflict.

---

## Build log (2026-07-01) — v1 complete, all phases delivered

Everything above was built in one pass and verified locally. Final state:

- **Verification:** server **93 pytest green + ruff clean** (4.6 s — see gotcha below);
  Android `:app:assembleDebug` + `:app:assembleRelease` + `:app:testDebugUnitTest` green
  (17 unit tests incl. 7 offline-sync repository tests against in-memory fakes); Alembic
  0001 applies to a fresh DB; the full Docker stack boots (migrations on entry) and passed
  an end-to-end smoke (register → recipe → add-to-list ×2 scale → check off → clear).
  Plate-side changes verified with Plate's full suite (292 green on a throwaway Postgres).
- **Pulse extracted:** `C:\Code\Pulse` → `design.pulse:pulse-ui`, consumed via composite
  build. Cookbook is amber-led (heat/fresh/info/plum in `ui/theme/CookbookTheme.kt`).
  Spotter/Plate still carry in-tree copies — migrating them is a separate task per app.
- **Plate integration is live in code, dormant in config:** Plate gained
  `GET /recipes/export` + `POST /cross-app/resolve-foods` + `POST /cross-app/log-recipe`
  (committed on Plate main, NOT yet pushed/deployed). Both sides 401/503 until
  `CROSS_APP_SECRET`/`PLATE_BASE_URL` are set — one ecosystem-wide secret shared by all
  three apps.
- **Host ports:** API on **127.0.0.1:8003** (8002 was taken by posterizarr), Postgres
  on 5434. The compose stack is up and version-stamped.
- **Local-test gotchas fixed at the root** (do not regress): the engine uses **NullPool**
  under tests (`DB_NULLPOOL`, set in conftest) so pooled asyncpg connections never bind a
  dead event loop — this was Plate's local-pytest failure; and `DATABASE_URL` must use
  **127.0.0.1, never localhost** (::1-first resolution + IPv4-only port publish stalls
  every fresh connection; the suite went 6+ min → 4.6 s).
- **Deferred:** on-device airplane-mode pass (needs a phone); weekly "what am I making"
  quick-picks (staples chips shipped instead); Roborazzi screenshot baselines (job exists
  in CI, manual-only, no baselines recorded yet); pushing to GitHub + registering the
  `cookbook` self-hosted runner + Cloudflare hostname (needs credentials only the human
  has — remotes are already set to CDRaab01/{Cookbook,Pulse}).

---

## v0.2 (2026-07-02) — capability-audit round

Everything above shipped, was pushed, and runs at https://cookbook.dragonflymedia.org
(push-to-deploy live via the `cookbook` runner; Spoonacular + Plate integration configured
in the deployed `.env`). v0.2 adds, from a user-driven capability audit:

- **Images everywhere** (Coil): recipe cards, detail header, Discover thumbnails; manual
  recipes take an image URL in the editor ("" clears on PATCH; null leaves untouched).
- **Discover preview**: tap a hit → bottom sheet with photo, meta, full ingredients, first
  steps → import. `GET /recipes/discover/{source_id}` fetches without saving.
- **URL import**: `POST /recipes/import-url` — native schema.org/Recipe JSON-LD parser
  (`recipes_ext/jsonld.py`: @graph/list/multi-type nodes, ISO-8601 durations, unicode-
  fraction ingredient-line parsing) with Spoonacular `/recipes/extract` fallback; SSRF
  guard (http(s) only, no private hosts). **Share-from-browser**: ACTION_SEND text/plain
  intent → URL plucked from shared prose → import dialog pre-filled (SharedIntentStore).
- **Shopping UX**: category picker on add + edit; tap-to-edit items (offline-capable —
  dirty rows now push full state on sync, not just `checked`); autocomplete from a new
  `item_history` table (migration 0002) which also powers **category recall** (re-adding
  "milk" lands where you last put it) with a keyword guesser fallback
  (`lists/categorize.py`) that also auto-categorizes JSON-LD imports; refresh action.
- **Organization**: `favorite` + `tags` on recipes (migration 0002; tags lowercase,
  deduped, ≤10); heart toggle on detail, favorites/tag filter chips + Name/Newest/Quickest
  sort on the list; tag editor chips.
- **Detail extras**: servings rescaler (display-only ingredient math), Duplicate,
  Share-as-text; honest cross-app 502 message (identity mismatch vs secret mismatch).
