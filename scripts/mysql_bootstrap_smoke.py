"""Verify first-time database bootstrap with different client/server socket defaults."""

import argparse
from pathlib import Path
import subprocess
import tempfile
import time
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", default="mysql:8.4.11")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not (root / ".env").is_file():
        parser.error("Run python scripts/init_env.py first")
    (root / "var").mkdir(exist_ok=True)
    name = "capyiot-bootstrap-" + uuid.uuid4().hex[:10]
    with tempfile.TemporaryDirectory(prefix="mysql-bootstrap-", dir=root / "var") as directory:
        config = Path(directory).resolve()
        assert config.is_relative_to((root / "var").resolve())
        settings = config / "socket.cnf"
        settings.write_text("[mysqld]\nsocket=/var/lib/mysql/mysql.sock\n"
                            "[client]\nsocket=/var/run/mysqld/mysqld.sock\n", encoding="utf-8")
        try:
            subprocess.run([
                "docker", "run", "-d", "--name", name, "--env-file", str(root / ".env"),
                "--memory", "896m", "--cpus", "2", "--mount",
                f"type=bind,source={settings},target=/test/socket.cnf,readonly", "--mount",
                f"type=bind,source={root / 'infra/helm/capyiot/files/01-create-databases.sh'},"
                "target=/docker-entrypoint-initdb.d/01-create-databases.sh,readonly", "--entrypoint", "sh",
                args.image, "-ec", "cp /test/socket.cnf /etc/my.cnf; chmod 644 /etc/my.cnf; "
                "exec docker-entrypoint.sh mysqld",
            ], check=True, stdout=subprocess.DEVNULL, timeout=180)
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                running = subprocess.run(["docker", "inspect", "--format", "{{.State.Running}}", name],
                                         capture_output=True, text=True, check=True).stdout.strip()
                if running != "true":
                    break
                result = subprocess.run([
                    "docker", "exec", name, "sh", "-ec",
                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=tcp -h127.0.0.1 -uroot -N '
                    '-e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name IN '
                    "('device_db','alarm_db','workflow_db')\"",
                ], capture_output=True, text=True, timeout=10)
                if result.returncode == 0 and result.stdout.strip() == "3":
                    print("PASS: three databases initialized with different client/server socket defaults")
                    return
                time.sleep(2)
            subprocess.run(["docker", "logs", "--tail", "40", name], check=False)
            raise RuntimeError("Database bootstrap did not finish")
        finally:
            subprocess.run(["docker", "rm", "-f", "-v", name], stdout=subprocess.DEVNULL, check=False)


if __name__ == "__main__":
    main()
