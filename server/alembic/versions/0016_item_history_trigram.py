"""item_history fuzzy-suggestion support (trigram + levenshtein)

Enables typo-tolerant add-dialog autocomplete. ``suggest_items`` supplements its substring
match with two Postgres string-distance functions so a misspelling still surfaces the item:
  * ``pg_trgm.similarity()`` — near-spellings of longer words ("bananna" -> "banana"), backed
    by a GIN trigram index so the scan stays fast as history grows;
  * ``fuzzystrmatch.levenshtein()`` — short-word typos/transpositions that trigrams miss
    ("mlik" -> "milk" scores only 0.11 on trigram similarity but is edit-distance 2).

Both are *trusted* extensions (PG13+), so the app DB role can create them without superuser.
The downgrade drops only the index — the extensions are left in place since other things may
come to rely on them and dropping a shared extension is riskier than keeping it.

Revision ID: 0016
Revises: 0015
Create Date: 2026-07-17
"""

from alembic import op

revision = "0016"
down_revision = "0015"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")
    op.execute("CREATE EXTENSION IF NOT EXISTS fuzzystrmatch")
    op.execute(
        "CREATE INDEX IF NOT EXISTS ix_item_history_name_trgm "
        "ON item_history USING gin (name gin_trgm_ops)"
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS ix_item_history_name_trgm")
