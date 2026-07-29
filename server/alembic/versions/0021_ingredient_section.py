"""Recipe ingredients carry the recipe's own section heading (v0.9).

A recipe is written in groups — "Steak Marinade:", "Fajitas:" — and step 1 ("combine the
ingredients for the marinade") is meaningless without them. Cookbook had nowhere to put a
grouping, so the detail screen grouped by store aisle instead, which is a *buy-list* concept:
it put ground cumin under Meat & Seafood and left "the marinade" undefined.

`section` is presentation only. Store-aisle routing stays in `category`; nothing about merging,
categorizing or shopping reads this column. Sized rather than Text — a heading is a short label,
and importers cap it.

Revision ID: 0021
Revises: 0020
Create Date: 2026-07-29
"""

import sqlalchemy as sa

from alembic import op

revision = "0021"
down_revision = "0020"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("recipe_ingredients", sa.Column("section", sa.String(80), nullable=True))


def downgrade() -> None:
    op.drop_column("recipe_ingredients", "section")
