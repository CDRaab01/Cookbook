"""Table tests for the keyword category guesser (v0.2)."""

import pytest

from app.lists.categorize import guess_category


@pytest.mark.parametrize(
    ("name", "expected"),
    [
        # ── food aisles ──
        ("Chicken breast", "meat"),
        ("Ground beef", "meat"),
        ("ground cinnamon", "pantry"),  # longest keyword wins over "ground"
        ("Salmon fillets", "meat"),
        ("Whole milk", "dairy"),
        ("Sour cream", "dairy"),
        ("Eggs", "dairy"),
        ("Cream cheese", "dairy"),
        ("Yellow onions", "produce"),
        ("Cherry tomatoes", "produce"),
        ("Baby spinach", "produce"),
        ("Sourdough bread", "bakery"),
        ("Flour tortillas", "bakery"),
        ("Frozen peas", "frozen"),
        ("Ice cream", "frozen"),
        ("All-purpose flour", "pantry"),
        ("Olive oil", "pantry"),
        ("Soy sauce", "pantry"),
        ("Baking powder", "pantry"),
        # ── new aisles (v0.7) ──
        ("Sliced salami", "deli"),
        ("Pepperoni", "deli"),
        ("Potato chips", "snacks"),
        ("Oreo cookies", "snacks"),
        ("Tortilla chips", "snacks"),
        ("Peanuts", "snacks"),
        ("Orange juice", "beverages"),
        ("Iced coffee", "beverages"),  # was miscategorized as pantry before
        ("Sparkling water", "beverages"),
        ("Diet coke", "beverages"),
        ("Paper towels", "household"),  # used to fall to Other
        ("Trash bags", "household"),
        ("Dish soap", "household"),
        ("Aluminum foil", "household"),
        ("Shampoo", "personal"),
        ("Toothpaste", "personal"),
        ("Ibuprofen", "personal"),
        ("Diapers", "baby"),
        ("Baby wipes", "baby"),
        ("Similac formula", "baby"),
        # ── disambiguation: a specific phrase beats a generic word it contains ──
        ("Milk collector", "baby"),  # NOT dairy — the whole point of the round
        ("Breast pump", "baby"),
        ("black pepper", "pantry"),  # NOT produce ("pepper")
        ("chocolate chips", "pantry"),  # baking, NOT snacks ("chocolate")
        ("almond milk", "dairy"),  # NOT snacks ("almond")
        ("coconut milk", "pantry"),  # canned, NOT dairy ("milk")
        ("peanut butter", "pantry"),  # NOT snacks ("peanut")
        ("dish soap", "household"),  # NOT personal ("soap")
        # ── word-boundary safety: a food word inside a larger word must not fire ──
        ("Eggplant", "produce"),  # NOT dairy via "egg"
        ("Orange juice", "beverages"),  # "juice" not swallowed by "ice" → frozen
        ("Chipotle peppers", "produce"),  # "chip" must not fire; "pepper" → produce
        ("Buttermilk", "dairy"),  # matches "milk" as a word? no — but butter→dairy anyway
        # ── genuine misses stay None ──
        ("Light bulb", "household"),
        ("Mystery widget", None),
        ("Phone charger", None),
    ],
)
def test_guess_category(name, expected):
    assert guess_category(name) == expected
