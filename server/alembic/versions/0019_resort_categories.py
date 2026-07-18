"""One-time re-sort of existing categories into the widened aisle set (v0.7).

Re-guesses each ``shopping_list_item`` and ``item_history`` row's store category with the new
word-boundary guesser — but ONLY when the currently stored category is exactly what the OLD
(pre-v0.7) substring guesser would have produced for that name. That means the value was
auto-assigned, not hand-picked: a manual categorization (stored != old auto-guess) is left
untouched. So items that fell into "Other" because the old 7-bucket set had no home for them
(household, baby, beverages, snacks…) get sorted, and stale auto-guesses get corrected
("iced coffee" pantry → beverages, "potato chips" produce → snacks), while anything you set by
hand stays put.

Data-only. Downgrade is a no-op (the prior guesses aren't recoverable). On a fresh DB there are
no rows, so this is a no-op there.

Revision ID: 0019
Revises: 0018
Create Date: 2026-07-18
"""

import sqlalchemy as sa

from alembic import op
from app.lists.categorize import guess_category
from app.lists.merge import normalize_name

revision = "0019"
down_revision = "0018"
branch_labels = None
depends_on = None

# Frozen snapshot of the pre-v0.7 substring guesser (app/lists/categorize.py before the rewrite).
# Kept verbatim so we can tell an old auto-guess from a manual choice; do NOT "improve" it.
_OLD_KEYWORDS: dict[str, str] = {
    "onion": "produce",
    "garlic": "produce",
    "tomato": "produce",
    "potato": "produce",
    "carrot": "produce",
    "celery": "produce",
    "pepper": "produce",
    "lettuce": "produce",
    "spinach": "produce",
    "kale": "produce",
    "broccoli": "produce",
    "cauliflower": "produce",
    "cucumber": "produce",
    "zucchini": "produce",
    "squash": "produce",
    "mushroom": "produce",
    "avocado": "produce",
    "lemon": "produce",
    "lime": "produce",
    "orange": "produce",
    "apple": "produce",
    "banana": "produce",
    "berry": "produce",
    "grape": "produce",
    "cilantro": "produce",
    "parsley": "produce",
    "basil": "produce",
    "thyme": "produce",
    "rosemary": "produce",
    "ginger": "produce",
    "scallion": "produce",
    "shallot": "produce",
    "cabbage": "produce",
    "corn": "produce",
    "green bean": "produce",
    "asparagus": "produce",
    "chicken": "meat",
    "beef": "meat",
    "pork": "meat",
    "turkey": "meat",
    "lamb": "meat",
    "bacon": "meat",
    "sausage": "meat",
    "ham": "meat",
    "steak": "meat",
    "ground": "meat",
    "salmon": "meat",
    "shrimp": "meat",
    "tuna": "meat",
    "fish": "meat",
    "cod": "meat",
    "tilapia": "meat",
    "crab": "meat",
    "brisket": "meat",
    "rib": "meat",
    "meatball": "meat",
    "milk": "dairy",
    "cheese": "dairy",
    "cheddar": "dairy",
    "mozzarella": "dairy",
    "parmesan": "dairy",
    "butter": "dairy",
    "yogurt": "dairy",
    "cream": "dairy",
    "egg": "dairy",
    "sour cream": "dairy",
    "half and half": "dairy",
    "feta": "dairy",
    "ricotta": "dairy",
    "bread": "bakery",
    "bun": "bakery",
    "roll": "bakery",
    "tortilla": "bakery",
    "bagel": "bakery",
    "pita": "bakery",
    "croissant": "bakery",
    "baguette": "bakery",
    "frozen": "frozen",
    "ice cream": "frozen",
    "flour": "pantry",
    "sugar": "pantry",
    "salt": "pantry",
    "oil": "pantry",
    "vinegar": "pantry",
    "rice": "pantry",
    "pasta": "pantry",
    "noodle": "pantry",
    "bean": "pantry",
    "lentil": "pantry",
    "broth": "pantry",
    "stock": "pantry",
    "sauce": "pantry",
    "paste": "pantry",
    "canned": "pantry",
    "can of": "pantry",
    "cereal": "pantry",
    "oat": "pantry",
    "honey": "pantry",
    "syrup": "pantry",
    "spice": "pantry",
    "cumin": "pantry",
    "paprika": "pantry",
    "oregano": "pantry",
    "cinnamon": "pantry",
    "vanilla": "pantry",
    "baking powder": "pantry",
    "baking soda": "pantry",
    "yeast": "pantry",
    "chocolate": "pantry",
    "peanut butter": "pantry",
    "mayo": "pantry",
    "mustard": "pantry",
    "ketchup": "pantry",
    "soy sauce": "pantry",
    "coffee": "pantry",
    "tea": "pantry",
    "nut": "pantry",
    "almond": "pantry",
    "walnut": "pantry",
    "quinoa": "pantry",
    "coconut": "pantry",
    "raisin": "pantry",
}
_OLD_ORDERED = sorted(_OLD_KEYWORDS.items(), key=lambda kv: len(kv[0]), reverse=True)


def _old_guess(normalized_name: str) -> str | None:
    """The pre-v0.7 naive-substring guess for an already-normalized name."""
    for keyword, category in _OLD_ORDERED:
        if keyword in normalized_name:
            return category
    return None


def resort_category(name: str, category: str | None) -> str | None:
    """The category to set for a re-sort, or None to leave the row unchanged.

    Only rewrites a value that matches the old auto-guess (so manual picks survive), and only
    when the new guesser produces a different, non-null aisle.
    """
    if category != _old_guess(normalize_name(name or "")):
        return None  # looks manual — leave it
    new = guess_category(name or "")
    if new is not None and new != category:
        return new
    return None


def upgrade() -> None:
    bind = op.get_bind()
    for table in ("shopping_list_items", "item_history"):
        rows = bind.execute(sa.text(f"SELECT id, name, category FROM {table}")).fetchall()
        for row_id, name, category in rows:
            new = resort_category(name, category)
            if new is not None:
                bind.execute(
                    sa.text(f"UPDATE {table} SET category = :c WHERE id = :i"),
                    {"c": new, "i": row_id},
                )


def downgrade() -> None:
    pass  # prior guesses aren't recoverable; the re-sort is intentionally one-way
