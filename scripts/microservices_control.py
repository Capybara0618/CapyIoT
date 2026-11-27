"""Fail-closed rollout commands. Every mutation needs an explicit target and --execute.

No down -v, password resets, offset resets, automatic source restart, or rollback after
new writers might have run. The saved source containers retain their original image/config.
"""

import argparse
import json
import os
import shutil
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

from microservices_common import (
    APPS,
    INFRA,
    OLD_APPS,
    ROOT,
    SCHEMAS,
    Target,
    containers,
    name,
    private_path,
    run,
    sha,
    write_json,
)

PASSWORD = 'if [ -f /run/secrets/mysql-root-password ]; then MYSQL_PWD="$(cat /run/secrets/mysql-root-password)"; else MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; fi; export MYSQL_PWD; '


def db(container, statement):
    return run(
        [
            "docker",
            "exec",
            "-i",
            container,
            "sh",
            "-c",
            PASSWORD + "exec mysql -uroot -N -B",
        ],
        data=statement.encode(),
    ).decode()


def service(items, wanted):
    matches = [c for c in items if c["service"] == wanted]
    if len(matches) != 1:
        raise ValueError("Expected exactly one " + wanted + " container")
    return matches[0]


def stopped(project):
    items = containers(project)
    unknown = [
        c
        for c in items
        if c["running"] and c["service"] not in INFRA and c["service"] not in OLD_APPS
    ]
    if unknown or any(
        c["running"] for c in items if c["service"] in (*OLD_APPS, "emqx")
    ):
        raise ValueError(
            "All application, ingress and simulator writers must be stopped"
        )
    return items


def stop_apps(project):
    items = containers(project)
    if any(
        c["running"] and c["service"] not in INFRA and c["service"] not in OLD_APPS
        for c in items
    ):
        raise ValueError(
            "Unknown running service; explicitly review its ownership before freezing"
        )
    # Stop input/confirmation/dispatch first. Durable backlog and unfinished operations are preserved.
    for names in (
        (
            "frontend",
            "gateway",
            "simulator",
            "agent-service",
            "device-ingestion",
            "emqx",
        ),
        APPS + ("core-service", "agent-migrate"),
    ):
        ids = [
            c["id"]
            for c in containers(project)
            if c["running"] and c["service"] in names
        ]
        if ids:
            run(["docker", "stop", "--time", "45", *ids])
    return stopped(project)


def offsets(kafka):
    text = run(
        [
            "docker",
            "exec",
            kafka,
            "/opt/kafka/bin/kafka-consumer-groups.sh",
            "--bootstrap-server",
            "localhost:29092",
            "--all-groups",
            "--describe",
        ]
    ).decode()
    result = {}
    for line in text.splitlines():
        fields = line.split()
        if len(fields) >= 6 and fields[2].isdigit():
            result["/".join(fields[:3])] = {"offset": fields[3], "end": fields[4]}
    return result


def validate_source_credentials(target, items):
    core = service(items, "core-service")["id"]
    entries = json.loads(
        run(["docker", "inspect", "--format", "{{json .Config.Env}}", core])
    )
    original = dict(entry.split("=", 1) for entry in entries if "=" in entry)
    for domain in ("DEVICE", "FAULT", "WORKFLOW", "PLATFORM"):
        expected = original.get(domain + "_COMMAND_FINGERPRINT_KEY") or original.get(
            "CORE_SERVICE_KEY"
        )
        if (
            not expected
            or (
                target.bundle / "secrets" / (domain.lower() + "-fingerprint-key")
            ).read_text(encoding="utf-8")
            != expected
        ):
            raise ValueError(
                "Command fingerprint material does not match the original writer"
            )
    mysql = service(items, "mysql")["id"]
    for role in (
        "mysql-root",
        "device-db",
        "alarm-db",
        "workflow-db",
        "platform-db",
        "agent-db",
    ):
        user = "root" if role == "mysql-root" else role[:-3] + "_app"
        command = (
            "IFS= read -r MYSQL_PWD; export MYSQL_PWD; exec mysql -u"
            + user
            + ' -N -B -e "SELECT 1; SHOW GRANTS;"'
        )
        grants = run(
            ["docker", "exec", "-i", mysql, "sh", "-c", command],
            data=(target.bundle / "secrets" / (role + "-password")).read_bytes()
            + b"\n",
        ).decode()
        if user != "root":
            own = role[:-3] + "_db"
            if any(
                " ON " in line
                and not (
                    " ON `" + own + "`.* " in line or "GRANT USAGE ON *.* " in line
                )
                for line in grants.splitlines()
            ):
                raise ValueError("An application account has grants outside its schema")


def inventory(mysql):
    tables = db(
        mysql,
        "SELECT table_schema,table_name FROM information_schema.tables WHERE table_schema IN ("
        + ",".join("'" + s + "'" for s in SCHEMAS)
        + ") AND table_type='BASE TABLE' ORDER BY table_schema,table_name;",
    )
    result = {"tables": {}, "checksums": {}, "migrations": {}, "unfinished": {}}
    import re

    names = []
    for line in tables.splitlines():
        schema, table = line.split("\t")
        if schema not in SCHEMAS or not re.fullmatch(r"[A-Za-z0-9_]+", table):
            raise ValueError("Unexpected table identifier")
        names.append((schema, table, schema + "." + table))
    if not names or not all(
        any(schema == expected for schema, _, _ in names) for expected in SCHEMAS
    ):
        raise ValueError("All five existing schemas must be present")
    counts = db(
        mysql,
        " UNION ALL ".join(
            "SELECT '" + full + "',COUNT(*) FROM " + full for _, _, full in names
        )
        + ";",
    )
    for line in counts.splitlines():
        full, count = line.split("\t")
        result["tables"][full] = int(count)
    checksums = db(
        mysql, "CHECKSUM TABLE " + ",".join(full for _, _, full in names) + " EXTENDED;"
    )
    for line in checksums.splitlines():
        full, checksum = line.split("\t")
        if not checksum.isdigit():
            raise ValueError(
                "A full table checksum is unavailable; do not claim restore parity"
            )
        result["checksums"][full] = checksum
    expected_names = {full for _, _, full in names}
    if (
        set(result["tables"]) != expected_names
        or set(result["checksums"]) != expected_names
    ):
        raise ValueError("Incomplete database inventory")
    metadata = {}
    columns = db(
        mysql,
        "SELECT table_schema,table_name,column_name FROM information_schema.columns WHERE table_schema IN ("
        + ",".join("'" + schema + "'" for schema in SCHEMAS)
        + ") ORDER BY table_schema,table_name,ordinal_position;",
    )
    for line in columns.splitlines():
        schema, table, column = line.split("\t")
        metadata.setdefault(schema + "." + table, []).append(column)
    for schema, table, full in names:
        if table == "flyway_schema_history":
            result["migrations"][full] = db(
                mysql,
                "SELECT version,checksum,success FROM "
                + full
                + " ORDER BY installed_rank;",
            )
        elif table == "alembic_version":
            result["migrations"][full] = db(
                mysql, "SELECT version_num FROM " + full + " ORDER BY version_num;"
            )
        # Bounded protocol metadata only; complete rows/payloads remain in the private SQL dump.
        if any(
            word in table for word in ("outbox", "reservation", "operation", "inbox")
        ):
            columns = metadata.get(full, [])
            selected = [
                c
                for c in columns
                if c
                in {
                    "id",
                    "operation_id",
                    "reservation_id",
                    "event_id",
                    "status",
                    "state",
                    "attempt_count",
                    "last_error",
                    "next_retry_at",
                    "notification_status",
                    "confirmation_id",
                    "draft_id",
                }
            ]
            if selected:
                result["unfinished"][full] = db(
                    mysql,
                    "SELECT "
                    + ",".join(selected)
                    + " FROM "
                    + full
                    + " ORDER BY "
                    + ",".join(selected)
                    + " LIMIT 10000;",
                )
    if not all(any(t.startswith(s + ".") for t in result["tables"]) for s in SCHEMAS):
        raise ValueError(
            "All five existing schemas must be present; do not silently replace a missing schema"
        )
    return result


def physical_volumes(items):
    result = {}
    for owner, destination in (
        ("mysql", "/var/lib/mysql"),
        ("kafka", "/var/lib/kafka/data"),
        ("redis", "/data"),
        ("emqx", "/opt/emqx/data"),
    ):
        item = service(items, owner)
        matches = [
            m
            for m in item["mounts"]
            if m["Destination"] == destination and m["Type"] == "volume"
        ]
        if len(matches) != 1:
            raise ValueError(
                "Expected an explicitly identified persistent " + owner + " volume"
            )
        result[owner] = matches[0]["Name"]
    return result


def assert_original_rings(target, items, folder):
    for owner, filename in (
        ("gateway", "user-keys.json"),
        ("core-service", "device-keys.json"),
        ("agent-service", "agent-keys.json"),
    ):
        source = service(items, owner)
        check = folder / ("source-" + filename)
        run(["docker", "cp", source["id"] + ":/keys/" + filename, str(check)])
        try:
            if sha(check) != sha(target.bundle / "keys" / filename):
                raise ValueError(
                    "A source signing ring changed after import; do not rotate or replace it implicitly"
                )
        finally:
            check.unlink(missing_ok=True)


@contextmanager
def lock(bundle):
    path = bundle / ".operation.lock"
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        raise ValueError(
            "Another rollout may be active; inspect the lock and Docker state before removing a stale lock"
        ) from None
    try:
        os.write(fd, str(os.getpid()).encode())
        os.close(fd)
        yield
    finally:
        path.unlink(missing_ok=True)


def snapshot(target, items, destination):
    destination.mkdir(mode=0o700)
    mysql, kafka = service(items, "mysql")["id"], service(items, "kafka")["id"]
    unsafe = db(
        mysql,
        "SELECT table_schema,table_name FROM information_schema.tables WHERE table_schema IN ("
        + ",".join("'" + s + "'" for s in SCHEMAS)
        + ") AND table_type='BASE TABLE' AND engine<>'InnoDB';",
    )
    if unsafe.strip():
        raise ValueError(
            "Non-transactional business tables require a separately coordinated backup"
        )
    before = inventory(mysql)
    with (destination / "five-databases.sql").open("xb") as output:
        run(
            [
                "docker",
                "exec",
                mysql,
                "sh",
                "-c",
                PASSWORD
                + "exec mysqldump -uroot --single-transaction --set-gtid-purged=OFF --hex-blob --routines --triggers --events --databases "
                + " ".join(SCHEMAS),
            ],
            output=output,
        )
    if inventory(mysql) != before:
        raise ValueError(
            "Database inventory changed while frozen; snapshot is not a valid baseline"
        )
    for folder in ("keys", "secrets", "pki"):
        shutil.copytree(target.bundle / folder, destination / folder)
    shutil.copyfile(target.env_file, destination / "deployment.env")
    shutil.copyfile(
        target.bundle / "manifest.json", destination / "bundle-manifest.json"
    )
    if (target.bundle / "video-devices.json").exists():
        shutil.copyfile(
            target.bundle / "video-devices.json", destination / "video-devices.json"
        )
    run_output = run(
        [
            "docker",
            "exec",
            kafka,
            "/opt/kafka/bin/kafka-configs.sh",
            "--bootstrap-server",
            "localhost:29092",
            "--entity-type",
            "topics",
            "--describe",
            "--all",
        ]
    )
    (destination / "topic-configs.txt").write_bytes(run_output)
    for kind in ("earliest", "latest"):
        output = run(
            [
                "docker",
                "exec",
                kafka,
                "/opt/kafka/bin/kafka-get-offsets.sh",
                "--bootstrap-server",
                "localhost:29092",
                "--time",
                kind,
            ]
        )
        (destination / ("topic-" + kind + ".txt")).write_bytes(output)
    report = {
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "inventory": before,
        "offsets": offsets(kafka),
        "sourceContainers": items,
        "volumes": physical_volumes(items),
        "candidateCommit": run(["git", "rev-parse", "HEAD"]).decode().strip(),
        "sqlSha256": sha(destination / "five-databases.sql"),
        "files": {
            p.relative_to(destination).as_posix(): sha(p)
            for p in destination.rglob("*")
            if p.is_file()
        },
    }
    write_json(destination / "backup.json", report)
    return report


def validate_backup(folder):
    folder = private_path(folder)
    report = json.loads((folder / "backup.json").read_text(encoding="utf-8"))
    for relative, expected in report["files"].items():
        path = (folder / relative).resolve()
        if folder not in path.parents or sha(path) != expected:
            raise ValueError("Backup material changed or missing")
    return folder, report


def restore_sql(mysql, folder):
    # Applications must already be stopped. The caller verifies isolation/rollback eligibility.
    db(mysql, ";".join("DROP DATABASE IF EXISTS " + s for s in SCHEMAS) + ";")
    with (folder / "five-databases.sql").open("rb") as stream:
        # Stream the full five-schema dump instead of loading it into memory or putting SQL in argv.
        import subprocess

        result = subprocess.run(
            ["docker", "exec", "-i", mysql, "sh", "-c", PASSWORD + "exec mysql -uroot"],
            stdin=stream,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=ROOT,
        )
    if result.returncode:
        raise RuntimeError(
            "Restore failed; keep every writer stopped and recover from the validated backup"
        )


def wait_mysql(mysql):
    for _ in range(60):
        try:
            db(mysql, "SELECT 1;")
            return
        except RuntimeError:
            time.sleep(1)
    raise RuntimeError("MySQL did not become ready; no application was resumed")


def candidate_images(target):
    return {
        service: run(
            [
                "docker",
                "image",
                "inspect",
                "capyiot/" + service + ":" + target.values["MS_IMAGE_TAG"],
                "--format",
                "{{.Id}}",
            ]
        )
        .decode()
        .strip()
        for service in (*APPS, "frontend")
    }


def operate(args):
    target = Target(
        args.project,
        args.compose_file,
        args.env_file,
        verification=args.action == "restore-rehearsal",
    )
    if args.action != "freeze-active":
        target.preflight()
    folder = private_path(args.report_dir)
    if target.bundle == folder or target.bundle in folder.parents:
        raise ValueError(
            "Reports must be outside the credential bundle to keep snapshots finite"
        )
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    state_file = folder / "rollout.json"
    state = (
        json.loads(state_file.read_text(encoding="utf-8"))
        if state_file.exists()
        else {
            "project": target.project,
            "bundle": str(target.bundle),
            "phase": "PREPARED",
            "everActivated": False,
        }
    )
    if state["project"] != target.project or state["bundle"] != str(target.bundle):
        raise ValueError("A rollout report belongs to a different target")
    source = target.manifest.get("sourceProject")
    if source and args.action not in ("freeze-active", "snapshot-active"):
        if name(source) == target.project:
            raise ValueError("Candidate and source projects must differ")
        actual = physical_volumes(containers(source))
        expected = {
            k: target.values["MS_VOLUME_PREFIX"] + "_" + v
            for k, v in [
                ("mysql", "mysql-data"),
                ("kafka", "kafka-data"),
                ("redis", "redis-data"),
                ("emqx", "emqx-data"),
            ]
        }
        if actual != expected:
            raise ValueError(
                "Candidate does not reuse the exact source physical volumes"
            )
    if args.action == "preflight":
        if source:
            old = containers(name(source))
            if source == target.project:
                raise ValueError("Candidate and source projects must differ")
            actual = physical_volumes(old)
            expected = {
                k: target.values["MS_VOLUME_PREFIX"] + "_" + v
                for k, v in [
                    ("mysql", "mysql-data"),
                    ("kafka", "kafka-data"),
                    ("redis", "redis-data"),
                    ("emqx", "emqx-data"),
                ]
            }
            if actual != expected:
                raise ValueError(
                    "Candidate does not reuse the exact four source data volumes"
                )
            assert_original_rings(target, old, folder)
            validate_source_credentials(target, old)
        run(
            target.compose(
                "config", "--quiet", offline=target.manifest["modelMode"] == "offline"
            )
        )
        print("Preflight passed; no containers or data changed.")
        return
    if not args.execute:
        raise ValueError(
            "This operation changes the explicitly selected target; pass --execute when ready"
        )
    with lock(target.bundle):
        if args.action == "freeze":
            if not source or state["everActivated"]:
                raise ValueError(
                    "Initial freeze requires the original source before activation"
                )
            if state["phase"] not in ("PREPARED", "FREEZING"):
                raise ValueError("Freeze is only valid before a baseline snapshot")
            original = containers(source)
            assert_original_rings(target, original, folder)
            validate_source_credentials(target, original)
            if state["phase"] == "PREPARED":
                state.update(phase="FREEZING", sourceContainers=original)
                write_json(state_file, state)
            stop_apps(source)
            state["phase"] = "SOURCE_FROZEN"
        elif args.action == "snapshot":
            if state["phase"] != "SOURCE_FROZEN" or not source:
                raise ValueError("Freeze source writers before taking the baseline")
            backup = folder / "baseline"
            snapshot(target, stopped(source), backup)
            state.update(phase="SNAPSHOT_READY", backup=str(backup))
        elif args.action == "start-passive":
            required = (
                ("PREPARED", "PASSIVE_STARTING")
                if not source
                else ("SNAPSHOT_READY", "PASSIVE_STARTING")
            )
            if state["phase"] not in required:
                raise ValueError(
                    "A frozen, verified baseline must precede candidate migration"
                )
            images = candidate_images(target)
            if not source and state["phase"] == "PREPARED":
                existing = (
                    run(["docker", "volume", "ls", "--format", "{{.Name}}"])
                    .decode()
                    .splitlines()
                )
                if containers(target.project) or any(
                    v.startswith(target.values["MS_VOLUME_PREFIX"] + "_")
                    for v in existing
                ):
                    raise ValueError(
                        "Fresh means unused project and volumes; select upgrade mode for existing data"
                    )
            if source:
                old = stopped(source)
                _, baseline = validate_backup(state["backup"])
                if offsets(service(old, "kafka")["id"]) != baseline["offsets"]:
                    raise ValueError(
                        "Kafka positions changed after the baseline; re-establish a frozen snapshot"
                    )
                for item in old:
                    if item["running"] and item["service"] in (
                        "mysql",
                        "kafka",
                        "redis",
                        "emqx",
                    ):
                        run(["docker", "stop", "--time", "45", item["id"]])
                # Stopped broker data is retained as an offline recovery artifact, never reset in place.
                offline = folder / "baseline/kafka-volume"
                if not offline.exists():
                    run(
                        [
                            "docker",
                            "cp",
                            service(old, "kafka")["id"] + ":/var/lib/kafka/data/.",
                            str(offline),
                        ]
                    )
                    baseline["files"].update(
                        {
                            p.relative_to(folder / "baseline").as_posix(): sha(p)
                            for p in offline.rglob("*")
                            if p.is_file()
                        }
                    )
                    baseline["kafkaColdCopy"] = True
                    write_json(folder / "baseline/backup.json", baseline)
            for volume in ("mysql-data", "redis-data", "kafka-data", "emqx-data"):
                using = (
                    run(
                        [
                            "docker",
                            "ps",
                            "--no-trunc",
                            "--filter",
                            "volume="
                            + target.values["MS_VOLUME_PREFIX"]
                            + "_"
                            + volume,
                            "-q",
                        ]
                    )
                    .decode()
                    .split()
                )
                own = {
                    c["id"] for c in containers(target.project) if c["service"] in INFRA
                }
                if set(using) - own:
                    raise ValueError(
                        "A target data volume is already mounted by a running container"
                    )
            state["phase"] = "PASSIVE_STARTING"
            write_json(state_file, state)
            run(
                target.compose(
                    "up",
                    "-d",
                    "--wait",
                    "--wait-timeout",
                    "360",
                    offline=target.manifest["modelMode"] == "offline",
                )
            )
            if (
                source
                and offsets(service(containers(target.project), "kafka")["id"])
                != baseline["offsets"]
            ):
                raise ValueError(
                    "Broker positions differ after restart; keep the candidate passive and investigate retention or extra producers"
                )
            state.update(
                phase="PASSIVE_READY",
                candidateImages=images,
                candidateCommit=run(["git", "rev-parse", "HEAD"]).decode().strip(),
            )
        elif args.action == "activate":
            if state["phase"] != "PASSIVE_READY" or state["everActivated"]:
                raise ValueError(
                    "Activation requires the passive candidate and is never an automatic retry"
                )
            if source:
                if not args.restore_proof:
                    raise ValueError(
                        "Upgrade activation requires an isolated restore proof for its baseline"
                    )
                _, baseline = validate_backup(state["backup"])
                proof = json.loads(
                    private_path(args.restore_proof).read_text(encoding="utf-8")
                )
                if (
                    proof.get("sqlSha256") != baseline["sqlSha256"]
                    or not proof.get("verifiedInventory")
                    or not proof.get("targetProject", "").startswith(
                        "capyiot-ms-verify-"
                    )
                ):
                    raise ValueError(
                        "Restore proof does not match this upgrade baseline"
                    )
            if (
                candidate_images(target) != state["candidateImages"]
                or run(["git", "rev-parse", "HEAD"]).decode().strip()
                != state["candidateCommit"]
            ):
                raise ValueError(
                    "Candidate image IDs or source commit changed after passive startup"
                )
            if source:
                stopped(source)
            if any(
                c["running"] and c["service"] == "core-service"
                for c in containers(target.project)
            ):
                raise ValueError("A Core writer must never be part of the candidate")
            state.update(phase="ACTIVATING", everActivated=True)
            write_json(state_file, state)
            (target.bundle / "active.env").write_text(
                "MS_WRITERS_ENABLED=true\nMS_PLATFORM_BOOTSTRAP=false\n",
                encoding="utf-8",
            )
            run(
                target.compose(
                    "--profile",
                    "public",
                    "--profile",
                    "ingress",
                    "up",
                    "-d",
                    "--wait",
                    "--wait-timeout",
                    "360",
                    active=True,
                    offline=target.manifest["modelMode"] == "offline",
                )
            )
            state["phase"] = "ACTIVE"
        elif args.action == "freeze-active":
            if not state["everActivated"]:
                raise ValueError("Candidate has not been activated")
            stop_apps(target.project)
            state["phase"] = "ACTIVE_FROZEN"
        elif args.action == "snapshot-active":
            if state["phase"] != "ACTIVE_FROZEN":
                raise ValueError(
                    "Freeze the candidate before recording post-cutover data"
                )
            destination = folder / (
                "post-cutover-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
            )
            snapshot(target, stopped(target.project), destination)
            state["postCutoverBackup"] = str(destination)
        elif args.action == "restore-rehearsal":
            if target.manifest["mode"] != "fresh" or not args.backup_dir:
                raise ValueError(
                    "Rehearse restore into an explicit fresh isolated project"
                )
            existing = (
                run(["docker", "volume", "ls", "--format", "{{.Name}}"])
                .decode()
                .splitlines()
            )
            if containers(target.project) or any(
                v.startswith(target.values["MS_VOLUME_PREFIX"] + "_") for v in existing
            ):
                raise ValueError(
                    "Restore rehearsal requires an unused isolated project and volumes"
                )
            backup, manifest = validate_backup(args.backup_dir)
            run(target.compose("up", "-d", "--wait", "mysql"))
            mysql = service(containers(target.project), "mysql")["id"]
            wait_mysql(mysql)
            restore_sql(mysql, backup)
            if inventory(mysql) != manifest["inventory"]:
                raise ValueError(
                    "Restored inventory does not match the five-schema backup"
                )
            write_json(
                folder / "restore-proof.json",
                {
                    "sqlSha256": manifest["sqlSha256"],
                    "verifiedInventory": True,
                    "targetProject": target.project,
                    "backup": str(backup),
                },
            )
            state["phase"] = "RESTORE_REHEARSED"
        elif args.action == "rollback":
            if state["everActivated"]:
                raise ValueError(
                    "New writers might have accepted writes: freeze, snapshot-active, restore separately and reconcile; automatic old-code rollback is forbidden"
                )
            if not source or not state.get("backup") or not args.restore_proof:
                raise ValueError(
                    "A baseline and successful isolated restore proof are required"
                )
            backup, manifest = validate_backup(state["backup"])
            proof = json.loads(
                private_path(args.restore_proof).read_text(encoding="utf-8")
            )
            if proof.get("sqlSha256") != manifest["sqlSha256"] or not proof.get(
                "verifiedInventory"
            ):
                raise ValueError("The restore proof does not match this baseline")
            stop_apps(target.project)
            candidate = stopped(target.project)
            kafka = service(candidate, "kafka")["id"]
            if offsets(kafka) != manifest["offsets"]:
                raise ValueError(
                    "Kafka offsets or log ends changed; do not rewind them or restart old writers"
                )
            for item in candidate:
                if item["running"] and item["service"] in INFRA:
                    run(["docker", "stop", "--time", "45", item["id"]])
            old = stopped(source)
            for saved in state["sourceContainers"]:
                if saved["service"] in ("simulator", "agent-migrate"):
                    continue
                current = next((c for c in old if c["id"] == saved["id"]), None)
                if current is None or current["image"] != saved["image"]:
                    raise ValueError(
                        "Original pinned containers/images are missing; no automatic source restart"
                    )
            state["phase"] = "SOURCE_RESTORING"
            write_json(state_file, state)
            mysql = service(old, "mysql")["id"]
            run(["docker", "start", mysql])
            wait_mysql(mysql)
            restore_sql(mysql, backup)
            if inventory(mysql) != manifest["inventory"]:
                raise ValueError(
                    "Source restore inventory differs; keep all writers stopped"
                )
            state["phase"] = "SOURCE_RESTORED_STOPPED"
            # Operator reviews the restored state before explicitly resuming retained source containers.
        elif args.action == "resume-source":
            if state["phase"] != "SOURCE_RESTORED_STOPPED" or state["everActivated"]:
                raise ValueError("Only a restored pre-activation source can be resumed")
            if any(
                c["running"]
                for c in containers(target.project)
                if c["service"] in OLD_APPS
            ):
                raise ValueError("Candidate writers still running")
            original = containers(source)
            for saved in state["sourceContainers"]:
                if saved["service"] in ("simulator", "agent-migrate"):
                    continue
                if not any(
                    c["id"] == saved["id"] and c["image"] == saved["image"]
                    for c in original
                ):
                    raise ValueError("Pinned source containers changed")
            for names in (
                ("mysql", "redis", "kafka", "emqx"),
                (
                    "core-service",
                    "platform-service",
                    "device-service",
                    "alarm-service",
                    "workflow-service",
                    "gateway",
                    "device-ingestion",
                    "agent-service",
                    "frontend",
                ),
            ):
                ids = [
                    c["id"]
                    for c in state["sourceContainers"]
                    if c["running"] and c["service"] in names
                ]
                if ids:
                    run(["docker", "start", *ids])
            state["phase"] = "SOURCE_RESUMED"
        write_json(state_file, state)
    print(
        "Completed explicit rollout step: " + args.action + "; state=" + state["phase"]
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "action",
        choices=(
            "preflight",
            "freeze",
            "snapshot",
            "start-passive",
            "activate",
            "freeze-active",
            "snapshot-active",
            "restore-rehearsal",
            "rollback",
            "resume-source",
        ),
    )
    parser.add_argument("--project", required=True)
    parser.add_argument("--compose-file", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--report-dir", type=Path, required=True)
    parser.add_argument("--backup-dir", type=Path)
    parser.add_argument("--restore-proof", type=Path)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    try:
        operate(args)
    except (ValueError, RuntimeError, OSError, KeyError):
        raise SystemExit(
            "Rollout stopped. Inspect the private state and target; keep writers frozen if outcome is unknown. No automatic volume deletion, offset reset or source restart was attempted."
        ) from None


if __name__ == "__main__":
    main()
