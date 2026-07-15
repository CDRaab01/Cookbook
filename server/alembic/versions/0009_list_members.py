"""Household sharing: shopping_list_members — users (besides the owner) who can view/edit a list.

Revision ID: 0009
Revises: 0008
Create Date: 2026-07-14
"""

import sqlalchemy as sa

from alembic import op

revision = "0009"
down_revision = "0008"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "shopping_list_members",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "list_id",
            sa.Uuid(),
            sa.ForeignKey("shopping_lists.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            sa.Uuid(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "added_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.UniqueConstraint("list_id", "user_id", name="uq_list_member"),
    )
    op.create_index("ix_shopping_list_members_list_id", "shopping_list_members", ["list_id"])
    op.create_index("ix_shopping_list_members_user_id", "shopping_list_members", ["user_id"])


def downgrade() -> None:
    op.drop_table("shopping_list_members")
