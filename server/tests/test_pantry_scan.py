"""Pantry photo scan: the vision draft parser + the endpoint's transport handling.
Mirrors test_photo_import.py — the pipelines share the LM Studio client."""

import json

import httpx
import pytest

from app.services.ai.pantry_scan_prompts import MAX_ITEMS, parse_scan
from app.services.ai.vision import estimate_pantry_photo


def _chat_response(content: str) -> httpx.Response:
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


class TestParseScan:
    def test_clean_json(self):
        draft = parse_scan(
            json.dumps(
                {
                    "items": [
                        {"name": "Pasta", "category": "pantry", "confidence": "high"},
                        {"name": "Cheddar cheese", "category": "dairy", "confidence": "low"},
                    ]
                }
            )
        )
        assert draft is not None
        assert [i.name for i in draft.items] == ["Pasta", "Cheddar cheese"]
        assert draft.items[0].category == "pantry"
        assert draft.items[1].confidence == "low"

    def test_strips_code_fences(self):
        draft = parse_scan('```json\n{"items": [{"name": "Milk"}]}\n```')
        assert draft is not None
        assert draft.items[0].name == "Milk"

    def test_salvages_widest_object_from_prose(self):
        draft = parse_scan('I can see:\n{"items": [{"name": "Eggs"}]}\nThat is all!')
        assert draft is not None
        assert draft.items[0].name == "Eggs"

    def test_dedupes_by_normalized_name(self):
        draft = parse_scan(
            json.dumps({"items": [{"name": "Eggs"}, {"name": "egg"}, {"name": "EGGS "}]})
        )
        assert draft is not None
        assert len(draft.items) == 1

    def test_invalid_category_falls_back_to_guesser(self):
        draft = parse_scan(
            json.dumps({"items": [{"name": "cheddar cheese", "category": "refrigerated"}]})
        )
        assert draft is not None
        assert draft.items[0].category == "dairy"  # guess_category, not the model's invention

    def test_unknown_confidence_coerced_to_low(self):
        draft = parse_scan(json.dumps({"items": [{"name": "Jar", "confidence": "maybe"}]}))
        assert draft is not None
        assert draft.items[0].confidence == "low"

    def test_caps_items_and_drops_nameless(self):
        many = [{"name": f"Item {i}"} for i in range(60)]
        draft = parse_scan(json.dumps({"items": [{"category": "pantry"}] + many}))
        assert draft is not None
        assert len(draft.items) == MAX_ITEMS
        assert all(i.name for i in draft.items)

    def test_empty_object_is_unreadable(self):
        assert parse_scan("{}") is None

    def test_empty_items_is_unreadable(self):
        assert parse_scan('{"items": []}') is None

    def test_garbage_is_unreadable(self):
        assert parse_scan("There is no food in this photo.") is None


class TestEstimatePantryPhoto:
    async def test_happy_path(self):
        handler_calls = []

        def handler(request: httpx.Request) -> httpx.Response:
            handler_calls.append(request)
            return _chat_response(json.dumps({"items": [{"name": "Pasta"}]}))

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        result = await estimate_pantry_photo(b"fake-bytes", "image/jpeg", client=client)

        assert result.items[0].name == "Pasta"
        assert result.low_confidence is False
        assert len(handler_calls) == 1
        sent = json.loads(handler_calls[0].content)
        image_part = sent["messages"][1]["content"][1]
        assert image_part["image_url"]["url"].startswith("data:image/jpeg;base64,")

    async def test_no_food_degrades_not_errors(self):
        client = httpx.AsyncClient(transport=httpx.MockTransport(lambda r: _chat_response("{}")))
        result = await estimate_pantry_photo(b"x", "image/png", client=client)
        assert result.low_confidence is True
        assert result.items == []
        assert result.note

    async def test_unreachable_lm_studio_is_503(self):
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("refused", request=request)

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        with pytest.raises(Exception) as exc_info:
            await estimate_pantry_photo(b"x", "image/png", client=client)
        assert exc_info.value.status_code == 503

    async def test_timeout_is_504(self):
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.TimeoutException("slow", request=request)

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        with pytest.raises(Exception) as exc_info:
            await estimate_pantry_photo(b"x", "image/png", client=client)
        assert exc_info.value.status_code == 504

    async def test_model_error_is_502(self):
        client = httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(500)))
        with pytest.raises(Exception) as exc_info:
            await estimate_pantry_photo(b"x", "image/png", client=client)
        assert exc_info.value.status_code == 502


async def test_endpoint_rejects_non_image(auth_client):
    resp = await auth_client.post(
        "/pantry/scan",
        files={"photo": ("fridge.txt", b"not an image", "text/plain")},
    )
    assert resp.status_code == 422


async def test_endpoint_rejects_oversized_photo(auth_client, monkeypatch):
    from app.config import settings

    monkeypatch.setattr(settings, "photo_max_bytes", 10)
    resp = await auth_client.post(
        "/pantry/scan",
        files={"photo": ("fridge.jpg", b"x" * 100, "image/jpeg")},
    )
    assert resp.status_code == 413
