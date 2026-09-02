"""Deterministic MCP client -> Runtime -> real Minecraft World Model acceptance.

Requires current :runtime:runtime-app:installDist, JAVA_HOME and cached Gradle dependencies.
REAL_MINECRAFT_GAMETEST / RUNTIME_BRIDGE_INTEGRATION, not LIVE_PROVIDER or HUMAN_PLAYTEST.
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
evidence = root / "artifacts/codex-verification"
evidence.mkdir(parents=True, exist_ok=True)
home = Path(tempfile.mkdtemp(prefix="mcac-world-model-"))
config = home / "runtime.yml"
config.write_text("""server:
  bind: 127.0.0.1
  port: 18876
  management_port: 18877
  profile_id: world-model-e2e
  instance_id: world-model-e2e
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
assert classpath, "Build the current Runtime installDist first"
runtime_log = (evidence / f"world-model-{args.loader}-runtime.log").open("w", encoding="utf-8")
game_log_path = evidence / f"world-model-{args.loader}-game.log"
game_log = game_log_path.open("w", encoding="utf-8")
runtime = game = None
token = ""


def request(path, data=None, headers=None):
    h = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
    h.update(headers or {})
    req = urllib.request.Request("http://127.0.0.1:18877" + path,
            data=None if data is None else json.dumps(data).encode(), headers=h)
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response), response.headers


def until(predicate, seconds, label):
    deadline = time.monotonic() + seconds
    while True:
        result = predicate()
        if result:
            return result
        assert runtime.poll() is None, "Runtime exited before " + label
        if game is not None:
            assert game.poll() is None, "GameTest exited before " + label
        assert time.monotonic() < deadline, "Timed out waiting for " + label
        time.sleep(0.1)


try:
    runtime = subprocess.Popen([str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe"), "-classpath", classpath,
            "com.mccompanion.runtime.RuntimeMain", "--config", str(config)], cwd=root,
            stdout=runtime_log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
    until(token_file.exists, 30, "Runtime token")
    token = token_file.read_text(encoding="utf-8").strip()

    def healthy():
        try:
            return request("/health")[0]
        except (OSError, ValueError):
            return None

    until(healthy, 30, "Runtime health")
    task = "runGameTest" if args.loader == "fabric" else "runGameTestServer"
    init = evidence / f"world-model-{args.loader}-bridge.init.gradle"
    init.write_text("allprojects { afterEvaluate { p -> if (p.name == 'minecraft-ai-companion-"
            + args.loader + "-" + version + "') { p.tasks.named('" + task + "').configure { jvmArgs "
            + "'-Dmccompanion.world.e2e=true', '-Dmccompanion.runtime.url=ws://127.0.0.1:18876', "
            + "'-Dmccompanion.runtime.tokenFile=" + token_file.as_posix() + "' } } } }", encoding="utf-8")
    game = subprocess.Popen([args.gradle, "-p", str(root / f"minecraft/{args.loader}-{version}"),
            "-I", str(root / "tools/world-model-tests.init.gradle"), "-I", str(init), task,
            "--offline", "--console=plain", "--no-parallel"], cwd=root,
            stdout=game_log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)

    def ready():
        match = re.search(r"world_model_ready companion=([0-9a-f-]+) cow=([0-9a-f-]+) drop=([0-9a-f-]+) "
                r"hostile=([0-9a-f-]+) chest=([-0-9,]+) target=([-0-9,]+) finish=([-0-9,]+)",
                game_log_path.read_text(encoding="utf-8", errors="replace"))
        return match if match and (healthy() or {}).get("onlineCompanionCount", 0) else None

    fixture = until(ready, 150, "real Minecraft registration")
    companion, cow, drop, hostile, chest, target, finish = fixture.groups()
    headers = {"X-MCAC-Controller-Id": "world-model-e2e", "X-MCAC-Brain-Session-Id": "world-model-e2e",
               "X-MCAC-Companion-Id": companion, "MCP-Protocol-Version": "2025-06-18"}
    _, response_headers = request("/mcp", {"jsonrpc": "2.0", "id": "init", "method": "initialize",
            "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                       "clientInfo": {"name": "world-model-e2e", "version": "test"}}}, headers)
    headers["Mcp-Session-Id"] = response_headers["Mcp-Session-Id"]

    def tool(name, arguments):
        result, _ = request("/mcp", {"jsonrpc": "2.0", "id": str(uuid.uuid4()), "method": "tools/call",
                                    "params": {"name": name, "arguments": arguments}}, headers)
        assert "error" not in result, result
        content = result["result"]["structuredContent"]
        assert not content.get("isError") and content.get("success", True), content
        return content

    def model():
        value = tool("world.query", {"select": "model"})["observation"]
        assert len(json.dumps(value, ensure_ascii=False, separators=(",", ":"))) <= 12000, value
        entries = sum((value[group] for group in ["current", "nearby", "memory"]), [])
        assert len(entries) <= 32
        assert all(not e["verified"] for e in entries if e["stale"]), value
        return value

    def fresh():
        current = model()
        expected = {cow, drop, hostile, "minecraft:overworld:" + chest}
        observed = {e["identity"] for e in current["nearby"] if e["verified"]}
        return current if expected.issubset(observed) else None

    before = until(fresh, 15, "fresh nearby identities")
    # All three entity categories and current sections come from the production bridge snapshot.
    kinds = {e["kind"] for e in before["nearby"]}
    assert {"player", "entity", "droppedItem", "threat", "container"}.issubset(kinds), kinds
    assert {"position", "dimension", "vitals", "equipment", "inventory", "menu", "vehicle", "behavior", "navigation"}.issubset(
            {e["kind"] for e in before["current"]}), before
    chest_position = dict(zip(["x", "y", "z"], map(int, chest.split(","))))
    chest_position["dimension"] = "minecraft:overworld"
    inspected = tool("block.inspect", {"position": chest_position})
    assert inspected["observation"]["block"] == "minecraft:chest", inspected

    def navigate_graph(coordinates, name):
        position = dict(zip(["x", "y", "z"], map(int, coordinates.split(","))))
        position["dimension"] = "minecraft:overworld"
        graph = {"version": "mcac-task-graph/1", "id": name, "permissions": ["READ_WORLD", "MOVE"],
                 "root": {"id": "root", "type": "sequence", "nodes": [
                     {"id": "observe", "type": "call_tool", "tool": "world.query", "arguments": {"select": "model"}},
                     {"id": "move", "type": "call_tool", "tool": "movement.navigate", "arguments": position},
                     {"id": "done", "type": "return", "value": "observed-and-moved"}]}}
        submitted = tool("task_graph.execute", {"graph": graph,
            "provenance": {"source": "LOCAL_DETERMINISTIC_EXTERNAL_CLIENT_E2E", "liveModel": False}})
        execution = submitted["observation"]["executionId"]

        def terminal():
            result = tool("task_graph.inspect", {"executionId": execution})
            state = result["observation"]["state"]
            assert state not in ["FAILED", "CANCELLED", "PAUSED", "BLOCKED", "RECONCILIATION_REQUIRED"], result
            return result if state == "SUCCEEDED" else None

        return until(terminal, 40, name)

    first = navigate_graph(target, "world-model-observe-move")

    def invalidated():
        current = model()
        if not any(e["identity"] == cow and e["verified"] for e in current["nearby"]):
            with sqlite3.connect(home / "data/companion.db") as db:
                saved = json.loads(db.execute("SELECT status_json FROM companion WHERE companion_id=?", (companion,)).fetchone()[0])["_worldModel"]
            cow_fact = saved["nearby"].get("entity:" + cow, {})
            container = saved["memory"].get("container:minecraft:overworld:" + chest, {})
            if cow_fact.get("invalidation") and container.get("invalidation"):
                return current, saved
        return None

    after, persisted = until(invalidated, 15, "real death and block-change invalidation")
    assert "path" in persisted["memory"], "Navigation snapshot did not reach the World Model"
    assert persisted["current"]["taskGraph"]["value"]["state"] == "SUCCEEDED", persisted
    second = navigate_graph(finish, "world-model-finish")
    assert game.wait(timeout=45) == 0, "Real GameTest failed; inspect its log"
    output = evidence / f"world-model-{args.loader}-evidence.json"
    output.write_text(json.dumps({"evidence": "RUNTIME_BRIDGE_REAL_MINECRAFT_GAMETEST", "loader": args.loader,
            "liveProvider": False, "humanPlaytest": False, "runtimeHome": str(home),
            "before": before, "after": after, "graphs": [first, second]}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PASS: real state, stable identities, bounded summary, graph navigation and invalidation:", output)
finally:
    for process in (game, runtime):
        if process is not None and process.poll() is None:
            subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True,
                           creationflags=subprocess.CREATE_NO_WINDOW)
    runtime_log.close()
    game_log.close()
