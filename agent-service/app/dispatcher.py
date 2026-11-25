"""Bounded in-process HTTP outbox; leases and original keys survive process restarts."""

import asyncio
import logging
import uuid
from datetime import timedelta

from pydantic import BaseModel, Field, ValidationError
from sqlalchemy import and_, or_, select
from starlette.concurrency import run_in_threadpool

from app.errors import ServiceError
from app.models import CommandOutbox, Draft, Execution
from app.repository import now

LOG = logging.getLogger("capyiot.agent.dispatcher")


class CreatedOrder(BaseModel):
    workOrderId: str = Field(pattern=r"^[A-Za-z0-9_-]{1,64}$")
    workOrderNo: str = Field(min_length=1, max_length=64)


class DispatchStore:
    def __init__(self, repository, settings):
        self.repo, self.settings = repository, settings

    def claim(self):
        with self.repo.sessions.begin() as session:
            ready = or_(
                and_(
                    CommandOutbox.status.in_(["PENDING", "RETRY"]),
                    or_(
                        CommandOutbox.next_retry_at.is_(None), CommandOutbox.next_retry_at <= now()
                    ),
                ),
                and_(
                    CommandOutbox.status == "RECONCILIATION_REQUIRED",
                    CommandOutbox.next_retry_at <= now(),
                ),
                and_(CommandOutbox.status == "DISPATCHING", CommandOutbox.lease_until <= now()),
            )
            row = session.scalar(
                select(CommandOutbox)
                .where(ready)
                .order_by(CommandOutbox.created_at)
                .with_for_update(skip_locked=True)
                .limit(1)
            )
            if row is None:
                return None
            execution = session.scalar(
                select(Execution)
                .where(Execution.confirmation_id == row.confirmation_id)
                .with_for_update()
            )
            if row.attempt_count >= self.settings.dispatch_max_attempts:
                row.status, row.next_retry_at, row.lease_token = (
                    "RECONCILIATION_REQUIRED",
                    None,
                    None,
                )
                execution.status, execution.error_code = (
                    "RECONCILIATION_REQUIRED",
                    "DISPATCH_LEASE_EXPIRED",
                )
                execution.version += 1
                return None
            reconcile = row.attempt_count > 0
            row.attempt_count += 1
            row.status, row.lease_token = "DISPATCHING", str(uuid.uuid4())
            row.lease_until = now() + timedelta(seconds=self.settings.dispatch_lease_seconds)
            execution.status, execution.started_at = "EXECUTING", now()
            execution.attempt_count, execution.version = row.attempt_count, execution.version + 1
            draft = session.get(Draft, row.draft_id)
            draft.status, draft.version = "EXECUTING", draft.version + 1
            return {
                "commandId": row.command_id,
                "leaseToken": row.lease_token,
                "actorUserId": row.actor_user_id,
                "confirmationId": row.confirmation_id,
                "draftId": row.draft_id,
                "draftRevision": row.draft_revision,
                "requestHash": row.request_hash,
                "idempotencyKey": row.idempotency_key,
                "payload": row.payload_json,
                "reconcile": reconcile,
            }

    def complete(self, command, result=None, error_code=None, unknown=False):
        with self.repo.sessions.begin() as session:
            row = session.get(CommandOutbox, command["commandId"], with_for_update=True)
            # A previous worker can never overwrite a newer lease's outcome.
            if row.status != "DISPATCHING" or row.lease_token != command["leaseToken"]:
                return
            execution = session.scalar(
                select(Execution)
                .where(Execution.confirmation_id == row.confirmation_id)
                .with_for_update()
            )
            draft = session.get(Draft, row.draft_id)
            row.lease_token, row.lease_until = None, None
            execution.version, draft.version = execution.version + 1, draft.version + 1
            if result:
                row.status, row.next_retry_at, row.last_error = "SUCCEEDED", None, None
                execution.status, execution.finished_at, execution.error_code = (
                    "SUCCEEDED",
                    now(),
                    None,
                )
                execution.target_object_type, execution.target_object_id = (
                    "WORK_ORDER",
                    result.workOrderId,
                )
                execution.target_object_no = result.workOrderNo
                draft.status, draft.result_business_object_type, draft.result_business_object_id = (
                    "EXECUTED",
                    "WORK_ORDER",
                    result.workOrderId,
                )
                self.repo.end_waiting(session, draft)
            elif unknown:
                row.status, execution.status = "RECONCILIATION_REQUIRED", "RECONCILIATION_REQUIRED"
                row.next_retry_at = (
                    now() + timedelta(seconds=self.settings.dispatch_retry_seconds)
                    if row.attempt_count < self.settings.dispatch_max_attempts
                    else None
                )
                row.last_error, execution.error_code = error_code, error_code
            else:
                row.status, row.next_retry_at, row.last_error = "DEAD", None, error_code
                execution.status, execution.error_code, execution.finished_at = (
                    "FAILED",
                    error_code,
                    now(),
                )
                draft.status = "FAILED"
                self.repo.end_waiting(session, draft)


class Dispatcher:
    def __init__(self, settings, repository, core, signer):
        self.settings, self.store = settings, DispatchStore(repository, settings)
        self.core, self.signer, self.task = core, signer, None

    async def once(self):
        command = await run_in_threadpool(self.store.claim)
        if command is None:
            return False
        try:
            token = self.signer.issue(command)
            if command["reconcile"]:
                try:
                    receipt = await self.core.command_receipt(command, token)
                except ServiceError as error:
                    if error.status != 404:
                        raise
                else:
                    await run_in_threadpool(
                        self.store.complete, command, CreatedOrder.model_validate(receipt)
                    )
                    return True
            response = await self.core.dispatch_work_order(command, token)
            await run_in_threadpool(
                self.store.complete, command, CreatedOrder.model_validate(response)
            )
        except ServiceError as error:
            # After an unknown earlier outcome, losing authorization cannot prove no order exists.
            unknown = (
                command["reconcile"]
                or error.code == "COMMAND_OUTCOME_UNKNOWN"
                or error.status >= 500
            )
            await run_in_threadpool(self.store.complete, command, None, error.code, unknown)
        except (ValidationError, ValueError, OSError, KeyError):
            await run_in_threadpool(
                self.store.complete, command, None, "COMMAND_OUTCOME_UNKNOWN", True
            )
        LOG.info("dispatch_attempt command=%s", command["commandId"])
        return True

    async def loop(self):
        while True:
            try:
                await self.once()
            except asyncio.CancelledError:
                raise
            except Exception as error:
                LOG.error("dispatch_unavailable category=%s", type(error).__name__)
            await asyncio.sleep(self.settings.dispatch_poll_seconds)

    def start(self):
        self.task = asyncio.create_task(self.loop())

    async def close(self):
        if self.task:
            self.task.cancel()
            await asyncio.gather(self.task, return_exceptions=True)
