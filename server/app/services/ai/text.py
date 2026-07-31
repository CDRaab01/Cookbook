"""LM Studio **text** client — the sibling of :func:`app.services.ai.vision._chat_vision`.

Same host, same model (`google/gemma-4-e4b`), same error mapping; what differs is that there's no
image and the callers want determinism, so ``temperature`` defaults to 0 and ``max_tokens`` is
always explicit. An unbounded completion from a local model is how a 200 ms classification turns
into a 30 s one.

The error taxonomy is deliberately identical to the vision path so the Android client only has to
learn it once: 503 = LM Studio unreachable, 504 = timed out (usually a cold model load),
502 = it answered with something unusable.
"""

import httpx
from fastapi import HTTPException, status

from app.config import settings


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
        return response.json()["choices"][0]["message"]["content"] or ""
    except (ValueError, KeyError, IndexError, TypeError) as e:
        # A 200 with a body we can't read is the model misbehaving, not the user's fault — same
        # 502 as an outright rejection so the client's messaging doesn't need a third case.
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="LM Studio returned an unreadable response.",
        ) from e
