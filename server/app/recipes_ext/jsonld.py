"""Native recipe extraction from a web page's schema.org/Recipe JSON-LD (v0.2).

Most recipe sites embed structured data for search engines in
``<script type="application/ld+json">`` blocks. This module fetches a page, finds a Recipe
node (handles ``@graph`` wrappers, lists, and multi-type ``@type`` arrays), and maps it to a
:class:`~app.recipes_ext.base.NormalizedRecipe`. It is the first engine for
``POST /recipes/import-url``; Spoonacular's extractor is the fallback for sites without
usable markup.

Ingredient lines arrive as free text ("1 ½ cups all-purpose flour, sifted") and are parsed
"lite": leading amount (unicode fractions, "1 1/2", decimals) + a known unit word + the rest as
the name, with the original line kept as the note. A line that defies parsing simply becomes a
name — never dropped.
"""

import json
import logging
import re

import httpx

from app.lists.categorize import guess_category
from app.recipes_ext.base import NormalizedIngredient, NormalizedRecipe

log = logging.getLogger(__name__)

MAX_PAGE_BYTES = 3 * 1024 * 1024

_SCRIPT_RE = re.compile(
    r"<script[^>]*type\s*=\s*[\"']application/ld\+json[\"'][^>]*>(.*?)</script>",
    re.IGNORECASE | re.DOTALL,
)
_TAG_RE = re.compile(r"<[^>]+>")

_FRACTIONS = {
    "½": 0.5,
    "⅓": 1 / 3,
    "⅔": 2 / 3,
    "¼": 0.25,
    "¾": 0.75,
    "⅕": 0.2,
    "⅖": 0.4,
    "⅗": 0.6,
    "⅘": 0.8,
    "⅙": 1 / 6,
    "⅚": 5 / 6,
    "⅛": 0.125,
    "⅜": 0.375,
    "⅝": 0.625,
    "⅞": 0.875,
}

_UNIT_WORDS = {
    "cup": "cup",
    "cups": "cup",
    "tablespoon": "tbsp",
    "tablespoons": "tbsp",
    "tbsp": "tbsp",
    "tbs": "tbsp",
    "teaspoon": "tsp",
    "teaspoons": "tsp",
    "tsp": "tsp",
    "ounce": "oz",
    "ounces": "oz",
    "oz": "oz",
    "pound": "lb",
    "pounds": "lb",
    "lb": "lb",
    "lbs": "lb",
    "gram": "g",
    "grams": "g",
    "g": "g",
    "kilogram": "kg",
    "kilograms": "kg",
    "kg": "kg",
    "milliliter": "ml",
    "milliliters": "ml",
    "ml": "ml",
    "liter": "l",
    "liters": "l",
    "l": "l",
    "can": "can",
    "cans": "can",
    "clove": "clove",
    "cloves": "clove",
    "slice": "slice",
    "slices": "slice",
    "stick": "stick",
    "sticks": "stick",
    "package": "package",
    "packages": "package",
    "pkg": "package",
    "bunch": "bunch",
    "bunches": "bunch",
    "head": "head",
    "heads": "head",
    "piece": "piece",
    "pieces": "piece",
    "pinch": "pinch",
    "dash": "dash",
}

# ISO-8601 durations as schema.org uses them: PT1H30M, PT45M, P0DT1H.
_DURATION_RE = re.compile(
    r"P(?:(?P<d>\d+)D)?T?(?:(?P<h>\d+)H)?(?:(?P<m>\d+)M)?(?:(?P<s>\d+)S)?", re.IGNORECASE
)


def _leading_amount(text: str) -> tuple[float | None, str]:
    """Parse a leading amount ("2", "1 1/2", "1½", "2.5", "½") off an ingredient line."""
    rest = text.strip()
    total = 0.0
    found = False
    while rest:
        m = re.match(r"^(\d+(?:\.\d+)?)(?:\s*/\s*(\d+))?", rest)
        if m:
            if m.group(2):  # "1/2"
                denom = float(m.group(2))
                if denom == 0:
                    break
                total += float(m.group(1)) / denom
            else:
                total += float(m.group(1))
            found = True
            rest = rest[m.end() :].lstrip()
            # A bare fraction may follow a whole number ("1 ½" or "1 1/2" handled above).
            if rest and rest[0] in _FRACTIONS:
                continue
            if re.match(r"^\d+\s*/\s*\d+", rest):
                continue
            break
        if rest[0] in _FRACTIONS:
            total += _FRACTIONS[rest[0]]
            found = True
            rest = rest[1:].lstrip()
            break
        break
    return (total if found else None), rest


# The upper bound of a range, possibly a compound number ("1 1/2"): "- 3", "– 2.5", "to 1 1/2".
_RANGE_TAIL_RE = re.compile(r"^(?:[-–—]|to\s)\s*(?:\d+(?:\.\d+)?(?:\s*/\s*\d+)?\s*)+")


def parse_ingredient_line(line: str) -> NormalizedIngredient:
    original = " ".join(line.split())
    quantity, rest = _leading_amount(original)
    if quantity is not None:
        # Ranges ("2-3 lbs", "2 to 3 lbs"): keep the lower bound, drop the upper so the tail
        # doesn't leak into the name as "-3 lbs chicken".
        rest = _RANGE_TAIL_RE.sub("", rest)
    unit = None
    if quantity is not None and rest:
        first, _, remainder = rest.partition(" ")
        unit_key = first.strip().rstrip(".").casefold()
        if unit_key in _UNIT_WORDS:
            unit = _UNIT_WORDS[unit_key]
            rest = remainder.strip()
    name = rest or original
    if name.casefold().startswith("of "):
        name = name[3:]
    # Trailing prep notes ("flour, sifted") stay in the name — the note holds the full line.
    return NormalizedIngredient(
        name=name[:255],
        quantity=quantity if quantity and quantity > 0 else None,
        unit=unit,
        category=guess_category(name),
        original_text=original if original != name else None,
    )


def _minutes(value) -> int | None:
    if not isinstance(value, str):
        return None
    m = _DURATION_RE.fullmatch(value.strip())
    if not m or not any(m.groups()):
        return None
    days = int(m.group("d") or 0)
    hours = int(m.group("h") or 0)
    mins = int(m.group("m") or 0)
    secs = int(m.group("s") or 0)
    total = days * 1440 + hours * 60 + mins + (1 if secs >= 30 else 0)
    return total or None


def _first_image(value) -> str | None:
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        return value.get("url") or value.get("contentUrl")
    if isinstance(value, list) and value:
        return _first_image(value[0])
    return None


def _servings(value) -> int | None:
    for candidate in value if isinstance(value, list) else [value]:
        if isinstance(candidate, (int, float)):
            return int(candidate)
        if isinstance(candidate, str):
            m = re.search(r"\d+", candidate)
            if m:
                return int(m.group())
    return None


def _steps(value) -> list[str]:
    """recipeInstructions: string | [string] | [HowToStep] | [HowToSection[itemListElement]]."""
    out: list[str] = []

    def walk(node) -> None:
        if isinstance(node, str):
            text = _TAG_RE.sub("", node).strip()
            if text:
                out.append(text)
        elif isinstance(node, list):
            for child in node:
                walk(child)
        elif isinstance(node, dict):
            if node.get("itemListElement"):
                walk(node["itemListElement"])
            elif node.get("text"):
                walk(node["text"])
            elif node.get("name"):
                walk(node["name"])

    walk(value)
    return out


def _is_recipe_node(node) -> bool:
    if not isinstance(node, dict):
        return False
    node_type = node.get("@type")
    types = node_type if isinstance(node_type, list) else [node_type]
    return any(isinstance(t, str) and t.casefold() == "recipe" for t in types)


def find_recipe_node(payload) -> dict | None:
    """Locate the Recipe node in a parsed JSON-LD document (handles @graph and lists)."""
    if _is_recipe_node(payload):
        return payload
    if isinstance(payload, dict):
        return find_recipe_node(payload.get("@graph") or [])
    if isinstance(payload, list):
        for node in payload:
            found = find_recipe_node(node)
            if found is not None:
                return found
    return None


def normalize_jsonld(node: dict, source_url: str) -> NormalizedRecipe | None:
    title = (node.get("name") or "").strip()
    raw_ingredients = node.get("recipeIngredient") or node.get("ingredients") or []
    ingredients = [
        parse_ingredient_line(line)
        for line in raw_ingredients
        if isinstance(line, str) and line.strip()
    ]
    if not title or not ingredients:
        return None
    total = _minutes(node.get("totalTime"))
    cook = _minutes(node.get("cookTime")) or total
    description = _TAG_RE.sub("", node.get("description") or "").strip() or None
    return NormalizedRecipe(
        source_id=source_url,
        title=title[:255],
        ingredients=ingredients,
        steps=_steps(node.get("recipeInstructions")),
        image=_first_image(node.get("image")),
        servings=_servings(node.get("recipeYield")),
        ready_in_minutes=total or cook,
        source_url=source_url,
        summary=description,
    )


def extract_jsonld_blocks(html: str):
    """Yield parsed JSON documents from the page's ld+json script blocks (bad JSON skipped)."""
    for match in _SCRIPT_RE.finditer(html):
        raw = match.group(1).strip()
        try:
            yield json.loads(raw)
        except ValueError:
            continue


async def fetch_recipe_from_url(url: str, client: httpx.AsyncClient) -> NormalizedRecipe | None:
    """Fetch a page and extract its Recipe JSON-LD; None when the page has no usable markup."""
    # A browser-like UA: several big recipe networks (Dotdash Meredith et al.) 403 anything
    # that self-identifies as a script, even for a single personal-use fetch of a public page.
    resp = await client.get(
        url,
        headers={
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            ),
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.9",
        },
        follow_redirects=True,
    )
    resp.raise_for_status()
    if len(resp.content) > MAX_PAGE_BYTES:
        log.info("import-url: page too large (%d bytes): %s", len(resp.content), url)
        return None
    for payload in extract_jsonld_blocks(resp.text):
        node = find_recipe_node(payload)
        if node is not None:
            normalized = normalize_jsonld(node, url)
            if normalized is not None:
                return normalized
    return None
