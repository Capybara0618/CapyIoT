import os
import secrets
import subprocess
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import inspect, select, text
from sqlalchemy.exc import DBAPIError, IntegrityError

from app.config import DatabaseSettings
from app.database import Database
from app.errors import ServiceError
from app.models import AgentRun, Base, Draft, DraftRevision, Message
from app.repository import Repository, now


@pytest.fixture(scope="module")
def database():
    name = "capyiot-agent-tests-"+uuid.uuid4().hex[:10]
    password, root_password = secrets.token_hex(24), secrets.token_hex(24)
    environment = {**os.environ, "MYSQL_ROOT_PASSWORD": root_password, "MYSQL_PASSWORD": password}
    subprocess.run(["docker", "run", "-d", "--name", name, "-e", "MYSQL_ROOT_PASSWORD", "-e", "MYSQL_PASSWORD",
                    "-e", "MYSQL_USER=agent_app", "-e", "MYSQL_DATABASE=agent_db", "-p", "127.0.0.1::3306", "mysql:8.4.11"],
                   env=environment, check=True, capture_output=True)
    db = None
    previous = {key: os.environ.get(key) for key in ("AGENT_DB_HOST", "AGENT_DB_PORT", "AGENT_DB_PASSWORD")}
    try:
        port = int(subprocess.check_output(["docker", "port", name, "3306"], text=True).strip().rsplit(":", 1)[1])
        os.environ.update(AGENT_DB_HOST="127.0.0.1", AGENT_DB_PORT=str(port), AGENT_DB_PASSWORD=password)
        db = Database(DatabaseSettings())
        deadline = time.monotonic()+90
        while True:
            try:
                with db.engine.connect() as connection:
                    connection.execute(text("SELECT 1"))
                break
            except DBAPIError:
                if time.monotonic() > deadline:
                    raise RuntimeError("Isolated MySQL did not become ready") from None
                time.sleep(1)
        config = Config(str(Path(__file__).resolve().parents[1]/"alembic.ini"))
        command.upgrade(config, "head")
        command.upgrade(config, "head")
        subprocess.run(["docker", "exec", "-i", name, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'],
                       input=b"CREATE DATABASE device_db; CREATE TABLE device_db.private_marker(id INT);",
                       check=True, capture_output=True)
        yield db
    finally:
        if db:
            db.close()
        subprocess.run(["docker", "rm", "-f", "-v", name], capture_output=True)
        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def test_real_mysql_schema_matches_models_and_account_is_restricted(database):
    schema = inspect(database.engine)
    assert set(schema.get_table_names()) == set(Base.metadata.tables)|{"alembic_version"}
    for name, table in Base.metadata.tables.items():
        assert {column["name"] for column in schema.get_columns(name)} == set(table.columns.keys())
    with database.engine.connect() as connection:
        assert connection.execute(text("SELECT @@session.time_zone")).scalar_one() == "+00:00"
        with pytest.raises(DBAPIError):
            connection.execute(text("SELECT * FROM device_db.private_marker"))


def test_restart_restores_history_and_draft_but_another_owner_cannot_read(database):
    repo = Repository(database.sessions)
    owner = uuid.uuid4().hex
    conversation = repo.create_conversation(owner, "交换机异常")
    identity = conversation["conversationId"]
    run, created = repo.start_run(owner, identity, "请核查设备", "request-one", "trace")
    assert created
    draft = repo.save_draft(owner, run, {"title": "待审阅维修草稿"}, [], "CAMPUS-A", now()+timedelta(minutes=10))
    database.engine.dispose()
    restored = Repository(database.sessions)
    assert restored.history(owner, identity)["messages"][0]["content"] == "请核查设备"
    assert restored.draft(owner, draft)["payload"]["title"] == "待审阅维修草稿"
    assert restored.conversations("another-user")["total"] == 0
    for model, resource in [(Draft, draft), (AgentRun, run)]:
        with database.sessions() as session, pytest.raises(ServiceError) as error:
            repo.owned(session, model, "another-user", resource)
        assert error.value.status == 404
    with pytest.raises(ServiceError):
        restored.history("another-user", identity)
    with database.sessions() as session:
        assert session.scalar(select(DraftRevision).where(DraftRevision.draft_id == draft)).content_json == {"title": "待审阅维修草稿"}


def test_concurrent_replay_has_one_message_run_and_rejects_changed_content(database):
    repo, owner = Repository(database.sessions), uuid.uuid4().hex
    identity = repo.create_conversation(owner, "并发检查")["conversationId"]
    with ThreadPoolExecutor(max_workers=4) as workers:
        results = list(workers.map(lambda _: repo.start_run(owner, identity, "设备状态", "same-key", "trace"), range(4)))
    assert len({run for run, _ in results}) == 1
    assert sum(created for _, created in results) == 1
    assert len(repo.history(owner, identity)["messages"]) == 1
    with pytest.raises(ServiceError) as error:
        repo.start_run(owner, identity, "不同内容", "same-key", "trace")
    assert error.value.code == "IDEMPOTENCY_KEY_REUSED"


def test_database_uniqueness_and_history_fail_closed(database):
    repo, owner = Repository(database.sessions), uuid.uuid4().hex
    identity = repo.create_conversation(owner, "历史权限")["conversationId"]
    run, _ = repo.start_run(owner, identity, "核查", "run-history", "trace")
    with database.sessions.begin() as session:
        session.add(Message(message_id=uuid.uuid4().hex, conversation_id=identity, message_seq=2,
                            role="ASSISTANT", content="已撤权的历史业务内容", run_id=run))
    answer = repo.history(owner, identity)["messages"][1]
    assert answer["content"] is None and answer["contentStatus"] == "REDACTED_BY_CURRENT_AUTHORIZATION"
    with pytest.raises(IntegrityError), database.sessions.begin() as session:
        session.add(Message(message_id=uuid.uuid4().hex, conversation_id=identity, message_seq=2, role="USER", content="duplicate"))
        session.flush()
    assert len(repo.history(owner, identity)["messages"]) == 2
