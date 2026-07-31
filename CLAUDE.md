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
- **2026-07-16 — Consented invites (`household_members.status`, migration `0015`):** adding a member
  now creates a **pending** invite that shares nothing until the invitee accepts — the cookbook +
  lists are never joined silently. `GET /household/invite` + `POST /household/{accept,decline}`;
  `household_member_ids` / `household_owner_id` count only `active` members (so the shared default
  list/plan resolution ignores a merely-invited user). Mirrors Magpie's `b2c3d4e5f6a7`. Server **309
  pytest green**; ruff 0.4.4 format+check clean. Android accept/decline UI still to follow.

---

## v0.5 (2026-07-18) — link items + the "Other never syncs" fix (`claude/shopping-list-other-category-9rv0c4`)

A household member pasted "milk collector <320-char Meijer product URL>" into the add bar and the
item appeared only on her phone (under OTHER). Root cause was **not** category sync:
`shopping_list_items.name` is `String(255)` with no schema cap, so the POST 500'd; the client's
optimistic Room row (only `IOException` was caught) stranded as a serverless ghost — and
`syncPending` **rethrew** non-404/409 `HttpException`s, so that one poison row also aborted every
reconnect sync pass. Fixes + the feature that falls out:

- **Validation:** `ItemCreate`/`ItemUpdate` name caps → clean 422s (raw add-bar text may run to
  2000 chars because a URL gets split out; the *cleaned* name caps at 255 in the service).
- **Sync robustness (client):** a rejected online add deletes its optimistic row and still
  errors; a rejected pending row is dropped and the drain continues (recipe-ops "server wins"
  precedent). A pre-fix wedged phone self-heals on its first post-update sync. Shopping-screen
  grouping now coerces unknown categories into "Other" (defense in depth — nothing can be
  counted in "to buy" yet render nowhere).
- **Link items:** pasting a product URL (Meijer/Walmart/anything) yields a readable row with the
  URL in a new `link_url` column (migration `0017`, Text) and a tappable domain chip. Typed
  text + URL ⇒ typed text is the name; URL-only ⇒ server fetches the page title (JSON-LD
  `Product.name` → `og:title` → `<title>`, `lists/link_items.py` + `link_title_service.py`,
  SSRF guard shared with URL import via new `services/url_guard.py`, 5s budget) with a
  slug-derived fallback (`name_from_url`). Links merge first-wins by normalized name; URL-derived
  names never touch `item_history` or keyword categorization. Edit dialog shows/removes the
  link; Room mirror v5 (`MIGRATION_4_5`) carries `linkUrl`; `util/LinkText.kt` renders the
  optimistic/offline row (server parse is authoritative).
- **Share chooser:** ACTION_SEND now lands in a nav-host dialog — "Import as recipe" (the old
  flow, unchanged when Discover is already open) or "Add to shopping list" (routes the raw text
  through the normal add path).
- **Verified:** server **341 pytest green** (31 new: split/slug tables, title-service fetch
  matrix, endpoint + 422 regressions, merge first-link-wins) + ruff clean at CI scope; alembic
  chain applies to a fresh DB. Android unit tests written (rejection-ghost, poison-row drain,
  offline link split, undo-with-link, LinkText tables) but **not run locally — no Pulse checkout
  in this environment**; CI (which checks out Pulse) is the Android gate for this branch.

## v0.6 (2026-07-18) — link previews, quantity, "buy again" (same branch)

Three follow-ons that make link items feel finished, built on the v0.5 base:

- **Product thumbnails (image-only):** the one guarded page fetch now returns a
  `LinkPreview(title, image_url)` — `resolve_link_preview` extracts JSON-LD `Product.image` /
  `og:image` alongside the title, so a link add (URL-only *or* typed+URL) gets a picture. Stored
  in `shopping_list_items.image_url` (migration `0018`, Text); the row shows a Coil thumbnail.
  Deliberately **no price** (per the CLAUDE.md price-scope note; user-confirmed image-only).
- **Quantity stepper:** link rows get a −/＋ "×N" count (Android `QuantityStepper` →
  `ShoppingViewModel.setLinkItemQuantity`, optimistic like check-off, reusing `editItem`); the
  recipe-measures caption is suppressed for link items so the two never double up.
- **"Buy again" link recall:** `item_history` gained `link_url`/`image_url`, written only from
  *typed* adds; `recall_link` re-attaches the remembered link + thumbnail when you re-add an item
  by name (e.g. "milk collector" after clearing it), with no re-paste. URL-only adds still never
  touch history, so autocomplete stays clean. Clearing a link (edit dialog) drops its thumbnail
  too, both server- and client-side.
- **Verified:** server **347 pytest green** (6 new: image extraction, thumbnail on both add
  paths, buy-again recall + its typed-only guard, clear-drops-thumbnail) + ruff clean; alembic
  chain (→0018) applies to a fresh DB. Android: DTO/Room `imageUrl` (schema v6, `MIGRATION_5_6`),
  new VM + repo tests; **not run locally (no Pulse checkout) — CI is the Android gate**.

## v0.7 (2026-07-18) — store-aisle categories + smarter guesser (same branch)

The 7 food-only buckets didn't fit a list that now carries real store items (the "milk collector"
landing in Other/Dairy was the tell). **Category set widened to 13 store aisles** in walk order:
produce, meat (label "Meat & Seafood"), deli, dairy ("Dairy & Eggs"), bakery, frozen, pantry,
snacks, beverages, household, personal ("Personal care"), baby, other. `STORE_CATEGORIES`
(`models/recipe.py`) + Android `DEFAULT_AISLE_ORDER` both updated; **no migration** (plain string
column). Android `categoryLabel`/`categoryEmoji` gained the new aisles. This **supersedes the
`produce|meat|dairy|bakery|frozen|pantry|other` enumerations in §4** (kept as history).

- **Guesser rewrite (`lists/categorize.py`):** naive substring → **word-boundary + longest-wins**.
  Fixes the substring false-positives ("milk collector"→baby not dairy, "eggplant" not *egg*,
  "chipotle peppers" not *chip*, "juice" not *ice*) and adds ~150 keywords across the new aisles
  ("iced coffee"→beverages, "paper towels"→household, "diapers"→baby, "potato chips"→snacks).
  Matched against both the raw and normalized name with a tolerant trailing plural (works around
  `normalize_name`'s "cookies"→"cooky" quirk). Spoonacular `_AISLE_MAP` + the pantry-scan prompt
  learned the new food aisles too.
- **Verified:** server **384 pytest green** (categorize table expanded to 54 cases incl. the
  substring-safety + new-aisle rows; `test_map_aisle` updated for beverages/snacks) + ruff clean.
  Android: `DEFAULT_AISLE_ORDER`/label/emoji + `AisleOrderTest` updated; CI is the Android gate.
- Existing items keep their stored category; only *new* adds/guesses use the wider set (history
  recall still wins first, so a corrected item stays corrected).

### One-time re-sort (migration `0019`, 2026-07-18)

A user-requested backfill so the *current* list benefits, not just new adds. `0019` re-guesses
every `shopping_list_items` + `item_history` row with the new guesser, but **only when the stored
category equals what the OLD (pre-v0.7) substring guesser would have produced** for that name —
i.e. it was auto-assigned, not hand-picked. Manual categorizations (stored != old auto-guess)
are left untouched. So Other-stranded items get a home (diapers→baby, paper towels→household)
and stale auto-guesses get corrected (iced coffee pantry→beverages, milk collector dairy→baby),
while a deliberately-placed item (e.g. chicken you filed in pantry) stays put. The migration
carries a **frozen snapshot of the old keyword map** to make that distinction; `resort_category`
is the pure, unit-tested helper (`tests/test_resort_migration.py`, 13 cases). Data-only,
downgrade is a no-op, and it no-ops on a fresh DB (no rows). Server **397 pytest green**.

## v0.8 (2026-07-19) — cooking measures off the buy list (`claude/shopping-list-font-scroll-pse16q`)

User feedback on the real list: items still showed nonsensical cooking amounts ("craisins 2 cups",
"2 tbsp oil", "3 tsp yeast"). A shopping list is a list of things you **buy**, and a cooking-volume
unit tells you how to *cook*, not what to *purchase* — you buy a bag, a bottle. This **reverses the
v0.2.1 decision to keep "2 tbsp + 2 tsp"** side by side (that was unreadable in-store); those older
sections stay as history, this is the current truth.

- **Rule (pure, in `lists/merge.py`):** `_COOKING_ONLY_UNITS = {tsp, tbsp, cup, pinch, dash}`;
  `is_buyable_measure(unit)` / `buyable_measures(measures)` drop them. Store units
  (lb/oz/g/kg/ml/l, can/bag/box/bottle/jar/tub/package, bunch/head) and bare counts ("3 eggs")
  are **kept** — they help you shop. `add_measure`/`merge_incoming` are unchanged (still aggregate);
  the buy-list filter is a separate, later step.
- **One choke point:** applied in `shopping_service._store_measures`, which every write path goes
  through (recipe add, plan-to-list, manual add, edit), so a cooking amount can't reach the list
  from anywhere. The item always stays; it just reads by name. `_record_history` also forgets a
  cooking-only unit so autocomplete/recall never re-suggests one.
- **Existing list backfilled — migration `0020`:** strips cooking-only measures from every
  `shopping_list_items` row (recomputing legacy quantity/unit) and nulls any cooking-only
  `item_history.unit`. Pure helper `cleaned_item` is unit-tested (`tests/test_cooking_measures_migration.py`,
  8 cases); data-only, one-way downgrade, no-ops on a fresh DB.
- **Also in this branch (Android-only):**
  - Shopping rows kept compact (16sp) with tighter 2dp row gaps after a bigger-font experiment was
    reverted per user preference; the surviving buy-amount caption ("8 oz") now reads in fresh/green.
  - **Pinned default list (both tabs).** A household owner's own empty "Groceries" was winning as the
    server default (`get_default_list` → owner's oldest), so the app opened to it instead of the list
    actually shared with a partner; the Plan tab also only held its context in memory and picked an
    arbitrary `shared.first()`. New client-side **pinned default** (`AppPreferences.pinnedListId`,
    DataStore): a "Set as default" action in the Shopping list switcher (+ a "· Default" marker) pins
    a list; `seedActiveListFromPin` makes it the active list once per launch (in-session switches
    still hold, pin re-applies next cold start); `PlanViewModel` defaults its context to the same pin.
    Server default logic unchanged.
- **Verified:** the pure merge + migration helpers pass locally (no server deps in this env — CI
  runs the full pytest suite incl. updated `test_lists`/`test_sharing` router tests, which were the
  cases that encoded the old keep-cooking-measures behavior). New/updated tests: `test_merge`
  (`is_buyable_measure` table + `buyable_measures`), `test_lists` (cooking-dropped, store-unit-kept,
  new `test_cooking_units_never_land_on_the_list`), `test_sharing` (merge now uses liters).

## v0.9 (2026-07-29) — recipes read like recipes; aisles belong to the shopping list

User feedback (via the household's other member) on an imported steak-fajitas recipe: the recipe
detail screen chopped its ingredients into **store aisles** — PRODUCE / MEAT & SEAFOOD / PANTRY —
instead of the way the recipe is written. Verbatim: *"the recipe should parse ingredients to the
shopping list categories but not parse it in recipe … recipe needs to maintain the recipe format
but when it parses ingredients to shopping list it should send to right category"*, and *"the
directions say to mix marinade but idk what that is"*. Three defects, all fixed here.

- **Aisle grouping is gone from the recipe screen.** `RecipeDetailScreen` renders ingredients in
  the recipe's own order; cook mode and share-as-text match. **This supersedes the v0.7 implication
  that the 13 store aisles order a recipe** — they order a *buy list*. The old `groupBy(category)`
  also silently dropped any ingredient whose category wasn't one of the 13 (nothing can be dropped
  now) and ignored the user's custom aisle order that the shopping screen honors.
- **Ingredient sections** (`recipe_ingredients.section`, migration `0021`, `String(80)`): the
  recipe's own heading ("Steak Marinade", "Fajitas"), rendered as contiguous runs — never a sort
  key, never read by merge/categorize/shopping. Recovered on import by the new
  `recipes_ext/ingredient_groups.py` since schema.org has **no** ingredient-group field: inline
  heading entries inside `recipeIngredient` ("For the marinade:") first, then a regex scrape of
  the page markup (one shape-based heading→`<li>` scanner covering WPRM/Tasty/Mediavine and
  anything similar, *not* three per-plugin parsers), aligned back onto the JSON-LD lines by
  normalized text. **The contract is that every stage declines rather than guesses** — a match
  ratio below 70%, non-contiguous assignments, or more headings than ingredients all return "no
  sections", making the import byte-identical to before. A false-positive heading *deletes* an
  ingredient, hence the paranoia (`is_section_heading` requires no digit/fraction anywhere, ≤60
  chars, and a colon / "For the …" / short ALL-CAPS that isn't a recognizable food). Spoonacular
  is left flat on purpose: `extendedIngredients` carries no groups and `analyzedInstructions[].name`
  groups *steps*, not ingredients. Photo import asks the vision model for `section` **and**
  `category` (the latter closes a standing gap — photo ingredients used to land uncategorized).
- **Categories are right now.** Verified bugs: `"ground cumin"` → meat (a bare `"ground": "meat"`
  keyword outranked the spice), `"large poblano (ribs and seeds removed then sliced)"` → meat
  (`"rib"` matched inside the *prep note*), `"red pepper flakes"` → produce, and `pineapple juice`
  vs `lime juice` disagreeing by keyword length. Fixes: a new `clean_for_category` strips
  parentheticals and trailing prep clauses **before** matching (`merge.normalize_name` is
  untouched — merge identity and `item_history` keys do not move); bare `"ground"` is gone in
  favor of the explicit cuts; ~20 keywords added. Cleaning is the identity function for a name
  with no parenthetical or prep clause, which is why nothing previously-correct moved (the
  existing table plus 20 new rows all pass).
- **The recipe → list path finally learns.** `add_recipe` and `plan_to_list` used to pass
  `ing.category` verbatim, skipping the history→guesser precedence manual adds use. Now:
  **history → recipe's value → guess**, batched into one query (`remembered_categories`). History
  outranks the recipe deliberately — the recipe's value is a machine guess from import, yours is
  where you actually found it. `update_item` now writes a re-file back to `item_history`, without
  which the recall could never learn anything.
- **Migration `0022`** re-sorts existing `recipe_ingredients` + `shopping_list_items` +
  `item_history` so the *already-imported* recipe benefits. Follows 0019's rule (rewrite only when
  the stored value equals the old auto-guess, so hand-picked aisles survive) but **reconstructs the
  old guesser from a ~25-line frozen `_REMOVED`/`_ADDED` diff instead of copying the 390-entry map
  again** — 0019 had to freeze the whole map because the matching *algorithm* changed; here only
  the map did. Documented coupling: a future keyword edit shifts this migration's notion of "old"
  for a DB that hasn't run it yet. Bounded, and why those literals must never be "kept in sync".
- **Editor:** sections are edited as a **marker on the row that starts each run**
  (`IngredientDraft.sectionHeader`), not derived by comparing values. Deriving breaks twice: two
  runs silently merge the instant you finish typing a name matching the run above, and clearing a
  heading collapses the field you're typing in. Ingredients also gained up/down reordering (they
  had none), and the row of **all 13 aisle chips under every ingredient** — a shopping concern
  shouting in the recipe editor — is now one compact "Aisle: …" dropdown.
- Also: an imported ingredient's note (the source's whole line) is suppressed when it only
  restates the row it sits under, which was doubling the height of every imported recipe.
  `CATEGORY_ORDER`/`categoryLabel` moved from `ui/recipe/RecipeDetailScreen.kt` to
  `util/AisleOrder.kt` (six call sites), so aisle vocabulary no longer lives in a recipe screen.
- **Verified:** the stdlib-pure server suites run green **locally** (168: categorize incl. the new
  `clean_for_category` table, the whole `ingredient_groups` module, the 0022 helper, and every
  JSON-LD parser test) + `ruff check`/`format` clean at CI scope. The DB-backed additions
  (`test_recipes` section round-trip, `test_lists` recall precedence, `test_photo_import`) and all
  Android tests are **CI-gated** — this environment has no Postgres and no Pulse checkout.
- **Gotcha worth keeping:** a regex heading-scanner must not treat a *container* class as a
  heading. WPRM's group `<div>` carries `…-ingredient-group`, and matching it made the heading
  branch swallow the entire list it was supposed to label (zero ingredients scraped). The heading
  capture now refuses to span `<li`/`<ul`/`<ol`/`<div`.

## v0.10 (2026-07-29) — bulk "share all my recipes" + the unshared-recipes prompt

Reported as *"sharing is broken — I can't see my wife's recipes."* It wasn't: the household was
`active` for both accounts, every endpoint was deployed, and `test_household.py` passed. The live
DB just had **`shared = false` on all 364 recipes** — nobody had ever used the per-recipe toggle.

The real defect was a **discoverability gap, not a bug**: accepting an invite shares the shopping
lists and meal plans *immediately*, while every recipe stays private until its creator opts it in
one at a time — and no surface said so. Two people who deliberately joined a household to share a
cookbook got a shared list and an invisible cookbook. Fixed by making the choice reachable, without
weakening who is allowed to make it.

- **`POST /recipes/share-all`** (`recipe_service.share_all_own_recipes`) — one bulk `UPDATE` setting
  `shared = true` on the caller's still-private recipes, returning `shared_count` (what actually
  changed, so a second call reports 0 rather than a total). **Filtered to `user_id == caller`**:
  the creator-only rule of the per-recipe toggle is preserved, so this can never share a
  co-member's cookbook on their behalf. There is deliberately **no bulk un-share** — reversing a
  share stays per-recipe, where you can see which one you're making private again.
- **`HouseholdOut.unshared_recipe_count`** (`count_unshared_own_recipes`, one indexed COUNT on the
  existing `GET /household` read) — how many of *your* recipes are still private. Per-caller, so a
  co-member with none of her own sees 0. The client doesn't re-derive it from a list it may have
  filtered.
- **Two surfaces, one action.** Settings → Family gains a "Share all my recipes" button (with a
  confirm dialog naming the count and stating only-yours-are-shared) shown when
  `shared && unshared_recipe_count > 0`; the recipe book gains a dismissible prompt in the same
  condition. Dismissal is a one-way DataStore flag (`AppPreferences.shareAllNudgeDismissed`) —
  "Not now" means *never again on this device*, not "re-nag next time", and nothing is lost because
  the Settings action is permanent. The prompt is strictly additive: a failed/offline `GET
  /household` just means no prompt, and an older server omitting the field defaults it to 0.
- **Deliberately not done:** flipping the existing 364 rows in prod, and defaulting new recipes to
  `shared` inside a household. Both were considered and rejected — the first overrides a privacy
  choice on data the actor doesn't own (the exact thing `is_owner` exists to prevent) and would be
  invisible to its owner; the second changes a privacy default and deserves its own decision.
  **A recipe still only becomes visible because its creator said so.**
- **Verified:** server **525 passed** (4 new in `test_household.py`: yours-only incl. an assertion
  that a co-member's private recipe survives, idempotence/solo, the count driving the prompt, auth)
  + `ruff check`/`ruff format --check` clean **on CI's pinned 0.4.4**, not just the newer pyproject
  pin. Android **118 unit tests, 0 failures** (5 new nudge tests) + `:app:compileDebugKotlin`, run
  locally against the sibling Pulse checkout.
- **Pre-existing local failures, confirmed not mine** by re-running the same files on a clean
  worktree of `main`: 8 env-dependent tests (`test_suite_auth` ×1, `test_plate_*` ×6, `test_pantry`
  ×1) fail identically before and after, because mounting the live `server/.env` into the test
  container supplies `SUITE_JWKS_URL`/`PLATE_BASE_URL` and they reach for real config. Green in CI.
  **Worth keeping:** the prod image has no `pytest` and its entrypoint ignores a passed command —
  run the suite with `--entrypoint sh` and `pip install pytest`, or you get a booted API and an
  empty log instead of a test run.

---

## v0.11 (2026-07-31) — store routing + the local model on the shopping list

The round the user asked for: bring the LM Studio Gemma (`google/gemma-4-e4b`, the same sidecar
photo import and pantry scan already use) to bear on the **shopping list**, and move toward routing
a real store — Meijer on Maysville Rd first, other stores selectable later. Built in phases on
`claude/store-routing`.

**Design decision that shapes everything else: two layers, so the 13-category vocabulary never
moves.** Items keep their canonical `category` (recipes, `item_history`, the keyword guesser and
migrations 0019/0022 all depend on it). A *store* is where that vocabulary meets a floor plan —
its own ordered aisles, each claiming some categories, plus per-item exceptions. Full rationale in
ARCHITECTURE.md "Store routing".

### Phase 1 — stores, aisles, placements (server, no AI)

- **Migration `0023`** + `models/store.py`: `stores` (creator + `name`/`label` — "Meijer" /
  "Maysville Rd", split so the AI layout prompt gets a clean chain name), `store_aisles` (ordered,
  `name` = whatever the sign says, `categories` = JSON list of canonical keys), `store_placements`
  (unique on `(store_id, key)`, key = `normalize_name`).
- **`/stores` router + `store_service`:** CRUD, `PUT /{id}/aisles`, placement upsert/delete.
  Access mirrors shopping lists exactly (`household_member_ids`) — two people shopping the same
  store want the same floor plan — while **delete stays creator-only** (the `is_owner` rule).
- **A new store seeds the canonical walk order** (one aisle per category), so selecting a
  brand-new store reproduces the grouping the user already had. A store can never make the list
  worse before it's been edited.
- **The aisle PUT preserves rows the payload identifies by id.** That is the whole point: a
  reorder or rename must not discard the placements someone learned by walking the store. Only an
  aisle genuinely removed loses them (DB cascade). An *unknown* id inserts rather than 404ing the
  save — a stale id means another device edited the layout, and losing the user's reordering over
  it would be worse.
- **A placement never rewrites the item's `category`,** and never touches `item_history`: where a
  thing sits in *this* store says nothing about the next one. Placements are household-shared (a
  fact about the store); `item_history` stays per-user (a preference).
- **`ItemOut.key`** (= `normalize_name(name)`, a pydantic `computed_field`) so the client looks up
  "which aisle is this here" with a map get instead of re-implementing the normalizer in Kotlin.
- **Gotcha, cost me four red tests:** the session is `expire_on_commit=False`, and these writes go
  through `db.add`/`db.delete` + a DB-level cascade rather than through the ORM collections the way
  `shopping_service` does — so the identity map handed back pre-write `aisles`/`placements` and the
  response claimed a deleted aisle still existed. `store_service._reload` uses
  `execution_options(populate_existing=True)`.
- **Verified: server 548 passed, 0 failed** (15 new in `tests/test_stores.py`), ruff 0.4.4
  `check` + `format --check` clean at CI scope (`app`).
- **Doc fix made along the way:** the local-test recipe in this file and ARCHITECTURE.md said
  `DATABASE_URL` on `127.0.0.1:5434`. **`cookbook-db-1` publishes no host port** — that recipe
  fails with `InvalidPasswordError` against whatever else is on 5434. The working recipe (throwaway
  container on `cookbook_default`, password read out of the container, `--entrypoint sh`) is now in
  ARCHITECTURE.md § Migrations & tests. Running from a git worktree also sidesteps the ~8
  env-dependent failures, since there is no live `server/.env` to mount. **Recreate the scratch DB
  per run** — `test_suite_auth` registers fixed emails and asserts a starting count of 0, so a
  reused scratch DB fails two tests for reasons unrelated to your change.

### Phase 2 — the local model reaches the shopping list (background aisle filing)

- **`services/ai/text.py::chat_text`** — the text sibling of `_chat_vision`: same host, same
  `client=` MockTransport seam, same 503/504/502 taxonomy, but `temperature=0` and a **mandatory
  `max_tokens`** (an unbounded local completion turns a 200 ms classification into a 30 s one).
  New `lm_studio_model` setting, pinned in compose `environment:` next to the vision one.
- **`services/ai/jsonish.py`** — the fence-stripping / widest-`{...}`-span salvage both vision
  prompt modules had privately, extracted once. `recipe_photo_prompts` and `pantry_scan_prompts`
  now import it; their existing tests cover the move.
- **`services/classification_service.py`** — for items the deterministic chain (history → keyword
  guesser) left NULL, ask Gemma which of the 13 aisles it belongs in. Runs as a `BackgroundTasks`
  job **after** the response on its own session, wired into `POST /lists/{id}/items`,
  `POST /lists/{id}/add-recipe` and `POST /plan/to-list`. The invariant is structural, not a
  promise: the add path's latency and result are unchanged, and every failure mode (model down,
  timeout, junk reply, item deleted mid-flight) leaves the row exactly as it was.
- **This is the suite's documented exception to drafts-only** — a category is metadata, the failure
  mode is "unfiled" not "wrong data committed" (Remnant's note classifier established it). The
  guardrails that make it safe: writes only `category` and only where still NULL; **never writes
  `item_history`** (a machine guess must not become "remembered" and outrank the guesser forever);
  re-checks the name under the write so a rename mid-call isn't clobbered; and the parser returns
  `None` rather than falling back to `other`, so an unplaced item stays eligible for a retry.
- **Self-healing without a polling loop:** every add re-queues *everything* unfiled on the list
  (capped at 15, idempotent), so a row stranded while LM Studio was down gets picked up by the next
  add. Cookbook has no `/sync` to piggyback on the way Remnant does.
- **Verified: 581 passed** (33 new in `tests/test_aisle_classification.py` — parser table, the
  transport taxonomy, and the DB-backed guards incl. rename-during-call and history-untouched);
  ruff 0.4.4 clean. **Live smoke against the loaded `google/gemma-4-e4b`:** 12/12 names the keyword
  guesser misses filed sensibly (cotton swabs→personal, kombucha→beverages, dryer sheets→household,
  za'atar→pantry, teething rings→baby, frozen edamame→frozen), ~0.3 s each once warm, 3.9 s on the
  first cold call.

### Phase 3 — "Organize list" (the same capability, as a draft)

Background classification only touches items *nobody has filed*. Reviewing the whole list means
proposing to move things the user placed by hand, so it can't be silent — it drafts and waits.

- **`POST /lists/{id}/organize`** (10/min) — unchecked items only (a checked item is history for
  this trip) → `services/ai/organize_prompts.py` → `OrganizeDraftOut`. **Saves nothing.**
  **`POST /lists/{id}/organize/apply`** takes back only the accepted moves, makes **no model call**
  (works with LM Studio down — the review screen may have been open a while), and skips rows that
  vanished rather than 404ing the batch.
- **Apply writes `item_history`** via the same `_remember_category` the edit dialog uses. That is
  the deliberate asymmetry with Phase 2: accepting a suggestion is a decision about where *you*
  file that item, so the next recipe mentioning it lands there too.
- **`parse_organize` treats the names that were sent as a whitelist** — a name the model invented
  or garbled is dropped, never fuzzy-matched, because guessing which row was meant is how the wrong
  item moves. It also drops invalid aisles and no-op "moves" (padding the review with noise trains
  the user to tap Apply without reading). `None` = unreadable reply; `[]` = read it, nothing to do
  — different messages in the UI.
- **Verified: 609 passed** (28 new in `tests/test_organize.py`). **Live smoke on a deliberately
  mis-filed 10-item list:** all 6 real mistakes caught (ground cumin meat→pantry, iced coffee
  pantry→beverages, paper towels produce→household, frozen peas pantry→frozen, diapers
  household→baby, dish soap personal→household), the 3 correctly-filed items left alone, no
  hallucinated moves. **It missed "milk collector" (a baby product filed under dairy)** — that
  obscure name is exactly the kind the user just moves themselves. **15.7 s for 10 items**, which
  is why the Android client needs a read timeout well above OkHttp's 10 s default (Phase 5).
- **Test-writing gotcha:** patching `chat_text` to raise `httpx.ConnectError` does *not* test the
  503/504 mapping — that mapping lives *inside* `chat_text`, so patching it out bypasses the thing
  under test (the exception escaped the ASGI app instead). Transport mapping is tested directly
  with `MockTransport` in `test_aisle_classification`; the endpoint test asserts only that Organize
  passes an `HTTPException` through rather than swallowing it into a cheerful 200.

### Phase 4 — "Suggest layout", and the reasoning-token trap that nearly hid it

- **`POST /stores/suggest-layout`** (5/min) — chain name → a draft aisle walk order. Saves nothing;
  the client opens it in the editor and commits via the normal `POST /stores` / `PUT .../aisles`.
  `parse_layout` guarantees the draft is *usable* rather than correct: names clamped, invented
  categories dropped, a twice-claimed category kept by the first aisle, and **every category the
  model forgot swept into a trailing "Everything else" aisle** (otherwise its items land in the
  client's "Unsorted" bucket and read as a bug in the layout you just saved). An unreachable or
  unreadable model returns the canonical walk order flagged `low_confidence` — adding a store must
  not depend on AI either.

- **⚠️ THE FINDING OF THIS ROUND — `google/gemma-4-e4b` is a *reasoning* model.** It spends hidden
  `reasoning_content` tokens that count against the **same `max_tokens` budget**, and emits **no
  content at all** until it has finished thinking. Sized for the visible answer, the call returns
  `finish_reason: "length"` and an **empty string** — which every forgiving parser in
  `services/ai/` correctly reports as "unreadable", so the feature falls back silently and looks
  like a model that just doesn't know the answer. Suggest-layout shipped at `max_tokens=900` and
  fell back to the default order **100% of the time**; only a live smoke test caught it, because
  every unit test passed and the endpoint returned a clean 200.

  Measured on this host: | prompt | reasoning | answer |
  |---|---|---|
  | single-item classification | **0** | 3 |
  | Organize, 10 items | 597 | 296 |
  | store layout | 932 | 169 |

  Budgets are now 64 / 3000 / 2500 and **`chat_text` logs a loud warning** on the empty-content +
  `finish_reason: length` signature, so this can't hide again. Classification is the one prompt
  simple enough that the model doesn't reason at all, which is why 64 is still fine there.
  **Anything added to `services/ai/text.py` must budget reasoning + answer, and must be smoke-
  tested against the live model — unit tests with a mocked transport cannot see this.** Worth
  checking whether Spotter and Remnant's text calls have the same latent problem.

- **Verified: 635 passed** (26 new in `tests/test_store_layout.py`, incl. the leftovers sweep and
  the draft→`POST /stores` round trip; 2 new truncation-warning tests). **Live smoke after the fix:**
  Meijer → 11 aisles, Aldi → 13, both plausible walk orders covering all 13 categories and
  genuinely differing per chain (Aldi puts pantry/snacks right after produce), ~10 s each. Organize
  also got faster with the bigger budget (15.7 s → 5.9 s), same 6 correct moves.

### Phase 5 — Android: the list actually routes by store

- **`util/StoreRouting.kt::groupForStore`** is the whole feature, pure and table-tested. No store →
  the v0.7 category grouping, unchanged. Store selected → **placement → first aisle claiming the
  item's category → trailing "Unsorted"**, empty aisles omitted (a walk order is only useful if it
  shows what's left). `ShoppingListBody` now renders `List<AisleSection>`; the checked/"In the cart"
  partition, the counts row and every row composable are untouched.
- **Two properties are pinned by tests**, because they're what make the feature safe to turn on:
  nothing is ever dropped (an item with no home is still an item you have to buy — including one
  whose placement points at an aisle deleted on another device), and **a default-seeded store
  renders identically to the category grouping**, so selecting a store can never make the list
  worse before you've edited anything.
- **Selected store = client DataStore** (`pref_selected_store_id`), per-device like the pinned list.
  Two household members can be standing in different stores at once even though the profiles are
  shared. Picker is a top-bar action that only appears once a store exists.
- **Room v7** caches stores/aisles/placements: aisle routing is only useful *inside* the store,
  which is exactly where signal is worst, so a network-only floor plan would defeat the feature.
  Store mutations are otherwise online-only; `pending_placements` is the one queued write, drained
  poison-row-safely (rejected row dropped, never wedges the backlog — the v0.5 lesson).
- **OkHttp timeouts finally set** (`di/NetworkModule.kt`): there were **none**, i.e. the 10 s read
  default, which the ~10 s layout suggestion would have raced and the cold-model path lost outright.
  Now connect 30 / read 120 / write 30, deliberately above the server's own 60 s `LM_STUDIO_TIMEOUT`
  so the server's honest 502/503/504 reaches the user instead of a generic client timeout (Spotter's
  precedent).
- `ItemOut.key` reaches the client as `ShoppingItemOut.key`, defaulting to `""` against an older
  server — which just means no placement matches and routing falls back to the category.
- **Verified: Android 134 unit tests, 0 failures** (16 new in `StoreRoutingTest`) +
  `:app:assembleDebug` green, run locally against the sibling Pulse checkout.

### Phase 6 — Android: managing stores, and the suggested-layout flow

- **Settings → Manage stores** → `ui/stores/StoresScreen` (list/add/delete) and
  `ui/stores/StoreEditScreen` (reorder / rename / assign categories / add / remove). Adding a store
  offers two routes to a floor plan: **"Start from defaults"** (server seeds the canonical walk
  order) or **"Suggest layout"** (the local model proposes the chain's aisles). The suggestion takes
  ~10 s, so the dialog says what it's waiting for rather than just spinning.
- **The draft goes through `util/StoreLayoutDraftStore`** (the `PantryDraftStore`/`RecipeDraftStore`
  idiom) and **the store does not exist until Save** — the house drafts-only rule, and the reason
  the editor can also create. A draft with no aisles (model unreachable/unreadable) seeds the
  standard order rather than showing a blank page with an error: the user asked to set up a store,
  and a list to drag around is a better answer.
- **Category assignment is exclusive** — assigning a category to an aisle takes it off whichever
  aisle had it. The server routes a twice-claimed category to the first aisle in walk order, so
  letting the editor show it in two places would display a rule the list doesn't follow. Anything
  left unassigned is named under the list ("Items in these land under Unsorted"), so the Unsorted
  pile is never a mystery discovered mid-shop.
- **Reset-to-standard keeps the existing aisle ids**, so a reset is a reorder rather than a wipe of
  every learned placement — the same reason the save carries ids through.
- Aisle-order editor copy now says it's the *no-store* fallback, and it renders `categoryLabel`
  instead of a capitalized key (it said "Meat" while the list it controls said "Meat & Seafood").
- **Verified: Android 149 unit tests, 0 failures** (15 new: draft prefill/consumption, id-carrying
  save, exclusive assignment, reset-keeps-ids, save guards) + `:app:assembleDebug` green.

### Phase 7 — Android: the Organize review + learning where things live

- **"Organize list…"** in the list-switcher menu → `POST /lists/{id}/organize` →
  `util/OrganizeDraftStore` → `ui/shopping/OrganizeReviewScreen` (the PantryConfirm idiom): one row
  per proposed move reading *Current → Suggested*, **all ticked by default** (every suggestion
  already survived the server parser, which drops anything it can't verify, so ticking each one
  would be busywork) with All/None and an "Apply N changes" button. An already-tidy list gets a
  snackbar, not a screen it has to dismiss. Error copy speaks the house taxonomy in shopper terms:
  503 → "Is LM Studio running?", 504 → "it may still be warming up".
- **"Move to a different aisle here…"** in the item edit dialog, offered only with a store selected.
  Optimistic against the Room cache so the list regroups under the finger, queued in
  `pending_placements` when offline, drained by `NetworkSyncObserver` alongside the shopping and
  recipe backlogs. The dialog states the scope out loud — *only changes where it sits at this store*
  — because the one thing a user could reasonably fear here is that it silently re-categorizes the
  item everywhere. It doesn't, deliberately.
- **Verified: Android 156 unit tests, 0 failures** (7 new: default-accept, draft consumption,
  only-ticked-moves-applied, none-selected-skips-the-server, offline apply keeps the screen open) +
  `:app:assembleDebug` green.
- **Test gotcha:** `whenever(...).thenThrow(IOException(...))` fails on a suspend repository method
  — Mockito rejects a checked exception the signature doesn't declare, and Kotlin declares none.
  Use `thenAnswer { throw ... }`.
