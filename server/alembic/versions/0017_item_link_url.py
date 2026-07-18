"""First-class link items: a product-page URL on a shopping-list item.

A pasted store link (Meijer, Walmart, …) is split out of the typed text at add time; the name
stays a clean human title and the raw URL lands here. Text, not a sized String — a truncated
URL is broken, and an oversize URL hitting a sized column would recreate the exact 500 this
round fixes (the pydantic cap is the sanity bound).

Revision ID: 0017
Revises: 0016
Create Date: 2026-07-18
"""

import sqlalchemy as sa

from alembic import op

revision = "0017"
down_revision = "0016"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("shopping_list_items", sa.Column("link_url", sa.Text(), nullable=True))


def downgrade() -> None:
    op.drop_column("shopping_list_items", "link_url")
