import hashlib
import json
import uuid
from datetime import UTC, datetime

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError

from app.errors import ServiceError
from app.models import AgentRun, Conversation, Draft, DraftRevision, Message


def now():
    return datetime.now(UTC).replace(tzinfo=None)


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


class Repository:
    def __init__(self, sessions):
        self.sessions = sessions

    @staticmethod
    def owned(session, model, owner, identity, lock=False):
        primary = next(iter(model.__table__.primary_key.columns))
        query = select(model).where(primary == identity, model.owner_user_id == owner)
        if lock:
            query = query.with_for_update()
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
        return {"conversationId": row.conversation_id, "title": row.title, "status": row.status,
                "createdAt": row.created_at, "lastMessageAt": row.last_message_at, "version": row.version}

    def conversations(self, owner, page=1, size=20):
        with self.sessions() as session:
            total = session.scalar(select(func.count()).select_from(Conversation).where(Conversation.owner_user_id == owner))
            rows = session.scalars(select(Conversation).where(Conversation.owner_user_id == owner)
                                   .order_by(Conversation.created_at.desc(), Conversation.conversation_id)
                                   .offset((page-1)*size).limit(size))
            return {"items": [self.conversation_view(row) for row in rows], "total": total, "page": page, "size": size}

    def history(self, owner, identity, after=0, size=20):
        with self.sessions() as session:
            conversation = self.owned(session, Conversation, owner, identity)
            rows = session.scalars(select(Message).where(Message.conversation_id == identity, Message.message_seq > after)
                                   .order_by(Message.message_seq).limit(size)).all()
            return {**self.conversation_view(conversation), "messages": [{"messageId": row.message_id,
                "sequence": row.message_seq, "role": row.role,
                # Until phase 3 implements current evidence authorization, fail closed for stored answers.
                "content": row.content if row.role == "USER" else None,
                "contentStatus": row.content_status if row.role == "USER" else "REDACTED_BY_CURRENT_AUTHORIZATION",
                "createdAt": row.created_at, "runId": row.run_id} for row in rows]}

    def start_run(self, owner, conversation_id, content, key, correlation):
        request_hash = fingerprint({"conversationId": conversation_id, "content": content})
        try:
            with self.sessions.begin() as session:
                conversation = self.owned(session, Conversation, owner, conversation_id, lock=True)
                old = session.scalar(select(AgentRun).where(AgentRun.owner_user_id == owner, AgentRun.idempotency_key == key))
                if old:
                    if old.request_hash != request_hash:
                        raise ServiceError("IDEMPOTENCY_KEY_REUSED", 409, "幂等键已用于不同请求")
                    return old.run_id, False
                if conversation.status != "ACTIVE":
                    raise ServiceError("CONVERSATION_ARCHIVED", 409, "归档对话不可写入")
                sequence = session.scalar(select(func.max(Message.message_seq)).where(Message.conversation_id == conversation_id)) or 0
                message_id, run_id = str(uuid.uuid4()), str(uuid.uuid4())
                session.add(Message(message_id=message_id, conversation_id=conversation_id, message_seq=sequence+1,
                                    role="USER", content=content, run_id=run_id))
                session.flush()
                session.add(AgentRun(run_id=run_id, conversation_id=conversation_id, request_message_id=message_id,
                                    owner_user_id=owner, idempotency_key=key, request_hash=request_hash, correlation_id=correlation))
                conversation.last_message_at = now()
                conversation.version += 1
                return run_id, True
        except IntegrityError:
            # An owner-scoped key may race across two different conversations. Re-read after rollback.
            with self.sessions() as session:
                old = session.scalar(select(AgentRun).where(AgentRun.owner_user_id == owner, AgentRun.idempotency_key == key))
                if old and old.request_hash == request_hash:
                    return old.run_id, False
            raise ServiceError("IDEMPOTENCY_KEY_REUSED", 409, "幂等键已用于不同请求") from None

    def save_draft(self, owner, run_id, payload, evidence_ids, park_id, expires_at):
        """Persistence foundation only; no confirmation, dispatch or business write in phase 2."""
        with self.sessions.begin() as session:
            run = self.owned(session, AgentRun, owner, run_id, lock=True)
            conversation = self.owned(session, Conversation, owner, run.conversation_id)
            if conversation.status != "ACTIVE" or run.status in {"CANCELLED", "FAILED", "TIMED_OUT"}:
                raise ServiceError("INVALID_STATE", 409, "当前运行不可生成草稿")
            identity, digest = str(uuid.uuid4()), fingerprint(payload)
            row = Draft(draft_id=identity, run_id=run_id, conversation_id=run.conversation_id, owner_user_id=owner,
                        payload_json=payload, related_evidence_ids_json=evidence_ids, target_park_id=park_id,
                        content_hash=digest, expires_at=expires_at)
            session.add(row)
            session.flush()
            session.add(DraftRevision(draft_id=identity, revision=1, content_json=payload,
                                      content_hash=digest, created_by_run_id=run_id))
            return identity

    def draft(self, owner, identity):
        with self.sessions() as session:
            row = self.owned(session, Draft, owner, identity)
            return {"draftId": row.draft_id, "revision": row.revision, "contentHash": row.content_hash,
                    "status": row.status, "payload": row.payload_json, "expiresAt": row.expires_at}
