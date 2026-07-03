"""Pantry: what the user has on hand (scan-confirmed or manual) + always-available staples.

Revision ID: 0007
Revises: 0006
Create Date: 2026-07-03
"""

import sqlalchemy as sa

from alembic import op

revision = "0007"
down_revision = "0006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "pantry_items",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "user_id",
            sa.Uuid(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("category", sa.String(16), nullable=True),
        sa.Column("source", sa.String(16), nullable=False, server_default="manual"),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
    )
    op.create_index("ix_pantry_items_user_id", "pantry_items", ["user_id"])

    # Staples live apart from pantry_items: different lifecycle (edited in Settings, replaced
    # wholesale via PUT, never listed with the pantry) and different semantics (always available).
    op.create_table(
        "pantry_staples",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "user_id",
            sa.Uuid(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
    )
    op.create_index("ix_pantry_staples_user_id", "pantry_staples", ["user_id"])

    # First-use confirmation marker — explicit, because "has any staple rows" breaks as a
    # proxy the moment a user deletes every staple.
    op.add_column(
        "users",
        sa.Column("staples_confirmed_at", sa.DateTime(timezone=True), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("users", "staples_confirmed_at")
    op.drop_table("pantry_staples")
    op.drop_table("pantry_items")
