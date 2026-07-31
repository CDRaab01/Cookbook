"""LM Studio **text** client — the sibling of :func:`app.services.ai.vision._chat_vision`.

Same host, same model (`google/gemma-4-e4b`), same error mapping; what differs is that there's no
image and the callers want determinism, so ``temperature`` defaults to 0 and ``max_tokens`` is
always explicit. An unbounded completion from a local model is how a 200 ms classification turns
into a 30 s one.

**Size ``max_tokens`` for reasoning + answer, not the answer.** gemma-4 thinks in hidden
``reasoning_content`` tokens that count against the same budget, and it emits *no content at all*
until it's done: measured on this host, a 10-item Organize spent 597 reasoning tokens before 296 of
answer, and a store layout spent 932 before 169. A budget sized for the visible answer returns
``finish_reason: "length"`` with an empty string, which every parser here correctly reads as
"unreadable" — so the feature degrades silently and looks like a dumb model instead of a small
number. That failure is now logged loudly below.

The error taxonomy is deliberately identical to the vision path so the Android client only has to
learn it once: 503 = LM Studio unreachable, 504 = timed out (usually a cold model load),
502 = it answered with something unusable.
"""

import logging

import httpx
from fastapi import HTTPException, status

from app.config import settings

logger = logging.getLogger(__name__)


async def chat_text(
    messages: list[dict],
    *,
    max_tokens: int,
    temperature: float = 0.0,
    client: httpx.AsyncClient | None = None,
) -> str:
    """One chat-completions round trip, returning the raw model text.

    ``client`` is the injection seam for tests (``httpx.MockTransport``); production calls always
    pass ``None`` and get a real-network client scoped to the call.
    """
    owns_client = client is None
    active = client or httpx.AsyncClient(timeout=settings.lm_studio_timeout)
    base_url = settings.lm_studio_base_url.rstrip("/")

    try:
        try:
            response = await active.post(
                f"{base_url}/chat/completions",
                json={
                    "model": settings.lm_studio_model,
                    "messages": messages,
                    "temperature": temperature,
                    "max_tokens": max_tokens,
                },
            )
            response.raise_for_status()
        finally:
            if owns_client:
                await active.aclose()
    except httpx.TimeoutException as e:
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail="LM Studio timed out — the model may still be loading.",
        ) from e
    except httpx.HTTPStatusError as e:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="LM Studio rejected the request.",
        ) from e
    except httpx.RequestError as e:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Couldn't reach LM Studio. Is it running?",
        ) from e

    try:
        choice = response.json()["choices"][0]
        content = choice["message"]["content"] or ""
    except (ValueError, KeyError, IndexError, TypeError) as e:
        # A 200 with a body we can't read is the model misbehaving, not the user's fault — same
        # 502 as an outright rejection so the client's messaging doesn't need a third case.
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="LM Studio returned an unreadable response.",
        ) from e

    # gemma-4 is a *reasoning* model: it spends hundreds of hidden tokens thinking before it emits
    # a single character of content, and `max_tokens` caps the two together. A budget sized for the
    # answer alone therefore yields `finish_reason: "length"` and an EMPTY string — which every
    # parser here dutifully reports as "unreadable", so the feature quietly stops working and looks
    # like a bad model rather than a too-small number. Loud, because it is a config bug.
    if not content.strip() and choice.get("finish_reason") == "length":
        logger.warning(
            "LM Studio truncated the completion before any content — max_tokens is too small for "
            "this prompt (the model's reasoning consumed the whole budget)."
        )
    return content
