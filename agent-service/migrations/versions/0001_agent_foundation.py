"""Immutable initial Agent schema; future model changes require a new revision."""

from pathlib import Path

from alembic import op

revision = "0001_agent_foundation"
down_revision = None
branch_labels = None
depends_on = None


def upgrade():
    ddl = Path(__file__).with_suffix(".sql").read_text(encoding="utf-8")
    for statement in ddl.split(";"):
        if statement.strip():
            op.execute(statement)


def downgrade():
    raise RuntimeError(
        "Agent history migrations are forward-only; restore a reviewed backup instead"
    )
