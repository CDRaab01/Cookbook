"""Pantry → recipe matching — pure functions, table-tested like merge.py/categorize.py.

Free-text on both sides ("boneless chicken breasts" vs a pantry "chicken") rules out exact
equality, and raw substring matching gives "ham" ⊂ "graham cracker" false positives. The
middle ground: compare **token sets** (normalized per word, descriptor words dropped) and
call an ingredient available when a pantry/staple token set is a subset of the ingredient's
*or vice versa* — pantry "chicken" covers "boneless chicken breast", and pantry "cheddar
cheese" covers a recipe's plain "cheese". Deliberately loose ("milk" covers "coconut milk");
the suggestions UI always shows the missing list, so a wrong match costs one glance.
"""

from dataclasses import dataclass

from app.lists.merge import is_purchasable, normalize_name

# Words that describe an ingredient without identifying it. Dropped before comparison so
# "2 large eggs, beaten" and a pantry "eggs" meet on {"egg"}.
_DESCRIPTORS = frozenset(
    {
        "fresh",
        "freshly",
        "chopped",
        "diced",
        "minced",
        "sliced",
        "grated",
        "shredded",
        "crushed",
        "melted",
        "softened",
        "beaten",
        "cooked",
        "uncooked",
        "raw",
        "dried",
        "dry",
        "canned",
        "frozen",
        "thawed",
        "large",
        "small",
        "medium",
        "big",
        "boneless",
        "skinless",
        "lean",
        "whole",
        "halved",
        "ripe",
        "peeled",
        "seeded",
        "trimmed",
        "divided",
        "packed",
        "optional",
        "plus",
        "more",
        "extra",
        "about",
        "roughly",
        "finely",
        "coarsely",
        "thinly",
        "lightly",
        "of",
        "or",
        "and",
        "to",
        "for",
        "taste",
        "serving",
        "garnish",
        "needed",
        "temperature",
        "room",
    }
)


def match_tokens(name: str) -> frozenset[str]:
    """The identity token set of a free-text food name: normalized per word (so plurals
    fold), descriptors and one-letter fragments dropped."""
    words = normalize_name(name).replace(",", " ").replace("(", " ").replace(")", " ").split()
    return frozenset(
        normalize_name(w) for w in words if len(w) > 1 and normalize_name(w) not in _DESCRIPTORS
    )


def ingredient_available(ingredient_name: str, available: list[frozenset[str]]) -> bool:
    """Whether one recipe ingredient is covered by any available (pantry or staple) token
    set. Non-purchasables (water, ice) are always available — nobody stocks water."""
    if not is_purchasable(ingredient_name):
        return True
    ing = match_tokens(ingredient_name)
    if not ing:
        return True  # nothing but descriptors ("to taste") — don't count it against a recipe
    return any(a and (a <= ing or ing <= a) for a in available)


@dataclass
class RecipeMatch:
    recipe_id: object
    name: str
    image_url: str | None
    total: int
    matched: int
    missing: list[str]


def match_recipes(
    recipes: list[tuple],
    pantry_names: list[str],
    staple_names: list[str],
    *,
    max_missing: int = 2,
) -> list[RecipeMatch]:
    """Score each recipe against the pantry. ``recipes`` are plain
    ``(id, name, image_url, [ingredient names])`` tuples — no ORM at this altitude.

    A recipe qualifies when at most ``max_missing`` purchasable ingredients are uncovered
    AND at least one ingredient was hit by an actual **pantry** item — staples alone must
    not suggest everything ("flour + salt + oil" covering a bread recipe says nothing about
    what's in the fridge). Sorted by fewest missing, then coverage, then name.
    """
    pantry_sets = [t for t in (match_tokens(n) for n in pantry_names) if t]
    staple_sets = [t for t in (match_tokens(n) for n in staple_names) if t]
    out: list[RecipeMatch] = []

    for recipe_id, name, image_url, ingredient_names in recipes:
        purchasable = [i for i in ingredient_names if is_purchasable(i) and match_tokens(i)]
        if not purchasable:
            continue
        missing: list[str] = []
        pantry_hit = False
        for ingredient in purchasable:
            if ingredient_available(ingredient, pantry_sets):
                pantry_hit = True
            elif not ingredient_available(ingredient, staple_sets):
                missing.append(ingredient)
        if len(missing) > max_missing or not pantry_hit:
            continue
        total = len(purchasable)
        out.append(
            RecipeMatch(
                recipe_id=recipe_id,
                name=name,
                image_url=image_url,
                total=total,
                matched=total - len(missing),
                missing=missing,
            )
        )

    out.sort(key=lambda m: (len(m.missing), -(m.matched / m.total), m.name.casefold()))
    return out
