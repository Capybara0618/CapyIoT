# Historical Core deployment helper. Native deployments use init_microservices.py and microservices_control.py.
"""Back up existing business schemas and reject ambiguous location migrations before V1 cutover."""

import argparse
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def sql(statement):
    result = subprocess.run(["docker", "compose", "exec", "-T", "mysql", "sh", "-c",
                             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B'],
                            input=statement.encode(), capture_output=True, cwd=ROOT, check=True)
    return result.stdout.decode()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bootstrap-platform", action="store_true")
    args = parser.parse_args()
    folder = ROOT / "var" / "backups" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    folder.mkdir(parents=True, exist_ok=False)
    report = {"createdAt": datetime.now(timezone.utc).isoformat()}
    report["migrations"] = sql("SELECT version,description,checksum,success FROM device_db.flyway_schema_history ORDER BY installed_rank;")
    report["tableRows"] = sql("SELECT table_schema,table_name,table_rows FROM information_schema.tables WHERE table_schema IN ('device_db','alarm_db','workflow_db') ORDER BY table_schema,table_name;")
    # This check is relevant before V8, whose tree reuses stable legacy location IDs.
    version = sql("SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM device_db.flyway_schema_history WHERE success=1;").strip()
    if int(version) < 8:
        collisions = sql("SELECT id,COUNT(*) FROM (SELECT id FROM device_db.building UNION ALL SELECT id FROM device_db.floor UNION ALL SELECT id FROM device_db.area) locations GROUP BY id HAVING COUNT(*)>1;")
        invalid = sql("SELECT d.id FROM device_db.device d LEFT JOIN device_db.building b ON b.id=d.building_id LEFT JOIN device_db.floor f ON f.id=d.floor_id LEFT JOIN device_db.area a ON a.id=d.area_id WHERE b.id IS NULL OR f.id IS NULL OR b.campus_id<>d.campus_id OR f.building_id<>b.id OR (d.area_id IS NOT NULL AND (a.id IS NULL OR a.floor_id<>f.id));")
        report["locationIdCollisions"] = collisions
        report["invalidDeviceLocations"] = invalid
        if collisions.strip() or invalid.strip():
            (folder / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
            raise SystemExit("Migration preflight rejected ambiguous locations; inspect the private backup report.")
    dump = folder / "business.sql"
    with dump.open("wb") as output:
        subprocess.run(["docker", "compose", "exec", "-T", "mysql", "sh", "-c",
                        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --databases device_db alarm_db workflow_db'],
                       stdout=output, stderr=subprocess.PIPE, cwd=ROOT, check=True)
    report["backupSha256"] = hashlib.sha256(dump.read_bytes()).hexdigest()
    report["backupBytes"] = dump.stat().st_size
    (folder / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.bootstrap_platform:
        subprocess.run(["docker", "compose", "exec", "-T", "mysql", "sh",
                        "/docker-entrypoint-initdb.d/01-create-databases.sh"], cwd=ROOT, check=True)
    print(f"Preflight passed. Private backup and row/checksum inventory: {folder}")


if __name__ == "__main__":
    main()
