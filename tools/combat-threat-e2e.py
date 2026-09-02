"""Focused deterministic external-client -> Runtime -> real Minecraft recovery evidence.

Requires the current :runtime:runtime-app:installDist, JAVA_HOME and a Gradle executable.
This is not LIVE_PROVIDER or HUMAN_PLAYTEST evidence. Fixtures own only generated test worlds.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import time
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("--loader", choices=["fabric", "forge"], required=True)
parser.add_argument("--gradle", required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
version = "1.21.1" if args.loader == "fabric" else "1.20.1"
project = root / "minecraft" / (args.loader + "-" + version)
evidence = root / "artifacts/codex-verification"
evidence.mkdir(parents=True, exist_ok=True)
home = Path(tempfile.mkdtemp(prefix="mcac-threat-"))
config = home / "runtime.yml"
config.write_text("""server:
  bind: 127.0.0.1
  port: 18866
  management_port: 18867
  profile_id: threat-e2e
  instance_id: threat-e2e
  token_file: ./data/pairing.token
database:
  path: ./data/companion.db
provider:
  mode: rules
brain:
  mode: disabled
logging:
  file: ./logs/runtime.log
  console: true
""", encoding="utf-8")
token_file = home / "data/pairing.token"
classpath = os.pathsep.join(str(p) for p in (root / "runtime/runtime-app/build/install/runtime-app/lib").glob("*.jar"))
assert classpath, "build the current Runtime installDist first"
java = Path(os.environ["JAVA_HOME"]) / "bin/java.exe"
runtime_log = (evidence / ("threat-" + args.loader + "-runtime.log")).open("w", encoding="utf-8")
game_log_path = evidence / ("threat-" + args.loader + "-bridge-game.log")
game_log = game_log_path.open("w", encoding="utf-8")
runtime = game = None
token = ""


def request(path, data=None, headers=None):
    request_headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
    request_headers.update(headers or {})
    req = urllib.request.Request("http://127.0.0.1:18867" + path,
            data=None if data is None else json.dumps(data).encode(), headers=request_headers)
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response), response.headers


def until(predicate, seconds, label):
    deadline = time.monotonic() + seconds
    while True:
        result = predicate()
        if result:
            return result
        assert runtime.poll() is None, "Runtime exited; inspect its log"
        if game is not None:
            assert game.poll() is None, "GameTest exited before " + label
        assert time.monotonic() < deadline, "Timed out waiting for " + label
        time.sleep(0.1)


try:
    runtime = subprocess.Popen([str(java), "-classpath", classpath, "com.mccompanion.runtime.RuntimeMain",
                               "--config", str(config)], cwd=root, stdout=runtime_log, stderr=subprocess.STDOUT,
                               creationflags=subprocess.CREATE_NO_WINDOW)
    until(token_file.exists, 30, "pairing token")
    token = token_file.read_text(encoding="utf-8").strip()

    def healthy():
        try:
            return request("/health")[0]
        except (OSError, ValueError):
            return None

    until(healthy, 30, "authenticated health")
    init = evidence / ("threat-" + args.loader + "-bridge.init.gradle")
    init_text = """allprojects { afterEvaluate { p ->
        if (p.name == 'minecraft-ai-companion-forge-1.20.1') {
            p.sourceSets.main.java.exclude { d -> d.file.name.endsWith('GameTests.java') &&
                !(d.file.name in ['CombatForgeGameTests.java', 'ThreatRecoveryForgeGameTests.java']) }
        }
        if (p.name == 'minecraft-ai-companion-fabric-1.21.1') {
            p.tasks.named('processGametestResources').configure {
                inputs.property('threatBridgeOnly', true)
                doLast {
                    def descriptor = new File(destinationDir, 'fabric.mod.json')
                    def data = new groovy.json.JsonSlurper().parse(descriptor)
                    data.entrypoints['fabric-gametest'] = ['com.mccompanion.minecraft.v121.ThreatRecoveryGameTests']
                    descriptor.text = groovy.json.JsonOutput.toJson(data)
                }
            }
        }
    } }
"""
    task = "runGameTest" if args.loader == "fabric" else "runGameTestServer"
    init_text += "\nallprojects { afterEvaluate { p -> if (p.name == 'minecraft-ai-companion-" + args.loader + "-" + version + "') {\n"
    init_text += "p.tasks.named('" + task + "').configure { jvmArgs '-Dmccompanion.threat.e2e=true', '-Dmccompanion.runtime.url=ws://127.0.0.1:18866', '-Dmccompanion.runtime.tokenFile=" + token_file.as_posix() + "' }\n} } }\n"
    init.write_text(init_text, encoding="utf-8")
    game = subprocess.Popen([args.gradle, "-p", str(project), "-I", str(init), task,
                             "--offline", "--console=plain", "--no-parallel"], cwd=root,
                            stdout=game_log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)

    def ready():
        log = game_log_path.read_text(encoding="utf-8", errors="replace")
        match = re.search(r"threat_e2e_ready companion=([0-9a-f-]{36})", log)
        return match.group(1) if match and (healthy() or {}).get("onlineCompanionCount", 0) > 0 else None

    companion = until(ready, 120, "Minecraft fixture registration")
    headers = {"X-MCAC-Controller-Id": "representative-e2e", "X-MCAC-Brain-Session-Id": "threat-e2e",
               "X-MCAC-Companion-Id": companion, "MCP-Protocol-Version": "2025-06-18"}
    _, response_headers = request("/mcp", {"jsonrpc": "2.0", "id": "init", "method": "initialize",
            "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                       "clientInfo": {"name": "mcac-threat-e2e", "version": "test"}}}, headers)
    headers["Mcp-Session-Id"] = response_headers["Mcp-Session-Id"]

    def tool(name, arguments):
        result, _ = request("/mcp", {"jsonrpc": "2.0", "id": str(uuid.uuid4()), "method": "tools/call",
                                    "params": {"name": name, "arguments": arguments}}, headers)
        assert "error" not in result, result
        content = result["result"]["structuredContent"]
        assert not content.get("isError") and content.get("success", True), content
        return content

    def tools_ready():
        reply, _ = request("/mcp", {"jsonrpc": "2.0", "id": str(uuid.uuid4()),
                                   "method": "tools/list", "params": {}}, headers)
        return any(t["name"] == "build.small_blueprint" for t in reply.get("result", {}).get("tools", []))

    until(tools_ready, 30, "exact companion's connected-body capabilities")

    blueprint = {"anchor": {"dimension": "minecraft:overworld", "x": 1539, "y": 100, "z": 1538},
                 "maxSize": {"x": 7, "y": 1, "z": 1},
                 "blocks": [{"position": {"x": x, "y": 0, "z": 0}, "block": "minecraft:cobblestone"}
                            for x in range(7)]}
    graph = {"version": "mcac-task-graph/1", "id": "threat-recovery",
             "permissions": ["READ_WORLD", "BUILD", "INVENTORY", "MOVE", "INTERACT"],
             "root": {"id": "root", "type": "sequence", "nodes": [
                 {"id": "before", "type": "call_tool", "tool": "inventory.inspect", "arguments": {}},
                 {"id": "build", "type": "call_tool", "tool": "build.small_blueprint", "arguments": blueprint},
                 {"id": "done", "type": "return", "value": "resumed-and-verified"}]}}
    submitted = tool("task_graph.execute", {"graph": graph,
            "provenance": {"source": "LOCAL_DETERMINISTIC_EXTERNAL_CLIENT_E2E", "liveModel": False}})
    execution = submitted["observation"]["executionId"]

    def succeeded():
        state = tool("task_graph.inspect", {"executionId": execution})
        status = state["observation"]["state"]
        assert status not in ["FAILED", "CANCELLED", "PAUSED", "BLOCKED", "RECONCILIATION_REQUIRED"], state
        return state if status == "SUCCEEDED" else None

    terminal = until(succeeded, 90, "durable graph completion")
    exit_code = game.wait(timeout=60)
    assert exit_code == 0, "real GameTests failed: " + str(exit_code)
    with sqlite3.connect(home / "data/companion.db") as database:
        events = database.execute("SELECT event_type, payload_json FROM runtime_event WHERE companion_id = ?",
                                  (companion,)).fetchall()
        low = [json.loads(payload) for kind, payload in events if kind == "LOW_HEALTH"]
        assert len(low) == 1 and low[0]["localSafetyHandling"], events
        tasks = database.execute("SELECT state, behavior_id FROM task WHERE companion_id = ?", (companion,)).fetchall()
        assert tasks and all(state == "COMPLETED" for state, _ in tasks), tasks
    result = {"evidence": "RUNTIME_BRIDGE_REAL_MINECRAFT_GAMETEST", "loader": args.loader,
              "liveProvider": False, "humanPlaytest": False, "executionId": execution,
              "graph": terminal, "lowHealthEvents": len(low), "tasks": tasks, "runtimeHome": str(home)}
    output = evidence / ("threat-" + args.loader + "-bridge-evidence.json")
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PASS: authenticated Runtime graph resumed the same durable task; one admitted low-health edge;", output)
finally:
    for process in (game, runtime):
        if process is not None and process.poll() is None:
            # Terminate only this fixture's process tree, including its Gradle-launched server.
            subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True,
                           creationflags=subprocess.CREATE_NO_WINDOW)
    runtime_log.close()
    game_log.close()
