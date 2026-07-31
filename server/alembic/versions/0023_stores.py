"""Store profiles: per-store aisles + learned item placements (v0.11).

The 13 store categories are a portable vocabulary — an item keeps its category wherever you shop.
A *store* is where that vocabulary meets a floor plan: its own ordered aisles, each collecting some
categories, plus per-item exceptions for the things a given store files somewhere odd.

Schema-only; nothing existing changes and nothing is backfilled. A user with no stores keeps the
category grouping they already had.

Revision ID: 0023
Revises: 0022
Create Date: 2026-07-31
"""

import sqlalchemy as sa

from alembic import op

revision = "0023"
down_revision = "0022"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "stores",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "user_id", sa.Uuid(), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False
        ),
        sa.Column("name", sa.String(120), nullable=False),
        sa.Column("label", sa.String(120), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
    )
    op.create_index("ix_stores_user_id", "stores", ["user_id"])

    op.create_table(
        "store_aisles",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "store_id", sa.Uuid(), sa.ForeignKey("stores.id", ondelete="CASCADE"), nullable=False
        ),
        sa.Column("order", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("name", sa.String(80), nullable=False),
        sa.Column("categories", sa.JSON(), nullable=True),
    )
    op.create_index("ix_store_aisles_store_id", "store_aisles", ["store_id"])

    op.create_table(
        "store_placements",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "store_id", sa.Uuid(), sa.ForeignKey("stores.id", ondelete="CASCADE"), nullable=False
        ),
        sa.Column(
            "aisle_id",
            sa.Uuid(),
            sa.ForeignKey("store_aisles.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("key", sa.String(255), nullable=False),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.UniqueConstraint("store_id", "key", name="uq_store_placement"),
    )
    op.create_index("ix_store_placements_store_id", "store_placements", ["store_id"])
    op.create_index("ix_store_placements_aisle_id", "store_placements", ["aisle_id"])
    op.create_index("ix_store_placements_key", "store_placements", ["key"])


def downgrade() -> None:
    op.drop_table("store_placements")
    op.drop_table("store_aisles")
    op.drop_table("stores")
