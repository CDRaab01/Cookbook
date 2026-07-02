"""Prompt + forgiving JSON parser for the recipe-photo vision draft (v0.3).

Mirrors Plate's app/services/ai/photo_prompts.py: constrain the model to strict JSON, then
parse defensively — vision models wrap output in prose or code fences often enough that a
naive json.loads() would fail on a large fraction of otherwise-usable responses.
"""

import json
import re

from app.lists.merge import canonical_unit
from app.schemas.photo import RecipePhotoDraft

MAX_INGREDIENTS = 60
MAX_STEPS = 40

NO_RECIPE_NOTE = (
    "Couldn't make out a recipe in that photo — try a clearer, well-lit shot of the card or page."
)

VISION_SYSTEM_PROMPT = (
    "You are a recipe-transcription assistant for a cooking app. You look at a photo of a "
    "recipe (a handwritten card, a cookbook page, a screenshot) and transcribe it into "
    "structured data. You only output JSON — never prose, never Markdown, never an "
    "explanation."
)

VISION_USER_PROMPT = (
    "Transcribe the recipe in this photo. Respond with ONLY a JSON object, no prose and no "
    "code fences, shaped exactly like this:\n"
    '{"name": string, "servings": number or null, "prep_minutes": number or null, '
    '"cook_minutes": number or null, '
    '"ingredients": [{"name": string, "quantity": number or null, "unit": string or null}], '
    '"steps": [string, ...]}\n'
    'Keep each ingredient\'s name just the food (put prep notes like "diced" in the name if '
    "that's how the card wrote it). Keep each step as one instruction. If you cannot read a "
    "recipe in the photo at all, respond with exactly {} and nothing else."
)


def build_vision_messages(image_data_url: str) -> list[dict]:
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


def _coerce_number(v) -> float | None:
    if v is None:
        return None
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, str):
        try:
            return float(v.strip())
        except ValueError:
            return None
    return None


def _coerce_int(v) -> int | None:
    n = _coerce_number(v)
    return int(n) if n is not None else None


def parse_draft(raw_text: str) -> RecipePhotoDraft | None:
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

    ingredients = []
    for raw in data.get("ingredients") or []:
        if len(ingredients) >= MAX_INGREDIENTS:
            break
        if not isinstance(raw, dict):
            continue
        name = str(raw.get("name") or "").strip()
        if not name:
            continue
        unit_raw = raw.get("unit")
        unit = canonical_unit(str(unit_raw).strip()) if unit_raw else None
        ingredients.append(
            {
                "name": name,
                "quantity": _coerce_number(raw.get("quantity")),
                "unit": unit,
            }
        )

    steps = [str(s).strip() for s in (data.get("steps") or [])[:MAX_STEPS] if str(s).strip()]

    name = str(data.get("name") or "").strip() or "Untitled recipe"
    if not ingredients and not steps and name == "Untitled recipe":
        return None

    return RecipePhotoDraft(
        name=name,
        servings=_coerce_int(data.get("servings")),
        prep_minutes=_coerce_int(data.get("prep_minutes")),
        cook_minutes=_coerce_int(data.get("cook_minutes")),
        ingredients=ingredients,
        steps=steps,
    )
