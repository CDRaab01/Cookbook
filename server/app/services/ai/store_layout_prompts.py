"""Prompt + parser for "suggest a layout for this store".

Setting up a store by hand means naming and ordering a dozen-plus aisles before you get any value
from it, which is the kind of chore that leaves the feature unused. The model knows roughly how a
big-box grocery store is walked, so it can produce a *starting point* — a draft the user reorders
and renames in the normal editor before anything is saved.

Expect this to be generic. The model has world knowledge of "Meijer", not a floor plan of the one
on Maysville Rd, so "edit before save" is the intended workflow, not a failure. What it genuinely
saves is the typing and the walk-order thinking.

The parser guarantees the draft is *usable*, not that it's right: names are clamped, unknown
aisles dropped, and any canonical category the model forgot is appended in a trailing aisle so no
item can end up unroutable.
"""

from app.limits import MAX_AISLE_NAME_LENGTH, MAX_STORE_AISLES
from app.models.recipe import STORE_CATEGORIES
from app.schemas.store import AisleIn
from app.services.ai.jsonish import parse_object

# Reasoning + answer, not answer (see services/ai/text.py). Measured on gemma-4-e4b: 932 hidden
# reasoning tokens before 169 of JSON — at 900 the model emitted an empty string and this feature
# fell back to the default layout 100% of the time while looking like it "just didn't know Meijer".
MAX_TOKENS = 2500

LOW_CONFIDENCE_NOTE = (
    "Couldn't get a layout from the local model, so this is the standard aisle order — "
    "drag it into the order you actually walk the store."
)
DRAFT_NOTE = (
    "A rough guess at this store's layout. Check the order against how you actually walk it, "
    "then save."
)
LEFTOVER_AISLE_NAME = "Everything else"

_CATEGORY_LIST = ", ".join(STORE_CATEGORIES)

SYSTEM_PROMPT = (
    "You describe the layout of grocery stores: which departments they have and the order a "
    "shopper walks past them from the entrance to the checkout. You only output JSON — never "
    "prose, never Markdown, never an explanation."
)


def build_messages(chain: str) -> list[dict]:
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {
            "role": "user",
            "content": (
                f'Describe the typical layout of a "{chain}" grocery store.\n\n'
                "List its departments in the order a shopper walks them, from the entrance to the "
                "checkout. For each one, say which of these categories it holds: "
                f"{_CATEGORY_LIST}.\n"
                "Every category must appear in exactly one department. Use department names a "
                'shopper would recognise from the signs ("Produce", "Aisle 5 — Baking").\n\n'
                "Respond with ONLY this JSON, no fences and no prose:\n"
                '{"aisles": [{"name": "<department name>", "categories": ["<category>", ...]}]}'
            ),
        },
    ]


def parse_layout(raw_text: str) -> list[AisleIn] | None:
    """An ordered, usable aisle draft, or ``None`` when nothing could be read.

    Every canonical category the model left out is swept into a trailing aisle. Without that a
    forgotten category would have no aisle to route to and its items would land in the client's
    "Unsorted" bucket — technically fine, but it reads as a bug in the layout the user just saved.
    """
    data = parse_object(raw_text)
    if data is None:
        return None
    raw_aisles = data.get("aisles")
    if not isinstance(raw_aisles, list):
        return None

    aisles: list[AisleIn] = []
    claimed: set[str] = set()
    for raw in raw_aisles:
        if len(aisles) >= MAX_STORE_AISLES - 1:  # leave room for the leftovers aisle
            break
        if not isinstance(raw, dict):
            continue
        name = str(raw.get("name") or "").strip()[:MAX_AISLE_NAME_LENGTH]
        if not name:
            continue
        categories: list[str] = []
        for value in raw.get("categories") or []:
            key = str(value or "").strip().casefold()
            # First aisle to claim a category keeps it — a duplicate later in the walk would
            # never be reached by the routing anyway, so dropping it keeps the draft honest.
            if key in STORE_CATEGORIES and key not in claimed:
                claimed.add(key)
                categories.append(key)
        aisles.append(AisleIn(name=name, categories=categories))

    if not aisles:
        return None
    leftovers = [c for c in STORE_CATEGORIES if c not in claimed]
    if leftovers:
        aisles.append(AisleIn(name=LEFTOVER_AISLE_NAME, categories=leftovers))
    return aisles
