"""household_members.status (pending invites)

Adds a ``status`` ("active" | "pending") to household_members so an invite doesn't share the
cookbook + lists until the invitee accepts. Existing rows default to "active" (already-sharing
members stay sharing).

Revision ID: 0015
Revises: 0014
Create Date: 2026-07-16
"""

import sqlalchemy as sa
from alembic import op

revision = "0015"
down_revision = "0014"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "household_members",
        sa.Column("status", sa.String(length=10), nullable=False, server_default="active"),
    )


def downgrade() -> None:
    op.drop_column("household_members", "status")
