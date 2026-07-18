"""Canonical sanity bounds, enforced via Pydantic Field constraints on the write schemas
(the Spotter/Plate convention: one module, no magic numbers inline)."""

# Recipes
MAX_RECIPE_STEPS = 50
MAX_RECIPE_INGREDIENTS = 100
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
