"""Offline model metadata fixture; no real model, generation or API key required."""

import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        body = json.dumps({"data": [{"id": "offline-fixture"}]}).encode()
        self.send_response(200 if self.path == "/models" else 404)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        messages = request["messages"]
        last_user = max(i for i, message in enumerate(messages) if message["role"] == "user")
        content = messages[last_user]["content"]
        intent = next(
            (
                str(m.get("content", ""))
                for m in reversed(messages)
                if m["role"] == "user" and "运行剩余预算" not in str(m.get("content", ""))
            ),
            "",
        )
        draft_mode = "draft-fixture" in intent
        if "slow-fixture" in str(content):
            time.sleep(5)
        if "fail-fixture" in str(content):
            self.send_response(503)
            self.end_headers()
            return
        # This fixture serves fresh conversations only. Request-scoped budget notices may be
        # rendered as additional user-role messages; they must not reset completed tool calls.
        results = [
            json.loads(message["content"]) for message in messages if message["role"] == "tool"
        ]
        by_name = {result["toolName"]: result for result in results}
        devices = by_name.get("query_devices", {}).get("data", {}).get("items", [])
        root = next(
            (item["deviceId"] for item in devices if item["deviceType"] == "SWITCH"),
            "missing-switch",
        )
        ipc = next(
            (item["deviceId"] for item in devices if item["deviceType"] == "IPC"), "missing-ipc"
        )
        incidents = by_name.get("query_incidents", {}).get("data", {}).get("items", [])
        incident = incidents[0]["incidentId"] if incidents else "missing-incident"
        sequence = [
            ("query_devices", {"keyword": "VIDEO"}),
            ("query_incidents", {}),
            ("get_device_detail", {"deviceId": root}),
            ("get_incident_detail", {"incidentId": incident}),
            ("get_topology_context", {"deviceId": root}),
            ("get_device_status_history", {"deviceId": ipc}),
            ("query_anomalies", {"deviceId": root, "severity": "FAULT"}),
            ("query_work_orders", {"deviceId": root}),
            ("get_maintenance_history", {"deviceId": root}),
        ]
        if draft_mode:
            import re

            incident_match = re.search(r"INCIDENT ([A-Za-z0-9_-]+)", intent)
            evidence = [
                ref["evidenceId"]
                for result in results
                for ref in result.get("evidenceReferences", [])
            ]
            sequence = [("query_devices", {"keyword": "VIDEO"})]
            if incident_match:
                sequence.append(("get_incident_detail", {"incidentId": incident_match.group(1)}))
            sequence.append(
                (
                    "prepare_work_order",
                    {
                        "sourceType": "INCIDENT" if incident_match else "MANUAL",
                        "incidentId": incident_match.group(1) if incident_match else None,
                        "deviceItems": [
                            {
                                "deviceId": root,
                                "deviceRole": "MAIN",
                                "issueDescription": "交换机连接异常待排查",
                            },
                            {
                                "deviceId": ipc,
                                "deviceRole": "RELATED",
                                "issueDescription": "关联摄像机录像核查",
                            },
                        ],
                        "title": "A区视频设备维修草稿",
                        "description": "根据模拟设备状态核查网络连接与供电，实际原因待现场确认",
                        "expectedResult": "设备连接恢复并核查录像",
                        "workCategory": "设备故障维修",
                        "priority": None if incident_match else "P3",
                        "evidenceReferenceIds": evidence,
                    },
                )
            )
        if len(results) < len(sequence):
            name, arguments = sequence[len(results)]
        else:
            name = next(
                tool["function"]["name"]
                for tool in request["tools"]
                if tool["function"]["name"]
                not in {
                    "query_devices",
                    "query_incidents",
                    "get_device_detail",
                    "get_incident_detail",
                    "get_topology_context",
                    "get_device_status_history",
                    "query_anomalies",
                    "query_work_orders",
                    "get_maintenance_history",
                    "prepare_work_order",
                }
            )

            def refs(tool):
                return [r["evidenceId"] for r in by_name[tool]["evidenceReferences"]]

            if draft_mode:
                arguments = {
                    "facts": [
                        {
                            "statement": "已查询设备状态，草稿仍需人工审阅确认",
                            "evidenceIds": refs("query_devices"),
                        }
                    ],
                    "inferences": [],
                    "recommendations": ["请在草稿审阅页核对后确认；尚未创建正式工单"],
                    "unknowns": ["现场实际原因尚待核查"],
                }
            else:
                arguments = {
                    "facts": [
                        {
                            "statement": "已查询当前可见摄像机的连接状态与状态历史",
                            "evidenceIds": refs("query_devices")
                            + refs("get_device_status_history"),
                        },
                        {
                            "statement": "故障根因仍为未确认状态",
                            "evidenceIds": refs("get_incident_detail"),
                        },
                    ],
                    "inferences": [
                        {
                            "statement": "交换机与下联设备存在网络关联，这是待人工核查的候选线索",
                            "evidenceIds": refs("get_topology_context"),
                        }
                    ],
                    "recommendations": ["结合交换机和下联设备证据进一步核查，不自动创建工单"],
                    "unknowns": ["实际现场故障原因尚待确认，当前为模拟设备输入"],
                }
        body = json.dumps(
            {
                "id": "offline-query-fixture",
                "object": "chat.completion",
                "created": 1,
                "model": "offline-fixture",
                "choices": [
                    {
                        "index": 0,
                        "finish_reason": "tool_calls",
                        "message": {
                            "role": "assistant",
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": "call-" + str(len(results)),
                                    "type": "function",
                                    "function": {
                                        "name": name,
                                        "arguments": json.dumps(arguments, ensure_ascii=False),
                                    },
                                }
                            ],
                        },
                    }
                ],
                "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20},
            }
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8099), Handler).serve_forever()
