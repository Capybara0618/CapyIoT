#!/bin/sh
set -eu

set -- -jar /app/app.jar
if [ -n "${CAPYIOT_JFR_OPTIONS:-}" ]; then
    set -- "-XX:StartFlightRecording=$CAPYIOT_JFR_OPTIONS" "$@"
fi
if [ -n "${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:-}" ]; then
    set -- -javaagent:/app/opentelemetry-javaagent.jar "$@"
fi
exec java "$@"
