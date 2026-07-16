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

- **Update `ARCHITECTURE.md` in the same PR** when a change alters architecture — a module's
  responsibility, a layer boundary, a cross-app contract, or the data model. Silently-drifting
  docs are how Spotter's API docs said `/plans` for a round (ROADMAP2 T2 #5c).
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

---

## v0.2.1 (2026-07-02) — buyable-list bug/reasoning audit

A user-reported broken shopping list (duplicate unit-split lines, water on the list,
measure-led unreadable rows) triggered a full audit, not just the one fix. Root cause:
the list modeled cooking data, not a buy list.

- **Merge identity is the normalized name only** (was name+unit — the "2 cup" vs
  "3 tsp oil" duplicate-line bug); amounts aggregate into a `measures` JSON column
  (migration 0003) as `Measure(quantity, unit)` — same canonical unit sums, mixed units
  sit side by side ("2 tbsp + 2 tsp"); legacy `quantity`/`unit` kept in sync for
  single-measure rows.
- **Canonical units everywhere** (`lists/merge.py::canonical_unit`), not just the
  JSON-LD path — mismatched spellings could defeat merging.
- **Non-purchasables filtered** at add-recipe (water/ice/"`<x>` water"); an all-water
  recipe 400s with a clear message instead of landing a useless line.
- Unquantified adds no longer erase a known amount (old rule: unknown + known = unknown).
- Editor PATCH clearing sentinels (`""`/`0`) fixed on both client and server — emptying
  a field now actually empties it.
- Android: name-first row layout (measures as caption), delete-undo snackbar rebuilding
  the aggregate through the normal merge path.
- URL-import range parsing ("2-3 lbs") keeps the lower bound instead of leaking into
  the name.

---

## v0.3.0 (2026-07-02) — "come up with whatever would be useful"

Seven features, each built and verified as its own branch/PR, then merged in the
sequence they were built (each PR was stacked on the last):

- **Recipe notes** (`claude/recipe-notes`): a `notes` TEXT column (migration 0004),
  separate from `description` so imports never clobber it; PATCH clearing convention
  (null = untouched, `""` = clear); "My notes" card on the detail screen.
- **Made-it tracking** (`claude/made-it`): `cook_events` table (migration 0005), one row
  per "I made this" tap — history, not state, so undo is just deleting the latest row.
  `times_cooked`/`last_cooked_at` are grouped aggregates. Detail button ties into the
  existing log-to-Plate dialog; new "Haven't made lately" sort.
- **Cook mode** (`claude/cook-mode`, Android-only): full-screen step-at-a-time view,
  screen-awake, tap-to-jump step dots, duration-detected tap-to-start timers
  (`StepDurations`, elapsedRealtime-anchored per the Spotter drift-free rule).
- **Multiple named lists** (`claude/multiple-lists`): `GET/POST /lists`,
  `GET/PATCH/DELETE /lists/{id}`; the default stays "the oldest list" (a regression test
  caught a hijack bug where a named list created before the first `/default` touch could
  become the default). Android's Shopping title is now a list switcher; Room rows carry
  `listId` (schema v3, destructive rebuild — it's a mirror).
- **Shopping-list widget** (`claude/widget`, Android-only): Glance home-screen widget
  reading the Room mirror via a Hilt EntryPoint, tap-to-check-off, redraws on any
  successful list state.
- **Weekly meal planner** (`claude/meal-planner`): `meal_plan_entries` table (migration
  0006) — a recipe or a free-text note on a date+slot. `POST /plan/to-list` is the
  payoff: every planned recipe in a range runs through the *same* merge module the
  shopping list uses, so a week of dinners becomes one aggregated add. New "Plan" bottom
  tab; no offline mirror (light-touch calendar, not in-store-critical).
- **Photo import** (`claude/photo-import`): ports Plate's LM Studio vision pipeline
  (`app/services/ai/`) to recipe cards/cookbook pages — base64 data-URL image in a
  multimodal chat message, strict-JSON prompt, a forgiving parser (fence-stripping,
  widest-object-span salvage, unit canonicalization), transport failures mapped to
  clean statuses (503/504/502) while content failures degrade to a low-confidence draft
  instead of erroring. `POST /recipes/import-photo` never saves — the client opens a
  fresh recipe editor pre-filled via `RecipeDraftStore` (the `SharedIntentStore`
  idiom), and the user reviews/edits before the normal create endpoint commits it.

**Verified:** server 200 pytest + ruff/format clean; Android `assembleDebug` +
`testDebugUnitTest` green — both on the fully merged tree, not just per-branch.
**Gotcha:** amending a pushed commit's message mid-stack (to fix a stale-scratch-file
copy/paste mistake) orphaned the branches built on top of it until a
`git rebase --onto <new> <old> <branch>` re-pointed them — remember amend creates a new
commit object even with an identical tree, so anything already branched off the old one
needs re-parenting, not just a force-push of the amended branch itself.
**Deferred:** multi-account household sharing, custom/reorderable aisles, camera-captured
recipe photos (vs. web images), pantry-based "what can I make".

---

## Suite membership — Dragonfly hub, SSO, releases (2026-07-02/03)

Cookbook is one of five apps in the personal suite; suite-wide architecture lives in the
**Dragonfly repo** (`CLAUDE.md` + `BROKER.md`). Cookbook was the **pilot app** for suite SSO, so
its implementation is the reference the others copied.

- **Releases:** `release.yml` publishes a suite-key-signed APK + `version.json` on any
  `android/**` push to `main` (the release job checks out the sibling **Pulse** repo for the
  composite build — keep that step when editing the workflow). Post-build `apksigner` guard pins
  the suite signer (`5a596c9e…`). versionCode = epoch minutes; a local debug build cannot
  install over a CI release without uninstalling.
- **Config broker (Phase 1):** `util/SuiteConfigReader` reads
  `content://com.dragonfly.suiteconfig/config/cookbook` in `App.onCreate` (signature-permission
  provider; needs a Cookbook process restart to pick up a changed value) and falls back to local
  prefs when the hub is absent/denied/blank.
- **SSO (Phases 2b/2c — LIVE, built here first):**
  - Server: `POST /auth/suite` (`app/routers/suite_auth.py` + `app/services/suite_auth.py`) —
    validates an RS256 suite access token against https://id.dragonflymedia.org JWKS
    (cached fetch; `aud=suite`, issuer-checked), find-or-creates the local user **by email**
    (random unusable password hash), returns normal Cookbook tokens. Feature-flagged on
    `suite_jwks_url`/`suite_issuer` (unset ⇒ 404; password auth untouched). **The two flag vars
    are pinned in `docker-compose.yml`'s `environment:` block deliberately** — Compose does not
    re-read changed `env_file` content on recreate, and an env_file-only flag silently vanishing
    on redeploy caused production 404s on this endpoint (twice, on Spotter). Secrets stay in
    `server/.env`; required non-secret config goes in `environment:`.
  - Client: AppAuth (`net.openid:appauth`) via `data/remote/SuiteAuthManager.kt` — client id
    `cookbook`, redirect `com.cookbook:/oauth2redirect`, PKCE code flow → `/token` →
    `/auth/suite` → TokenStore. "Sign in with Dragonfly" on LoginScreen; email/password stays as
    fallback. The manifest overrides `net.openid.appauth.RedirectUriReceiverActivity` with
    `Theme.AppCompat.Translucent.NoTitleBar` + `tools:node="merge"` — Cookbook originally dodged
    the AppAuth-on-Material-theme crash only via `launchMode=singleTask`; the override is the
    real fix, keep it.
- **Local server-test recipe** (throwaway DB in the live cookbook-db container is fine):
  ```powershell
  docker exec cookbook-db-1 createdb -U cookbook cookbook_scratch
  $env:DATABASE_URL = "postgresql+asyncpg://cookbook:cookbook@127.0.0.1:5434/cookbook_scratch"
  $env:DB_NULLPOOL = "true"; $env:SECRET_KEY = "x"
  cd server; .venv\Scripts\python.exe -m pytest
  ```
  (127.0.0.1 not localhost; NullPool per the v1 build-log gotchas. conftest drops bcrypt to 4
  rounds for the registration-heavy suites — intentional, tests-only.)
- **Human-gated leftovers:** Roborazzi baselines: Home light+dark recorded 2026-07-03
  (`com.cookbook.screenshot.ScreenshotTest`, PR #2); other screens still unrecorded. The
  screenshots job is manual-only (`workflow_dispatch`), so it doesn't gate PR/deploy.

---

## v0.4.0 (2026-07-03) — pantry scan (`claude/pantry-scan`, local branch)

Photo of the fridge/pantry → the LM Studio vision pipeline lists the food it sees →
confirmation screen ("I see these — anything to add?") → confirmed items persist in a new
per-user **pantry** → "What can I make?" merges local matches over saved recipes with
Spoonacular `findByIngredients`. Closes the v0.3 deferrals "pantry-based what-can-I-make"
and "camera-captured photos".

- **Backend** (migration 0007): `pantry_items` + `pantry_staples` +
  `users.staples_confirmed_at`. `/pantry` router: scan (multipart, 10/min, never persists),
  CRUD (dedupe by `merge_key` — re-adding "Eggs" updates "eggs"), bulk confirm
  (merge-or-replace), staples GET/PUT (seeded `DEFAULT_STAPLES`, one-time confirm marker;
  before confirmation the defaults still count in matching), suggestions (30/min).
- **Matching** (`app/lists/pantry_match.py`, pure): token-set comparison, subset in either
  direction ("chicken" covers "boneless chicken breast"; "cheddar cheese" covers "cheese"),
  descriptor stopwords stripped, water always available; a recipe qualifies with
  ≤ max_missing missing AND ≥1 non-staple pantry hit (staples alone suggest nothing).
  Accepted looseness: "milk" ⊆ "coconut milk" — documented in tests.
- **Vision**: `pantry_scan_prompts.py` mirrors the recipe-photo prompt/salvage pattern;
  the LM Studio transport in `vision.py` refactored to a shared `_chat_vision`.
- **Spoonacular**: `find_by_ingredients` (`ranking=2`, `ignorePantry=true`, ≤20 names,
  pantry before staples) → `IngredientSearchHit`; its `source_id` feeds the existing
  discover-preview/import, so web suggestions are importable for free. No key / API down ⇒
  `external_available:false`, local matches survive.
- **Android**: Pantry via Home quick action (bottom bar stays at five tabs). In-app camera
  (CAMERA permission + FileProvider `com.cookbook.fileprovider` → `cache/scans/`) plus
  gallery; **both paths downscale to ≤1600px JPEG** (`util/ImageBytes.kt`) — camera captures
  exceed the 8 MB cap otherwise. Confirm flow via `PantryDraftStore` (RecipeDraftStore
  idiom); first-use staples sheet (swipe-away = skip this visit, returns until confirmed);
  Settings → "Edit pantry staples" persists per edit. No Room mirror (meal-planner precedent).
- **Deploy fix caught along the way**: the server container's LM Studio default
  (`localhost:1234`) can never reach the host, so deployed photo import had been silently
  503ing since v0.3. Compose now pins `LM_STUDIO_BASE_URL=http://host.docker.internal:1234/v1`
  (verified reachable from the running container) and `LM_STUDIO_VISION_MODEL=google/gemma-4-e4b`
  — the gemma-3-12b weights the code default names are no longer loaded on this machine.
  If scans 502, check which model LM Studio actually has loaded (`GET :1234/v1/models`).
- **Verified**: 261 server tests + ruff clean (the lone red test,
  `test_disabled_by_default_returns_404`, is the known local-only `.env` SUITE_JWKS_URL
  artifact — green in CI); Android `testDebugUnitTest` (8 new VM tests) + `assembleDebug`;
  E2E smoke against live LM Studio: a real 4000px fridge photo → 16 items with categories +
  confidence in ~7s on gemma-4-e4b → confirm → staples PUT → seeded recipe matched 5/6 with
  `missing: ["heavy cream"]`.
- **Human-gated**: on-device pass (camera permission flow, confirm UX); push + PR + deploy.

---

## v0.4.0 (2026-07-03) — Pantry scan (the AI round)

Built on branch `claude/pantry-scan` (3 commits), merged to main 2026-07-03. Photo → pantry →
"what can I make?", following the house AI rules (LM Studio vision, draft-confirm, nothing
auto-committed, shopping list untouched).

- **Scan:** `POST /pantry/scan` (10/min) — fridge/pantry photo → `estimate_pantry_photo`
  (`services/ai/vision.py` + `services/ai/pantry_scan_prompts.py`) → a draft candidate list.
  **Nothing is saved**; the client's confirmation screen posts the reviewed list to
  `POST /pantry/confirm` (merge by default, replace on request).
- **Pantry CRUD:** `GET /pantry`, `POST /pantry/items` (re-add by normalized name returns the
  existing row), `PATCH/DELETE /pantry/items/{id}`. Models in `models/pantry.py`, migration
  `0007_pantry`.
- **Staples:** `GET/PUT /pantry/staples` — the always-assumed-available list; first GET returns
  seeded defaults with `confirmed=false` so the client shows a one-time review sheet.
- **Suggestions:** `GET /pantry/suggestions?max_missing=0..5` (30/min) — saved recipes coverable
  by pantry+staples (ingredient matching in `lists/pantry_match.py`, reusing the merge module's
  normalization) plus Spoonacular find-by-ingredients ideas when configured (silently absent,
  not an error, when not).
- **Android:** Pantry tab (list/edit), scan via camera or gallery (`util/ImageBytes.kt`,
  FileProvider `file_paths.xml`), `PantryConfirmScreen` (review/edit draft →
  confirm), `PantrySuggestionsScreen`, Settings → `StaplesEditorScreen`;
  `PantryDraftStore` follows the `SharedIntentStore`/`RecipeDraftStore` idiom.
- **Compose pin:** the server container's LM Studio host URL + vision model are pinned in
  `docker-compose.yml` `environment:` (per the suite env_file rule).
- **Verified at merge time:** server 261/262 pytest green against a throwaway DB (see recipe
  above). The 1 failure is **env-dependent, pre-existing, green in CI**:
  `test_suite_auth.py::test_disabled_by_default_returns_404` fails locally whenever the live
  `server/.env` has `SUITE_JWKS_URL` set (same class as Plate's Spoonacular test). Android was
  built green on the branch; not re-verified at merge.

---

## Road to 1.0 (2026-07-16) — family mode + the CI gates

The suite's 1.0 polish round. The headline landed plus two long-standing gate items.

- **Family mode (household sharing) — the headline.** Cookbook moved from per-list sharing to a
  **household** (the Magpie pattern): one household, managed in **Settings → Family** (invite by
  email — the invitee must have signed in once — member roster with owner badge,
  owner-removes / member-leaves). New `/household` router (`GET`, `POST /members`,
  `DELETE /members/{user_id}`, `POST /leave`), `models/household.py`, `services/household_service.py`.
  Recipes gained a **`shared`** flag: a *family* recipe is visible AND editable to the whole
  household (fully collaborative; **Delete + the share toggle stay creator-only**, gated by
  `is_owner`), private recipes stay the creator's. `POST /recipes/{id}/share` flips it; the
  recipe list returns own + household-family recipes each flagged `shared`/`is_owner` so the
  Android list splits into **Family** vs **Yours** with a family badge. Shopping lists + meal
  plans are reachable by co-members — every access check resolves through
  `household_service.household_member_ids` (shopping_service + recipe_service + plan_service);
  the **legacy per-list `ListMember` shares still work**. Migration `0014` (households,
  household_members, recipes.shared). The Android per-list `ShareSheet` was **retired** — sharing
  is household-wide now, one surface. Commits `1edb703` (server) + `1e91689` (client).
- **This supersedes the stale "not v1 / deferred" notes above.** §1 still says "Explicitly not
  v1: … sharing lists between users" and the v0.3.0 log defers "multi-account household sharing"
  — both are **now shipped**. (The weekly meal planner deferred in §1 shipped back in v0.3.0
  too.) Those older sections are kept as history; this entry is the current truth.
- **Roborazzi baselines recorded** for the five previously-uncaptured screens (recipe list,
  recipe detail, shopping list, pantry, discover), light + dark — 10 PNGs under
  `android/app/screenshots/`. Home was captured in v0.2. The screenshots job is still
  `workflow_dispatch`-only (doesn't gate PR/deploy), but the "record or delete" ROADMAP gate is
  now satisfied. Commit `4fe9a46`.
- **Static launcher shortcuts** (long-press the app icon): **Shopping list**, **Add item**,
  **Scan pantry** — `res/xml/shortcuts.xml`, each a `cookbook://shortcut/<target>` VIEW intent
  MainActivity captures and the nav host honors after the auth gate. Commit `0257e0c`.
- **Designed empty state** when a recipe search/filter matches nothing (part of the Phase-8
  empty-states sweep; other screens still to do). Commit `c4fafbb`.
- **Verified (per commit `1edb703`):** server **306 pytest green** (3 new family-mode tests in
  `tests/test_household.py`). versionName is still **`0.4.0`** — the 1.0 bump + airplane-mode
  on-device pass are the remaining gate items (ROADMAP "Road to 1.0" #5).
