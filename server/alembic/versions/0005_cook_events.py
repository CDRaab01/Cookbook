"""Made-it tracking: one row per time a recipe was cooked.

Revision ID: 0005
Revises: 0004
Create Date: 2026-07-02
"""

import sqlalchemy as sa

from alembic import op

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "cook_events",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "user_id",
            sa.Uuid(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "recipe_id",
            sa.Uuid(),
            sa.ForeignKey("recipes.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "cooked_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
    )
    op.create_index("ix_cook_events_user_id", "cook_events", ["user_id"])
    op.create_index("ix_cook_events_recipe_id", "cook_events", ["recipe_id"])


def downgrade() -> None:
    op.drop_table("cook_events")
