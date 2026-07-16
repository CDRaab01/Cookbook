"""household sharing (family mode) + recipes.shared

Adds `households` + `household_members` (the Settings-managed sharing unit) and a `shared` flag on
recipes (family vs private). Additive; existing recipes default to private (shared=false) and
existing single users have no household rows.

Revision ID: 0014
Revises: 0013
Create Date: 2026-07-16
"""

import sqlalchemy as sa
from alembic import op

revision = "0014"
down_revision = "0013"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "recipes",
        sa.Column("shared", sa.Boolean(), nullable=False, server_default="false"),
    )
    op.create_table(
        "households",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "owner_user_id",
            sa.Uuid(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
    )
    op.create_index("ix_households_owner_user_id", "households", ["owner_user_id"])
    op.create_table(
        "household_members",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "household_id",
            sa.Uuid(),
            sa.ForeignKey("households.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            sa.Uuid(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
            unique=True,
        ),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
    )
    op.create_index("ix_household_members_household_id", "household_members", ["household_id"])
    op.create_index(
        "ix_household_members_user_id", "household_members", ["user_id"], unique=True
    )


def downgrade() -> None:
    op.drop_table("household_members")
    op.drop_table("households")
    op.drop_column("recipes", "shared")
