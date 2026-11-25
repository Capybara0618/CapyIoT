"""Persist decision identity without storing any authentication token."""

import sqlalchemy as sa
from alembic import op

revision = "0004_confirmation_request"
down_revision = "0003_draft_dispatch"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("confirmation_record", sa.Column("request_hash", sa.String(64)))
    op.add_column("confirmation_record", sa.Column("reason", sa.String(1000)))


def downgrade():
    raise RuntimeError("Agent history migrations are forward-only")
