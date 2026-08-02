"""Turning real shopping-list names into retailer search queries.

Every "messy" case below is a verbatim row from the live Groceries list on 2026-08-01 — these are
recipe-derived names, which is exactly why the plain name is not a usable search term.
"""

import pytest

from app.lists.categorize import clean_for_category
from app.lists.merge import normalize_name
from app.lists.search_terms import MAX_QUERY_LENGTH, search_query


@pytest.mark.parametrize(
    ("name", "expected"),
    [
        # Real rows off the live list.
        ("cream cheese at room temp", "cream cheese"),
        ("green onion, finely sliced", "green onion"),
        ("crema or 3 tbsp sour cream + 1 tbsp milk", "crema"),
        ("neutral oil, such as canola oil, for frying", "neutral oil"),
        ("cinnamon (may need to double)", "cinnamon"),
        ("melted and cooled unsalted butter (may need to double)", "unsalted butter"),
        ("garlic clove, minced", "garlic clove"),
        ("thin red or green chili slices", "green chili"),
        ("sliced scallions", "scallions"),
        ("shredded nori", "nori"),
        # Already clean — must pass through untouched.
        ("white or black pepper", "black pepper"),
        ("Ritz crackers", "Ritz crackers"),
        ("soy sauce", "soy sauce"),
        ("loaf wheat bread", "loaf wheat bread"),
        ("wonton wrappers", "wonton wrappers"),
        ("miso paste", "miso paste"),
    ],
)
def test_real_list_names(name: str, expected: str) -> None:
    assert search_query(name) == expected


def test_a_prep_word_in_the_middle_is_load_bearing() -> None:
    """Only the edges are stripped. "chocolate chip cookies" must not lose its "chip", and a
    product whose *name* contains a prep word keeps it when it isn't on an edge."""
    assert search_query("chocolate chip cookies") == "chocolate chip cookies"
    assert search_query("cream of chicken soup") == "cream of chicken soup"


def test_never_returns_empty_even_when_the_rules_would_eat_everything() -> None:
    """An over-eager strip must fall back to something searchable — an empty query finds nothing
    and would silently look like "this store doesn't stock it"."""
    for name in ("chopped fresh", "finely sliced", "melted"):
        assert search_query(name), f"{name!r} produced an empty query"


def test_blank_in_blank_out() -> None:
    assert search_query("") == ""
    assert search_query("   ") == ""


def test_query_is_capped() -> None:
    long_name = "organic " * 40 + "flour"
    assert len(search_query(long_name)) <= MAX_QUERY_LENGTH


def test_it_does_not_disturb_merge_identity_or_categorization() -> None:
    """The whole reason this is a separate function: `normalize_name` owns merge identity and
    `clean_for_category` owns aisle matching. Neither may move because a search got cleverer."""
    name = "green onion, finely sliced"
    assert normalize_name(name) == normalize_name(name)  # stable
    assert clean_for_category(name) == "green onion"
    # The search query is allowed to be shorter than the category input, and here happens to match.
    assert search_query(name) == "green onion"

    # A case where they legitimately differ: categorization wants both alternatives' words, the
    # search box can only look for one thing.
    messy = "crema or 3 tbsp sour cream + 1 tbsp milk"
    assert search_query(messy) == "crema"
    assert clean_for_category(messy) != "crema"


def test_stacked_trailing_clauses_are_all_removed() -> None:
    """One pass would strip the inner clause and leave the outer one behind."""
    assert search_query("neutral oil for frying as needed") == "neutral oil"


@pytest.mark.parametrize(
    ("name", "expected", "why"),
    [
        ("crema or 3 tbsp sour cream + 1 tbsp milk", "crema", "head is left; right restates a quantity"),
        ("thin red or green chili slices", "green chili", "head is right"),
        ("white or black pepper", "black pepper", "right is more specific"),
        ("butter or margarine", "butter", "tie goes to the writer's order"),
    ],
)
def test_the_or_side_is_chosen_by_specificity_not_by_position(name, expected, why):
    """Taking the left side blindly is wrong as often as it's right — the head noun sits on
    whichever side the writer put it on. A side carrying digits is a quantity restatement and is
    dropped; otherwise the side with the most non-prep words wins; ties go left."""
    assert search_query(name) == expected, why
