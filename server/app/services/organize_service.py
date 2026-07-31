""" "Organize list": a whole-list aisle review the user confirms, and the apply that follows.

Two endpoints, deliberately split. The draft reads the list and asks the local model what it would
move — and saves **nothing**. The apply takes back only the moves the user accepted and writes
them. That split is the house rule (AI output is a user-confirmed draft) and it also means apply
works perfectly well with LM Studio switched off, which matters when someone re-taps Apply after
the review screen has been sitting open.

Unlike the background classifier, this may propose moving items the user placed by hand, which is
exactly why it can't be silent.
"""

import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from app.schemas.shopping import ListOut, OrganizeApplyRequest, OrganizeDraftOut, OrganizeSuggestion
from app.services.ai.organize_prompts import (
    LOW_CONFIDENCE_NOTE,
    MAX_ITEMS,
    MAX_TOKENS,
    NOTHING_TO_DO_NOTE,
    build_messages,
    parse_organize,
)
from app.services.ai.text import chat_text
from app.services.shopping_service import _remember_category, _reload, load_accessible_list


async def organize_draft(
    db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID, client=None
) -> OrganizeDraftOut:
    """Ask the model which items are in the wrong aisle. Saves nothing."""
    shopping_list = await load_accessible_list(db, user_id, list_id)
    # Only what's left to buy: a checked-off item is history for this trip, and re-filing it would
    # shuffle the "in the cart" section for no benefit.
    items = [i for i in shopping_list.items if not i.checked][:MAX_ITEMS]
    if not items:
        return OrganizeDraftOut(suggestions=[], note=NOTHING_TO_DO_NOTE)

    current = {item.name: item.category for item in items}
    raw = await chat_text(
        build_messages(list(current.items())), max_tokens=MAX_TOKENS, client=client
    )
    moves = parse_organize(raw, current)
    if moves is None:
        return OrganizeDraftOut(suggestions=[], low_confidence=True, note=LOW_CONFIDENCE_NOTE)

    # Resolve names back to ids server-side: the client applies by id, so a name collision on the
    # list can't send the wrong row somewhere.
    by_name: dict[str, list] = {}
    for item in items:
        by_name.setdefault(item.name, []).append(item)
    suggestions = [
        OrganizeSuggestion(
            item_id=by_name[name][0].id,
            name=name,
            current_category=by_name[name][0].category,
            suggested_category=category,
        )
        for name, category in moves
        if name in by_name
    ]
    note = None if suggestions else NOTHING_TO_DO_NOTE
    return OrganizeDraftOut(suggestions=suggestions, note=note)


async def organize_apply(
    db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID, req: OrganizeApplyRequest
) -> ListOut:
    """Write the accepted moves. No model call — this works with LM Studio down.

    Items that vanished or were re-filed between the draft and the tap are skipped silently rather
    than 404ing the batch: the user accepted a set of changes, and failing all of them because one
    row moved on another device would be the wrong trade.

    Accepted moves **do** write ``item_history`` — via the same ``_remember_category`` the edit
    dialog uses — because accepting a suggestion is a deliberate user decision about where they
    file that item, not a machine guess. That is precisely what distinguishes this from the
    background classifier.
    """
    shopping_list = await load_accessible_list(db, user_id, list_id)
    by_id = {item.id: item for item in shopping_list.items}
    for move in req.moves:
        item = by_id.get(move.item_id)
        if item is None or item.category == move.category:
            continue
        item.category = move.category
        await _remember_category(db, user_id, item.name, move.category)
    await db.commit()
    return ListOut.model_validate(await _reload(db, list_id))
