import json
from datetime import timedelta

import jwt
from cryptography.hazmat.primitives import serialization
from sqlalchemy import select
from test_drafts import decide, propose, running
from test_repository import database as database

from app.delegation import DelegationSigner
from app.dispatcher import Dispatcher, DispatchStore
from app.errors import ServiceError
from app.models import CommandOutbox
from app.repository import now


def queued(database):
    repo, owner, run = running(database)
    draft = propose(repo, owner, run)
    decide(repo, owner, draft)
    return repo, owner, draft


def test_signing_keys_are_independent_short_lived_bound_and_rotatable(tmp_path):
    signer = DelegationSigner(tmp_path / "agent-key.json", generate=True)
    command = {
        "actorUserId": "user",
        "confirmationId": "confirmation",
        "draftId": "draft",
        "draftRevision": 2,
        "requestHash": "a" * 64,
        "idempotencyKey": "agent-command",
    }
    first = signer.issue(command)
    public = jwt.PyJWK.from_dict(signer.jwks()["keys"][0]).key
    claims = jwt.decode(
        first, public, algorithms=["RS256"], audience="capyiot-core", issuer="capyiot-agent-service"
    )
    assert claims["exp"] - claims["iat"] == 60
    assert all(claims[k] == value for k, value in command.items())
    assert "privateKey" not in json.dumps(signer.jwks()) and '"d"' not in json.dumps(signer.jwks())
    old_kid = jwt.get_unverified_header(first)["kid"]
    signer.rotate()
    second = signer.issue(command)
    assert jwt.get_unverified_header(second)["kid"] != old_kid
    assert old_kid in {key["kid"] for key in signer.jwks()["keys"]}
    stored = json.loads(signer.path.read_text())
    assert "privateKey" not in stored["keys"][1]
    serialization.load_pem_public_key(stored["keys"][1]["publicKey"].encode())


class Core:
    def __init__(self):
        self.created, self.keys = {}, []
        self.lose_response, self.deny = True, False

    async def dispatch_work_order(self, command, _):
        self.keys.append(command["idempotencyKey"])
        if self.deny:
            raise ServiceError("FORBIDDEN", 403, "撤权")
        self.created.setdefault(
            command["idempotencyKey"], {"workOrderId": "101", "workOrderNo": "WO-101"}
        )
        if self.lose_response:
            self.lose_response = False
            raise ServiceError("COMMAND_OUTCOME_UNKNOWN", 503, "响应丢失")
        return self.created[command["idempotencyKey"]]

    async def command_receipt(self, command, _):
        if self.deny:
            raise ServiceError("FORBIDDEN", 403, "撤权")
        if command["idempotencyKey"] not in self.created:
            raise ServiceError("NOT_FOUND", 404, "没有结果")
        return self.created[command["idempotencyKey"]]


def ready_retry(database, draft):
    with database.sessions.begin() as session:
        row = session.scalar(
            select(CommandOutbox).where(CommandOutbox.draft_id == draft["draftId"])
        )
        row.next_retry_at = now() - timedelta(seconds=1)


async def test_lost_response_reconciles_after_restart_without_creating_twice(
    database, settings, tmp_path
):
    repo, owner, draft = queued(database)
    core, signer = Core(), DelegationSigner(tmp_path / "key.json", generate=True)
    dispatcher = Dispatcher(settings, repo, core, signer)
    assert await dispatcher.once()
    assert repo.draft(owner, draft["draftId"])["execution"]["status"] == "RECONCILIATION_REQUIRED"
    ready_retry(database, draft)
    assert await Dispatcher(settings, repo, core, signer).once()
    final = repo.draft(owner, draft["draftId"])
    assert final["status"] == "EXECUTED" and final["execution"]["workOrderNo"] == "WO-101"
    assert len(core.created) == 1 and len(core.keys) == 1


async def test_revocation_before_dispatch_fails_without_business_creation(
    database, settings, tmp_path
):
    repo, owner, draft = queued(database)
    core = Core()
    core.deny = True
    await Dispatcher(
        settings, repo, core, DelegationSigner(tmp_path / "key.json", generate=True)
    ).once()
    assert not core.created
    assert repo.draft(owner, draft["draftId"])["execution"]["status"] == "FAILED"


async def test_unknown_outcome_remains_unknown_after_revocation_and_is_bounded(
    database, settings, tmp_path
):
    repo, owner, draft = queued(database)
    core = Core()
    dispatcher = Dispatcher(
        settings, repo, core, DelegationSigner(tmp_path / "key.json", generate=True)
    )
    await dispatcher.once()
    core.deny = True
    for _ in range(settings.dispatch_max_attempts - 1):
        ready_retry(database, draft)
        await dispatcher.once()
    assert not await dispatcher.once()
    assert repo.draft(owner, draft["draftId"])["execution"]["status"] == "RECONCILIATION_REQUIRED"
    assert len(core.created) == 1


def test_expired_lease_is_reconciled_and_stale_worker_cannot_override(database, settings):
    repo, owner, draft = queued(database)
    store = DispatchStore(repo, settings)
    first = store.claim()
    assert first and not first["reconcile"] and store.claim() is None
    with database.sessions.begin() as session:
        session.get(CommandOutbox, first["commandId"]).lease_until = now() - timedelta(seconds=1)
    second = DispatchStore(repo, settings).claim()
    assert second["reconcile"] and second["idempotencyKey"] == first["idempotencyKey"]
    store.complete(first, error_code="STALE", unknown=False)
    assert repo.draft(owner, draft["draftId"])["execution"]["status"] == "EXECUTING"
