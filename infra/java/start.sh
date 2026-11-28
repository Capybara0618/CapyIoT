#!/bin/sh
set -eu
rm -f /tmp/capyiot-draining

# Each container receives only its own mounted files. Values never appear in Docker argv/inspect.
load_secret() {
    if [ -f "/run/secrets/$2" ]; then
        value="$(cat "/run/secrets/$2")"
        [ -n "$value" ] || exit 1
        export "$1=$value"
        unset value
    fi
}
case "${CAPYIOT_DB_PASSWORD_NAME:-}" in
    DEVICE_DB_PASSWORD|ALARM_DB_PASSWORD|WORKFLOW_DB_PASSWORD|PLATFORM_DB_PASSWORD)
        load_secret "$CAPYIOT_DB_PASSWORD_NAME" db-password ;;
    '') ;;
    *) exit 1 ;;
esac
case "${CAPYIOT_FINGERPRINT_NAME:-}" in
    DEVICE_COMMAND_FINGERPRINT_KEY|FAULT_COMMAND_FINGERPRINT_KEY|WORKFLOW_COMMAND_FINGERPRINT_KEY|PLATFORM_COMMAND_FINGERPRINT_KEY)
        load_secret "$CAPYIOT_FINGERPRINT_NAME" fingerprint-key ;;
    '') ;;
    *) exit 1 ;;
esac
load_secret SERVICE_TLS_KEYSTORE_PASSWORD tls-password
load_secret SERVER_SSL_KEY_STORE_PASSWORD tls-password
load_secret SESSION_PROXY_KEY session-proxy-key
load_secret EMQX_API_KEY emqx-api-key
load_secret EMQX_API_SECRET emqx-api-secret
load_secret DEMO_ADMIN_PASSWORD_HASH demo-admin-password-hash
load_secret DEMO_OPERATOR_PASSWORD_HASH demo-operator-password-hash

if [ "${CAPYIOT_MIGRATION_ONLY:-false}" = true ]; then
    exec java -Dloader.main=io.capyiot.service.runtime.SchemaMigrationMain -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
fi
set -- -jar /app/app.jar
if [ -n "${CAPYIOT_JFR_OPTIONS:-}" ]; then
    set -- "-XX:StartFlightRecording=$CAPYIOT_JFR_OPTIONS" "$@"
fi
if [ -n "${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:-}" ]; then
    set -- -javaagent:/app/opentelemetry-javaagent.jar "$@"
fi
exec java "$@"
