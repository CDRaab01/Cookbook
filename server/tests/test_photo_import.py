"""Recipe photo import (v0.3): the vision draft parser + the endpoint's transport handling."""

import json

import httpx
import pytest

from app.services.ai.recipe_photo_prompts import parse_draft
from app.services.ai.vision import estimate_recipe_photo


def _chat_response(content: str) -> httpx.Response:
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


class TestParseDraft:
    def test_clean_json(self):
        draft = parse_draft(
            json.dumps(
                {
                    "name": "Chili",
                    "servings": 4,
                    "prep_minutes": 10,
                    "cook_minutes": 30,
                    "ingredients": [
                        {"name": "Ground beef", "quantity": 1, "unit": "lb"},
                        {"name": "Onion", "quantity": None, "unit": None},
                    ],
                    "steps": ["Brown the beef.", "Simmer 30 minutes."],
                }
            )
        )
        assert draft is not None
        assert draft.name == "Chili"
        assert draft.servings == 4
        assert draft.ingredients[0].name == "Ground beef"
        assert draft.ingredients[0].quantity == 1.0
        assert len(draft.steps) == 2

    def test_strips_code_fences(self):
        draft = parse_draft('```json\n{"name": "Tacos", "steps": ["Fill.", "Fold."]}\n```')
        assert draft is not None
        assert draft.name == "Tacos"

    def test_salvages_widest_object_from_prose(self):
        draft = parse_draft(
            'Sure, here you go:\n{"name": "Soup", "steps": ["Boil."]}\nHope that helps!'
        )
        assert draft is not None
        assert draft.name == "Soup"

    def test_coerces_stringy_numbers_and_canonicalizes_units(self):
        draft = parse_draft(
            json.dumps(
                {
                    "name": "Pancakes",
                    "servings": "4",
                    "ingredients": [{"name": "Flour", "quantity": "2", "unit": "cups"}],
                    "steps": [],
                }
            )
        )
        assert draft is not None
        assert draft.servings == 4
        assert draft.ingredients[0].quantity == 2.0
        assert draft.ingredients[0].unit == "cup"

    def test_empty_object_is_unreadable(self):
        assert parse_draft("{}") is None

    def test_garbage_is_unreadable(self):
        assert parse_draft("I can't read this photo, sorry!") is None

    def test_drops_nameless_ingredients_and_caps_lists(self):
        many = [{"name": f"Item {i}", "quantity": 1} for i in range(80)]
        draft = parse_draft(
            json.dumps(
                {
                    "name": "Kitchen sink",
                    "ingredients": [{"quantity": 1, "unit": "cup"}] + many,
                    "steps": [f"Step {i}" for i in range(60)],
                }
            )
        )
        assert draft is not None
        assert len(draft.ingredients) == 60  # MAX_INGREDIENTS, nameless entry doesn't count
        assert all(i.name for i in draft.ingredients)
        assert len(draft.steps) == 40  # MAX_STEPS


class TestEstimateRecipePhoto:
    async def test_happy_path(self):
        handler_calls = []

        def handler(request: httpx.Request) -> httpx.Response:
            handler_calls.append(request)
            return _chat_response(
                json.dumps({"name": "Chili", "steps": ["Cook it."], "ingredients": []})
            )

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        result = await estimate_recipe_photo(b"fake-bytes", "image/jpeg", client=client)

        assert result.name == "Chili"
        assert result.low_confidence is False
        assert len(handler_calls) == 1
        sent = json.loads(handler_calls[0].content)
        # The image travels as a base64 data URL inside the multimodal content array.
        image_part = sent["messages"][1]["content"][1]
        assert image_part["image_url"]["url"].startswith("data:image/jpeg;base64,")

    async def test_unreadable_photo_degrades_not_errors(self):
        client = httpx.AsyncClient(transport=httpx.MockTransport(lambda r: _chat_response("{}")))
        result = await estimate_recipe_photo(b"x", "image/png", client=client)
        assert result.low_confidence is True
        assert result.note

    async def test_unreachable_lm_studio_is_503(self):
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("refused", request=request)

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        with pytest.raises(Exception) as exc_info:
            await estimate_recipe_photo(b"x", "image/png", client=client)
        assert exc_info.value.status_code == 503

    async def test_timeout_is_504(self):
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.TimeoutException("slow", request=request)

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        with pytest.raises(Exception) as exc_info:
            await estimate_recipe_photo(b"x", "image/png", client=client)
        assert exc_info.value.status_code == 504

    async def test_model_error_is_502(self):
        client = httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(500)))
        with pytest.raises(Exception) as exc_info:
            await estimate_recipe_photo(b"x", "image/png", client=client)
        assert exc_info.value.status_code == 502


async def test_endpoint_rejects_non_image(auth_client):
    resp = await auth_client.post(
        "/recipes/import-photo",
        files={"photo": ("recipe.txt", b"not an image", "text/plain")},
    )
    assert resp.status_code == 422


async def test_endpoint_rejects_oversized_photo(auth_client, monkeypatch):
    from app.config import settings

    monkeypatch.setattr(settings, "photo_max_bytes", 10)
    resp = await auth_client.post(
        "/recipes/import-photo",
        files={"photo": ("recipe.jpg", b"x" * 100, "image/jpeg")},
    )
    assert resp.status_code == 413
