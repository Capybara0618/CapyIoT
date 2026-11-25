import uuid
from datetime import timedelta

import pytest
from pydantic import ValidationError
from sqlalchemy import select

from app.draft_schemas import PrepareWorkOrder
from app.drafts import DraftRepository
from app.errors import ServiceError
from app.models import Draft, DraftRevision, Execution, Confirmation, CommandOutbox
from app.repository import now
from test_repository import database as database


def payload(**updates):
    return {
        "sourceType": "MANUAL", "deviceItems": [{"deviceId": "ipc-1", "deviceRole": "MAIN", "issueDescription": "持续离线"}],
        "title": "入口设备排查", "description": "核查连接与供电", "expectedResult": "恢复录像并核查", "priority": "P3",
        **updates,
    }


@pytest.mark.parametrize("updates", [
    {"assignedRepairerId": "attacker"}, {"createdBy": "attacker"}, {"sourceType": "LEGACY_ALARM"},
    {"sourceType": "INCIDENT"}, {"incidentId": "inc-1"}, {"priority": None},
    {"deviceItems": [{"deviceId": "ipc-1", "deviceRole": "RELATED", "issueDescription": "核查"}]},
])
def test_draft_arguments_reject_unauthorized_fields_and_invalid_shapes(updates):
    with pytest.raises(ValidationError):
        PrepareWorkOrder.model_validate(payload(**updates))


def running(database):
    repo, owner = DraftRepository(database.sessions), uuid.uuid4().hex
    conversation = repo.create_conversation(owner, "工单草稿")
    run, _ = repo.start_run(owner, conversation["conversationId"], "请生成维修草稿", uuid.uuid4().hex, "trace")
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
        assert len(session.scalars(select(DraftRevision).where(DraftRevision.draft_id == first["draftId"])).all()) == 2
        for model in (Execution, Confirmation, CommandOutbox):
            assert not session.scalars(select(model).where(model.draft_id == first["draftId"])).all()


def test_unconfirmed_expiry_keeps_business_unexecuted(database):
    repo, owner, run = running(database)
    draft = propose(repo, owner, run)
    repo.finish_run(owner, run, "SUCCEEDED", {"facts": [], "inferences": [], "recommendations": [], "unknowns": ["待确认"]})
    assert repo.run_view(owner, run)["status"] == "WAITING_CONFIRMATION"
    with database.sessions.begin() as session:
        session.get(Draft, draft["draftId"]).expires_at = now() - timedelta(seconds=1)
    assert repo.draft(owner, draft["draftId"])["status"] == "EXPIRED"
    assert repo.run_view(owner, run)["executionStatus"] == "NOT_EXECUTED"
