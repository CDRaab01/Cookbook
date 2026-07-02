"""Table-driven tests for the pure merge module — the shopping list's load-bearing math."""

import pytest

from app.lists.merge import (
    IncomingItem,
    combine_quantities,
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
    ("a", "b", "same"),
    [
        (("Eggs", None), ("egg", None), True),
        (("Tomatoes", "lb"), ("tomato", "LB"), True),
        (("chicken", "lb"), ("chicken", "breast"), False),  # unit mismatch
        (("chicken", "lb"), ("chicken", None), False),  # missing unit is a different key
        (("basil", None), ("parsley", None), False),
    ],
)
def test_merge_key(a, b, same):
    assert (merge_key(*a) == merge_key(*b)) is same


@pytest.mark.parametrize(
    ("a", "b", "expected"),
    [
        (2.0, 1.0, 3.0),
        (0.5, 0.25, 0.75),
        # "a number plus 'some' is still 'some'"
        (None, 2.0, None),
        (2.0, None, None),
        (None, None, None),
        # float noise is rounded away
        (0.1, 0.2, 0.3),
    ],
)
def test_combine_quantities(a, b, expected):
    assert combine_quantities(a, b) == expected


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


def test_merge_incoming_collapses_batch_duplicates():
    incoming = [
        IncomingItem(name="Garlic", quantity=2, unit="clove"),
        IncomingItem(name="Onion", quantity=1, unit=None),
        IncomingItem(name="garlic", quantity=3, unit="clove", category="produce"),
    ]
    merged = merge_incoming(incoming)
    assert len(merged) == 2
    garlic = merged[0]
    assert garlic.name == "Garlic"  # first spelling wins
    assert garlic.quantity == 5
    assert garlic.category == "produce"  # first non-null metadata survives the merge
    assert merged[1].name == "Onion"


def test_merge_incoming_keeps_unit_mismatch_separate():
    incoming = [
        IncomingItem(name="chicken", quantity=2, unit="lb"),
        IncomingItem(name="chicken", quantity=1, unit="breast"),
    ]
    assert len(merge_incoming(incoming)) == 2


def test_merge_incoming_preserves_order():
    incoming = [IncomingItem(name=n) for n in ["c", "a", "b"]]
    assert [i.name for i in merge_incoming(incoming)] == ["c", "a", "b"]
