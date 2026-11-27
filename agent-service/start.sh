#!/bin/sh
set -eu
if [ -f /run/secrets/db-password ]; then
    AGENT_DB_PASSWORD="$(cat /run/secrets/db-password)"
    export AGENT_DB_PASSWORD
fi
if [ -f /run/secrets/deepseek-api-key ]; then
    DEEPSEEK_API_KEY="$(cat /run/secrets/deepseek-api-key)"
    export DEEPSEEK_API_KEY
fi
exec "$@"
