# Cookbook

A recipe tracker that doubles as a grocery shopping checklist. Third app in the family
alongside [Spotter](https://github.com/CDRaab01/Spotter) (fitness) and
[Plate](https://github.com/CDRaab01/Plate) (nutrition) — same stack, same conventions, same
[PULSE](https://github.com/CDRaab01/Pulse) design system (consumed as a shared library,
amber-led here).

**What it does**

- **Recipe book** — recipes with ordered steps and free-text ingredients (quantity, unit,
  store-aisle category), searchable, fully editable.
- **Shopping list** — tap *Add to shopping list* on any recipe (optionally scaled) and the
  ingredients autofill; duplicates merge (same normalized name + unit ⇒ quantities sum). Check
  items off in the store, grouped by aisle; checked items sweep to the bottom; *Clear checked*
  when done. **Works offline** — mutations queue in Room and sync on reconnect.
- **Discover** — search real recipes (Spoonacular) and import one straight into the book,
  aisle categories included. Import from a photo (recipe card / cookbook page) or a web URL too.
- **Meal planner** — plan recipes onto a weekly calendar; *Add planned meals to list* funnels a
  whole week of dinners through the same merge, each entry scalable for leftovers.
- **Pantry** — snap a photo of the fridge/pantry, confirm what's seen, and *What can I make?*
  suggests recipes you can cover from the pantry + staples.
- **Family mode** — share a **household** (Settings → Family, invite by email). Family recipes
  are visible and editable to the whole household (private recipes stay yours); shopping lists
  and meal plans are shared with household co-members.
- **Plate integration** — one-tap migration of Plate's saved recipes; per-recipe macro
  estimates (Plate resolves the free-text ingredients against its food database); "Log to
  Plate diary" sends the eaten share to a meal.

**Stack**: Android (Kotlin/Compose, MVVM, Hilt, Room, Retrofit) · FastAPI + SQLAlchemy async +
Alembic · Postgres · Docker Compose · self-hosted runner CD. See `CLAUDE.md` for the build
spec and `deploy/README.md` for operations.

**Local dev**

```bash
# server (needs the compose db: docker compose up -d db)
cd server && cp .env.example .env  # set SECRET_KEY
./run.sh                            # or: .venv\Scripts\uvicorn app.main:app --reload

# android (needs the sibling Pulse checkout: <parent>/{Cookbook,Pulse})
cd android && ./gradlew :app:assembleDebug
```

Cross-app auth uses one ecosystem-wide `CROSS_APP_SECRET` (Spotter ↔ Plate ↔ Cookbook);
tokens are short-lived JWTs carrying the user's email, validated by each app independently.
