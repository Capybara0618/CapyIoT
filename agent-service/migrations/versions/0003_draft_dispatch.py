"""Bind drafts to model calls and fence dispatcher leases."""

import sqlalchemy as sa
from alembic import op

revision = "0003_draft_dispatch"
down_revision = "0002_query_evidence"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("operation_draft", sa.Column("origin_tool_call_id", sa.String(128)))
    op.create_unique_constraint("uq_draft_origin", "operation_draft", ["origin_tool_call_id"])
    op.add_column("operation_draft_revision", sa.Column("origin_tool_call_id", sa.String(128)))
    op.create_unique_constraint(
        "uq_revision_origin", "operation_draft_revision", ["origin_tool_call_id"]
    )
    op.add_column("agent_command_outbox", sa.Column("lease_token", sa.String(64)))
    op.add_column("execution_record", sa.Column("target_object_no", sa.String(64)))
    op.create_unique_constraint(
        "uq_confirmation_actor_key", "confirmation_record", ["actor_user_id", "idempotency_key"]
    )


def downgrade():
    raise RuntimeError("Agent history migrations are forward-only")
