"""Prompt + forgiving JSON parser for the pantry-scan vision draft.

Same defensive posture as :mod:`app.services.ai.recipe_photo_prompts`: constrain the model
to strict JSON, then salvage aggressively — vision models wrap output in prose or fences
often enough that a naive ``json.loads`` would reject usable responses.
"""

import json
import re

from app.lists.categorize import guess_category
from app.lists.merge import normalize_name
from app.models.recipe import STORE_CATEGORIES
from app.schemas.pantry import PantryScanDraftOut, PantryScanItem

MAX_ITEMS = 40

NO_FOOD_NOTE = "Couldn't spot any food in that photo — try a closer, well-lit shot of the shelves."

VISION_SYSTEM_PROMPT = (
    "You are a kitchen-inventory assistant for a cooking app. You look at a photo of a "
    "fridge, pantry, or kitchen counter and list the distinct FOOD items you can identify. "
    "You only output JSON — never prose, never Markdown, never an explanation."
)

VISION_USER_PROMPT = (
    "List the food items visible in this photo. Respond with ONLY a JSON object, no prose "
    "and no code fences, shaped exactly like this:\n"
    '{"items": [{"name": string, '
    '"category": "produce"|"meat"|"dairy"|"bakery"|"frozen"|"pantry"|"other" or null, '
    '"confidence": "high" or "low"}]}\n'
    'Rules: use short generic names ("cheddar cheese", "pasta"), never brand names; one '
    "entry per distinct item, no duplicates; do not guess at items hidden behind others — "
    'if something is only partly visible and you are unsure, mark it "low"; ignore '
    "everything that is not food (containers, appliances, dishes). If you cannot identify "
    "any food in the photo at all, respond with exactly {} and nothing else."
)


def build_scan_messages(image_data_url: str) -> list[dict]:
    return [
        {"role": "system", "content": VISION_SYSTEM_PROMPT},
        {
            "role": "user",
            "content": [
                {"type": "text", "text": VISION_USER_PROMPT},
                {"type": "image_url", "image_url": {"url": image_data_url}},
            ],
        },
    ]


_FENCE_RE = re.compile(r"^```(?:json)?\s*|\s*```$", re.IGNORECASE | re.MULTILINE)


def _strip_fences(text: str) -> str:
    return _FENCE_RE.sub("", text).strip()


def _widest_object_span(text: str) -> str | None:
    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end < start:
        return None
    return text[start : end + 1]


def parse_scan(raw_text: str) -> PantryScanDraftOut | None:
    """Best-effort parse of the model's response. Returns None (never raises) when nothing
    usable can be salvaged — the caller turns that into a low-confidence empty draft."""
    stripped = _strip_fences(raw_text)
    candidates = [stripped, _widest_object_span(stripped)]
    data = None
    for candidate in candidates:
        if not candidate:
            continue
        try:
            data = json.loads(candidate)
        except (json.JSONDecodeError, TypeError):
            continue
        if isinstance(data, dict):
            break
        data = None
    if not isinstance(data, dict) or not data:
        return None

    items: list[PantryScanItem] = []
    seen: set[str] = set()  # vision models repeat items; dedupe on the normalized name
    for raw in data.get("items") or []:
        if len(items) >= MAX_ITEMS:
            break
        if not isinstance(raw, dict):
            continue
        name = str(raw.get("name") or "").strip()[:255]
        if not name:
            continue
        key = normalize_name(name)
        if key in seen:
            continue
        seen.add(key)
        category = str(raw.get("category") or "").strip().lower() or None
        if category not in STORE_CATEGORIES:
            category = guess_category(name)
        confidence = "high" if raw.get("confidence") == "high" else "low"
        items.append(PantryScanItem(name=name, category=category, confidence=confidence))

    if not items:
        return None
    return PantryScanDraftOut(items=items)
