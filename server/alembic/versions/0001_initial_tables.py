"""Initial tables: users, recipes, recipe_steps, recipe_ingredients, shopping_lists,
shopping_list_items (CLAUDE.md §4).

Revision ID: 0001
Revises:
Create Date: 2026-07-01
"""

import sqlalchemy as sa

from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("email", sa.String(255), nullable=False),
        sa.Column("hashed_password", sa.String(255), nullable=False),
        sa.Column("settings", sa.Text(), nullable=True),
        sa.Column("reset_token", sa.String(64), nullable=True),
        sa.Column("reset_token_expires_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("ix_users_email", "users", ["email"], unique=True)

    op.create_table(
        "recipes",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "user_id",
            sa.Uuid(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("servings", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("prep_minutes", sa.Integer(), nullable=True),
        sa.Column("cook_minutes", sa.Integer(), nullable=True),
        sa.Column("source", sa.String(16), nullable=False, server_default="manual"),
        sa.Column("source_id", sa.String(64), nullable=True),
        sa.Column("image_url", sa.Text(), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"),
            nullable=False,
        ),
    )
    op.create_index("ix_recipes_user_id", "recipes", ["user_id"])

    op.create_table(
        "recipe_steps",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "recipe_id",
            sa.Uuid(),
            sa.ForeignKey("recipes.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("order", sa.Integer(), nullable=False),
        sa.Column("text", sa.Text(), nullable=False),
    )
    op.create_index("ix_recipe_steps_recipe_id", "recipe_steps", ["recipe_id"])

    op.create_table(
        "recipe_ingredients",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "recipe_id",
            sa.Uuid(),
            sa.ForeignKey("recipes.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("order", sa.Integer(), nullable=False),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("quantity", sa.Float(), nullable=True),
        sa.Column("unit", sa.String(32), nullable=True),
        sa.Column("category", sa.String(16), nullable=True),
        sa.Column("note", sa.String(255), nullable=True),
        sa.Column("plate_food_id", sa.Uuid(), nullable=True),
    )
    op.create_index("ix_recipe_ingredients_recipe_id", "recipe_ingredients", ["recipe_id"])

    op.create_table(
        "shopping_lists",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "user_id",
            sa.Uuid(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"),
            nullable=False,
        ),
    )
    op.create_index("ix_shopping_lists_user_id", "shopping_lists", ["user_id"])

    op.create_table(
        "shopping_list_items",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "list_id",
            sa.Uuid(),
            sa.ForeignKey("shopping_lists.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("quantity", sa.Float(), nullable=True),
        sa.Column("unit", sa.String(32), nullable=True),
        sa.Column("category", sa.String(16), nullable=True),
        sa.Column("checked", sa.Boolean(), nullable=False, server_default=sa.text("false")),
        sa.Column("checked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "recipe_id",
            sa.Uuid(),
            sa.ForeignKey("recipes.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("order", sa.Integer(), nullable=False, server_default="0"),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"),
            nullable=False,
        ),
    )
    op.create_index("ix_shopping_list_items_list_id", "shopping_list_items", ["list_id"])


def downgrade() -> None:
    op.drop_table("shopping_list_items")
    op.drop_table("shopping_lists")
    op.drop_table("recipe_ingredients")
    op.drop_table("recipe_steps")
    op.drop_table("recipes")
    op.drop_table("users")
