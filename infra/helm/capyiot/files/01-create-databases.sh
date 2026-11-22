#!/bin/sh
set -eu

# init_env.py generates hexadecimal passwords for these SQL literals.
for password in "$DEVICE_DB_PASSWORD" "$ALARM_DB_PASSWORD" "$WORKFLOW_DB_PASSWORD"; do
  case "$password" in
    ''|*[!0-9a-fA-F]*) echo 'Database passwords must be hexadecimal' >&2; exit 1 ;;
  esac
done

# The image's client and server may have different compiled socket defaults.
# Match the entrypoint's temporary server; TCP is disabled during initialization.
socket="$(mysqld --verbose --help --log-bin-index="$(mktemp -u)" 2>/dev/null | awk '$1 == "socket" && /^[^ \t]/ { print $2; exit }')"
[ -n "$socket" ] || { echo 'Cannot determine the MySQL server socket' >&2; exit 1; }
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --socket="$socket" -uroot <<SQL
CREATE DATABASE IF NOT EXISTS device_db CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS alarm_db CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS workflow_db CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'device_app'@'%' IDENTIFIED BY '$DEVICE_DB_PASSWORD';
CREATE USER IF NOT EXISTS 'alarm_app'@'%' IDENTIFIED BY '$ALARM_DB_PASSWORD';
CREATE USER IF NOT EXISTS 'workflow_app'@'%' IDENTIFIED BY '$WORKFLOW_DB_PASSWORD';
GRANT ALL PRIVILEGES ON device_db.* TO 'device_app'@'%';
GRANT ALL PRIVILEGES ON alarm_db.* TO 'alarm_app'@'%';
GRANT ALL PRIVILEGES ON workflow_db.* TO 'workflow_app'@'%';
SQL
