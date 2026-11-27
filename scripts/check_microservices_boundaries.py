"""Source-only architecture guard; never imports application code or connects to services.

Reports deliberate historical-data compatibility separately from runtime dependencies.
This is not compilation, SQL execution, a call-graph proof, or runtime verification.
"""

import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
APPLICATIONS = {
    "gateway",
    "device-ingestion",
    "device-service",
    "alarm-service",
    "workflow-service",
    "platform-service",
}
OWNERS = {
    "device-service": "device",
    "alarm-service": "alarm",
    "workflow-service": "workflow",
    "platform-service": "platform",
}
SCANS = {
    "device-service": "io.capyiot.device.video",
    "alarm-service": "io.capyiot.alarm.fault",
    "workflow-service": "io.capyiot.workflow.work",
}
GROUPS = {
    "core-device-state-v1",
    "core-fault-observation-v1",
    "core-fault-workorder-v1",
    "core-platform-audit-v1",
}


def review(root=ROOT):
    root = Path(root)
    issues = []

    def require(condition, description):
        if not condition:
            issues.append(description)

    reactor = ET.parse(root / "backend/pom.xml").getroot()
    modules = [element.text for element in reactor.findall("m:modules/m:module", NS)]
    require(
        set(modules) == APPLICATIONS | {"shared-contract", "service-support"},
        "Maven reactor must contain six applications and two shared libraries only",
    )
    deps = {}
    groups = []
    table_declarations = {}
    java_count = 0
    migration_hashes = {}
    for module in modules:
        pom = ET.parse(root / "backend" / module / "pom.xml").getroot()
        own = []
        for dependency in pom.findall("m:dependencies/m:dependency", NS):
            if dependency.findtext("m:groupId", namespaces=NS) == "io.capyiot":
                artifact = dependency.findtext("m:artifactId", namespaces=NS)
                own.append(artifact)
                allowed = (
                    {"shared-contract"}
                    if module == "service-support"
                    else set()
                    if module == "shared-contract"
                    else {"shared-contract", "service-support"}
                )
                require(
                    artifact in allowed,
                    f"{module}: forbidden implementation dependency {artifact}",
                )
        deps[module] = own
        paths = list((root / "backend" / module / "src/main").rglob("*.java"))
        for path in paths:
            java_count += 1
            source = path.read_text(encoding="utf-8-sig")
            relative = path.relative_to(root).as_posix()
            for forbidden in (
                "io.capyiot.core",
                "CORE_SERVICE_URL",
                "CORE_SERVICE_KEY",
                "capyiot-core",
                "LegacyWindow",
                "LiveUserJwtDecoder",
                "class MqttIngestion",
                "class OutboxConfiguration",
                "/internal/foundation/",
                "/internal/device-bridge/",
                "/internal/fault-bridge/",
            ):
                require(
                    forbidden not in source,
                    f"{relative}: retired runtime dependency {forbidden}",
                )
            for qualified in re.findall(
                r"\b(device_db|alarm_db|workflow_db|platform_db|agent_db)\.", source
            ):
                require(
                    module in OWNERS and qualified == OWNERS[module] + "_db",
                    f"{relative}: foreign qualified schema {qualified}",
                )
            for imported in re.findall(
                r"import\s+(io\.capyiot\.[A-Za-z0-9_.]+)", source
            ):
                for foreign, prefix in (
                    ("device-service", "io.capyiot.device."),
                    ("alarm-service", "io.capyiot.alarm."),
                    ("workflow-service", "io.capyiot.workflow."),
                    ("platform-service", "io.capyiot.platform."),
                ):
                    require(
                        foreign == module or not imported.startswith(prefix),
                        f"{relative}: imports another domain implementation {imported}",
                    )
            groups += re.findall(r'groupId\s*=\s*"([^"]+)"', source)
            if path.name.endswith("Application.java") and module in SCANS:
                require(
                    'scanBasePackages="' + SCANS[module] + '"'
                    in source.replace(" ", ""),
                    f"{module}: component scan must stay in its domain",
                )
            if module in ("gateway", "device-ingestion"):
                require(
                    not re.search(
                        r"\b(?:JdbcTemplate|DataSource|EntityManager)\b", source
                    ),
                    f"{relative}: entry application must not access business schemas",
                )
        config = root / "backend" / module / "src/main/resources/application.yml"
        if config.exists():
            source = config.read_text(encoding="utf-8")
            require(
                not any(
                    word in source
                    for word in ("CORE_SERVICE_", "core-service", "    legacy:")
                ),
                f"{module}: obsolete runtime configuration",
            )
            if module in OWNERS:
                domain = OWNERS[module]
                credentials = set(
                    re.findall(r"\$\{([A-Z]+)_DB_(?:URL|USER|PASSWORD)", source)
                )
                require(
                    credentials == {domain.upper()},
                    f"{module}: credentials cross schema ownership",
                )
                require(
                    source.count("  datasource:") == 1,
                    f"{module}: requires exactly one datasource",
                )
                expected = (
                    "classpath:db/platform"
                    if domain == "platform"
                    else "classpath:db/migration"
                )
                require(
                    "locations: " + expected in source,
                    f"{module}: migration owner changed",
                )
        for path in (root / "backend" / module / "src/main/resources/db").rglob(
            "*.sql"
        ):
            relative = path.relative_to(root).as_posix()
            content = path.read_bytes()
            migration_hashes[relative] = hashlib.sha256(content).hexdigest()
            table_declarations.setdefault(module, set()).update(
                re.findall(
                    r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)",
                    content.decode("utf-8"),
                    re.I,
                )
            )
    require(
        set(groups) == GROUPS and len(groups) == len(GROUPS),
        "Logical consumer identities must remain stable and have one implementation each",
    )
    require(
        not list((root / "backend/core-service/src/main").rglob("*.java")),
        "Core production classes remain",
    )
    compose = (root / "compose.microservices.yml").read_text(encoding="utf-8")
    require(
        not re.search(r"^  core-service:", compose, re.M),
        "Candidate still deploys Core",
    )
    require("CORE_SERVICE_" not in compose, "Candidate still injects Core credentials")
    for service in APPLICATIONS | {"agent-service"}:
        require(
            re.search(r"^  " + service + r":", compose, re.M),
            f"Candidate missing {service}",
        )
    require(
        not (root / "compose.yml").exists(),
        "Obsolete default Compose can accidentally deploy old writers",
    )
    require(
        not (root / "infra/compose.agent-test.yml").exists(),
        "Obsolete Agent override remains executable",
    )
    producer_files = {}
    for module in OWNERS:
        file_name = (
            "PlatformEvents.java"
            if module == "platform-service"
            else "LocalEvents.java"
        )
        paths = list((root / "backend" / module / "src/main/java").rglob(file_name))
        require(len(paths) == 1, f"{module}: ambiguous domain producer")
        if paths:
            source = paths[0].read_text(encoding="utf-8")
            require(
                re.search(r'put\("producer",\s*"' + module + r'"\)', source),
                f"{module}: publishes as another process",
            )
            producer_files[module] = paths[0].relative_to(root).as_posix()
    # Historical message producers/receipt lookups are needed to drain backlog and recover old operations.
    contracts = (
        root
        / "backend/shared-contract/src/main/java/io/capyiot/contract/internal/InternalEndpoint.java"
    ).read_text(encoding="utf-8")
    endpoint_policy = re.findall(
        r'(\w+)\(ServiceId\.(\w+),\s*"(GET|POST)",\s*"([^"]+)"', contracts
    )
    report = {
        "kind": "SOURCE_ONLY",
        "issues": issues,
        "modules": modules,
        "dependencies": deps,
        "productionJavaFiles": java_count,
        "logicalConsumerGroups": groups,
        "producerOwners": producer_files,
        "internalOperationCount": len(endpoint_policy),
        "declaredTables": {
            module: sorted(tables) for module, tables in table_declarations.items()
        },
        "migrationSha256": migration_hashes,
        "historicalDataCompatibility": [
            "DomainProducer accepts historical envelopes under explicit flag",
            "fault reservation lookup requires matching committed workflow receipt",
            "read-only historical asset/event/alarm/workorder paths",
        ],
        "runtimeVerificationPerformed": False,
        "limits": [
            "Source matching does not prove all SQL semantics",
            "Operation-level no-recursion and transaction boundaries also require MS-09 fault injection",
        ],
    }
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    report = review()
    output = args.report.resolve()
    if (ROOT / "var").resolve() not in output.parents:
        parser.error("Reports must remain below ignored var/")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    if report["issues"]:
        for issue in report["issues"]:
            print(issue)
        raise SystemExit(
            "Source boundary review rejected the current code; this is not a runtime test result"
        )
    print("Source boundary review completed; runtime verification is still pending")


if __name__ == "__main__":
    main()
