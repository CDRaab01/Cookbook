""" "Suggest a layout" — the AI starting point for a new store profile.

Setting up a store by hand means naming and ordering a dozen-plus aisles before it's worth
anything. The model can produce a rough walk order to drag around. Two properties are load-bearing:
the draft must always be *usable* (every category routable, nothing unbounded), and asking for one
must never be able to stop you adding a store.
"""

import json

import httpx
import pytest
from fastapi import HTTPException

from app.limits import MAX_STORE_AISLES
from app.models.recipe import STORE_CATEGORIES
from app.services.ai.store_layout_prompts import LEFTOVER_AISLE_NAME, parse_layout


def _layout(*pairs) -> str:
    return json.dumps({"aisles": [{"name": n, "categories": c} for n, c in pairs]})


# --- the parser: pure, stdlib-only ---


class TestParseLayout:
    def test_clean_json_keeps_order(self):
        aisles = parse_layout(
            _layout(
                ("Produce", ["produce"]),
                ("Aisle 5 — Baking", ["pantry", "bakery"]),
            )
        )
        assert aisles is not None
        assert [a.name for a in aisles[:2]] == ["Produce", "Aisle 5 — Baking"]
        assert aisles[1].categories == ["pantry", "bakery"]

    def test_forgotten_categories_are_swept_into_a_trailing_aisle(self):
        """Nothing may be left unroutable: a category with no aisle would silently land in the
        client's "Unsorted" bucket and read as a bug in the layout the user just saved."""
        aisles = parse_layout(_layout(("Produce", ["produce"])))
        assert aisles is not None
        assert aisles[-1].name == LEFTOVER_AISLE_NAME
        covered = [c for a in aisles for c in a.categories]
        assert sorted(covered) == sorted(STORE_CATEGORIES)
        assert len(covered) == len(set(covered))  # each category exactly once

    def test_a_complete_layout_gets_no_leftovers_aisle(self):
        aisles = parse_layout(_layout(("Everything", list(STORE_CATEGORIES))))
        assert aisles is not None and len(aisles) == 1

    def test_duplicate_categories_go_to_the_first_aisle_that_claims_them(self):
        # A later duplicate would never be reached by the routing anyway.
        aisles = parse_layout(_layout(("Front", ["dairy"]), ("Back", ["dairy", "frozen"])))
        assert aisles is not None
        assert aisles[0].categories == ["dairy"]
        assert aisles[1].categories == ["frozen"]

    def test_invented_categories_are_dropped(self):
        aisles = parse_layout(_layout(("Tech", ["electronics", "produce"])))
        assert aisles is not None
        assert aisles[0].categories == ["produce"]

    def test_unnamed_aisles_are_dropped(self):
        aisles = parse_layout(_layout(("", ["produce"]), ("Real", ["dairy"])))
        assert aisles is not None
        assert aisles[0].name == "Real"

    def test_long_names_are_clamped_to_the_column(self):
        aisles = parse_layout(_layout(("A" * 500, ["produce"])))
        assert aisles is not None
        assert len(aisles[0].name) <= 80

    def test_a_runaway_layout_is_capped(self):
        aisles = parse_layout(_layout(*[(f"Aisle {i}", []) for i in range(200)]))
        assert aisles is not None
        assert len(aisles) <= MAX_STORE_AISLES

    def test_strips_fences_and_prose(self):
        raw = "Here you go:\n```json\n" + _layout(("Produce", ["produce"])) + "\n```"
        aisles = parse_layout(raw)
        assert aisles is not None and aisles[0].name == "Produce"

    @pytest.mark.parametrize(
        "raw", ["", "I don't know that store", "{}", '{"aisles": "lots"}', '{"aisles": []}']
    )
    def test_unreadable_is_none(self, raw):
        assert parse_layout(raw) is None


# --- the endpoint ---


async def test_suggest_layout_returns_a_draft_and_saves_nothing(auth_client, monkeypatch):
    async def fake_chat(messages, **kwargs):
        return _layout(("Produce", ["produce"]), ("Dairy & Eggs", ["dairy"]))

    monkeypatch.setattr("app.services.store_service.chat_text", fake_chat)
    r = await auth_client.post("/stores/suggest-layout", json={"chain": "Meijer"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["low_confidence"] is False
    assert [a["name"] for a in body["aisles"]][:2] == ["Produce", "Dairy & Eggs"]
    # A draft is a draft — no store was created.
    assert (await auth_client.get("/stores")).json() == []


async def test_the_chain_name_reaches_the_prompt(auth_client, monkeypatch):
    seen = {}

    async def fake_chat(messages, **kwargs):
        seen["prompt"] = messages[-1]["content"]
        return _layout(("Produce", ["produce"]))

    monkeypatch.setattr("app.services.store_service.chat_text", fake_chat)
    await auth_client.post("/stores/suggest-layout", json={"chain": "Trader Joe's"})
    assert "Trader Joe's" in seen["prompt"]


@pytest.mark.parametrize("status_code", [503, 504, 502])
async def test_a_dead_model_still_gives_you_a_usable_layout(auth_client, monkeypatch, status_code):
    """Adding a store must not depend on AI any more than the list does — degrade to the standard
    walk order (flagged), never to an error the user has to work around."""

    async def boom(messages, **kwargs):
        raise HTTPException(status_code=status_code, detail="nope")

    monkeypatch.setattr("app.services.store_service.chat_text", boom)
    r = await auth_client.post("/stores/suggest-layout", json={"chain": "Meijer"})
    assert r.status_code == 200
    body = r.json()
    assert body["low_confidence"] is True
    assert [a["categories"][0] for a in body["aisles"]] == list(STORE_CATEGORIES)
    assert body["note"]


async def test_an_unreadable_reply_falls_back_the_same_way(auth_client, monkeypatch):
    async def fake_chat(messages, **kwargs):
        return "I'm not familiar with that store."

    monkeypatch.setattr("app.services.store_service.chat_text", fake_chat)
    body = (await auth_client.post("/stores/suggest-layout", json={"chain": "Zorbnax"})).json()
    assert body["low_confidence"] is True
    assert len(body["aisles"]) == len(STORE_CATEGORIES)


async def test_the_draft_round_trips_into_a_real_store(auth_client, monkeypatch):
    """The whole point of the shape: what the draft returns is what POST /stores accepts."""

    async def fake_chat(messages, **kwargs):
        return _layout(("Produce", ["produce"]), ("Aisle 5", ["pantry"]))

    monkeypatch.setattr("app.services.store_service.chat_text", fake_chat)
    draft = (await auth_client.post("/stores/suggest-layout", json={"chain": "Meijer"})).json()

    r = await auth_client.post(
        "/stores", json={"name": "Meijer", "label": "Maysville Rd", "aisles": draft["aisles"]}
    )
    assert r.status_code == 201, r.text
    saved = r.json()
    assert [a["name"] for a in saved["aisles"]] == [a["name"] for a in draft["aisles"]]
    covered = [c for a in saved["aisles"] for c in a["categories"]]
    assert sorted(covered) == sorted(STORE_CATEGORIES)


async def test_empty_chain_is_422(auth_client):
    r = await auth_client.post("/stores/suggest-layout", json={"chain": "   "})
    assert r.status_code == 422


async def test_suggest_layout_requires_auth(client):
    client.headers.pop("Authorization", None)
    r = await client.post("/stores/suggest-layout", json={"chain": "Meijer"})
    assert r.status_code == 401


async def test_suggest_layout_is_not_parsed_as_a_store_id(auth_client, monkeypatch):
    """The fixed path is declared before /{store_id}; a regression would 422 on the UUID parse."""

    async def fake_chat(messages, **kwargs):
        return _layout(("Produce", ["produce"]))

    monkeypatch.setattr("app.services.store_service.chat_text", fake_chat)
    assert (
        await auth_client.post("/stores/suggest-layout", json={"chain": "Meijer"})
    ).status_code == 200
