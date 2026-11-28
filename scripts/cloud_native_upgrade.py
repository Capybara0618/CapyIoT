"""Restore an explicit pre-CN five-schema backup and upgrade only its isolated copy."""

import hashlib
import json
import re
import subprocess
import sys
import uuid
from pathlib import Path

import cloud_native_common as cn
from cloud_native_environment import verified_endpoints
from cloud_native_manifests import DATABASES
from microservices_common import ROOT, Target, command_env, write_json
from microservices_control import containers, db, inventory, service, validate_backup


def upgrade_copy(target, backup, folder):
    verified_endpoints(target)
    deployments=json.loads(cn.execute(target.kubectl("get","deployments","-l","app.kubernetes.io/part-of=capyiot","-o","json")))["items"]
    if len(deployments)!=8 or any(d["spec"]["replicas"] for d in deployments):
        raise ValueError("Freeze candidate applications before the additional restore resource window")
    backup,original=validate_backup(backup)
    if not original["inventory"]["migrations"]["device_db.flyway_schema_history"].splitlines()[-1].startswith("15\t"):
        raise ValueError("An explicit pre-CN device schema 15 backup is required")
    project="capyiot-ms-verify-cn-upgrade-"+uuid.uuid4().hex[:8]
    bundle=ROOT/"var/bundles"/project
    folder.mkdir(parents=True,exist_ok=False,mode=0o700)
    def run(name,command):
        with (folder/(name+".log")).open("xb") as stream:
            value=subprocess.run(list(map(str,command)),cwd=ROOT,env=command_env(command),stdout=stream,stderr=subprocess.STDOUT)
        if value.returncode: raise RuntimeError("Isolated upgrade step failed; keep its private log")
    run("init",[sys.executable,ROOT/"scripts/init_microservices.py","--project",project,"--bundle",bundle,"--mode","fresh"])
    clone=Target(project,ROOT/"compose.microservices.yml",bundle/"deployment.env",verification=True)
    try:
        run("restore",[sys.executable,ROOT/"scripts/microservices_control.py","restore-rehearsal","--project",project,
            "--compose-file",ROOT/"compose.microservices.yml","--env-file",bundle/"deployment.env","--report-dir",folder/"restore",
            "--backup-dir",backup,"--execute"])
        clone.preflight()
        mysql=service(containers(project),"mysql")["id"]
        before=inventory(mysql)
        columns=db(mysql,"SELECT COLUMN_NAME FROM information_schema.columns WHERE TABLE_SCHEMA='device_db' AND TABLE_NAME='broker_disconnect_outbox' ORDER BY ORDINAL_POSITION").splitlines()
        if not columns or any(not re.fullmatch(r"[a-z_]+",c) for c in columns): raise ValueError("Unexpected historical columns")
        statement="SELECT "+",".join("`"+c+"`" for c in columns)+" FROM device_db.broker_disconnect_outbox ORDER BY id"
        old_rows=hashlib.sha256(db(mysql,statement).encode()).hexdigest()
        active=json.loads((target.bundle/"active-release.json").read_text())
        candidate=json.loads((target.bundle/("release-"+active["releaseId"]+".json")).read_text())
        for app in (*DATABASES,"agent-service"):
            domain="agent" if app=="agent-service" else DATABASES[app][0]
            command=["docker","run","--rm","--network",project+"_default","--memory","256m","--read-only",
                "--tmpfs","/tmp:rw,nosuid,size=32m","--security-opt","no-new-privileges","--cap-drop","ALL",
                "--mount","type=bind,source="+(bundle/"secrets"/(domain+"-db-password")).as_posix()+",target=/run/secrets/db-password,readonly",
                "-e","CAPYIOT_MIGRATION_ONLY=true","-e","CAPYIOT_MIGRATION_ACTION=migrate","-e","CAPYIOT_SCHEMA="+domain,
                "-e","AGENT_DB_HOST=mysql",candidate["images"][app]]
            if domain=="agent": command += ["alembic","upgrade","head"]
            run("migrate-"+domain,command)
        after=inventory(mysql)
        for table,count in before["tables"].items():
            if after["tables"][table]!=count+(1 if table=="device_db.flyway_schema_history" else 0):
                raise AssertionError("Upgrade changed historical row counts")
            if table not in {"device_db.flyway_schema_history","device_db.broker_disconnect_outbox"} and after["checksums"][table]!=before["checksums"][table]:
                raise AssertionError("Upgrade changed historical business rows")
        if hashlib.sha256(db(mysql,statement).encode()).hexdigest()!=old_rows: raise AssertionError("Outbox migration changed existing column values")
        if not after["migrations"]["device_db.flyway_schema_history"].startswith(before["migrations"]["device_db.flyway_schema_history"]):
            raise AssertionError("Historical migration checksums changed")
        if not after["migrations"]["device_db.flyway_schema_history"].splitlines()[-1].startswith("16\t"):
            raise AssertionError("Device schema did not reach version 16")
        write_json(folder/"upgrade-proof.json",{"project":project,"sourceSqlSha256":original["sqlSha256"],
            "schemaBefore":15,"schemaAfter":16,"historicalRowsPreserved":True,"outboxOldColumnsSha256":old_rows,
            "applicationWritersStarted":False,"inventoryBefore":before,"inventoryAfter":after})
    finally:
        subprocess.run(clone.compose("stop"),cwd=ROOT,env=command_env(clone.compose("stop")),stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
