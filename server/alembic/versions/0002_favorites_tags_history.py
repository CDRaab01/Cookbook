"""Recipe favorites + tags, and the shopping item-history table (v0.2).

Revision ID: 0002
Revises: 0001
Create Date: 2026-07-02
"""

import sqlalchemy as sa

from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "recipes",
        sa.Column("favorite", sa.Boolean(), nullable=False, server_default=sa.text("false")),
    )
    op.add_column("recipes", sa.Column("tags", sa.JSON(), nullable=True))

    # One row per distinct grocery item a user has ever added (manual or via recipe), keyed by
    # the merge-normalized name. Powers add-dialog autocomplete and category recall.
    op.create_table(
        "item_history",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "user_id",
            sa.Uuid(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("key", sa.String(255), nullable=False),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("unit", sa.String(32), nullable=True),
        sa.Column("category", sa.String(16), nullable=True),
        sa.Column("use_count", sa.Integer(), nullable=False, server_default="1"),
        sa.Column(
            "last_used",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
    )
    op.create_index("ix_item_history_user_id", "item_history", ["user_id"])
    op.create_index(
        "ux_item_history_user_key", "item_history", ["user_id", "key"], unique=True
    )


def downgrade() -> None:
    op.drop_table("item_history")
    op.drop_column("recipes", "tags")
    op.drop_column("recipes", "favorite")
