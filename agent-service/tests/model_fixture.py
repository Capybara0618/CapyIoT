"""Offline model metadata fixture; no real model, generation or API key required."""

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import time


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
        if "slow-fixture" in str(content):
            time.sleep(5)
        if "fail-fixture" in str(content):
            self.send_response(503)
            self.end_headers()
            return
        results = [json.loads(message["content"]) for message in messages[last_user+1:] if message["role"] == "tool"]
        by_name = {result["toolName"]: result for result in results}
        devices = by_name.get("query_devices", {}).get("data", {}).get("items", [])
        root = next((item["deviceId"] for item in devices if item["deviceType"] == "SWITCH"), "missing-switch")
        ipc = next((item["deviceId"] for item in devices if item["deviceType"] == "IPC"), "missing-ipc")
        incidents = by_name.get("query_incidents", {}).get("data", {}).get("items", [])
        incident = incidents[0]["incidentId"] if incidents else "missing-incident"
        sequence = [("query_devices", {"keyword": "VIDEO"}), ("query_incidents", {"status": "PENDING_REVIEW"}),
                    ("get_device_detail", {"deviceId": root}), ("get_incident_detail", {"incidentId": incident}),
                    ("get_topology_context", {"deviceId": root}), ("get_device_status_history", {"deviceId": ipc}),
                    ("query_anomalies", {"deviceId": root}), ("query_work_orders", {"deviceId": root}),
                    ("get_maintenance_history", {"deviceId": root})]
        if len(results) < len(sequence):
            name, arguments = sequence[len(results)]
        else:
            name = next(tool["function"]["name"] for tool in request["tools"] if tool["function"]["name"] not in {pair[0] for pair in sequence})
            refs = lambda tool: [r["evidenceId"] for r in by_name[tool]["evidenceReferences"]]
            arguments = {"facts": [{"statement": "已查询当前可见摄像机的连接状态与状态历史", "evidenceIds": refs("query_devices")+refs("get_device_status_history")},
                                    {"statement": "故障根因仍为未确认状态", "evidenceIds": refs("get_incident_detail")}],
                         "inferences": [{"statement": "交换机与下联设备存在网络关联，这是待人工核查的候选线索", "evidenceIds": refs("get_topology_context")}],
                         "recommendations": ["结合交换机和下联设备证据进一步核查，不自动创建工单"], "unknowns": ["实际现场故障原因尚待确认，当前为模拟设备输入"]}
        body = json.dumps({"id": "offline-query-fixture", "object": "chat.completion", "created": 1,
            "model": "offline-fixture", "choices": [{"index": 0, "finish_reason": "tool_calls", "message": {
                "role": "assistant", "content": None, "tool_calls": [{"id": "call-"+str(len(results)), "type": "function", "function": {
                    "name": name, "arguments": json.dumps(arguments, ensure_ascii=False)}}]}}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8099), Handler).serve_forever()
