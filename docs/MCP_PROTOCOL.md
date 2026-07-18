# MCAC local MCP protocol

MCAC Runtime exposes a local, authenticated MCP Streamable HTTP endpoint at:

```text
http://127.0.0.1:<management_port>/mcp
```

It supports MCP `2025-03-26` and `2025-06-18`. The endpoint is stateless at the HTTP
transport layer; MCAC binds every tool operation to explicit controller, Brain-session, and
companion identities. It exposes only bounded MCAC tools and never exposes a shell, arbitrary
files, arbitrary URLs, credentials, cookies, direct world editing, or direct inventory editing.

## Authentication and identity

Every HTTP request requires the Runtime pairing token:

```text
Authorization: Bearer <pairing token>
```

After `initialize`, every request also requires the negotiated version:

```text
MCP-Protocol-Version: 2025-06-18
```

`tools/list`, `tools/call`, and `notifications/cancelled` require:

```text
X-MCAC-Controller-Id: hermes
X-MCAC-Brain-Session-Id: <stable Brain session ID>
X-MCAC-Companion-Id: <connected companion UUID>
```

The controller header is optional and defaults to `mcp`; Brain-session and companion headers
are mandatory. These headers are routing claims, not sufficient proof of identity by themselves.
Runtime also binds calls to the authenticated pairing context and durable session/task state.
A client must not reuse one Brain-session ID across independent users or tasks.

## Lifecycle

Initialize without `MCP-Protocol-Version`:

```json
{"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{
  "protocolVersion":"2025-06-18","capabilities":{},
  "clientInfo":{"name":"Hermes","version":"<client version>"}
}}
```

Then send `notifications/initialized` with the negotiated protocol header. Tool discovery uses
the identity headers and returns only tools currently allowed by Runtime configuration and the
connected Fabric body's capability report:

```json
{"jsonrpc":"2.0","id":"list-1","method":"tools/list","params":{}}
```

Each tool definition contains its bounded JSON `inputSchema`. A typical call is:

```json
{"jsonrpc":"2.0","id":"call-1","method":"tools/call","params":{
  "name":"world.observe","arguments":{},
  "_meta":{"progressToken":"progress-call-1"}
}}
```

With `Accept: application/json`, Runtime returns one terminal JSON-RPC response. With
`Accept: text/event-stream`, Runtime sends SSE `message` events. Task state changes use the
client's progress token:

```json
{"jsonrpc":"2.0","method":"notifications/progress","params":{
  "progressToken":"progress-call-1","progress":4,"message":"RUNNING",
  "structuredContent":{"callId":"mcp-...","code":"TOOL_PROGRESS","terminal":false,
    "observation":{"state":"RUNNING","taskId":"...","taskRevision":4}}
}}
```

The final SSE message is the ordinary JSON-RPC response. Its MCP content includes a readable
text envelope and the same machine-readable `structuredContent`. `isError` is true for a
verified failure, block, interruption, cancellation, timeout, unavailable capability, invalid
argument, or missing companion; tool acceptance is never reported as tool completion.

## Cancellation, timeout, and disconnect

Cancel using the original JSON-RPC request ID and the same identity headers:

```json
{"jsonrpc":"2.0","method":"notifications/cancelled","params":{
  "requestId":"call-1","reason":"owner changed goal"
}}
```

Runtime deterministically maps the request ID plus bound identities to the internal call ID, so
the notification cannot cancel another Brain session's call. A disconnected SSE stream triggers
the same cancellation path. Runtime waits at most 30 seconds for a call and then dispatches task
cancellation; its terminal Observation states whether cancellation was confirmed.

Before dispatch, Runtime durably binds that internal call ID to the exact Tool name and canonical
argument hash. An identical retry after completion returns the persisted terminal result without
dispatching the Tool again. Reusing the request ID with different Tool input is rejected as
`MCP_REQUEST_REPLAY_CONFLICT`; a concurrent duplicate is rejected as
`MCP_REQUEST_IN_PROGRESS`. If Runtime stops while a request is in flight, startup changes the
ledger entry to `MCP_REQUEST_RECONCILIATION_REQUIRED` rather than guessing whether an external
effect occurred. A replay-ledger database failure is fail-closed before dispatch, or quarantines a
result-persistence failure for reconciliation.

Tool calls, progress, terminal results, task/behavior revisions, and delivery state use the
existing durable MCAC audit path. The authenticated `/brain/audit` endpoint is the current audit
inspection surface.

## Hermes connection example

Use Hermes as the high-level Brain and point its Streamable HTTP MCP client at the local URL.
Configure the pairing token as an authorization secret, and inject the three identity headers
from the current Hermes/MCAC session rather than hard-coding a companion. In MCP clients that use
the common server-map shape, the equivalent connection is:

```json
{
  "mcpServers": {
    "mcac": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:18766/mcp",
      "headers": {
        "Authorization": "Bearer ${MCAC_PAIRING_TOKEN}",
        "MCP-Protocol-Version": "2025-06-18",
        "X-MCAC-Controller-Id": "hermes",
        "X-MCAC-Brain-Session-Id": "${MCAC_BRAIN_SESSION_ID}",
        "X-MCAC-Companion-Id": "${MCAC_COMPANION_ID}"
      }
    }
  }
}
```

The exact configuration keys depend on the Hermes release. Treat this as the connection contract,
not as proof that an unverified Hermes build accepts a particular configuration-file shape. The
MCAC Terminal Doctor performs a live local negotiation and bounded-tool check before use.

## Current protocol limits

- Full lease expiry, a cryptographic per-client identity/nonce protocol, progress-resume token,
  and cross-companion authorization remain tracked as `PARTIAL`; this document does not claim
  they are complete. The durable request ledger prevents exact Tool-call redispatch but does not
  turn caller-supplied identity headers into cryptographic identity proof.
- SSE event replay/resumption is not implemented; reconnect and reconcile through durable task
  and audit state.
- HTTP transport sessions are stateless; MCAC identity and task state remain durable.
- Live concurrent cancellation with a real Hermes process remains external verification.
- Search tools appear only when Search is configured and authorized; they accept bounded queries
  and source IDs, never arbitrary URL fetches.
