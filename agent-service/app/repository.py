import hashlib
import json
import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError

from app.errors import ServiceError
from app.models import (
    AgentRun,
    AgentStep,
    Conversation,
    Draft,
    DraftRevision,
    Evidence,
    Execution,
    Message,
    ToolCall,
)

ACTIVE_RUNS = {"CREATED", "PLANNING", "RUNNING"}


def now():
    return datetime.now(UTC).replace(tzinfo=None)


def fingerprint(value):
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    ).hexdigest()


class Repository:
    def __init__(self, sessions):
        self.sessions = sessions

    @staticmethod
    def owned(session, model, owner, identity, lock=False):
        primary = next(iter(model.__table__.primary_key.columns))
        query = select(model).where(primary == identity, model.owner_user_id == owner)
        if lock:
            query = query.with_for_update().execution_options(populate_existing=True)
        row = session.scalar(query)
        if row is None:
            raise ServiceError("NOT_FOUND", 404, "资源不存在或不可访问")
        return row

    def create_conversation(self, owner, title):
        with self.sessions.begin() as session:
            item = Conversation(conversation_id=str(uuid.uuid4()), owner_user_id=owner, title=title)
            session.add(item)
            session.flush()
            return self.conversation_view(item)

    @staticmethod
    def conversation_view(row):
        return {
            "conversationId": row.conversation_id,
            "title": row.title,
            "status": row.status,
            "createdAt": row.created_at,
            "lastMessageAt": row.last_message_at,
            "version": row.version,
        }

    def conversations(self, owner, page=1, size=20):
        with self.sessions() as session:
            total = session.scalar(
                select(func.count())
                .select_from(Conversation)
                .where(Conversation.owner_user_id == owner)
            )
            rows = session.scalars(
                select(Conversation)
                .where(Conversation.owner_user_id == owner)
                .order_by(
                    func.coalesce(Conversation.last_message_at, Conversation.created_at).desc(),
                    Conversation.conversation_id,
                )
                .offset((page - 1) * size)
                .limit(size)
            )
            return {
                "items": [self.conversation_view(row) for row in rows],
                "total": total,
                "page": page,
                "size": size,
            }

    def history(self, owner, identity, after=0, size=20, before=None, latest=False):
        with self.sessions() as session:
            conversation = self.owned(session, Conversation, owner, identity)
            backwards = latest or before is not None
            query = select(Message).where(Message.conversation_id == identity)
            if before is not None:
                query = query.where(Message.message_seq < before)
            elif not latest:
                query = query.where(Message.message_seq > after)
            query = query.order_by(Message.message_seq.desc() if backwards else Message.message_seq)
            rows = session.scalars(query.limit(size + 1)).all()
            more = len(rows) > size
            rows = rows[:size]
            if backwards:
                rows.reverse()
            first, last = (rows[0].message_seq, rows[-1].message_seq) if rows else (None, None)
            return {
                **self.conversation_view(conversation),
                "hasMoreBefore": more if backwards else bool(first and first > 1),
                "hasMoreAfter": bool(
                    last
                    and session.scalar(
                        select(Message.message_id)
                        .where(Message.conversation_id == identity, Message.message_seq > last)
                        .limit(1)
                    )
                )
                if backwards
                else more,
                "firstSequence": first,
                "lastSequence": last,
                "messages": [
                    {
                        "messageId": row.message_id,
                        "sequence": row.message_seq,
                        "role": row.role,
                        "content": row.content if row.role == "USER" else None,
                        "contentStatus": row.content_status
                        if row.role == "USER"
                        else "REDACTED_BY_CURRENT_AUTHORIZATION",
                        "createdAt": row.created_at,
                        "runId": row.run_id,
                    }
                    for row in rows
                ],
            }

    def start_run(self, owner, conversation_id, content, key, correlation):
        request_hash = fingerprint({"conversationId": conversation_id, "content": content})
        try:
            with self.sessions.begin() as session:
                conversation = self.owned(session, Conversation, owner, conversation_id, lock=True)
                old = session.scalar(
                    select(AgentRun).where(
                        AgentRun.owner_user_id == owner, AgentRun.idempotency_key == key
                    )
                )
                if old:
                    if old.request_hash != request_hash:
                        raise ServiceError("IDEMPOTENCY_KEY_REUSED", 409, "幂等键已用于不同请求")
                    return old.run_id, False
                if conversation.status != "ACTIVE":
                    raise ServiceError("CONVERSATION_ARCHIVED", 409, "归档对话不可写入")
                for active in session.scalars(
                    select(AgentRun).where(
                        AgentRun.conversation_id == conversation_id,
                        AgentRun.status.in_(ACTIVE_RUNS),
                    )
                ):
                    if active.lease_until and active.lease_until < now():
                        active.status, active.failure_code, active.finished_at = (
                            "TIMED_OUT",
                            "RUN_INTERRUPTED",
                            now(),
                        )
                        active.version += 1
                    else:
                        raise ServiceError(
                            "RUN_ALREADY_IN_PROGRESS", 409, "该对话已有运行中的请求，请稍后查询"
                        )
                sequence = (
                    session.scalar(
                        select(func.max(Message.message_seq)).where(
                            Message.conversation_id == conversation_id
                        )
                    )
                    or 0
                )
                message_id, run_id = str(uuid.uuid4()), str(uuid.uuid4())
                session.add(
                    Message(
                        message_id=message_id,
                        conversation_id=conversation_id,
                        message_seq=sequence + 1,
                        role="USER",
                        content=content,
                        run_id=run_id,
                    )
                )
                session.flush()
                session.add(
                    AgentRun(
                        run_id=run_id,
                        conversation_id=conversation_id,
                        request_message_id=message_id,
                        owner_user_id=owner,
                        idempotency_key=key,
                        request_hash=request_hash,
                        correlation_id=correlation,
                        lease_until=now() + timedelta(seconds=240),
                    )
                )
                conversation.last_message_at = now()
                conversation.version += 1
                return run_id, True
        except IntegrityError:
            # An owner-scoped key may race across two different conversations. Re-read after rollback.
            with self.sessions() as session:
                old = session.scalar(
                    select(AgentRun).where(
                        AgentRun.owner_user_id == owner, AgentRun.idempotency_key == key
                    )
                )
                if old and old.request_hash == request_hash:
                    return old.run_id, False
            raise ServiceError("IDEMPOTENCY_KEY_REUSED", 409, "幂等键已用于不同请求") from None

    def save_draft(self, owner, run_id, payload, evidence_ids, park_id, expires_at):
        """Persistence foundation only; no confirmation, dispatch or business write in phase 2."""
        with self.sessions.begin() as session:
            run = self.owned(session, AgentRun, owner, run_id, lock=True)
            conversation = self.owned(session, Conversation, owner, run.conversation_id)
            if conversation.status != "ACTIVE" or run.status in {
                "CANCELLED",
                "FAILED",
                "TIMED_OUT",
            }:
                raise ServiceError("INVALID_STATE", 409, "当前运行不可生成草稿")
            identity, digest = str(uuid.uuid4()), fingerprint(payload)
            row = Draft(
                draft_id=identity,
                run_id=run_id,
                conversation_id=run.conversation_id,
                owner_user_id=owner,
                payload_json=payload,
                related_evidence_ids_json=evidence_ids,
                target_park_id=park_id,
                content_hash=digest,
                expires_at=expires_at,
            )
            session.add(row)
            session.flush()
            session.add(
                DraftRevision(
                    draft_id=identity,
                    revision=1,
                    content_json=payload,
                    content_hash=digest,
                    created_by_run_id=run_id,
                )
            )
            return identity

    def draft(self, owner, identity):
        with self.sessions() as session:
            row = self.owned(session, Draft, owner, identity)
            return {
                "draftId": row.draft_id,
                "revision": row.revision,
                "contentHash": row.content_hash,
                "status": row.status,
                "payload": row.payload_json,
                "expiresAt": row.expires_at,
            }

    def begin_run(self, owner, identity, duration):
        with self.sessions.begin() as session:
            run = self.owned(session, AgentRun, owner, identity, lock=True)
            if run.status != "CREATED":
                raise ServiceError("INVALID_STATE", 409, "运行不可重复执行")
            run.status, run.lease_until = "RUNNING", now() + timedelta(seconds=duration + 15)
            run.current_step_no, run.version = 1, run.version + 1
            session.add(
                AgentStep(
                    step_id=str(uuid.uuid4()),
                    run_id=identity,
                    sequence_no=1,
                    step_type="QUERY_ANALYSIS",
                    status="STARTED",
                    summary="查询当前授权范围内的业务证据",
                )
            )

    def run_view(self, owner, identity):
        with self.sessions.begin() as session:
            run = self.owned(session, AgentRun, owner, identity, lock=True)
            if run.status in ACTIVE_RUNS and run.lease_until and run.lease_until < now():
                run.status, run.failure_code, run.finished_at = (
                    "TIMED_OUT",
                    "RUN_INTERRUPTED",
                    now(),
                )
                run.version += 1
            answer = session.get(Message, run.answer_message_id) if run.answer_message_id else None
            draft = session.scalar(select(Draft).where(Draft.run_id == identity))
            execution = (
                session.scalar(select(Execution).where(Execution.draft_id == draft.draft_id))
                if draft
                else None
            )
            return {
                "runId": identity,
                "requestKey": run.idempotency_key,
                "conversationId": run.conversation_id,
                "status": run.status,
                "startedAt": run.started_at,
                "finishedAt": run.finished_at,
                "answerMessageId": run.answer_message_id,
                "answer": json.loads(answer.content) if answer else None,
                "toolCallCount": run.tool_call_count,
                "draftId": draft.draft_id if draft else None,
                "executionStatus": execution.status if execution else "NOT_EXECUTED",
                "errorCode": run.failure_code,
                "version": run.version,
            }

    def finish_run(self, owner, identity, status, answer=None, error_code=None):
        with self.sessions.begin() as session:
            original = self.owned(session, AgentRun, owner, identity)
            conversation = self.owned(
                session, Conversation, owner, original.conversation_id, lock=True
            )
            run = self.owned(session, AgentRun, owner, identity, lock=True)
            if run.status not in ACTIVE_RUNS:
                return run.answer_message_id
            if status not in {"SUCCEEDED", "PARTIAL_SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"}:
                raise ValueError("Invalid terminal status")
            if answer is not None:
                sequence = (
                    session.scalar(
                        select(func.max(Message.message_seq)).where(
                            Message.conversation_id == run.conversation_id
                        )
                    )
                    or 0
                )
                message = Message(
                    message_id=str(uuid.uuid4()),
                    conversation_id=run.conversation_id,
                    message_seq=sequence + 1,
                    role="ASSISTANT",
                    content=json.dumps(answer, ensure_ascii=False),
                    content_type="STRUCTURED_ANSWER",
                    run_id=identity,
                )
                session.add(message)
                run.answer_message_id = message.message_id
                conversation.last_message_at, conversation.version = now(), conversation.version + 1
            if status in {"SUCCEEDED", "PARTIAL_SUCCEEDED"} and session.scalar(
                select(Draft).where(
                    Draft.run_id == identity, Draft.status == "PENDING_CONFIRMATION"
                )
            ):
                status = "WAITING_CONFIRMATION"
            run.status, run.failure_code, run.finished_at = status, error_code, now()
            run.version += 1
            for step in session.scalars(
                select(AgentStep).where(AgentStep.run_id == identity, AgentStep.status == "STARTED")
            ):
                step.status, step.finished_at, step.error_code = status, now(), error_code
            for tool in session.scalars(
                select(ToolCall).where(ToolCall.run_id == identity, ToolCall.status == "STARTED")
            ):
                tool.status, tool.finished_at, tool.error_code = (
                    "FAILED",
                    now(),
                    error_code or "RUN_TERMINATED",
                )
            return run.answer_message_id

    def start_tool(self, owner, run_id, name, arguments, budget):
        with self.sessions.begin() as session:
            run = self.owned(session, AgentRun, owner, run_id, lock=True)
            if run.status != "RUNNING":
                raise ServiceError("RUN_CANCELLED", 409, "运行已停止")
            if run.tool_call_count >= budget:
                raise ServiceError("RUN_BUDGET_EXCEEDED", 409, "工具调用已达到上限")
            run.tool_call_count += 1
            identity = str(uuid.uuid4())
            session.add(
                ToolCall(
                    tool_call_id=identity,
                    run_id=run_id,
                    sequence_no=run.tool_call_count,
                    tool_name=name,
                    input_summary=json.dumps(arguments, ensure_ascii=False, separators=(",", ":")),
                    input_hash=fingerprint(arguments),
                    authorization_decision="PENDING",
                    status="STARTED",
                )
            )
            return identity

    def finish_tool(
        self, owner, run_id, call_id, result, provenance=None, result_hash=None, latency=0
    ):
        with self.sessions.begin() as session:
            self.owned(session, AgentRun, owner, run_id, lock=True)
            call = session.get(ToolCall, call_id)
            if call is None or call.run_id != run_id:
                raise ServiceError("NOT_FOUND", 404, "工具记录不存在")
            if call.status != "STARTED":
                return
            call.status, call.finished_at, call.latency_ms = result.status, now(), latency
            call.error_code = result.errorCode
            call.authorization_decision = (
                "DENIED" if result.authorization == "DENIED" else "CURRENT_USER_SCOPE"
            )
            call.result_summary = f"status={result.status}; completeness={result.completeness}; truncated={result.truncated}"
            for reference in result.evidenceReferences:
                session.add(
                    Evidence(
                        evidence_id=reference.evidenceId,
                        run_id=run_id,
                        tool_call_id=call_id,
                        source_context=reference.sourceContext,
                        source_type="TOOL_RESULT",
                        source_id=call_id,
                        source_version=reference.sourceVersion,
                        source_mode="CORE_API",
                        relation="FACT",
                        evidence_summary=reference.summary,
                        freshness_status=reference.freshnessStatus,
                        query_json=provenance,
                        result_hash=result_hash,
                    )
                )

    def evidence(self, owner, run_id):
        with self.sessions() as session:
            self.owned(session, AgentRun, owner, run_id)
            return [
                {
                    "evidenceId": row.evidence_id,
                    "toolName": row.query_json.get("tool") if row.query_json else None,
                    "arguments": row.query_json.get("arguments") if row.query_json else None,
                    "authorizationVersion": row.query_json.get("authorizationVersion")
                    if row.query_json
                    else None,
                    "resultHash": row.result_hash,
                    "sourceObjects": row.query_json.get("sourceObjects", [])
                    if row.query_json
                    else [],
                    "retrievedAt": row.retrieved_at.replace(tzinfo=UTC).isoformat(),
                    "sourceContext": row.source_context,
                    "sourceId": row.source_id,
                    "sourceVersion": row.source_version,
                    "freshnessStatus": row.freshness_status,
                    "summary": row.evidence_summary,
                }
                for row in session.scalars(
                    select(Evidence).where(Evidence.run_id == run_id).order_by(Evidence.evidence_id)
                )
            ]

    def recent_messages(self, owner, conversation_id, limit):
        with self.sessions() as session:
            self.owned(session, Conversation, owner, conversation_id)
            rows = (
                list(
                    session.scalars(
                        select(Message)
                        .where(Message.conversation_id == conversation_id)
                        .order_by(Message.message_seq.desc())
                        .limit(limit)
                    )
                )
                if limit
                else []
            )
            return [
                {"role": row.role, "content": row.content, "runId": row.run_id}
                for row in reversed(rows)
            ]
