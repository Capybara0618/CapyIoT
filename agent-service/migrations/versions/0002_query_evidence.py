"""Persist safe query provenance and bound read-only Run lifetimes."""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.mysql import DATETIME

revision = "0002_query_evidence"
down_revision = "0001_agent_foundation"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("agent_run", sa.Column("lease_until", DATETIME(fsp=3), nullable=True))
    op.add_column("evidence_reference", sa.Column("query_json", sa.JSON(), nullable=True))
    op.add_column("evidence_reference", sa.Column("result_hash", sa.String(64), nullable=True))


def downgrade():
    raise RuntimeError("Agent history migrations are forward-only")
