"""Add the Agent schema to an existing Compose MySQL volume without replacing data."""
import re
import subprocess
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    values = dict(line.split("=", 1) for line in (root / ".env").read_text().splitlines()
                  if "=" in line and not line.startswith("#"))
    password = values.get("AGENT_DB_PASSWORD", "")
    if not re.fullmatch(r"[0-9a-fA-F]{32,128}", password):
        raise SystemExit("Run scripts/init_env.py to generate AGENT_DB_PASSWORD first")
    sql = "CREATE DATABASE IF NOT EXISTS agent_db CHARACTER SET utf8mb4;\n" + \
          "CREATE USER IF NOT EXISTS 'agent_app'@'%' IDENTIFIED BY '" + password + "';\n" + \
          "GRANT ALL PRIVILEGES ON agent_db.* TO 'agent_app'@'%';\n"
    # Root password stays inside the existing container; SQL/credential go over stdin, not argv.
    result = subprocess.run(["docker", "compose", "exec", "-T", "mysql", "sh", "-c",
                             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'],
                            cwd=root, input=sql.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise SystemExit("Agent schema provisioning failed; check the MySQL container and local credentials")
    print("Agent schema and restricted account are available; no existing data was removed.")


if __name__ == "__main__":
    main()
