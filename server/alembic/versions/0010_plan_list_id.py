"""Shared meal planning: meal_plan_entries.list_id — the plan belongs to a shopping list, so a
household that shares a list plans meals together.

Revision ID: 0010
Revises: 0009
Create Date: 2026-07-14
"""

import sqlalchemy as sa

from alembic import op

revision = "0010"
down_revision = "0009"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "meal_plan_entries",
        sa.Column(
            "list_id",
            sa.Uuid(),
            sa.ForeignKey("shopping_lists.id", ondelete="CASCADE"),
            nullable=True,
        ),
    )
    # A planner might never have opened the shopping list — give those users a default "Groceries"
    # list so every existing plan entry has one to attach to.
    op.execute(
        """
        INSERT INTO shopping_lists (id, user_id, name, created_at)
        SELECT gen_random_uuid(), m.user_id, 'Groceries', now()
        FROM (SELECT DISTINCT user_id FROM meal_plan_entries) m
        WHERE NOT EXISTS (SELECT 1 FROM shopping_lists s WHERE s.user_id = m.user_id)
        """
    )
    # Backfill each entry to its user's oldest (default) list.
    op.execute(
        """
        UPDATE meal_plan_entries m
        SET list_id = (
            SELECT s.id FROM shopping_lists s
            WHERE s.user_id = m.user_id
            ORDER BY s.created_at ASC, s.id ASC
            LIMIT 1
        )
        """
    )
    op.alter_column("meal_plan_entries", "list_id", nullable=False)
    op.create_index("ix_meal_plan_entries_list_id", "meal_plan_entries", ["list_id"])


def downgrade() -> None:
    op.drop_index("ix_meal_plan_entries_list_id", table_name="meal_plan_entries")
    op.drop_column("meal_plan_entries", "list_id")
