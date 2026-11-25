"""Only Agent-owned data. Business source IDs never reference another schema."""
from datetime import datetime

from sqlalchemy import JSON, BigInteger, CheckConstraint, ForeignKey, Index, String, Text, UniqueConstraint, text
from sqlalchemy.dialects.mysql import DATETIME
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


def stamp():
    return mapped_column(DATETIME(fsp=3), server_default=text("CURRENT_TIMESTAMP(3)"))


class Conversation(Base):
    __tablename__ = "conversation"
    conversation_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    owner_user_id: Mapped[str] = mapped_column(String(64), index=True)
    title: Mapped[str] = mapped_column(String(200))
    status: Mapped[str] = mapped_column(String(16), default="ACTIVE")
    created_at: Mapped[datetime] = stamp()
    last_message_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=3))
    archived_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=3))
    version: Mapped[int] = mapped_column(BigInteger, default=0)
    __table_args__ = (CheckConstraint("status IN ('ACTIVE','ARCHIVED')", name="ck_conversation_status"),)


class Message(Base):
    __tablename__ = "conversation_message"
    message_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    conversation_id: Mapped[str] = mapped_column(ForeignKey("conversation.conversation_id"))
    message_seq: Mapped[int] = mapped_column(BigInteger)
    role: Mapped[str] = mapped_column(String(16))
    content: Mapped[str] = mapped_column(Text)
    content_type: Mapped[str] = mapped_column(String(32), default="TEXT")
    content_status: Mapped[str] = mapped_column(String(40), default="AVAILABLE")
    run_id: Mapped[str | None] = mapped_column(String(64), index=True)
    created_at: Mapped[datetime] = stamp()
    __table_args__ = (UniqueConstraint("conversation_id", "message_seq"), CheckConstraint("role IN ('USER','ASSISTANT')"))


class AgentRun(Base):
    __tablename__ = "agent_run"
    run_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    conversation_id: Mapped[str] = mapped_column(ForeignKey("conversation.conversation_id"))
    request_message_id: Mapped[str] = mapped_column(ForeignKey("conversation_message.message_id"), unique=True)
    owner_user_id: Mapped[str] = mapped_column(String(64))
    intent_type: Mapped[str] = mapped_column(String(32), default="USER_REQUEST")
    status: Mapped[str] = mapped_column(String(32), default="CREATED")
    idempotency_key: Mapped[str] = mapped_column(String(128))
    request_hash: Mapped[str] = mapped_column(String(64))
    started_at: Mapped[datetime] = stamp()
    finished_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=3))
    current_step_no: Mapped[int] = mapped_column(BigInteger, default=0)
    tool_call_count: Mapped[int] = mapped_column(BigInteger, default=0)
    correlation_id: Mapped[str] = mapped_column(String(64))
    failure_code: Mapped[str | None] = mapped_column(String(64))
    answer_message_id: Mapped[str | None] = mapped_column(String(64))
    version: Mapped[int] = mapped_column(BigInteger, default=0)
    __table_args__ = (UniqueConstraint("owner_user_id", "idempotency_key"),
        CheckConstraint("status IN ('CREATED','PLANNING','RUNNING','WAITING_CONFIRMATION','SUCCEEDED','PARTIAL_SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')"))


class AgentStep(Base):
    __tablename__ = "agent_step"
    step_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    run_id: Mapped[str] = mapped_column(ForeignKey("agent_run.run_id"))
    sequence_no: Mapped[int] = mapped_column(BigInteger)
    step_type: Mapped[str] = mapped_column(String(32))
    status: Mapped[str] = mapped_column(String(32))
    summary: Mapped[str] = mapped_column(String(1000))
    started_at: Mapped[datetime] = stamp()
    finished_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=3))
    error_code: Mapped[str | None] = mapped_column(String(64))
    latency_ms: Mapped[int | None] = mapped_column(BigInteger)
    __table_args__ = (UniqueConstraint("run_id", "sequence_no"),)


class ToolCall(Base):
    __tablename__ = "tool_call_record"
    tool_call_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    run_id: Mapped[str] = mapped_column(ForeignKey("agent_run.run_id"))
    sequence_no: Mapped[int] = mapped_column(BigInteger)
    tool_name: Mapped[str] = mapped_column(String(64))
    schema_version: Mapped[int] = mapped_column(default=1)
    input_summary: Mapped[str] = mapped_column(String(1000))
    input_hash: Mapped[str] = mapped_column(String(64))
    authorization_decision: Mapped[str] = mapped_column(String(32))
    status: Mapped[str] = mapped_column(String(32))
    result_summary: Mapped[str | None] = mapped_column(String(1000))
    result_ref: Mapped[str | None] = mapped_column(String(128))
    error_code: Mapped[str | None] = mapped_column(String(64))
    retry_count: Mapped[int] = mapped_column(default=0)
    latency_ms: Mapped[int | None] = mapped_column(BigInteger)
    started_at: Mapped[datetime] = stamp()
    finished_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=3))
    __table_args__ = (UniqueConstraint("run_id", "sequence_no"), CheckConstraint("status IN ('STARTED','SUCCEEDED','EMPTY','REJECTED','FAILED','TIMED_OUT')"))


class Evidence(Base):
    __tablename__ = "evidence_reference"
    evidence_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    run_id: Mapped[str] = mapped_column(ForeignKey("agent_run.run_id"))
    tool_call_id: Mapped[str] = mapped_column(ForeignKey("tool_call_record.tool_call_id"))
    claim_id: Mapped[str | None] = mapped_column(String(64))
    source_context: Mapped[str] = mapped_column(String(32))
    source_type: Mapped[str] = mapped_column(String(32))
    source_id: Mapped[str] = mapped_column(String(64))
    source_version: Mapped[int | None] = mapped_column(BigInteger)
    source_event_sequence: Mapped[int | None] = mapped_column(BigInteger)
    occurred_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=3))
    retrieved_at: Mapped[datetime] = stamp()
    source_mode: Mapped[str] = mapped_column(String(32))
    relation: Mapped[str] = mapped_column(String(32))
    evidence_summary: Mapped[str] = mapped_column(String(2000))
    freshness_status: Mapped[str] = mapped_column(String(32))
    source_link: Mapped[str | None] = mapped_column(String(256))


class Draft(Base):
    __tablename__ = "operation_draft"
    draft_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    run_id: Mapped[str] = mapped_column(ForeignKey("agent_run.run_id"))
    conversation_id: Mapped[str] = mapped_column(ForeignKey("conversation.conversation_id"))
    owner_user_id: Mapped[str] = mapped_column(String(64), index=True)
    action_type: Mapped[str] = mapped_column(String(32), default="WORK_ORDER")
    source_type: Mapped[str] = mapped_column(String(32), default="USER_REQUEST")
    payload_json: Mapped[dict] = mapped_column(JSON)
    related_evidence_ids_json: Mapped[list] = mapped_column(JSON)
    target_park_id: Mapped[str] = mapped_column(String(64))
    revision: Mapped[int] = mapped_column(default=1)
    content_hash: Mapped[str] = mapped_column(String(64))
    status: Mapped[str] = mapped_column(String(32), default="DRAFTED")
    presented_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=3))
    expires_at: Mapped[datetime] = mapped_column(DATETIME(fsp=3))
    confirmed_by: Mapped[str | None] = mapped_column(String(64))
    confirmed_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=3))
    result_business_object_type: Mapped[str | None] = mapped_column(String(32))
    result_business_object_id: Mapped[str | None] = mapped_column(String(64))
    version: Mapped[int] = mapped_column(BigInteger, default=0)
    __table_args__ = (CheckConstraint("action_type='WORK_ORDER' AND source_type='USER_REQUEST'"),
        CheckConstraint("status IN ('DRAFTED','PENDING_CONFIRMATION','CONFIRMED','EXECUTING','EXECUTED','REJECTED','INVALIDATED','EXPIRED','FAILED')"))


class DraftRevision(Base):
    __tablename__ = "operation_draft_revision"
    draft_id: Mapped[str] = mapped_column(ForeignKey("operation_draft.draft_id"), primary_key=True)
    revision: Mapped[int] = mapped_column(primary_key=True)
    content_json: Mapped[dict] = mapped_column(JSON)
    content_hash: Mapped[str] = mapped_column(String(64))
    created_at: Mapped[datetime] = stamp()
    created_by_run_id: Mapped[str] = mapped_column(ForeignKey("agent_run.run_id"))


class Confirmation(Base):
    __tablename__ = "confirmation_record"
    confirmation_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    draft_id: Mapped[str] = mapped_column(ForeignKey("operation_draft.draft_id"))
    draft_revision: Mapped[int] = mapped_column()
    content_hash: Mapped[str] = mapped_column(String(64))
    actor_user_id: Mapped[str] = mapped_column(String(64))
    action: Mapped[str] = mapped_column(String(16))
    confirmed_at: Mapped[datetime] = stamp()
    request_id: Mapped[str] = mapped_column(String(128))
    authorization_result: Mapped[str] = mapped_column(String(32))
    idempotency_key: Mapped[str] = mapped_column(String(128))
    __table_args__ = (UniqueConstraint("draft_id", "draft_revision", "action"),
        CheckConstraint("action IN ('CONFIRM','REJECT')"))


class Execution(Base):
    __tablename__ = "execution_record"
    execution_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    draft_id: Mapped[str] = mapped_column(ForeignKey("operation_draft.draft_id"))
    confirmation_id: Mapped[str] = mapped_column(ForeignKey("confirmation_record.confirmation_id"))
    target_command: Mapped[str] = mapped_column(String(64))
    status: Mapped[str] = mapped_column(String(32))
    idempotency_key: Mapped[str] = mapped_column(String(128), unique=True)
    request_hash: Mapped[str] = mapped_column(String(64))
    target_object_type: Mapped[str | None] = mapped_column(String(32))
    target_object_id: Mapped[str | None] = mapped_column(String(64))
    started_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=3))
    finished_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=3))
    error_code: Mapped[str | None] = mapped_column(String(64))
    error_summary: Mapped[str | None] = mapped_column(String(1000))
    attempt_count: Mapped[int] = mapped_column(default=0)
    version: Mapped[int] = mapped_column(BigInteger, default=0)
    __table_args__ = (CheckConstraint("status IN ('QUEUED','EXECUTING','SUCCEEDED','FAILED','RECONCILIATION_REQUIRED')"),)


class CommandOutbox(Base):
    __tablename__ = "agent_command_outbox"
    command_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    draft_id: Mapped[str] = mapped_column(ForeignKey("operation_draft.draft_id"))
    draft_revision: Mapped[int] = mapped_column()
    confirmation_id: Mapped[str] = mapped_column(ForeignKey("confirmation_record.confirmation_id"), unique=True)
    actor_user_id: Mapped[str] = mapped_column(String(64))
    command_type: Mapped[str] = mapped_column(String(32))
    idempotency_key: Mapped[str] = mapped_column(String(128), unique=True)
    request_hash: Mapped[str] = mapped_column(String(64))
    payload_json: Mapped[dict] = mapped_column(JSON)
    status: Mapped[str] = mapped_column(String(32), default="PENDING")
    attempt_count: Mapped[int] = mapped_column(default=0)
    next_retry_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=3))
    lease_until: Mapped[datetime | None] = mapped_column(DATETIME(fsp=3))
    created_at: Mapped[datetime] = stamp()
    last_error: Mapped[str | None] = mapped_column(String(1000))
    __table_args__ = (Index("ix_agent_dispatch", "status", "next_retry_at"), CheckConstraint("command_type='CREATE_WORK_ORDER'"),
        CheckConstraint("status IN ('PENDING','DISPATCHING','RETRY','SUCCEEDED','DEAD','RECONCILIATION_REQUIRED')"))
