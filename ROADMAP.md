# ROADMAP.md — Cookbook (departing-engineer assessment, 2026-07-03)

**Update 2026-07-03: the AI round shipped as v0.4.0 pantry scan** (photo → confirm → pantry +
staples → "what can I make" suggestions — see CLAUDE.md v0.4.0). It followed the ground rules
below, which remain the contract for any future AI work here.

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

## Non-AI work (valuable regardless of the AI round)

1. **Household sharing** — the deferred item that matters most. Two adults, one grocery list is
   the actual usage pattern; single-account is the last "personal-app" limitation in a
   two-person household. Smallest honest version: share a *list* (not accounts) — a second user
   joins a list by invite; merge logic already handles concurrent adds. Suite SSO (accounts
   linked by email at dragonfly-id) is the identity foundation for it.
2. **Custom/reorderable aisles** — deferred from v0.3; category recall (`item_history`) solved
   "where does milk go", this solves "what order do I walk the store". Per-list category order,
   drag-to-reorder (Hawksnest's Customize has the drag-and-drop precedent).
3. ~~Pantry model (thin)~~ — **shipped in v0.4.0** as pantry items + confirmed staples (still
   deliberately NOT quantity inventory — keep it that way). Follow-on worth doing:
   recently-bought inference (checked-off shopping history → "probably in the pantry" hints).
4. **Record the Roborazzi baselines** — the CI job has existed since v1 with no baselines;
   either record them or delete the job (a permanently-skipped check erodes trust in CI).
5. **Camera-captured recipe photos** (vs web URLs) — deferred from v0.3; pairs naturally with
   photo import since the capture UX is the same.

## Explicitly not worth it

- Price tracking / store APIs — rejected in v1, still right: fragile scrapers, low value.
- Full pantry inventory with quantities — see above; thin staples model or nothing.
- Public recipe sharing/community — this is a household tool.
