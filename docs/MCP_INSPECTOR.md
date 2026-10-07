# MCP Inspector commands for manual testing

Run these commands in PowerShell. Both MCP services were discovered using the
Java SDK on 2026-10-07; the shortener check below was repeated through HAProxy.
Listing tools does not create links or allocate payment/worker IDs.

## Choose the endpoint

| Service | MCP URL | Implementation and expected tools |
| --- | --- | --- |
| Worker/payment gateway | `http://127.0.0.1:8080/worker-coordinator/mcp` | This gateway adapts MCP calls to REST; combined profile exposes nine coordinator tools plus `create_sample_payment` |
| URL shortener through HAProxy | `http://127.0.0.1:8119/mcp` | Native MCP inside the shortener replicas; `create_short_link` and `get_link` |
| URL shortener replica A | `http://127.0.0.1:8117/mcp` | Direct native MCP, bypassing HAProxy |
| URL shortener replica B | `http://127.0.0.1:8118/mcp` | Direct native MCP, bypassing HAProxy |
| URL shortener replica C | `http://127.0.0.1:8123/mcp` | Direct native MCP, bypassing HAProxy |

HAProxy replaced Nginx on the same port, 8119. It forwards `/mcp` to the native
shortener servers; this Java gateway does not aggregate their tools. The
shortener transport is stateless; the worker/payment gateway owns MCP sessions.
Both services negotiated protocol `2025-11-25` in the recorded SDK checks.

## Open the browser Inspector

Requires Node 22.19.0 or newer and npm/npx. The local versions checked were
Node 24.11.0 and npm 11.6.1. Commands pin Inspector 2.9.0; `http` means
Streamable HTTP, and `legacy` selects the protocol family used by these servers.
See the [official Inspector documentation](https://modelcontextprotocol.io/docs/tools/inspector).

Inspector was already listening on port 6274 during the latest inspection.
Use its existing browser UI at `http://127.0.0.1:6274` and add/select the target
URL above. If it is not running, choose ONE launch command below. Keep its
terminal open; Ctrl+C stops Inspector, leaving the MCP servers running.

For the URL shortener through HAProxy:

```powershell
npx --yes @modelcontextprotocol/inspector@2.9.0 --web --transport http --server-url http://127.0.0.1:8119/mcp --protocol-era legacy
```

For the worker/payment gateway:

```powershell
npx --yes @modelcontextprotocol/inspector@2.9.0 --web --transport http --server-url http://127.0.0.1:8080/worker-coordinator/mcp --protocol-era legacy
```

For a direct shortener comparison, substitute
`http://127.0.0.1:8117/mcp` in the first command.

Open the URL printed by Inspector. It carries the Inspector authentication
token; keep that token out of source and shared documentation. In the UI, select
the HTTP/Streamable HTTP transport and the intended server URL, connect, then
open Tools and list the available tools. Leave custom Origin and Authorization
headers unset for these local demo servers.

Use `get_link` with an existing code for a read-only shortener check. Creating a
short link writes to the shared database: supply `owner`, `url`, and
`requestKey`, with optional `ttlSeconds`/`customCode` as advertised in the tool schema.
Reuse the same owner, request key and exact arguments when reconciling an
uncertain creation outcome. Payment creation and coordinator registration/lease
tools also change backend state; listing them does not invoke them.

## Optional manual CLI discovery

These commands list tools and exit; they do not invoke any tool:

```powershell
npx --yes @modelcontextprotocol/inspector@2.9.0 --cli --transport http --server-url http://127.0.0.1:8119/mcp --protocol-era legacy --connect-timeout 10000 --method tools/list
```

```powershell
npx --yes @modelcontextprotocol/inspector@2.9.0 --cli --transport http --server-url http://127.0.0.1:8080/worker-coordinator/mcp --protocol-era legacy --connect-timeout 10000 --method tools/list
```

## Troubleshooting

- Port 6274 in use: reuse the existing Inspector UI. It is independent of the
  servers on ports 8080 and 8119.
- HTTP 403: remove a manually supplied Origin header. The gateway allows
  `http://localhost:6274` and `http://127.0.0.1:6274`; the shortener's configured
  allowlist controls its own Origin checks. Clients without Origin are accepted.
- HTTP 404 on the worker endpoint: default gateway configuration disables MCP;
  the `worker-payments` profile enables the combined ten-tool catalog. See
  [worker profile commands](WORKER_TOOLS.md).
- HTTP 405 from opening shortener `/mcp` in a browser: its stateless MCP
  transport uses POST. Connect with Inspector rather than navigating to `/mcp`.
- HAProxy HTTP 503: check its readiness/backends. Compare discovery against a
  direct replica before changing the MCP client configuration.
- Tool discovery succeeds but calls fail: discovery uses local tool metadata;
  it does not prove REST backends or the shortener database are available. The
  worker/payment gateway's configured application retries remain bounded by
  one deadline; do not assume one upstream attempt per invocation.

## Evidence and limits

`npm view @modelcontextprotocol/inspector version engines --json` reported
2.9.0 / Node >=22.19.0. Both `npx --yes
@modelcontextprotocol/inspector@2.9.0 --web --help` and the corresponding
`--cli --help` passed and confirmed these flags. Official
[web client documentation](https://github.com/modelcontextprotocol/inspector/blob/main/clients/web/README.md)
and [environment settings](https://github.com/modelcontextprotocol/inspector/blob/main/docs/environment-variables.md)
describe the browser connection and default loopback port.

HAProxy container inspection reported its 3.2 image healthy;
`GET /gateway/health` returned 200 with `X-Gateway: url-shortener-haproxy`.
Actual Java SDK discovery through `http://127.0.0.1:8119/mcp` identified
`url-shortener` 1.0.0 and listed exactly the two shortener tools. This task did
not launch another Inspector UI, run its CLI discovery commands, invoke tools,
test failover/streaming, or resume the paused automated suite.
