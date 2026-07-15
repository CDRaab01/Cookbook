import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.shopping import (
    AddRecipeRequest,
    GrocerySpendOut,
    ItemCreate,
    ItemUpdate,
    ListCreate,
    ListOut,
    ListRename,
    ListSummaryOut,
    MemberOut,
    ShareRequest,
    SuggestionOut,
)
from app.security import CurrentUser
from app.services.grocery_spend_service import fetch_month_grocery_spend
from app.services.shopping_service import (
    add_item,
    add_member_by_email,
    add_recipe,
    clear_checked,
    create_list,
    delete_item,
    delete_list,
    get_list_out,
    get_one_list,
    list_all_lists,
    list_members,
    remove_member,
    rename_list,
    suggest_items,
    update_item,
)

router = APIRouter(prefix="/lists", tags=["lists"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


@router.get("", response_model=list[ListSummaryOut])
async def all_lists(current_user: CurrentUser, db: DbSession):
    """Every list with unchecked/total counts, the default first."""
    return await list_all_lists(db, current_user.id)


@router.post("", response_model=ListOut, status_code=status.HTTP_201_CREATED)
async def create_new_list(req: ListCreate, current_user: CurrentUser, db: DbSession):
    return await create_list(db, current_user.id, req)


@router.get("/default", response_model=ListOut)
async def default_list(current_user: CurrentUser, db: DbSession):
    """The user's default list ("Groceries"), created on first touch."""
    return await get_list_out(db, current_user.id)


@router.get("/suggest", response_model=list[SuggestionOut])
async def suggest(
    current_user: CurrentUser,
    db: DbSession,
    q: Annotated[str, Query(description="Partial item name")],
):
    """Autocomplete from the user's own item history (v0.2). Declared before /{list_id} so
    "suggest" never parses as a list id."""
    return await suggest_items(db, current_user.id, q)


@router.get("/grocery-spend", response_model=GrocerySpendOut | None)
async def grocery_spend(current_user: CurrentUser):
    """This month's grocery dollars spent, reported by Magpie (federated awareness Link D).

    Declared before /{list_id} so "grocery-spend" never parses as a list id. Returns null (not an
    error) when the Magpie integration is off or unreachable — the shopping tile then just hides
    (CROSS-APP.md rule 7, degrade to absence)."""
    import datetime

    spend = await fetch_month_grocery_spend(current_user.email, datetime.date.today())
    if spend is None:
        return None
    return GrocerySpendOut(month=spend.month, spent_dollars=spend.spent_dollars)


@router.get("/{list_id}", response_model=ListOut)
async def one_list(list_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    return await get_one_list(db, current_user.id, list_id)


@router.patch("/{list_id}", response_model=ListOut)
async def rename(list_id: uuid.UUID, req: ListRename, current_user: CurrentUser, db: DbSession):
    return await rename_list(db, current_user.id, list_id, req)


@router.delete("/{list_id}", status_code=status.HTTP_204_NO_CONTENT)
async def remove_list(list_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    """Items cascade; deleting the last list just means the default recreates on next touch."""
    await delete_list(db, current_user.id, list_id)


@router.post("/{list_id}/items", response_model=ListOut, status_code=status.HTTP_201_CREATED)
async def create_item(
    list_id: uuid.UUID, req: ItemCreate, current_user: CurrentUser, db: DbSession
):
    return await add_item(db, current_user.id, list_id, req)


@router.post("/{list_id}/add-recipe", response_model=ListOut)
async def add_recipe_to_list(
    list_id: uuid.UUID, req: AddRecipeRequest, current_user: CurrentUser, db: DbSession
):
    """Autofill from a recipe (scaled), merging duplicates. 409 when its unchecked items are
    already on the list and ``force`` is false — the client's re-add/skip prompt."""
    return await add_recipe(db, current_user.id, list_id, req)


@router.patch("/{list_id}/items/{item_id}", response_model=ListOut)
async def patch_item(
    list_id: uuid.UUID,
    item_id: uuid.UUID,
    req: ItemUpdate,
    current_user: CurrentUser,
    db: DbSession,
):
    return await update_item(db, current_user.id, list_id, item_id, req)


@router.delete("/{list_id}/items/{item_id}", response_model=ListOut)
async def remove_item(
    list_id: uuid.UUID, item_id: uuid.UUID, current_user: CurrentUser, db: DbSession
):
    return await delete_item(db, current_user.id, list_id, item_id)


@router.post("/{list_id}/clear-checked", response_model=ListOut)
async def clear_checked_items(list_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    return await clear_checked(db, current_user.id, list_id)


# --- Household sharing ---
@router.get("/{list_id}/members", response_model=list[MemberOut])
async def get_members(list_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    """Everyone on a shared list (owner first). Any member may view."""
    return await list_members(db, current_user.id, list_id)


@router.post(
    "/{list_id}/members", response_model=list[MemberOut], status_code=status.HTTP_201_CREATED
)
async def share_list(
    list_id: uuid.UUID, req: ShareRequest, current_user: CurrentUser, db: DbSession
):
    """Invite a suite user (by their SSO email) to a shopping list. Owner-only."""
    return await add_member_by_email(db, current_user.id, list_id, req.email)


@router.delete("/{list_id}/members/{member_id}", status_code=status.HTTP_204_NO_CONTENT)
async def remove_list_member(
    list_id: uuid.UUID, member_id: uuid.UUID, current_user: CurrentUser, db: DbSession
):
    """Owner removes a member; a member removes themselves (leave)."""
    await remove_member(db, current_user.id, list_id, member_id)
