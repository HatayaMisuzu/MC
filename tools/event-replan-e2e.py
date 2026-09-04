"""REAL_MINECRAFT_GAMETEST + Runtime/Bridge + deterministic external Brain protocol fixture.

No live model, no human playtest, no task/world/inventory database writes.
Requires current Runtime installDist, cached Gradle dependencies and JAVA_HOME.
"""
import argparse
import copy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("--loader", choices=["fabric", "forge"], required=True)
parser.add_argument("--gradle", required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
version = "1.21.1" if args.loader == "fabric" else "1.20.1"
evidence = root / "artifacts/codex-verification"
evidence.mkdir(parents=True, exist_ok=True)
home = Path(tempfile.mkdtemp(prefix="mcac-event-replan-"))
goal = "Collect one diamond and deliver that diamond to the original destination chest."
fixture_token = str(uuid.uuid4())
graph = None
detour = None
brain_requests = []
brain_errors = []


class BrainFixture(BaseHTTPRequestHandler):
    def log_message(self, *unused):
        pass

    def do_POST(self):
        try:
            assert self.headers.get("Authorization") == "Bearer " + fixture_token
            payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            if self.path.endswith("/sessions"):
                result = {"sessionId": "replan-fixture-session"}
            elif self.path.endswith("/turns"):
                message = payload.get("userMessage", "")
                if message == goal:
                    result = {"kind": "TOOL_CALLS", "toolCalls": [{"callId": "resource-execution",
                        "name": "task_graph.execute", "arguments": {"graph": graph,
                            "provenance": {"source": "DETERMINISTIC_EXTERNAL_BRAIN_FIXTURE", "liveProvider": False}}}]}
                elif message.startswith("{") and json.loads(message).get("type") == "task_graph_replan":
                    request = json.loads(message)
                    brain_requests.append(request)
                    assert request["originalGoal"] == goal, request
                    assert "collect" in request["completedNodes"], request
                    assert request["epoch"] == 0, request
                    assert request["event"]["eventType"] in ["TASK_BLOCKED", "TASK_FAILED", "TASK_GRAPH_TERMINAL"], request
                    revised = copy.deepcopy(request["graph"])
                    nodes = revised["root"]["nodes"]
                    nodes[1] = {"id": "detour", "type": "call_tool", "tool": "movement.navigate", "arguments": detour}
                    result = {"kind": "TOOL_CALLS", "toolCalls": [{"callId": "rewrite-" + request["requestId"],
                        "name": "task_graph.replan", "arguments": {"executionId": request["executionId"],
                            "requestId": request["requestId"], "epoch": request["epoch"],
                            "expectedRevision": request["revision"], "graph": revised}}]}
                else:
                    # The fixture never claims final success; SQL and real chest observations verify it.
                    result = {"kind": "WAIT", "reason": "await verified graph outcome"}
            else:
                result = {"accepted": True}
            self.send_response(200)
        except Exception as failure:
            brain_errors.append(repr(failure))
            result = {"error": str(failure)}
            self.send_response(500)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(result).encode())


brain_server = ThreadingHTTPServer(("127.0.0.1", 18888), BrainFixture)
threading.Thread(target=brain_server.serve_forever, daemon=True).start()
config = home / "runtime.yml"
config.write_text("""server:
  bind: 127.0.0.1
  port: 18886
  management_port: 18887
  profile_id: event-replan-e2e
  instance_id: event-replan-e2e
  token_file: ./data/pairing.token
database:
  path: ./data/companion.db
provider:
  mode: rules
brain:
  mode: hermes
  endpoint: http://127.0.0.1:18888/
  token_env: MCAC_REPLAN_FIXTURE_TOKEN
  max_tool_calls_per_turn: 8
  max_input_tokens: 200000
  timeout_seconds: 20
logging:
  file: ./logs/runtime.log
  console: true
""", encoding="utf-8")
runtime_log = (evidence / f"event-replan-{args.loader}-runtime.log").open("w", encoding="utf-8")
game_log_path = evidence / f"event-replan-{args.loader}-game.log"
game_log = game_log_path.open("w", encoding="utf-8")
runtime = game = None
token = ""


def request(path, data=None):
    req = urllib.request.Request("http://127.0.0.1:18887" + path,
        data=None if data is None else json.dumps(data).encode(),
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def until(predicate, seconds, label):
    deadline = time.monotonic() + seconds
    while True:
        value = predicate()
        if value:
            return value
        assert not brain_errors, brain_errors
        assert runtime.poll() is None, "Runtime exited before " + label
        if game is not None:
            assert game.poll() is None, "GameTest exited before " + label
        assert time.monotonic() < deadline, "Timed out: " + label
        time.sleep(0.1)


try:
    classpath = os.pathsep.join(str(p) for p in (root / "runtime/runtime-app/build/install/runtime-app/lib").glob("*.jar"))
    assert classpath, "Build current Runtime installDist first"
    env = os.environ.copy()
    env["MCAC_REPLAN_FIXTURE_TOKEN"] = fixture_token
    runtime = subprocess.Popen([str(Path(env["JAVA_HOME"]) / "bin/java.exe"), "-classpath", classpath,
        "com.mccompanion.runtime.RuntimeMain", "--config", str(config), "--no-cli"], cwd=root,
        env=env, stdout=runtime_log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
    token_file = home / "data/pairing.token"
    until(token_file.exists, 30, "Runtime token")
    token = token_file.read_text(encoding="utf-8").strip()

    def healthy():
        try:
            return request("/health")
        except (OSError, ValueError):
            return None

    until(healthy, 30, "Runtime health")
    task = "runGameTest" if args.loader == "fabric" else "runGameTestServer"
    init = evidence / f"event-replan-{args.loader}-bridge.init.gradle"
    init.write_text("allprojects { afterEvaluate { p -> if (p.name == 'minecraft-ai-companion-"
        + args.loader + "-" + version + "') { p.tasks.named('" + task + "').configure { jvmArgs "
        + "'-Dmccompanion.replan.e2e=true', '-Dmccompanion.runtime.url=ws://127.0.0.1:18886', "
        + "'-Dmccompanion.runtime.tokenFile=" + token_file.as_posix() + "' } } } }", encoding="utf-8")
    game = subprocess.Popen([args.gradle, "-p", str(root / f"minecraft/{args.loader}-{version}"),
        "-I", str(root / "tools/event-replan-tests.init.gradle"), "-I", str(init), task,
        "--offline", "--console=plain", "--no-parallel"], cwd=root,
        stdout=game_log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)

    def ready():
        match = re.search(r"event_replan_ready companion=([0-9a-f-]+) blocked=([-0-9,]+) detour=([-0-9,]+) chest=([-0-9,]+)",
            game_log_path.read_text(encoding="utf-8", errors="replace"))
        return match if match and (healthy() or {}).get("onlineCompanionCount", 0) else None

    companion, blocked_text, detour_text, chest_text = until(ready, 180, "Minecraft registration").groups()

    def position(text):
        return dict(zip(["x", "y", "z"], map(int, text.split(","))), dimension="minecraft:overworld")

    blocked, detour, chest = map(position, [blocked_text, detour_text, chest_text])
    arrival = dict(chest, z=chest["z"] + 2)
    graph = {"version": "mcac-task-graph/1", "id": "original-resource-goal",
        "permissions": ["COLLECT", "MOVE", "INVENTORY"], "root": {"id": "root", "type": "sequence", "nodes": [
            {"id": "collect", "type": "call_tool", "tool": "entity.collect", "arguments": {"item": "minecraft:diamond", "quantity": 1}},
            {"id": "old-waypoint", "type": "call_tool", "tool": "movement.navigate", "arguments": blocked},
            {"id": "destination", "type": "call_tool", "tool": "movement.navigate", "arguments": arrival},
            {"id": "deposit", "type": "call_tool", "tool": "inventory.transfer", "arguments": {
                "direction": "TO_CONTAINER", "item": "minecraft:diamond", "quantity": 1, "container": chest}},
            {"id": "finish", "type": "return", "value": "original-resource-goal-complete"}]}}
    response = request("/brain", {"controllerId": "runtime-primary", "companionId": companion, "text": goal})
    assert response.get("accepted"), response
    database = home / "data/companion.db"

    def completed():
        with sqlite3.connect(database) as db:
            db.row_factory = sqlite3.Row
            row = db.execute("SELECT * FROM task_graph_execution WHERE execution_id='resource-execution'").fetchone()
        if row is None:
            return None
        row = dict(row)
        replan = json.loads(row["replan_json"])
        assert replan.get("phase") != "BLOCKED", (row["state"], row["result_code"], replan)
        assert row["state"] not in ["CANCELLED", "RECONCILIATION_REQUIRED"], row
        assert not (row["state"] == "PAUSED" and replan.get("phase") == "RESUMED"), "resumed graph was unexpectedly paused"
        if row["state"] == "FAILED" and replan.get("epoch", 0) >= 1:
            raise AssertionError("rewritten graph failed: " + row["tool_results_json"])
        return row if row["state"] == "SUCCEEDED" else None

    result = until(completed, 150, "pause/replan/resume/resource-delivery")
    replan = json.loads(result["replan_json"])
    assert replan["epoch"] == 1 and replan["phase"] == "RESUMED", replan
    assert replan["originalGoal"] == goal and len(brain_requests) == 1, replan
    results = json.loads(result["tool_results_json"])
    assert sum(v["nodeId"] == "collect" for v in results.values()) == 1, results
    assert results["resource-execution:deposit:1"]["success"], results
    assert game.wait(timeout=45) == 0, "Real GameTest failed; inspect game log"
    assert "event_replan_delivered" in game_log_path.read_text(encoding="utf-8", errors="replace")
    output = evidence / f"event-replan-{args.loader}-evidence.json"
    output.write_text(json.dumps({"evidence": "REAL_MINECRAFT_GAMETEST_RUNTIME_BRIDGE_REPLAY_BRAIN",
        "loader": args.loader, "liveProvider": False, "humanPlaytest": False, "runtimeHome": str(home),
        "request": brain_requests[0], "replan": replan, "toolResults": results,
        "finalState": result["state"], "completedNodes": json.loads(result["completed_nodes_json"]),
        "realChestDiamonds": 1}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PASS: original diamond collected once, semantic route failure, one external replan, real detour and chest deposit:", output)
finally:
    for process in (game, runtime):
        if process is not None and process.poll() is None:
            subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True,
                creationflags=subprocess.CREATE_NO_WINDOW)
    brain_server.shutdown()
    brain_server.server_close()
    runtime_log.close()
    game_log.close()
