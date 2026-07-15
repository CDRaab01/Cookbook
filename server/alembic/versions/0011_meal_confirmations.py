"""Per-user meal confirmations — each member confirms (and Plate-logs) their own eating.

Revision ID: 0011
Revises: 0010
Create Date: 2026-07-14

A shared household plan needs per-person "I ate this", not one flag on the shared entry: calories
are per-user. This table holds one confirmation per (entry, user) with the portion eaten and the
Plate correlation ref. The old ``meal_plan_entries.eaten`` column is left in place but retired from
reads (no data migration — nobody relied on the shared flag being personal).
"""

import sqlalchemy as sa

from alembic import op

revision = "0011"
down_revision = "0010"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "meal_confirmations",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "entry_id",
            sa.Uuid(),
            sa.ForeignKey("meal_plan_entries.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            sa.Uuid(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("eaten", sa.Boolean(), server_default=sa.true(), nullable=False),
        sa.Column("servings", sa.Float(), server_default="1", nullable=False),
        sa.Column("plate_ref", sa.String(length=128), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False
        ),
        sa.UniqueConstraint("entry_id", "user_id", name="uq_meal_confirm_entry_user"),
    )
    op.create_index("ix_meal_confirmations_entry_id", "meal_confirmations", ["entry_id"])
    op.create_index("ix_meal_confirmations_user_id", "meal_confirmations", ["user_id"])


def downgrade() -> None:
    op.drop_table("meal_confirmations")
