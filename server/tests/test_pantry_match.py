"""Pantry → recipe matching math (pure, table-driven like test_merge.py)."""

import pytest

from app.lists.pantry_match import ingredient_available, match_recipes, match_tokens


class TestMatchTokens:
    @pytest.mark.parametrize(
        ("name", "expected"),
        [
            ("Eggs", {"egg"}),
            ("boneless skinless chicken breasts", {"chicken", "breast"}),
            ("2 cloves garlic, minced", {"clove", "garlic"}),
            ("fresh basil, chopped", {"basil"}),
            ("salt to taste", {"salt"}),
            ("cheddar cheese", {"cheddar", "cheese"}),
        ],
    )
    def test_tokens(self, name, expected):
        assert match_tokens(name) == frozenset(expected)

    def test_descriptor_only_name_is_empty(self):
        assert match_tokens("fresh, chopped") == frozenset()


class TestIngredientAvailable:
    def _available(self, *names):
        return [match_tokens(n) for n in names]

    def test_exact_match(self):
        assert ingredient_available("eggs", self._available("egg"))

    def test_plural_folding(self):
        assert ingredient_available("tomatoes", self._available("tomato"))

    def test_pantry_subset_of_ingredient(self):
        # Pantry "chicken" covers the recipe's fancier phrasing.
        assert ingredient_available("boneless skinless chicken breasts", self._available("chicken"))

    def test_ingredient_subset_of_pantry(self):
        # Pantry "cheddar cheese" covers a recipe's plain "cheese".
        assert ingredient_available("cheese", self._available("cheddar cheese"))

    def test_descriptors_stripped_before_comparison(self):
        assert ingredient_available("2 large eggs, beaten", self._available("eggs"))

    def test_water_always_available(self):
        assert ingredient_available("water", [])
        assert ingredient_available("warm water", [])

    def test_no_substring_false_positive(self):
        # Token sets, not substrings: "ham" must not match "graham crackers".
        assert not ingredient_available("graham crackers", self._available("ham"))

    def test_known_looseness_milk_covers_coconut_milk(self):
        # Documented v1 trade-off: subset matching is deliberately loose.
        assert ingredient_available("coconut milk", self._available("milk"))

    def test_unrelated_does_not_match(self):
        assert not ingredient_available("salmon fillet", self._available("pasta", "cheese"))


def _recipe(rid, name, ingredients, image=None):
    return (rid, name, image, ingredients)


class TestMatchRecipes:
    def test_full_coverage_and_missing_list(self):
        recipes = [
            _recipe(1, "Carbonara", ["spaghetti", "eggs", "parmesan cheese", "bacon"]),
            _recipe(2, "Beef stew", ["beef", "carrots", "potatoes", "onion"]),
        ]
        matches = match_recipes(
            recipes, ["spaghetti", "eggs", "parmesan", "bacon"], ["salt"], max_missing=2
        )
        assert [m.name for m in matches] == ["Carbonara"]
        assert matches[0].matched == 4
        assert matches[0].total == 4
        assert matches[0].missing == []

    def test_max_missing_threshold(self):
        recipes = [_recipe(1, "Stir fry", ["chicken", "soy sauce", "broccoli", "ginger"])]
        # Chicken on hand, soy sauce a staple: broccoli + ginger missing.
        assert match_recipes(recipes, ["chicken"], ["soy sauce"], max_missing=2)
        assert not match_recipes(recipes, ["chicken"], ["soy sauce"], max_missing=1)

    def test_staples_cover_but_do_not_qualify_alone(self):
        # Everything covered by staples, nothing by the pantry ⇒ excluded: the pantry
        # says nothing about this recipe.
        recipes = [_recipe(1, "Flatbread", ["flour", "salt", "olive oil"])]
        assert match_recipes(recipes, ["chicken"], ["flour", "salt", "olive oil"]) == []
        # One real pantry hit qualifies it.
        matches = match_recipes(recipes, ["flour"], ["salt", "olive oil"])
        assert [m.name for m in matches] == ["Flatbread"]

    def test_water_not_counted_against_recipe(self):
        recipes = [_recipe(1, "Rice", ["rice", "water", "salt"])]
        matches = match_recipes(recipes, ["rice"], ["salt"])
        assert matches and matches[0].total == 2  # water isn't a purchasable ingredient

    def test_sort_order_missing_then_coverage(self):
        recipes = [
            _recipe(1, "Two missing", ["pasta", "cream", "basil"]),
            _recipe(2, "Complete", ["pasta", "butter"]),
            _recipe(3, "One missing", ["pasta", "cheese", "cream"]),
        ]
        matches = match_recipes(recipes, ["pasta", "cheese"], ["butter"], max_missing=2)
        assert [m.name for m in matches] == ["Complete", "One missing", "Two missing"]

    def test_recipe_with_no_purchasable_ingredients_skipped(self):
        recipes = [_recipe(1, "Water diet", ["water", "ice"])]
        assert match_recipes(recipes, ["pasta"], []) == []
