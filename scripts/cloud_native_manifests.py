"""Versioned application resources and per-service file selections; no runtime side effects."""

import base64
import json
import re

import cloud_native_common as cn
from microservices_common import APPS

PORTS = dict(zip(APPS, (8087, 8082, 8083, 8084, 8080, 8081, 8086)))
DATABASES = {"platform-service": ("platform", "PLATFORM", "4"),
             "device-service": ("device", "DEVICE", "16"),
             "alarm-service": ("alarm", "FAULT", "7"),
             "workflow-service": ("workflow", "WORKFLOW", "6")}


def files_for(service):
    files = {"internal-ca.pem": "pki/ca.pem", "server.p12": f"pki/{service}/server.p12",
             "tls-password": "secrets/tls-password", "service-keys.json": f"keys/{service}.json"}
    if service in DATABASES:
        domain, fingerprint, _ = DATABASES[service]
        files.update({"db-password": f"secrets/{domain}-db-password",
                      "fingerprint-key": f"secrets/{fingerprint.lower()}-fingerprint-key"})
    if service == "platform-service":
        files.update({"demo-admin-password-hash": "secrets/demo-admin-password-hash",
                      "demo-operator-password-hash": "secrets/demo-operator-password-hash"})
    if service == "device-service":
        files.update({"device-keys.json": "keys/device-keys.json", "emqx-api-key": "secrets/emqx-api-key",
                      "emqx-api-secret": "secrets/emqx-api-secret"})
    if service == "gateway":
        files.update({"user-keys.json": "keys/user-keys.json", "session-proxy-key": "secrets/session-proxy-key"})
    if service == "device-ingestion":
        files["mqtt-ca.pem"] = "pki/edge/ca.pem"
    if service == "agent-service":
        files = {"internal-ca.pem": "pki/ca.pem", "db-password": "secrets/agent-db-password",
                 "service-keys.json": "keys/agent-service.json", "agent-keys.json": "keys/agent-keys.json",
                 "deepseek-api-key": "secrets/deepseek-api-key",
                 "server.pem": "pki/agent-service/server.pem", "server.key": "pki/agent-service/server.key"}
    if service == "frontend":
        files = {"internal-ca.pem": "pki/ca.pem", "server.pem": "pki/edge/server.pem",
                 "server.key": "pki/edge/server.key", "session-proxy-key": "secrets/session-proxy-key"}
    return files


def configuration(service, *, active=False, fresh=False, model=None):
    enabled = "true" if active else "false"
    env = {"SERVICE_TLS_CA_FILE": "/run/secrets/internal-ca.pem", "SERVICE_TLS_ENABLED": "true",
           "SERVICE_TLS_KEYSTORE": "/run/secrets/server.p12", "SERVER_SSL_ENABLED": "true",
           "SERVER_SSL_KEY_STORE": "/run/secrets/server.p12", "SERVER_SSL_KEY_STORE_TYPE": "PKCS12",
           "USER_KEYS_GENERATE": "false", "KAFKA_BOOTSTRAP_SERVERS": "kafka:39092",
           "USER_JWKS_URI": "https://gateway:8080/.well-known/jwks.json",
           "AGENT_JWKS_URI": "https://agent-service:8086/.well-known/agent-jwks.json",
           "SPRING_FLYWAY_ENABLED": "false", "SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE": "45s",
           "MANAGEMENT_ENDPOINT_HEALTH_GROUP_LIVENESS_INCLUDE": "livenessState",
           "MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE": "readinessState,runtime",
           "CAPYIOT_DRAIN_FILE": "/tmp/capyiot-draining"}
    for name, key in (("platform-service", "PLATFORM"), ("device-service", "DEVICE"),
                      ("alarm-service", "ALARM"), ("workflow-service", "WORKFLOW"),
                      ("device-ingestion", "INGESTION"), ("agent-service", "AGENT")):
        env[key + "_SERVICE_URL"] = f"https://{name}:{PORTS[name]}"
    key = {"alarm-service": "FAULT", "device-ingestion": "INGESTION"}.get(service, service.split("-")[0].upper())
    env[key + "_SERVICE_KEY_FILE"] = "/run/secrets/service-keys.json"
    if service in DATABASES:
        domain, fingerprint, version = DATABASES[service]
        env.update({domain.upper() + "_DB_URL": f"jdbc:mysql://mysql:3306/{domain}_db?connectionTimeZone=UTC",
                    domain.upper() + "_DB_USER": domain + "_app", "CAPYIOT_DB_PASSWORD_NAME": domain.upper() + "_DB_PASSWORD",
                    "CAPYIOT_FINGERPRINT_NAME": fingerprint + "_COMMAND_FINGERPRINT_KEY",
                    fingerprint + "_DOMAIN_RUNTIME_ENABLED": enabled, "CAPYIOT_SCHEMA_VERSION": version,
                    "MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE": "readinessState,runtime,localDatabase"})
        if service != "platform-service":
            env[fingerprint + "_RUNTIME_ENABLED"] = enabled
        else:
            env["PLATFORM_BOOTSTRAP_ENABLED"] = "true" if fresh and not active else "false"
    if service == "device-service":
        env.update(DEVICE_KEY_FILE="/run/secrets/device-keys.json", EMQX_MANAGEMENT_URL="http://emqx:18083")
    if service == "gateway":
        env.update(USER_KEY_FILE="/run/secrets/user-keys.json", MANAGEMENT_SERVER_SSL_ENABLED="false")
    if service == "device-ingestion":
        env.update(MQTT_CONSUMPTION_ENABLED=enabled, MQTT_URL="ssl://emqx:8883",
                   MQTT_CLIENT_ID="capyiot-ingestion-cn-verify-1", MQTT_CA_FILE="/run/secrets/mqtt-ca.pem")
        if active:
            env["MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE"] += ",mqtt"
    if service == "agent-service":
        env = {"AGENT_DB_HOST": "mysql", "AGENT_SERVICE_CA_FILE": "/run/secrets/internal-ca.pem",
               "AGENT_SERVICE_KEY_FILE": "/run/secrets/service-keys.json",
               "AGENT_JWKS_URL": "https://gateway:8080/.well-known/jwks.json",
               "AGENT_DELEGATION_KEY_FILE": "/run/secrets/agent-keys.json",
               "AGENT_DELEGATION_KEYS_GENERATE": "false", "AGENT_DISPATCH_ENABLED": enabled,
               "AGENT_MODEL_TIMEOUT_SECONDS": "30", "AGENT_RUN_TIMEOUT_SECONDS": "90",
               "AGENT_MAX_MODEL_REQUESTS": "3", "AGENT_MAX_TOOL_CALLS": "5",
               "DEEPSEEK_MODEL": (model or {}).get("id", "offline-fixture"),
               "DEEPSEEK_BASE_URL": (model or {}).get("baseUrl", "http://model-fixture:8099")}
        for name, prop in (("platform", "platform"), ("device", "device"), ("fault", "alarm"), ("workflow", "workflow")):
            service_name = prop + "-service"
            env["AGENT_" + name.upper() + "_BASE_URL"] = f"https://{service_name}:{PORTS[service_name]}"
        if env["DEEPSEEK_MODEL"] == "offline-fixture":
            env["DEEPSEEK_BASE_URL"] = "http://model-fixture:8099"
            env["AGENT_ALLOW_INSECURE_MODEL_ENDPOINT"] = "true"
    if service == "frontend":
        env = {"NGINX_RESOLVER": "10.249.0.10"}
    return env


def application_objects(*, version="unreleased", active=False, fresh=False, images=None, model=None, replicas=None):
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,39}", version):
        raise ValueError("Invalid immutable release version")
    replicas = replicas or {}
    if set(replicas) - set((*APPS, "frontend")):
        raise ValueError("Unknown application")
    if any(not isinstance(n, int) or n < 1 or n > (2 if s in {"gateway", "platform-service"} else 1)
           for s, n in replicas.items()):
        raise ValueError("This release supports multiple replicas only for gateway/platform")
    result = [{"apiVersion": "v1", "kind": "Namespace", "metadata": {"name": cn.NAMESPACE,
               "labels": {"io.capyiot.cn/target": cn.CLUSTER}}}]
    for service in (*APPS, "frontend"):
        labels = {"app.kubernetes.io/name": service, "app.kubernetes.io/part-of": "capyiot", "io.capyiot.cn/target": cn.CLUSTER}
        meta = {"name": service, "namespace": cn.NAMESPACE, "labels": labels}
        config_name, secret_name = service + "-config-" + version, service + "-credentials-" + version
        result.extend([{"apiVersion": "v1", "kind": "ServiceAccount", "metadata": meta, "automountServiceAccountToken": False},
                       {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {**meta, "name": config_name}, "immutable": True,
                        "data": configuration(service, active=active, fresh=fresh, model=model)}])
        frontend = service == "frontend"
        port = 8080 if frontend else PORTS[service]
        if frontend:
            live = ["/bin/sh", "-c", "wget -qO- http://127.0.0.1:8080/ >/dev/null"]
        elif service == "agent-service":
            live = ["/app/.venv/bin/python", "-c", "import ssl,urllib.request;urllib.request.urlopen('https://127.0.0.1:8086/health/live',context=ssl.create_default_context(cafile='/run/secrets/internal-ca.pem'),timeout=3)"]
        elif service == "gateway":
            live = ["curl", "-fsS", "--max-time", "3", "http://127.0.0.1:9090/actuator/health/liveness"]
        else:
            live = ["curl", "-fsS", "--max-time", "3", "--cacert", "/run/secrets/internal-ca.pem", f"https://127.0.0.1:{port}/actuator/health/liveness"]
        ready = [x.replace("/health/live'", "/health/ready'").replace("/health/liveness", "/health/readiness") for x in live]
        container = {"name": service, "image": (images or {}).get(service, "capyiot/" + service + ":unreleased"),
                     "imagePullPolicy": "IfNotPresent", "ports": [{"name": "https", "containerPort": port}],
                     "envFrom": [{"configMapRef": {"name": config_name}}],
                     "resources": {"requests": {"cpu": "100m", "memory": "256Mi" if not frontend else "32Mi"},
                                   "limits": {"cpu": "1", "memory": "512Mi" if not frontend else "128Mi"}},
                     "securityContext": {"allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True,
                                         "capabilities": {"drop": ["ALL"]}},
                     "volumeMounts": [{"name": "credentials", "mountPath": "/run/secrets", "readOnly": True}, {"name": "tmp", "mountPath": "/tmp"}],
                     "startupProbe": {"exec": {"command": live}, "periodSeconds": 5, "timeoutSeconds": 5, "failureThreshold": 36},
                     "livenessProbe": {"exec": {"command": live}, "periodSeconds": 10, "timeoutSeconds": 5, "failureThreshold": 3},
                     "readinessProbe": {"exec": {"command": ready}, "periodSeconds": 5, "timeoutSeconds": 5, "failureThreshold": 2},
                     "lifecycle": {"preStop": {"exec": {"command": ["/bin/sh", "-c", "touch /tmp/capyiot-draining; sleep 5"]}}}}
        volumes = [{"name": "credentials", "secret": {"secretName": secret_name, "defaultMode": 292}},
                   {"name": "tmp", "emptyDir": {"sizeLimit": "64Mi"}}]
        if service == "agent-service":
            container["args"] = ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8086", "--ssl-keyfile", "/run/secrets/server.key", "--ssl-certfile", "/run/secrets/server.pem", "--workers", "1", "--timeout-graceful-shutdown", "45", "--no-access-log"]
        if frontend:
            container["ports"].append({"name": "tls", "containerPort": 8443})
            for filename, dest in (("server.pem", "/etc/nginx/local-tls/server.pem"), ("server.key", "/etc/nginx/local-tls/server.key"), ("internal-ca.pem", "/etc/nginx/internal-tls/ca.pem")):
                container["volumeMounts"].append({"name": "credentials", "mountPath": dest, "subPath": filename, "readOnly": True})
            volumes.append({"name": "nginx-config", "emptyDir": {"sizeLimit": "1Mi"}})
            container["volumeMounts"].append({"name": "nginx-config", "mountPath": "/etc/nginx/conf.d"})
        strategy = {"type": "RollingUpdate", "rollingUpdate": {"maxSurge": 1, "maxUnavailable": 0}} if service in {"gateway", "platform-service", "frontend"} else {"type": "Recreate"}
        result.append({"apiVersion": "apps/v1", "kind": "Deployment", "metadata": meta, "spec": {
            "replicas": replicas.get(service, 1), "strategy": strategy, "minReadySeconds": 5,
            "progressDeadlineSeconds": 240, "revisionHistoryLimit": 3, "selector": {"matchLabels": {"app.kubernetes.io/name": service}},
            "template": {"metadata": {"labels": {**labels, "io.capyiot.cn/release": version}}, "spec": {
                "serviceAccountName": service, "automountServiceAccountToken": False, "terminationGracePeriodSeconds": 60,
                "securityContext": {"runAsNonRoot": True, "runAsUser": 101 if frontend else 10001, "fsGroup": 101 if frontend else 10001},
                "containers": [container], "volumes": volumes}}}})
        ports = [{"name": "https", "port": port, "targetPort": port}]
        if service == "gateway":
            ports.append({"name": "management", "port": 9090, "targetPort": 9090})
        if frontend:
            ports[0]["nodePort"] = 30080
            ports.append({"name": "tls", "port": 8443, "targetPort": 8443, "nodePort": 30443})
        if service == "device-service":
            ports[0]["nodePort"] = 30482
        result.append({"apiVersion": "v1", "kind": "Service", "metadata": meta,
                       "spec": {"type": "NodePort" if frontend or service == "device-service" else "ClusterIP",
                                "selector": {"app.kubernetes.io/name": service}, "ports": ports}})
    return result


def secret_objects(bundle, version):
    result = []
    for service in (*APPS, "frontend"):
        result.append({"apiVersion": "v1", "kind": "Secret", "type": "Opaque", "immutable": True,
                       "metadata": {"name": service + "-credentials-" + version, "namespace": cn.NAMESPACE},
                       "data": {name: base64.b64encode(cn.safe_file(bundle, path).read_bytes()).decode()
                                for name, path in files_for(service).items()}})
    return {"apiVersion": "v1", "kind": "List", "items": result}
