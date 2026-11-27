#!/bin/sh
set -eu
EMQX_NODE__COOKIE="$(cat /run/secrets/emqx-node-cookie)"
export EMQX_NODE__COOKIE
exec /usr/bin/docker-entrypoint.sh /opt/emqx/bin/emqx foreground
