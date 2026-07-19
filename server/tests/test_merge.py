"""Table-driven tests for the pure merge module — the shopping list's load-bearing math.

v0.2.1 semantics: identity = normalized name; measures aggregate (same canonical unit sums,
different units sit side by side); non-purchasables are dropped; units are canonicalized.
"""

import pytest

from app.lists.merge import (
    IncomingItem,
    Measure,
    add_measure,
    buyable_measures,
    canonical_unit,
    is_buyable_measure,
    is_purchasable,
    merge_incoming,
    merge_key,
    normalize_name,
    scale_quantity,
)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        # casefold + whitespace
        ("Chicken Breast", "chicken breast"),
        ("  chicken   breast  ", "chicken breast"),
        ("GARLIC", "garlic"),
        # plural folding
        ("eggs", "egg"),
        ("tomatoes", "tomato"),
        ("potatoes", "potato"),
        ("berries", "berry"),
        ("onions", "onion"),
        ("carrots", "carrot"),
        # protected pseudo-plurals
        ("hummus", "hummus"),
        ("molasses", "molasses"),
        ("swiss", "swiss"),
        ("couscous", "couscous"),
        # too short to fold
        ("gas", "gas"),
        ("peas", "pea"),
        # already singular
        ("milk", "milk"),
        ("flour", "flour"),
    ],
)
def test_normalize_name(raw: str, expected: str):
    assert normalize_name(raw) == expected


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("cups", "cup"),
        ("Cup", "cup"),
        ("teaspoons", "tsp"),
        ("Tablespoon", "tbsp"),
        ("tbs", "tbsp"),
        ("POUNDS", "lb"),
        ("ounce", "oz"),
        ("servings", "serving"),
        ("cans", "can"),
        ("tbsp.", "tbsp"),
        (None, None),
        ("", None),
        ("weird-unit", "weird-unit"),  # unknown units pass through, self-consistent
    ],
)
def test_canonical_unit(raw, expected):
    assert canonical_unit(raw) == expected


@pytest.mark.parametrize(
    ("a", "b", "same"),
    [
        ("Eggs", "egg", True),
        ("Tomatoes", "tomato", True),
        # v0.2.1: the unit is NOT part of the identity — you buy one "oil".
        ("oil", "Oil", True),
        ("basil", "parsley", False),
    ],
)
def test_merge_key(a, b, same):
    assert (merge_key(a) == merge_key(b)) is same


@pytest.mark.parametrize(
    ("name", "purchasable"),
    [
        ("water", False),
        ("Warm water", False),
        ("cold water", False),
        ("ice", False),
        ("Watermelon", True),  # suffix rule needs the word boundary
        ("chicken", True),
        # Debatable ones documented as-is: "<x> water" is treated as tap-water phrasing. If a
        # bottled "coconut water" habit shows up, it belongs on the list via manual add rename.
        ("coconut water", False),
    ],
)
def test_is_purchasable(name, purchasable):
    assert is_purchasable(name) is purchasable


def test_add_measure_sums_same_unit_and_keeps_mixed_units():
    measures = add_measure([], 2.0, "tablespoons")
    measures = add_measure(measures, 2.0, "tsp")
    measures = add_measure(measures, 1.0, "tbsp")
    assert measures == [Measure(3.0, "tbsp"), Measure(2.0, "tsp")]


def test_add_measure_ignores_unquantified():
    measures = add_measure([], 2.0, "tbsp")
    # Bare "oil" adds nothing — presence already means "buy some"; it must NOT erase 2 tbsp.
    measures = add_measure(measures, None, None)
    assert measures == [Measure(2.0, "tbsp")]


def test_add_measure_bare_counts():
    measures = add_measure([], 3.0, None)
    measures = add_measure(measures, 2.0, None)
    assert measures == [Measure(5.0, None)]


@pytest.mark.parametrize(
    ("unit", "buyable"),
    [
        (None, True),  # bare count "3 eggs"
        ("tsp", False),
        ("tbsp", False),
        ("cup", False),
        ("pinch", False),
        ("dash", False),
        ("teaspoons", False),  # canonicalized first
        ("Cups", False),
        ("lb", True),
        ("oz", True),
        ("g", True),
        ("ml", True),
        ("l", True),
        ("can", True),
        ("bag", True),
        ("bottle", True),
        ("box", True),
        ("bunch", True),
        ("head", True),
        ("package", True),
    ],
)
def test_is_buyable_measure(unit, buyable):
    assert is_buyable_measure(unit) is buyable


def test_buyable_measures_drops_cooking_keeps_store_units_and_counts():
    # A recipe's "8 oz + 2 tbsp" cream cheese keeps the oz you shop by, drops the tbsp.
    kept = buyable_measures([Measure(8.0, "oz"), Measure(2.0, "tbsp")])
    assert kept == [Measure(8.0, "oz")]
    # All-cooking ("2 tbsp + 2 tsp" oil) collapses to nothing — buy a bottle.
    assert buyable_measures([Measure(2.0, "tbsp"), Measure(2.0, "tsp")]) == []
    # Bare counts and store units are untouched.
    assert buyable_measures([Measure(3.0, None), Measure(2.0, "can")]) == [
        Measure(3.0, None),
        Measure(2.0, "can"),
    ]


@pytest.mark.parametrize(
    ("quantity", "scale", "expected"),
    [
        (2.0, 1.0, 2.0),
        (2.0, 0.5, 1.0),
        (3.0, 1.5, 4.5),
        (None, 2.0, None),
        (1.0, 0.333, 0.333),
    ],
)
def test_scale_quantity(quantity, scale, expected):
    assert scale_quantity(quantity, scale) == expected


def test_merge_incoming_collapses_across_units():
    incoming = [
        IncomingItem(name="Oil", quantity=2, unit="tablespoons"),
        IncomingItem(name="Onion", quantity=1, unit=None),
        IncomingItem(name="oil", quantity=2, unit="tsp", category="pantry"),
    ]
    merged = merge_incoming(incoming)
    assert len(merged) == 2
    oil = merged[0]
    assert oil.name == "Oil"  # first spelling wins
    assert oil.measures == [Measure(2.0, "tbsp"), Measure(2.0, "tsp")]
    assert oil.category == "pantry"  # first non-null metadata survives the merge
    assert merged[1].name == "Onion"


def test_merge_incoming_drops_water():
    incoming = [
        IncomingItem(name="Warm water", quantity=1.5, unit="cups"),
        IncomingItem(name="Flour", quantity=4, unit="cups"),
    ]
    merged = merge_incoming(incoming)
    assert [i.name for i in merged] == ["Flour"]


def test_merge_incoming_preserves_order():
    incoming = [IncomingItem(name=n) for n in ["c", "a", "b"]]
    assert [i.name for i in merge_incoming(incoming)] == ["c", "a", "b"]


def test_merge_incoming_first_link_wins():
    incoming = [
        IncomingItem(name="Milk", link_url="https://one.example.com/milk"),
        IncomingItem(name="milk", link_url="https://two.example.com/milk"),
        IncomingItem(name="Eggs"),
    ]
    merged = merge_incoming(incoming)
    assert [i.link_url for i in merged] == ["https://one.example.com/milk", None]
