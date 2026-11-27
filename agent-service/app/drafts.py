"""Agent-owned proposals. Formal business state is always validated by Java."""

import uuid
from datetime import timedelta

from pydantic import ValidationError
from pydantic_ai import Tool
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from starlette.concurrency import run_in_threadpool

from app.draft_schemas import PrepareWorkOrder
from app.errors import ServiceError
from app.models import AgentRun, CommandOutbox, Confirmation, Draft, DraftRevision, Execution
from app.repository import Repository, fingerprint, now
from app.tool_schemas import ToolResult


class DraftRepository(Repository):
    def invalidate(self, owner, identity, revision, digest):
        with self.sessions.begin() as session:
            row = self.owned(session, Draft, owner, identity, lock=True)
            if (
                row.status == "PENDING_CONFIRMATION"
                and row.revision == revision
                and row.content_hash == digest
            ):
                row.status, row.version = "INVALIDATED", row.version + 1
                self.end_waiting(session, row)

    def decide(self, owner, identity, revision, digest, action, key, correlation, reason=None):
        try:
            return self._decide(owner, identity, revision, digest, action, key, correlation, reason)
        except IntegrityError:
            raise ServiceError("IDEMPOTENCY_KEY_REUSED", 409, "幂等键已用于其他确认请求") from None

    def _decide(self, owner, identity, revision, digest, action, key, correlation, reason=None):
        error = None
        with self.sessions.begin() as session:
            row = self.owned(session, Draft, owner, identity, lock=True)
            if row.revision != revision or row.content_hash != digest:
                raise ServiceError("DRAFT_REVISION_MISMATCH", 409, "草稿版本已变化，请重新审阅")
            old_key = session.scalar(
                select(Confirmation).where(
                    Confirmation.actor_user_id == owner, Confirmation.idempotency_key == key
                )
            )
            request_hash = fingerprint(
                {
                    "draftId": identity,
                    "revision": revision,
                    "contentHash": digest,
                    "action": action,
                    "reason": reason,
                }
            )
            if old_key and old_key.request_hash != request_hash:
                raise ServiceError("IDEMPOTENCY_KEY_REUSED", 409, "幂等键已用于其他确认请求")
            old = session.scalar(
                select(Confirmation).where(
                    Confirmation.draft_id == identity, Confirmation.draft_revision == revision
                )
            )
            if old:
                if old.action != action or old.reason != reason:
                    raise ServiceError("INVALID_STATE_TRANSITION", 409, "草稿已经确认或拒绝")
                return self.draft_view(session, row)
            if row.status == "EXPIRED" or row.expires_at <= now():
                row.status, row.version = "EXPIRED", row.version + 1
                self.end_waiting(session, row)
                error = ServiceError("DRAFT_EXPIRED", 410, "草稿已过期，请重新生成")
            elif row.status != "PENDING_CONFIRMATION":
                raise ServiceError("INVALID_STATE_TRANSITION", 409, "草稿当前不可确认或拒绝")
            else:
                confirmation_id = str(uuid.uuid4())
                session.add(
                    Confirmation(
                        confirmation_id=confirmation_id,
                        draft_id=identity,
                        draft_revision=revision,
                        content_hash=digest,
                        actor_user_id=owner,
                        action=action,
                        request_id=correlation,
                        authorization_result="ALLOWED",
                        idempotency_key=key,
                        request_hash=request_hash,
                        reason=reason,
                    )
                )
                session.flush()
                row.version += 1
                if action == "REJECT":
                    row.status = "REJECTED"
                    self.end_waiting(session, row)
                else:
                    row.status, row.confirmed_by, row.confirmed_at = "CONFIRMED", owner, now()
                    # A server-owned command key is reused for every dispatch/reconciliation attempt.
                    command_key = "agent-" + confirmation_id
                    command_hash = fingerprint(row.payload_json)
                    session.add(
                        Execution(
                            execution_id=str(uuid.uuid4()),
                            draft_id=identity,
                            confirmation_id=confirmation_id,
                            target_command="CREATE_WORK_ORDER",
                            status="QUEUED",
                            idempotency_key=command_key,
                            request_hash=command_hash,
                        )
                    )
                    session.add(
                        CommandOutbox(
                            command_id=str(uuid.uuid4()),
                            draft_id=identity,
                            draft_revision=revision,
                            confirmation_id=confirmation_id,
                            actor_user_id=owner,
                            command_type="CREATE_WORK_ORDER",
                            idempotency_key=command_key,
                            request_hash=command_hash,
                            payload_json=row.payload_json,
                            status="PENDING",
                        )
                    )
                    session.flush()
            result = self.draft_view(session, row)
        if error:
            raise error
        return result

    def propose(self, owner, run_id, origin, payload, evidence_ids, park_id, lifetime):
        with self.sessions.begin() as session:
            run = self.owned(session, AgentRun, owner, run_id, lock=True)
            if run.status != "RUNNING":
                raise ServiceError("RUN_CANCELLED", 409, "运行已停止")
            old_revision = session.scalar(
                select(DraftRevision).where(DraftRevision.origin_tool_call_id == origin)
            )
            previous = session.get(Draft, old_revision.draft_id) if old_revision else None
            digest = fingerprint({"payload": payload, "evidenceReferenceIds": evidence_ids})
            if previous:
                if (
                    previous.owner_user_id != owner
                    or old_revision.content_hash != digest
                    or previous.revision != old_revision.revision
                ):
                    raise ServiceError("IDEMPOTENCY_KEY_REUSED", 409, "草稿工具调用内容冲突")
                return self.draft_view(session, previous)
            # A material edit within the same Run is a new immutable revision, never an overwrite.
            row = session.scalar(select(Draft).where(Draft.run_id == run_id).with_for_update())
            if row and row.status != "PENDING_CONFIRMATION":
                raise ServiceError("INVALID_STATE_TRANSITION", 409, "已处理的草稿不可修改")
            if row is None:
                row = Draft(
                    draft_id=str(uuid.uuid4()),
                    run_id=run_id,
                    conversation_id=run.conversation_id,
                    owner_user_id=owner,
                    revision=1,
                    version=0,
                )
                session.add(row)
            else:
                row.revision += 1
                row.version += 1
            row.origin_tool_call_id = origin
            row.payload_json, row.related_evidence_ids_json = payload, evidence_ids
            row.content_hash, row.target_park_id = digest, park_id
            row.status, row.presented_at = "PENDING_CONFIRMATION", now()
            row.expires_at = now() + timedelta(seconds=lifetime)
            session.flush()
            session.add(
                DraftRevision(
                    draft_id=row.draft_id,
                    revision=row.revision,
                    content_json={"payload": payload, "evidenceReferenceIds": evidence_ids},
                    content_hash=digest,
                    created_by_run_id=run_id,
                    origin_tool_call_id=origin,
                )
            )
            return self.draft_view(session, row)

    @staticmethod
    def draft_view(session, row):
        execution = session.scalar(select(Execution).where(Execution.draft_id == row.draft_id))
        return {
            "draftId": row.draft_id,
            "runId": row.run_id,
            "revision": row.revision,
            "contentHash": row.content_hash,
            "status": row.status,
            "sourceType": row.source_type,
            "actionType": row.action_type,
            "payload": row.payload_json,
            "relatedEvidenceReferences": row.related_evidence_ids_json,
            "summary": row.payload_json.get("title", "维修工单草稿"),
            "expiresAt": row.expires_at,
            "confirmedAt": row.confirmed_at,
            "execution": None
            if execution is None
            else {
                "executionId": execution.execution_id,
                "status": execution.status,
                "workOrderId": execution.target_object_id,
                "workOrderNo": execution.target_object_no,
                "errorCode": execution.error_code,
                "updatedAt": execution.finished_at or execution.started_at or row.confirmed_at,
            },
        }

    def draft(self, owner, identity):
        with self.sessions.begin() as session:
            row = self.owned(session, Draft, owner, identity, lock=True)
            if row.status == "PENDING_CONFIRMATION" and row.expires_at <= now():
                row.status, row.version = "EXPIRED", row.version + 1
                self.end_waiting(session, row)
            return self.draft_view(session, row)

    @staticmethod
    def end_waiting(session, draft):
        run = session.get(AgentRun, draft.run_id)
        if run.status == "WAITING_CONFIRMATION":
            run.status, run.finished_at, run.version = "SUCCEEDED", now(), run.version + 1


class DraftService:
    def __init__(self, settings, repository, tools, business):
        self.settings, self.repository, self.tools, self.business = (
            settings,
            repository,
            tools,
            business,
        )

    async def validate(self, principal, payload, evidence_ids, run_id, correlation):
        principal.require("agent.draft.prepare")
        principal.require("workorder.create")
        rows = await run_in_threadpool(self.repository.evidence, principal.user_id, run_id)
        selected = [row for row in rows if row["evidenceId"] in evidence_ids]
        if len(selected) != len(evidence_ids) or (
            selected
            and not await self.tools.revalidate(selected, principal, self.business, correlation)
        ):
            raise ServiceError(
                "DRAFT_EVIDENCE_INVALID", 409, "草稿证据已变化或不可访问，请重新查询"
            )
        return await self.business.validate_work_order(principal, correlation, payload)

    async def visible(self, principal, draft_id, correlation):
        view = await run_in_threadpool(self.repository.draft, principal.user_id, draft_id)
        # Current DataScope is checked on every read, including terminal proposals.
        view["devices"] = []
        for item in view["payload"]["deviceItems"]:
            device = await self.business.read(
                "get_device_detail", principal, correlation, identity=item["deviceId"]
            )
            view["devices"].append(
                {
                    key: device.get(key, "")
                    for key in ("deviceId", "name", "deviceType", "assetCode")
                }
            )
        if view["payload"].get("incidentId"):
            await self.business.read(
                "get_incident_detail",
                principal,
                correlation,
                identity=view["payload"]["incidentId"],
            )
        view["evidenceStatus"] = "VERIFIED"
        if view["relatedEvidenceReferences"]:
            rows = await run_in_threadpool(
                self.repository.evidence, principal.user_id, view["runId"]
            )
            selected = [
                row for row in rows if row["evidenceId"] in view["relatedEvidenceReferences"]
            ]
            if not await self.tools.revalidate(selected, principal, self.business, correlation):
                if view["execution"] is None:
                    raise ServiceError("DRAFT_EVIDENCE_INVALID", 409, "草稿证据已变化或不可访问")
                # A successful command can itself change a paged query's disclosed objects.
                # Hide the historical references while retaining the one durable receipt;
                # owner, current device and Incident access were all checked above.
                view["relatedEvidenceReferences"] = []
                view["evidenceStatus"] = "UNAVAILABLE"
                return view
            view["relatedEvidenceReferences"] = [
                {
                    k: v
                    for k, v in row.items()
                    if k not in {"arguments", "authorizationVersion", "resultHash"}
                }
                for row in selected
            ]
        return view

    async def decision(self, principal, draft_id, body, action, key, correlation):
        principal.require("agent.draft.confirm")
        principal.require("workorder.create")
        view = await self.visible(principal, draft_id, correlation)
        if view["revision"] != body.draftRevision or view["contentHash"] != body.contentHash:
            raise ServiceError("DRAFT_REVISION_MISMATCH", 409, "草稿版本已变化，请重新审阅")
        if action == "CONFIRM" and view["status"] == "PENDING_CONFIRMATION":
            try:
                await self.validate(
                    principal,
                    view["payload"],
                    [r["evidenceId"] for r in view["relatedEvidenceReferences"]],
                    view["runId"],
                    correlation,
                )
            except ServiceError as error:
                if error.status in {400, 409}:
                    await run_in_threadpool(
                        self.repository.invalidate,
                        principal.user_id,
                        draft_id,
                        body.draftRevision,
                        body.contentHash,
                    )
                    raise ServiceError(
                        "DRAFT_INVALIDATED", 409, "业务状态已变化，请重新生成并审阅草稿"
                    ) from None
                raise
        await run_in_threadpool(
            self.repository.decide,
            principal.user_id,
            draft_id,
            body.draftRevision,
            body.contentHash,
            action,
            key,
            correlation,
            getattr(body, "reason", None),
        )
        return await self.visible(principal, draft_id, correlation)

    async def prepare(self, arguments, deps, origin):
        parsed = PrepareWorkOrder.model_validate(arguments)
        safe = parsed.model_dump(mode="json")
        # Full descriptions/100-device proposals do not fit a 1000-character audit summary.
        # The digest still binds all validated arguments, while the revision stores full content.
        audit = {
            "sourceType": parsed.sourceType,
            "incidentId": parsed.incidentId,
            "title": parsed.title,
            "deviceIdsPreview": [item.deviceId for item in parsed.deviceItems[:4]],
            "deviceCount": len(parsed.deviceItems),
            "evidenceCount": len(parsed.evidenceReferenceIds),
            "argumentsHash": fingerprint(safe),
        }
        call_id = await run_in_threadpool(
            self.repository.start_tool,
            deps.principal.user_id,
            deps.run_id,
            "prepare_work_order",
            audit,
            self.settings.max_tool_calls,
        )
        await deps.emit("tool.started", {"toolCallId": call_id, "toolName": "prepare_work_order"})
        try:
            checked = await self.validate(
                deps.principal,
                parsed.command(),
                parsed.evidenceReferenceIds,
                deps.run_id,
                deps.correlation_id,
            )
            payload = parsed.command()
            payload["priority"] = checked["priority"]
            view = await run_in_threadpool(
                self.repository.propose,
                deps.principal.user_id,
                deps.run_id,
                deps.run_id + ":" + origin,
                payload,
                parsed.evidenceReferenceIds,
                checked["parkId"],
                self.settings.draft_lifetime_seconds,
            )
            result = ToolResult(
                toolName="prepare_work_order",
                status="SUCCEEDED",
                data={
                    "draftId": view["draftId"],
                    "revision": view["revision"],
                    "contentHash": view["contentHash"],
                    "status": view["status"],
                    "summary": view["summary"],
                    "expiresAt": view["expiresAt"].isoformat() + "Z",
                },
            )
        except ServiceError as error:
            result = ToolResult(
                toolName="prepare_work_order",
                status="REJECTED",
                authorization="DENIED" if error.status in {401, 403, 404} else "CURRENT_USER_SCOPE",
                errorCode=error.code,
                safeMessage="草稿未生成，请检查权限、设备、故障和证据；未创建正式工单",
            )
        await run_in_threadpool(
            self.repository.finish_tool, deps.principal.user_id, deps.run_id, call_id, result
        )
        await deps.emit(
            "tool.completed",
            {"toolCallId": call_id, "toolName": "prepare_work_order", "status": result.status},
        )
        return result.model_dump(mode="json")

    def tool(self):
        async def invoke(ctx, **arguments):
            try:
                return await self.prepare(arguments, ctx.deps, ctx.tool_call_id)
            except ValidationError:
                return {
                    "status": "REJECTED",
                    "errorCode": "INVALID_ARGUMENT",
                    "safeMessage": "工单草稿参数无效",
                }

        return Tool.from_schema(
            invoke,
            name="prepare_work_order",
            description="仅在用户要求创建维修工单时生成待人工确认的草稿，绝不创建正式工单。",
            json_schema=PrepareWorkOrder.model_json_schema(),
            takes_ctx=True,
            sequential=True,
        )
