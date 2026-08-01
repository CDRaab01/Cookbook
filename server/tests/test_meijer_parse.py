"""Table tests for reading Meijer's location strings.

Fixtures are trimmed from real renders of store 138 (Maysville Rd) on 2026-08-01, and keep the
surrounding noise — stock line, variant chips — because that noise is exactly what a careless
regex trips over.
"""

import pytest

from app.retailers.meijer import (
    Location,
    aisle_display_name,
    clean_card_text,
    normalize_aisle_label,
    parse_location,
    walk_sort_key,
)

PEANUT_BUTTER = (
    "$1.89 $2.29\nSave $0.40\n4.4\n(37)\nWrite a review\nIn stock at Maysville Rd\n"
    "Check nearby stores\nAisle B | 16\nSection 39\nType\nCreamy\n$1.89\nCrunchy\n$1.89"
)
BANANAS = "In stock at Maysville Rd\nCheck nearby stores\nAisle A | 11\nSection 10\nOption\n$0.25\nOrganic"
PAPER_TOWELS = (
    "In stock at Maysville Rd\nCheck nearby stores\nAisle B | 14\nSection 31\nSize\n"
    "Double Roll\n$7.49\nTriple"
)
MILK = "In stock at Maysville Rd\nCheck nearby stores\nAisle B | 17\nSection 35\nFat Content\n1%"


@pytest.mark.parametrize(
    ("text", "aisle", "section"),
    [
        (PEANUT_BUTTER, "B | 16", "39"),
        (BANANAS, "A | 11", "10"),
        (PAPER_TOWELS, "B | 14", "31"),
        (MILK, "B | 17", "35"),
        # Spacing around the pipe is a rendering detail, not a guarantee.
        ("Aisle C|7\nSection 2", "C | 7", "2"),
        ("Aisle  d  |  22 \nSection  8", "D | 22", "8"),
    ],
)
def test_parses_real_pages(text: str, aisle: str, section: str) -> None:
    assert parse_location(text) == Location(aisle=aisle, section=section)


def test_pending_widget_is_retryable_not_empty() -> None:
    """The loading placeholder must never be cached as "this item has no aisle"."""
    assert parse_location("In stock at Maysville Rd\nFinding Aisle Sections") is None


def test_pending_text_alongside_a_real_answer_still_parses() -> None:
    """The placeholder lingers in the DOM for other variants after one resolves, so its presence
    must not veto an answer that is actually on the page."""
    assert parse_location("Aisle B | 16\nSection 39\nFinding Aisle Sections") == Location(
        aisle="B | 16", section="39"
    )


def test_service_counter_has_no_aisle_and_that_is_final() -> None:
    """A fully-rendered page with no location is a real, permanent answer — an empty Location, not
    None. Conflating the two would retry a deli counter forever."""
    location = parse_location("In stock at Maysville Rd\nCheck nearby stores\nAdd to Cart")
    assert location is not None and location.is_empty


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("Aisle B | 16", "B | 16"),
        ("B|16", "B | 16"),
        ("b | 16", "B | 16"),
        ("  B  |  016  ", "B | 16"),  # leading zeros must not fork the aisle
        ("Produce", None),
        ("", None),
    ],
)
def test_label_normalization_collapses_spelling_variants(raw: str, expected: str | None) -> None:
    """Every spelling of one physical aisle must land on the same StoreAisle row, or a store grows
    three aisles for one location and the walk order becomes nonsense."""
    assert normalize_aisle_label(raw) == expected


def test_aisle_display_name() -> None:
    assert aisle_display_name("B | 16") == "Aisle B | 16"


def test_walk_order_sorts_by_zone_then_number() -> None:
    names = ["Aisle B | 16", "Aisle A | 11", "Aisle B | 7", "Aisle A | 2"]
    assert sorted(names, key=walk_sort_key) == [
        "Aisle A | 2",
        "Aisle A | 11",  # numeric, not lexical: 11 must follow 2
        "Aisle B | 7",
        "Aisle B | 16",
    ]


def test_seeded_category_aisles_sort_after_every_real_aisle() -> None:
    """The 13 default aisles are the fallback for items nobody has looked up. They belong in a
    block at the end, which visibly shrinks as coverage grows."""
    names = ["Produce", "Aisle B | 16", "Dairy & Eggs", "Aisle A | 1"]
    assert sorted(names, key=walk_sort_key) == [
        "Aisle A | 1",
        "Aisle B | 16",
        # Python's sort is stable, so these keep input order rather than being alphabetized.
        "Produce",
        "Dairy & Eggs",
    ]


def test_every_unparseable_name_shares_one_key_so_callers_must_tiebreak_on_order() -> None:
    """Regression: an earlier version tiebroke on the *name* here, which alphabetized the seeded
    block (Baby, Bakery, Beverages, …) and destroyed the canonical produce→meat→dairy walk order
    the store was seeded with. Sorting a walk order by name is never right, so the key deliberately
    carries no name and the caller breaks ties on the existing ``order``."""
    assert walk_sort_key("Produce") == walk_sort_key("Bakery")


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (
            "Meijer Whole Milk, GallonOriginal price $2.26(322)4.3 out of 5",
            "Meijer Whole Milk, Gallon",
        ),
        ("Jif Creamy Peanut Butter, 28-Ounce Jar$3.99", "Jif Creamy Peanut Butter, 28-Ounce Jar"),
        ("Bananas", "Bananas"),
    ],
)
def test_card_text_keeps_only_the_product_name(raw: str, expected: str) -> None:
    assert clean_card_text(raw) == expected
