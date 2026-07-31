"""Salvage helpers for "the model was told to emit JSON and mostly did".

Local models wrap output in code fences or bracket it with an apology often enough that a naive
``json.loads`` throws away usable responses. Every AI surface here parses the same way — strip
fences, then fall back to the widest ``{...}`` span — so the behaviour lives in one place rather
than being re-derived per prompt module.

Nothing here raises. A caller that gets ``None`` turns it into a low-confidence draft, never an
error: content failures are the model's problem, not the user's.
"""

import json
import re

_FENCE_RE = re.compile(r"^```(?:json)?\s*|\s*```$", re.IGNORECASE | re.MULTILINE)


def strip_fences(text: str) -> str:
    return _FENCE_RE.sub("", text).strip()


def widest_object_span(text: str) -> str | None:
    """The outermost ``{...}`` — catches an object buried in prose ("Sure! {...} Hope that
    helps."). Widest rather than first because a model that narrates often opens a brace in the
    narration."""
    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end < start:
        return None
    return text[start : end + 1]


def parse_object(raw_text: str) -> dict | None:
    """The whole salvage ladder: fences off, try it, then try the widest span. ``None`` when
    nothing parses to a non-empty dict."""
    stripped = strip_fences(raw_text)
    for candidate in (stripped, widest_object_span(stripped)):
        if not candidate:
            continue
        try:
            data = json.loads(candidate)
        except (json.JSONDecodeError, TypeError):
            continue
        if isinstance(data, dict) and data:
            return data
    return None
