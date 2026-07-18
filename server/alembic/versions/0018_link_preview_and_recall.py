"""Link previews + "buy again" recall (v0.6).

A pasted product link now carries a thumbnail (`shopping_list_items.image_url`), and
`item_history` remembers the link + image for a typed item name so re-adding it by name
re-attaches both (`recall_link`) — without ever surfacing URL-derived product titles in
autocomplete.

Revision ID: 0018
Revises: 0017
Create Date: 2026-07-18
"""

import sqlalchemy as sa

from alembic import op

revision = "0018"
down_revision = "0017"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("shopping_list_items", sa.Column("image_url", sa.Text(), nullable=True))
    op.add_column("item_history", sa.Column("link_url", sa.Text(), nullable=True))
    op.add_column("item_history", sa.Column("image_url", sa.Text(), nullable=True))


def downgrade() -> None:
    op.drop_column("item_history", "image_url")
    op.drop_column("item_history", "link_url")
    op.drop_column("shopping_list_items", "image_url")
