"""Recovering a recipe's own ingredient headings on import (v0.9).

The safety property under test throughout: when the recovery isn't convincing, these functions
return "no sections" rather than a guess, so the import degrades to exactly what it was before
sections existed. A wrong heading misfiles ingredients; a heading mistaken for an ingredient
deletes one.
"""

import pytest

from app.recipes_ext.ingredient_groups import (
    align_sections,
    clean_section_label,
    extract_ingredient_groups,
    is_section_heading,
    split_inline_sections,
)

# ── Heading detection ────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("line", "expected"),
    [
        # Headings: no amount anywhere, and written the way headings are written.
        ("Steak Marinade:", True),
        ("Fajitas:", True),
        ("For the marinade", True),
        ("For serving", True),
        ("FOR THE TOPPING", True),
        ("Sauce:", True),
        # Ingredients: an amount anywhere disqualifies it outright.
        ("2 cups flour", False),
        ("½ teaspoon black pepper", False),
        ("1/3 cup lime juice, freshly squeezed", False),
        ("1 1/2 - 2 pounds skirt or flank steak", False),
        # Ingredients without amounts still aren't headings — no colon, no "for the", not caps.
        ("Salt to taste", False),
        ("skirt or flank steak", False),
        ("Kosher salt and freshly ground black pepper", False),
        # Shouted short ingredient names are common on recipe cards; the guesser recognizes
        # them as food, so they stay ingredients.
        ("SALT", False),
        ("OLIVE OIL", False),
        # ...but a shouted heading that isn't a recognizable food is still a heading.
        ("TOPPING", True),
        # Too long to be a heading.
        ("A heading so long that nobody would ever actually write it this way as a label:", False),
        ("", False),
    ],
)
def test_is_section_heading(line, expected):
    assert is_section_heading(line) is expected


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("Steak Marinade:", "Steak Marinade"),
        ("  For the   sauce  ", "Sauce"),
        ("For a quick slaw", "Quick slaw"),
        ("FOR THE TOPPING", "Topping"),
        ("<span>Fajitas</span>:", "Fajitas"),
        ("Salt &amp; pepper mix:", "Salt & pepper mix"),
        # The page's own furniture, not a section of the recipe.
        ("Ingredients", None),
        ("INGREDIENTS:", None),
        ("   ", None),
    ],
)
def test_clean_section_label(raw, expected):
    assert clean_section_label(raw) == expected


# ── Inline headings inside recipeIngredient ──────────────────────────────────


def test_split_inline_sections_consumes_headings():
    lines = [
        "Steak Marinade:",
        "1/3 cup lime juice",
        "2 teaspoons ground cumin",
        "Fajitas:",
        "1 1/2 pounds skirt steak",
        "2 medium white onions",
    ]
    assert split_inline_sections(lines) == [
        ("Steak Marinade", "1/3 cup lime juice"),
        ("Steak Marinade", "2 teaspoons ground cumin"),
        ("Fajitas", "1 1/2 pounds skirt steak"),
        ("Fajitas", "2 medium white onions"),
    ]


def test_split_inline_sections_handles_a_small_recipe():
    """Two groups of one is a real recipe shape, not a misfire."""
    lines = ["For the marinade:", "1/3 cup lime juice", "Fajitas:", "2 onions"]
    assert split_inline_sections(lines) == [
        ("Marinade", "1/3 cup lime juice"),
        ("Fajitas", "2 onions"),
    ]


def test_split_inline_sections_leaves_an_ungrouped_recipe_alone():
    lines = ["2 cups flour", "1 egg", "Salt to taste"]
    assert split_inline_sections(lines) == [(None, line) for line in lines]


def test_split_inline_sections_bails_when_everything_looks_like_a_heading():
    """More headings than ingredients means the heuristic misfired on this page's phrasing —
    keep every line as an ingredient rather than deleting most of the recipe."""
    lines = ["Salt:", "Pepper:", "Sugar:", "Flour:"]
    assert split_inline_sections(lines) == [(None, line) for line in lines]


# ── HTML scrape ──────────────────────────────────────────────────────────────

WPRM = """
<html><body><p>Blog prose about summer grilling.</p>
<div class="wprm-recipe-ingredients-container">
  <div class="wprm-recipe-ingredient-group">
    <h4 class="wprm-recipe-group-name">Steak Marinade:</h4>
    <ul class="wprm-recipe-ingredients">
      <li class="wprm-recipe-ingredient"><span class="wprm-recipe-ingredient-amount">1/3</span>
        <span class="wprm-recipe-ingredient-unit">cup</span>
        <span class="wprm-recipe-ingredient-name">lime juice</span>
        <span class="wprm-recipe-ingredient-notes">, freshly squeezed</span></li>
      <li class="wprm-recipe-ingredient">2 teaspoons ground cumin</li>
    </ul>
  </div>
  <div class="wprm-recipe-ingredient-group">
    <h4 class="wprm-recipe-group-name">Fajitas:</h4>
    <ul class="wprm-recipe-ingredients">
      <li class="wprm-recipe-ingredient">1 1/2 &ndash; 2 pounds skirt or flank steak</li>
      <li class="wprm-recipe-ingredient">2 medium white onions<span
        class="wprm-recipe-ingredient-notes">, sliced</span></li>
    </ul>
  </div>
</div>
<div class="wprm-recipe-instructions-container"><ul><li>Combine the marinade.</li></ul></div>
<div id="comments"><ul><li>Great recipe, I used chicken!</li></ul></div>
</body></html>
"""

TASTY = """<div class="tasty-recipes-ingredients"><div class="tasty-recipes-ingredients-body">
<h4>For the sauce</h4><ul><li>1 cup mayo</li><li>2 tbsp sriracha</li></ul>
<h4>For the bowls</h4><ul><li>2 cups rice</li><li>1 lb salmon</li></ul>
</div></div><div class="tasty-recipes-instructions"><ul><li>Mix.</li></ul></div>"""

MEDIAVINE = """<div class="mv-create-ingredients"><h4>Cake</h4><ul><li>2 cups flour</li>
<li>1 cup sugar</li></ul><h4>Frosting</h4><ul><li>1 stick butter</li></ul></div>
<div class="mv-create-instructions"><ul><li>Bake.</li></ul></div>"""


def test_extract_groups_from_wp_recipe_maker():
    assert extract_ingredient_groups(WPRM) == [
        ("Steak Marinade", "1/3 cup lime juice , freshly squeezed"),
        ("Steak Marinade", "2 teaspoons ground cumin"),
        ("Fajitas", "1 1/2 – 2 pounds skirt or flank steak"),
        ("Fajitas", "2 medium white onions , sliced"),
    ]


def test_extract_groups_from_tasty_recipes():
    assert extract_ingredient_groups(TASTY) == [
        ("Sauce", "1 cup mayo"),
        ("Sauce", "2 tbsp sriracha"),
        ("Bowls", "2 cups rice"),
        ("Bowls", "1 lb salmon"),
    ]


def test_extract_groups_from_mediavine_create():
    assert extract_ingredient_groups(MEDIAVINE) == [
        ("Cake", "2 cups flour"),
        ("Cake", "1 cup sugar"),
        ("Frosting", "1 stick butter"),
    ]


def test_extract_stops_before_instructions_and_comments():
    """The region bound is what keeps a commenter's list item out of the ingredients."""
    scraped = extract_ingredient_groups(WPRM)
    assert not any("chicken" in text for _, text in scraped)
    assert not any("Combine" in text for _, text in scraped)


def test_extract_returns_nothing_without_recognizable_markup():
    assert extract_ingredient_groups("<html><body><p>Just prose.</p></body></html>") == []


def test_extract_bails_on_an_implausible_number_of_items():
    html = '<div class="recipe-ingredients"><ul>' + "<li>x</li>" * 400 + "</ul></div>"
    assert extract_ingredient_groups(html) == []


# ── Alignment onto the JSON-LD lines ─────────────────────────────────────────

JSONLD_LINES = [
    "1/3 cup lime juice, freshly squeezed",
    "2 teaspoons ground cumin",
    "1 1/2 - 2 pounds skirt or flank steak",
    "2 medium white onions, sliced",
]


def test_align_matches_across_punctuation_and_entity_differences():
    """The JSON-LD string and the rendered <li> come from the same generator but differ in
    whitespace, entities ("&ndash;" vs "-") and note markup."""
    assert align_sections(JSONLD_LINES, extract_ingredient_groups(WPRM)) == [
        "Steak Marinade",
        "Steak Marinade",
        "Fajitas",
        "Fajitas",
    ]


def test_align_interpolates_a_line_the_scrape_missed():
    """A line the page rendered differently, sandwiched inside one section, belongs to it.

    (Enough surrounding lines match to clear the confidence gate — on a list this short a
    single miss would rightly sink the whole alignment.)
    """
    scraped = [
        ("Sauce", "1 cup mayo"),
        ("Sauce", "2 tbsp sriracha"),
        ("Sauce", "1 tsp sesame oil"),
        ("Sauce", "1 tbsp rice vinegar"),
        ("Sauce", "2 cloves garlic"),
    ]
    lines = [
        "1 cup mayo",
        "2 tbsp sriracha",
        "something the page wrote another way entirely",
        "1 tbsp rice vinegar",
        "2 cloves garlic",
    ]
    assert align_sections(lines, scraped) == ["Sauce"] * 5


def test_align_declines_when_the_text_does_not_match():
    scraped = extract_ingredient_groups(WPRM)
    lines = ["300 g of something else", "2 completely unrelated items"]
    assert align_sections(lines, scraped) == [None, None]


def test_align_declines_when_sections_are_not_contiguous():
    """A, B, A means the alignment drifted — a recipe doesn't return to an earlier group."""
    scraped = [("A", "1 cup a"), ("B", "1 cup b"), ("A", "1 cup c")]
    assert align_sections(["1 cup a", "1 cup b", "1 cup c"], scraped) == [None, None, None]


def test_align_declines_when_there_is_nothing_to_align_against():
    assert align_sections(JSONLD_LINES, []) == [None] * 4
    assert align_sections([], extract_ingredient_groups(WPRM)) == []


def test_align_declines_when_the_page_has_no_groups():
    """An ungrouped page scrapes fine but assigns nothing — that must read as "no sections",
    not as one giant unnamed section."""
    html = '<div class="recipe-ingredients"><ul><li>2 cups flour</li><li>1 egg</li></ul></div>'
    assert align_sections(["2 cups flour", "1 egg"], extract_ingredient_groups(html)) == [
        None,
        None,
    ]
