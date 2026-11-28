"""Explicit release, per-schema Jobs and compatible application rollback; no data rollback."""

import argparse
import json
import re
from pathlib import Path

import cloud_native_common as cn
from cloud_native_environment import verified_endpoints
from cloud_native_manifests import APPS, DATABASES, application_objects, secret_objects
from microservices_common import ROOT, sha, write_json


def apply(target, objects):
    cn.execute(target.kubectl("apply", "-f", "-"), data=json.dumps(objects).encode())


def resources(items):
    return {"apiVersion": "v1", "kind": "List", "items": items}


def release_path(target, release_id):
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,35}", release_id):
        raise ValueError("Explicit release ID must be a DNS label of at most 36 characters")
    return target.bundle / ("release-" + release_id + ".json")


def read_release(target, release_id):
    value = json.loads(release_path(target, release_id).read_text(encoding="utf-8"))
    if value["ownerId"] != target.manifest["ownerId"] or value["releaseId"] != release_id:
        raise ValueError("Release does not belong to the selected private target")
    return value


def save_release(target, value, phase):
    write_json(release_path(target, value["releaseId"]), {**value, "phase": phase})


def assert_target(target):
    state = target.state()
    target.assert_daemon(state)
    target.assert_cluster(state)
    verified_endpoints(target)


def prepare_release(target, release_id, image_tag):
    path = release_path(target, release_id)
    if path.exists():
        raise ValueError("Release ID already exists; reconcile or choose a new reviewed release")
    if not re.fullmatch(r"cn-[a-f0-9]{7,40}(?:-[a-z0-9-]{1,16})?", image_tag):
        raise ValueError("Image tag must contain the source commit")
    assert_target(target)
    images, image_ids = {}, {}
    for service in (*APPS, "frontend"):
        ref = "capyiot/" + service + ":" + image_tag
        obj = json.loads(cn.execute(["docker", "image", "inspect", ref]))[0]
        images[service], image_ids[service] = ref, obj["Id"]
    active_file = target.bundle / "active-release.json"
    previous = json.loads(active_file.read_text()) if active_file.exists() else None
    if previous and previous["ownerId"] != target.manifest["ownerId"]:
        raise ValueError("Active release belongs to another owner")
    write_json(path, {"releaseId": release_id, "ownerId": target.manifest["ownerId"],
                     "phase": "PLANNED", "images": images, "imageIds": image_ids,
                     "commit": cn.execute(["git", "rev-parse", "HEAD"]).decode().strip(),
                     "sourceDigest": sha(ROOT / "scripts/cloud_native_manifests.py"),
                     "previousReleaseId": previous["releaseId"] if previous else None})


def assert_images(target, release):
    if release["sourceDigest"] != sha(ROOT / "scripts/cloud_native_manifests.py"):
        raise ValueError("Manifest source changed after release preparation")
    for name, image in release["images"].items():
        if json.loads(cn.execute(["docker", "image", "inspect", image]))[0]["Id"] != release["imageIds"][name]:
            raise ValueError("Candidate image tag changed after release preparation")


def migration_job(target, release, service):
    domain = "agent" if service == "agent-service" else DATABASES[service][0]
    name = "migration-" + domain + "-" + release["releaseId"]
    secret_name = name + "-credentials"
    import base64
    secret = {"apiVersion": "v1", "kind": "Secret", "immutable": True, "type": "Opaque",
              "metadata": {"name": secret_name, "namespace": cn.NAMESPACE},
              "data": {"db-password": base64.b64encode(cn.safe_file(target.bundle, "secrets/" + domain + "-db-password").read_bytes()).decode()}}
    container = {"name": "migration", "image": release["images"][service], "imagePullPolicy": "IfNotPresent",
                 "env": [{"name": "CAPYIOT_MIGRATION_ONLY", "value": "true"},
                         {"name": "CAPYIOT_SCHEMA", "value": domain},
                         {"name": "CAPYIOT_MIGRATION_ACTION", "value": "migrate"},
                         {"name": "AGENT_DB_HOST", "value": "mysql"}],
                 "volumeMounts": [{"name": "credentials", "mountPath": "/run/secrets", "readOnly": True}, {"name": "tmp", "mountPath": "/tmp"}],
                 "resources": {"requests": {"cpu": "100m", "memory": "128Mi"}, "limits": {"cpu": "1", "memory": "256Mi"}},
                 "securityContext": {"allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True, "capabilities": {"drop": ["ALL"]}}}
    if domain == "agent":
        container["args"] = ["alembic", "upgrade", "head"]
    job = {"apiVersion": "batch/v1", "kind": "Job", "metadata": {"name": name, "namespace": cn.NAMESPACE},
           "spec": {"backoffLimit": 0, "activeDeadlineSeconds": 180, "template": {"spec": {
               "restartPolicy": "Never", "automountServiceAccountToken": False,
               "securityContext": {"runAsNonRoot": True, "runAsUser": 10001, "fsGroup": 10001},
               "containers": [container], "volumes": [{"name": "credentials", "secret": {"secretName": secret_name, "defaultMode": 292}}, {"name": "tmp", "emptyDir": {"sizeLimit": "32Mi"}}]}}}}
    return secret, job


def migrate(target, release):
    if release["phase"] != "PLANNED":
        raise ValueError("Migration requires a new planned release; uncertain Jobs must be reconciled")
    assert_images(target, release)
    for image in release["images"].values():
        cn.execute([target.manifest["kindBinary"], "load", "docker-image", "--name", cn.CLUSTER, image])
    apply(target, {"apiVersion": "v1", "kind": "Namespace", "metadata": {"name": cn.NAMESPACE, "labels": {"io.capyiot.cn/target": cn.CLUSTER}}})
    apply(target, verified_endpoints(target))
    save_release(target, release, "MIGRATING")
    for service in (*DATABASES, "agent-service"):
        secret, job = migration_job(target, release, service)
        apply(target, resources([secret, job]))
        cn.execute(target.kubectl("wait", "--for=condition=complete", "job/" + job["metadata"]["name"], "--timeout=180s"))
    save_release(target, release, "MIGRATED")


def deploy(target, release, *, active=False):
    allowed = {"PASSIVE_READY"} if active else {"MIGRATED"}
    if release["phase"] not in allowed:
        raise ValueError("Release is not in the required migration/passive phase")
    assert_images(target, release)
    version = release["releaseId"] + ("-a" if active else "-p")
    apply(target, secret_objects(target.bundle, version))
    items = application_objects(version=version, active=active,
        fresh=target.manifest["mode"] == "fresh" and not release["previousReleaseId"],
        images=release["images"], model=target.manifest["model"])
    save_release(target, release, "ACTIVATING" if active else "DEPLOYING_PASSIVE")
    apply(target, resources(items))
    for service in (*APPS, "frontend"):
        cn.execute(target.kubectl("rollout", "status", "deployment/" + service, "--timeout=240s"))
    save_release(target, release, "ACTIVE" if active else "PASSIVE_READY")
    if active:
        write_json(target.bundle / "active-release.json", {"ownerId": target.manifest["ownerId"], "releaseId": release["releaseId"]})


def rollback(target, release):
    previous_id = release.get("previousReleaseId")
    if not previous_id:
        raise ValueError("No previously active compatible release is recorded")
    previous = read_release(target, previous_id)
    if previous["phase"] != "ACTIVE":
        raise ValueError("Rollback target is not a recorded active release")
    # Validate current schemas with the previous images before routing to the old binary.
    # Flyway validate checks every historical checksum and rejects a future migration unknown to that binary.
    assert_images(target, previous)
    for service in (*DATABASES, "agent-service"):
        secret, job = migration_job(target, {**previous, "releaseId": release["releaseId"] + "-rb"}, service)
        if service == "agent-service":
            job["spec"]["template"]["spec"]["containers"][0]["args"] = ["python", "-c", "from app.database import Database;from app.config import DatabaseSettings;d=Database(DatabaseSettings());d.ready();d.close()"]
        else:
            for variable in job["spec"]["template"]["spec"]["containers"][0]["env"]:
                if variable["name"] == "CAPYIOT_MIGRATION_ACTION": variable["value"] = "validate"
        apply(target, resources([secret, job]))
        cn.execute(target.kubectl("wait", "--for=condition=complete", "job/" + job["metadata"]["name"], "--timeout=180s"))
    apply(target, resources(application_objects(version=previous_id + "-a", active=True, images=previous["images"], model=target.manifest["model"])))
    for service in (*APPS, "frontend"):
        cn.execute(target.kubectl("rollout", "status", "deployment/" + service, "--timeout=240s"))
    write_json(target.bundle / "active-release.json", {"ownerId": target.manifest["ownerId"], "releaseId": previous_id})
    save_release(target, release, "ROLLED_BACK_APPLICATION_ONLY")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["plan", "prepare", "migrate", "passive", "activate", "rollback", "status"])
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--image-tag")
    for name in ("cluster", "context", "namespace", "infra-project"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    try:
        cn.validate_selection(args.cluster, args.context, args.namespace, args.infra_project)
        if args.action == "plan" or not args.execute:
            print(json.dumps({"execution": False, "action": args.action, "releaseId": args.release_id, "namespace": cn.NAMESPACE}))
            return
        target = cn.Target(args.bundle, cluster=args.cluster, context=args.context, namespace=args.namespace, infra_project=args.infra_project)
        with cn.operation_lock(target.bundle):
            assert_target(target)
            if args.action == "prepare":
                prepare_release(target, args.release_id, args.image_tag or "")
            else:
                release = read_release(target, args.release_id)
                if args.action == "migrate": migrate(target, release)
                elif args.action in {"passive", "activate"}: deploy(target, release, active=args.action == "activate")
                elif args.action == "rollback": rollback(target, release)
                else: print(json.dumps({"releaseId": release["releaseId"], "phase": release["phase"]}))
        print("CN release action completed; schema and Kafka contents were not rolled back")
    except (ValueError, RuntimeError, OSError, KeyError, TypeError):
        parser.exit(1, "CN release refused or failed; retain the private record and reconcile the selected target\n")


if __name__ == "__main__":
    main()
