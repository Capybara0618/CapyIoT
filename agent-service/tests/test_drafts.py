import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

import pytest
from pydantic import ValidationError
from sqlalchemy import event, select
from test_repository import database as database

from app.draft_schemas import PrepareWorkOrder
from app.drafts import DraftRepository
from app.errors import ServiceError
from app.models import CommandOutbox, Confirmation, Draft, DraftRevision, Execution
from app.repository import now


def payload(**updates):
    return {
        "sourceType": "MANUAL",
        "deviceItems": [
            {"deviceId": "ipc-1", "deviceRole": "MAIN", "issueDescription": "持续离线"}
        ],
        "title": "入口设备排查",
        "description": "核查连接与供电",
        "expectedResult": "恢复录像并核查",
        "priority": "P3",
        **updates,
    }


@pytest.mark.parametrize(
    "updates",
    [
        {"assignedRepairerId": "attacker"},
        {"createdBy": "attacker"},
        {"sourceType": "LEGACY_ALARM"},
        {"sourceType": "INCIDENT"},
        {"incidentId": "inc-1"},
        {"priority": None},
        {
            "deviceItems": [
                {"deviceId": "ipc-1", "deviceRole": "RELATED", "issueDescription": "核查"}
            ]
        },
    ],
)
def test_draft_arguments_reject_unauthorized_fields_and_invalid_shapes(updates):
    with pytest.raises(ValidationError):
        PrepareWorkOrder.model_validate(payload(**updates))


def running(database):
    repo, owner = DraftRepository(database.sessions), uuid.uuid4().hex
    conversation = repo.create_conversation(owner, "工单草稿")
    run, _ = repo.start_run(
        owner, conversation["conversationId"], "请生成维修草稿", uuid.uuid4().hex, "trace"
    )
    repo.begin_run(owner, run, 60)
    return repo, owner, run


def propose(repo, owner, run, origin="call-1", **updates):
    command = PrepareWorkOrder.model_validate(payload(**updates)).command()
    return repo.propose(owner, run, run + origin, command, [], "park-1", 1800)


def test_draft_tool_idempotency_revision_and_owner(database):
    repo, owner, run = running(database)
    first = propose(repo, owner, run)
    assert first["status"] == "PENDING_CONFIRMATION"
    assert propose(repo, owner, run)["draftId"] == first["draftId"]
    changed = propose(repo, owner, run, "call-2", title="复核后更新描述")
    assert changed["revision"] == 2 and changed["contentHash"] != first["contentHash"]
    with pytest.raises(ServiceError):
        propose(repo, owner, run)
    with pytest.raises(ServiceError) as error:
        repo.draft("another-owner", first["draftId"])
    assert error.value.status == 404
    with database.sessions() as session:
        assert (
            len(
                session.scalars(
                    select(DraftRevision).where(DraftRevision.draft_id == first["draftId"])
                ).all()
            )
            == 2
        )
        for model in (Execution, Confirmation, CommandOutbox):
            assert not session.scalars(
                select(model).where(model.draft_id == first["draftId"])
            ).all()


def test_unconfirmed_expiry_keeps_business_unexecuted(database):
    repo, owner, run = running(database)
    draft = propose(repo, owner, run)
    repo.finish_run(
        owner,
        run,
        "SUCCEEDED",
        {"facts": [], "inferences": [], "recommendations": [], "unknowns": ["待确认"]},
    )
    assert repo.run_view(owner, run)["status"] == "WAITING_CONFIRMATION"
    with database.sessions.begin() as session:
        session.get(Draft, draft["draftId"]).expires_at = now() - timedelta(seconds=1)
    assert repo.draft(owner, draft["draftId"])["status"] == "EXPIRED"
    assert repo.run_view(owner, run)["executionStatus"] == "NOT_EXECUTED"


def decide(repo, owner, draft, action="CONFIRM", key="confirm-1"):
    return repo.decide(
        owner, draft["draftId"], draft["revision"], draft["contentHash"], action, key, "trace"
    )


def test_repeated_and_concurrent_confirmation_queue_one_command(database):
    repo, owner, run = running(database)
    draft = propose(repo, owner, run)
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: decide(repo, owner, draft), range(4)))
    ids = {r["execution"]["executionId"] for r in results}
    assert len(ids) == 1
    with database.sessions() as session:
        for model in (Confirmation, Execution, CommandOutbox):
            assert (
                len(session.scalars(select(model).where(model.draft_id == draft["draftId"])).all())
                == 1
            )
        execution = session.scalar(select(Execution).where(Execution.draft_id == draft["draftId"]))
        command = session.scalar(
            select(CommandOutbox).where(CommandOutbox.draft_id == draft["draftId"])
        )
        assert execution.idempotency_key == command.idempotency_key
        assert "token" not in str(command.payload_json).lower()
    assert decide(repo, owner, draft, key="another-key")["execution"]["executionId"] in ids
    with pytest.raises(ServiceError):
        decide(repo, owner, draft, "REJECT", "reject-after-confirm")


def test_confirmation_transaction_rolls_back_all_three_records(database):
    repo, owner, run = running(database)
    draft = propose(repo, owner, run)

    def interrupt(*_):
        raise RuntimeError("simulate process failure before outbox insert")

    event.listen(CommandOutbox, "before_insert", interrupt)
    try:
        with pytest.raises(RuntimeError):
            decide(repo, owner, draft)
    finally:
        event.remove(CommandOutbox, "before_insert", interrupt)
    assert repo.draft(owner, draft["draftId"])["status"] == "PENDING_CONFIRMATION"
    with database.sessions() as session:
        for model in (Confirmation, Execution, CommandOutbox):
            assert not session.scalars(
                select(model).where(model.draft_id == draft["draftId"])
            ).all()


def test_rejection_expiry_revision_and_key_reuse(database):
    repo, owner, run = running(database)
    old = propose(repo, owner, run)
    draft = propose(repo, owner, run, "edited", description="补充现场排查")
    with pytest.raises(ServiceError) as error:
        decide(repo, owner, old)
    assert error.value.code == "DRAFT_REVISION_MISMATCH"
    assert decide(repo, owner, draft, "REJECT")["execution"] is None
    assert decide(repo, owner, draft, "REJECT")["status"] == "REJECTED"
    conversation = repo.create_conversation(owner, "another")
    run2, _ = repo.start_run(owner, conversation["conversationId"], "维修", "new-run", "trace")
    repo.begin_run(owner, run2, 60)
    next_draft = propose(repo, owner, run2)
    with pytest.raises(ServiceError) as error:
        decide(repo, owner, next_draft)
    assert error.value.code == "IDEMPOTENCY_KEY_REUSED"
    with database.sessions.begin() as session:
        session.get(Draft, next_draft["draftId"]).expires_at = now() - timedelta(seconds=1)
    with pytest.raises(ServiceError) as error:
        decide(repo, owner, next_draft, key="expired")
    assert error.value.code == "DRAFT_EXPIRED"
    assert repo.draft(owner, next_draft["draftId"])["status"] == "EXPIRED"


async def test_changed_business_state_invalidates_before_confirmation(database, settings):
    from app.business_client import Principal
    from app.draft_schemas import DraftDecision
    from app.drafts import DraftService

    repo, owner, run = running(database)
    draft = propose(repo, owner, run)

    class Core:
        async def read(self, *_, **__):
            return {"deviceId": "ipc-1", "name": "入口摄像机"}

        async def validate_work_order(self, *_):
            raise ServiceError("CONFLICT", 409, "设备业务状态已变化")

    principal = Principal(
        owner,
        "session",
        1,
        frozenset(
            {
                "agent.use",
                "device.read",
                "workorder.create",
                "agent.draft.prepare",
                "agent.draft.confirm",
            }
        ),
        "not-persisted",
    )
    service = DraftService(settings, repo, None, Core())
    with pytest.raises(ServiceError) as error:
        await service.decision(
            principal,
            draft["draftId"],
            DraftDecision(draftRevision=1, contentHash=draft["contentHash"]),
            "CONFIRM",
            "decision",
            "trace",
        )
    assert error.value.code == "DRAFT_INVALIDATED"
    assert repo.draft(owner, draft["draftId"])["status"] == "INVALIDATED"
    assert repo.run_view(owner, run)["executionStatus"] == "NOT_EXECUTED"


async def test_full_length_draft_uses_bounded_audit_and_permission_denials_are_recorded(
    database, settings
):
    import json

    from app.agent import AgentDeps
    from app.business_client import Principal
    from app.drafts import DraftService
    from app.models import ToolCall

    repo, owner, run = running(database)

    class Core:
        async def validate_work_order(self, *_):
            return {"parkId": "park-1", "priority": "P3"}

    principal = Principal(
        owner,
        "session",
        1,
        frozenset({"agent.use", "workorder.create", "agent.draft.prepare"}),
        "not-persisted",
    )
    service = DraftService(settings, repo, None, Core())
    result = await service.prepare(
        payload(description="检" * 2000),
        AgentDeps(principal, service.business, run, "trace"),
        "full-length",
    )
    assert result["status"] == "SUCCEEDED"
    assert len(repo.draft(owner, result["data"]["draftId"])["payload"]["description"]) == 2000
    with database.sessions() as session:
        call = session.scalar(select(ToolCall).where(ToolCall.run_id == run))
        assert len(call.input_summary) < 1000 and json.loads(call.input_summary)["argumentsHash"]
    unauthorized = Principal(owner, "session", 1, frozenset({"agent.use"}), "not-persisted")
    denied = await service.prepare(
        payload(), AgentDeps(unauthorized, service.business, run, "trace"), "denied"
    )
    assert denied["authorization"] == "DENIED"
