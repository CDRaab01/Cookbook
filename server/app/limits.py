"""Canonical sanity bounds, enforced via Pydantic Field constraints on the write schemas
(the Spotter/Plate convention: one module, no magic numbers inline)."""

# Recipes
MAX_RECIPE_STEPS = 50
MAX_RECIPE_INGREDIENTS = 100
# An ingredient's section heading ("Steak Marinade") — a label, not prose. Matches the column.
MAX_SECTION_LENGTH = 80
SERVINGS_BOUNDS = (1, 100)
MINUTES_BOUNDS = (0, 24 * 60)

# Quantities (shared by ingredients and shopping-list items). Free-unit quantities, so the cap
# just guards against nonsense (1e12 cups of flour).
QUANTITY_BOUNDS = (0.0, 100_000.0)

# Shopping lists
MAX_LIST_ITEMS = 500
# The stored item name column is String(255); the raw add-bar text may legitimately be longer
# because it can carry a pasted product URL that the service strips into link_url.
MAX_ITEM_NAME_LENGTH = 255
MAX_ITEM_RAW_INPUT_LENGTH = 2000
MAX_LINK_URL_LENGTH = 2048

# "Add recipe to list" servings multiplier.
SCALE_BOUNDS = (0.1, 20.0)

# Stores (v0.11). A household shops a handful of places; the aisle cap is generous enough for a
# real supercenter walked aisle-by-aisle without letting a bad AI layout suggestion run away.
MAX_STORES = 20
# Raised from 60 in v0.12: a hand-built layout is a dozen aisles, but an *imported* one is the
# store's real floor plan (a supercenter runs to two zones of ~30 runs each) on top of the 13
# seeded category aisles that remain as the fallback. 60 would have truncated a real Meijer.
MAX_STORE_AISLES = 150
MAX_STORE_NAME_LENGTH = 120
# Matches the store_aisles.name column — an aisle label ("Aisle 12 — Baking"), not prose.
MAX_AISLE_NAME_LENGTH = 80
# The retailer's own store id ("138"), matching the stores.retailer_store_id column.
MAX_RETAILER_STORE_ID_LENGTH = 16
# One import batch. Sized well above a weekly list so a harvest is never split, but bounded so a
# runaway client can't drive an unbounded transaction.
MAX_PLACEMENT_IMPORT_ROWS = 300
