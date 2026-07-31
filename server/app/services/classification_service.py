"""Background aisle-filing for items the deterministic path couldn't place (v0.11).

Runs **after** the response, as a FastAPI background task, on its own session — the request's
session is gone by then. That placement is the whole design: the shopping list must never depend
on AI (ARCHITECTURE.md invariant), so the add path returns at exactly the speed it always did and
this either improves the row a moment later or doesn't. LM Studio being down is not an error here;
it's an item that stays unfiled and gets another chance on the next add.

This is the suite's documented exception to "AI output is a user-confirmed draft" — a category is
metadata, not user-visible AI content, and the failure mode is *unfiled*, never *wrong data
committed*. Remnant's note classifier established it; the guardrails are the same:

- Only ever writes ``shopping_list_items.category``, and only where it is still NULL.
- **Never writes ``item_history``.** History is the record of where *you* file things; a machine
  guess must not become "remembered" and outrank the keyword guesser for every future recipe.
- Re-checks the name under the write, so an edit that lands during the model call can't be
  overwritten by a label computed for the old text.
"""

import logging
import uuid

from sqlalchemy import select, update

from app.database import AsyncSessionLocal
from app.models.shopping_list import ShoppingListItem
from app.services.ai.category_prompts import MAX_TOKENS, build_messages, parse_item_category
from app.services.ai.text import chat_text

logger = logging.getLogger(__name__)

# How many items one add is willing to classify. A "week of dinners" plan-to-list can drop 60 new
# rows at once; filing the first slice and letting the rest ride the next add keeps the local model
# (shared with Spotter, Plate and kidbot) from being monopolised by one request.
MAX_PER_BATCH = 15


def unfiled_item_ids(items) -> list[uuid.UUID]:
    """The ids on a just-returned list that still have no aisle, capped.

    Takes everything unfiled rather than only the items this request added, which is what makes
    the feature self-healing: a row stranded while LM Studio was down gets picked up by whatever
    add happens next, with no polling loop and no migration.
    """
    return [item.id for item in items if item.category is None][:MAX_PER_BATCH]


async def unfiled_item_ids_for_list(db, list_id: uuid.UUID) -> list[uuid.UUID]:
    """Same, for the paths whose response doesn't carry the items (plan → list)."""
    result = await db.execute(
        select(ShoppingListItem.id)
        .where(ShoppingListItem.list_id == list_id, ShoppingListItem.category.is_(None))
        .limit(MAX_PER_BATCH)
    )
    return list(result.scalars().all())


async def classify_unfiled_items(item_ids: list[uuid.UUID]) -> None:
    """Ask the local model where each item belongs; write only what's still unfiled.

    Never raises: this runs detached from any request, so an exception here would only ever be a
    log line with no user to receive it. Every failure mode — model down, timeout, junk reply,
    item deleted mid-flight — leaves the row exactly as it was.
    """
    if not item_ids:
        return
    async with AsyncSessionLocal() as db:
        for item_id in item_ids:
            try:
                row = (
                    await db.execute(
                        select(ShoppingListItem.name, ShoppingListItem.category).where(
                            ShoppingListItem.id == item_id
                        )
                    )
                ).one_or_none()
                if row is None or row.category is not None:
                    continue  # deleted, or someone filed it while we were queued
                name = row.name
                raw = await chat_text(build_messages(name), max_tokens=MAX_TOKENS)
                category = parse_item_category(raw)
                if category is None:
                    continue  # unfiled is a fine outcome; it stays eligible for a retry
                # Re-check under the write: the row must still be unfiled AND still be the same
                # text we classified. Without the name check, renaming "milk" to "milk collector"
                # during the model call would land the answer for the old name on the new item.
                await db.execute(
                    update(ShoppingListItem)
                    .where(
                        ShoppingListItem.id == item_id,
                        ShoppingListItem.category.is_(None),
                        ShoppingListItem.name == name,
                    )
                    .values(category=category)
                )
                await db.commit()
            except Exception:  # noqa: BLE001 — a background best-effort must not escalate
                await db.rollback()
                logger.warning("Aisle classification failed for item %s", item_id, exc_info=True)
