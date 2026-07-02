"""Table tests for the keyword category guesser (v0.2)."""

import pytest

from app.lists.categorize import guess_category


@pytest.mark.parametrize(
    ("name", "expected"),
    [
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
        ("Paper towels", None),
        ("Batteries", None),
    ],
)
def test_guess_category(name, expected):
    assert guess_category(name) == expected
