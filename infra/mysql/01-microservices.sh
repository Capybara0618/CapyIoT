#!/bin/sh
set -eu
socket="$(mysqld --verbose --help --log-bin-index="$(mktemp -u)" 2>/dev/null | awk '$1 == "socket" && /^[^ \t]/ { print $2; exit }')"
[ -n "$socket" ] || exit 1
# Fresh volumes only. Existing accounts/passwords are never altered by this entrypoint.
for domain in device alarm workflow platform agent; do
    password="$(cat "/run/secrets/$domain-db-password")"
    case "$password" in ''|*[!0-9a-fA-F]*) echo 'Invalid DB secret format' >&2; exit 1 ;; esac
    [ "${#password}" -ge 32 ] && [ "${#password}" -le 128 ] || exit 1
    MYSQL_PWD="$(cat /run/secrets/mysql-root-password)" mysql --protocol=socket --socket="$socket" -uroot <<SQL
CREATE DATABASE IF NOT EXISTS ${domain}_db CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS '${domain}_app'@'%' IDENTIFIED BY '$password';
GRANT ALL PRIVILEGES ON ${domain}_db.* TO '${domain}_app'@'%';
SQL
done
