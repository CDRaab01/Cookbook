"""LM Studio vision client for recipe-photo import (v0.3). Mirrors Plate's
app/services/ai/vision.py: local OpenAI-compatible chat-completions call, low temperature
for faithfulness over creativity, transport failures mapped to clean HTTP statuses so the
client can distinguish "not configured/unreachable" from "photo unreadable".
"""

import base64

import httpx
from fastapi import HTTPException, status

from app.config import settings
from app.schemas.photo import RecipePhotoDraftOut
from app.services.ai.recipe_photo_prompts import (
    NO_RECIPE_NOTE,
    build_vision_messages,
    parse_draft,
)


def _data_url(image_bytes: bytes, content_type: str) -> str:
    encoded = base64.b64encode(image_bytes).decode("ascii")
    return f"data:{content_type};base64,{encoded}"


async def estimate_recipe_photo(
    image_bytes: bytes,
    content_type: str,
    client: httpx.AsyncClient | None = None,
) -> RecipePhotoDraftOut:
    """`client` is an injection seam for tests (httpx.MockTransport) — production calls
    always go through the default, real-network client."""
    image_data_url = _data_url(image_bytes, content_type)
    messages = build_vision_messages(image_data_url)
    owns_client = client is None
    active = client or httpx.AsyncClient(timeout=settings.lm_studio_timeout)

    try:
        try:
            response = await active.post(
                f"{settings.lm_studio_base_url}/chat/completions",
                json={
                    "model": settings.lm_studio_vision_model,
                    "messages": messages,
                    "temperature": 0.2,
                },
            )
            response.raise_for_status()
        finally:
            if owns_client:
                await active.aclose()
    except httpx.TimeoutException as e:
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail="LM Studio timed out — the vision model may still be loading.",
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

    body = response.json()
    raw_text = body["choices"][0]["message"]["content"]
    draft = parse_draft(raw_text)

    if draft is None:
        return RecipePhotoDraftOut(low_confidence=True, note=NO_RECIPE_NOTE)
    return RecipePhotoDraftOut(**draft.model_dump())
