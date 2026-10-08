# Secured MCP foundation: run, demonstrate and assess evidence

The `secured` profile adds caller authentication to `worker-payments`, `workers`
or `payments`. Legacy profiles and `mock` are unauthenticated local demos.
Default MCP remains disabled. See [ADR 0006](decisions/0006-secured-mcp-foundation.md)
and the [threat model](SECURITY_THREAT_MODEL.md) for the enforced boundary.

The selected next [UC-01 enterprise slice](UC01_ADMISSION_PLAN.md) is pending.
Its v1.4 contract adds separate application/human/delegation facts, catalog
eligibility, durable admission audit and bounded revocation. This foundation's
shared discovery, restart-only tenant policy and terminal logs do not establish
those guarantees; see [the separate identity tracker](IDENTITY_ACCEPTANCE.md).

## Run with your existing authorization server

Build and select a different loopback gateway port without restarting existing demos:

```powershell
.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/foundation' verify
$env:MCP_ISSUER = 'https://issuer.example'
$env:MCP_JWK_SET_URI = 'https://issuer.example/jwks'
$env:MCP_RESOURCE = 'https://gateway.example/worker-coordinator/mcp'
$env:MCP_POLICY_LOCATION = 'file:./config/local/tenant-policy.json'
java -jar target/foundation/mcp-gateway-server-0.1.0.jar --spring.profiles.active=worker-payments,secured --server.port=18080
```

These URL values are placeholders, not a provisioned issuer or deployment.
Issuer and resource must be canonical HTTPS URLs; bind TLS/remote access only
as a separately selected deployment. The gateway remains loopback-only. A
local issuer demonstration can explicitly set
`--gateway-security.allow-loopback-http=true`; HTTP then permits only localhost
URLs. The test suite uses that option with random issuer/gateway ports and a
fixed synthetic resource audience. The configured resource is authoritative;
untrusted Host/forwarded headers do not choose the audience or metadata URL.

Supply an access token with:

- RS256 signature from the configured JWKS and header `typ: at+jwt`.
- Exact `iss`, `aud` containing `MCP_RESOURCE`, nonblank `sub` and `tenant`.
- Required `iat` and `exp`, valid `nbf` when present; no future issuance or expired
  credentials. Timestamp validation uses zero clock skew in this bounded demo.
- Space-separated `scope` containing `gateway:read` for reads and/or
  `gateway:write` for mutations. Write scope does not imply read scope.

Never put access tokens in catalogs, tracked configuration, command arguments,
logs or worklogs. For the SDK smoke client, supply `MCP_ACCESS_TOKEN` privately
through the environment. Discovery is the default; `--allocate` creates payments
and is appropriate only against an independently selected mock/demo target.
The resource server does not implement login or token issuance; a compatible
OAuth host obtains tokens from the advertised external issuer. Interactive
authorization-code/PKCE flows are not verified by these JWT fixture tests.

`/.well-known/oauth-protected-resource/worker-coordinator/mcp` (and the root
metadata endpoint) publishes resource/issuer/scopes. A missing/invalid credential
returns HTTP 401 with a `WWW-Authenticate` resource-metadata challenge. Authenticated
callers share public tool metadata. Tool denials return sanitized `ACCESS_DENIED`
with `allocationOutcome: not_attempted`, absent structured content and zero business
backend calls. Origin and session-owner violations return HTTP 403 before dispatch.

## Provision the bounded tenant policy

[tenant-policy.json](../examples/tenant-policy.json) contains synthetic fixture
data, not credentials or a deployed policy. Copy/adapt it into ignored
`config/local/` using your provisioned identity/resource contract.

Each tenant has preauthorized product IDs, service → product relationships,
worker-type → service relationships, allowed regions, subject → owner UUID pairs,
and a payment-admission flag. Registration cannot create an unlisted ID. Lookups
of service/worker-type IDs use trusted policy without querying REST to discover
ownership. All lease namespace parents, region and owner UUIDs must agree.
Caller owner UUIDs remain caller-supplied; the gateway does not generate them.
Two subjects cannot share an owner pair, and resource IDs cannot overlap across
tenants within each kind. Policy edits require restart.

This is gateway admission isolation. It does not add tenant columns or enforce
authorization on direct backend access. Payment admission does not establish
persisted tenant ownership. Backend origins/tokens remain independent deployment
settings, and caller JWTs are never forwarded as REST bearer credentials.

Session owners are issuer/subject/tenant tuples, separate from credential expiry
or scopes. Every HTTP request revalidates the current token. A refreshed token
from the same owner can use the session; another subject/tenant cannot. Both SDK
sessions and admission entries have 15-minute idle expiry and a 256-session cap.
Restart invalidates all sessions. No distributed session store, token revocation
service, automatic policy reload or HA guarantee is provided.

## Reproducible independent security/failure demonstration

Run all tests/package:

```powershell
.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/foundation' verify
```

For the focused foundation demonstration:

```powershell
.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/foundation' '-Dtest=SecurityPolicyTest,CommittedCancellationTest' '-Dit.test=SecuredGatewayIT,ResponseLossIT' verify
```

The running Boot servers, HTTP business fixtures and ephemeral issuer/JWKS
listeners all use random loopback ports. The signer is generated in memory;
private keys/tokens are not written or printed. Issuer traffic has its own
counter, separate from REST business requests. No test targets existing demos.

| Demonstration | Executable evidence |
| --- | --- |
| Invalid signature/issuer/audience/time claims; missing credentials on POST/GET/DELETE | SecuredGatewayIT authentication matrix: HTTP 401, zero REST calls |
| Own-tenant read and denied registration/lease/payment writes | SecuredGatewayIT: exact separate backend token, explicit scopes, sanitized denial |
| Cross-tenant lookup, mixed parents, unapproved registration and forged owner UUIDs | SecuredGatewayIT: complete namespace/relationship matrix, zero calls on denial |
| Foreign subject/tenant session reuse and expired credentials | SecuredGatewayIT: POST/GET/DELETE rejection; original owner discovery still works |
| Concurrent A/B callers; forged MCP `_meta` | SecuredGatewayIT: no identity/scope leakage, allowed paths only |
| Missing settings, disabled secured profile and ambiguous policies | SecuredGatewayIT + SecurityPolicyTest: startup/validation failure, immutable snapshots |
| Commit → lost response → retry | ResponseLossIT: two identical attempts, one logical result, exact backend ID |
| New MCP invocation after successful recovery | ResponseLossIT: another key and logical result; no cross-invocation idempotency claim |
| Commit followed by response loss until deadline | ResponseLossIT: bounded attempts, unknown outcome, retained logical commit, attempts stop |
| Cancellation during wait/backoff after commit | CommittedCancellationTest local disposal and ResponseLossIT actual SDK HTTP stream reset; no further attempts, commit persists |

The HTTP-reset fixture forwards bytes without parsing/capturing bodies. A reset
cancels the server response publisher and executor. **Graceful SDK client closure
alone did not cancel an outstanding invocation in the experiment.** SDK 2.0.0
does not guarantee that behavior; a cancelled client future likewise is not proof
of server cancellation. Retain total deadlines as the final bound. Do not promise
rollback or recommend automatically issuing a new payment invocation after an
unknown result.

The independent payment fixture models a backend that persists logical results
by request key before intentionally dropping the response. This is mock evidence,
not a live payment resilience test. Native shortener creation instead accepts
caller-supplied owner/requestKey for durable replay across invocations; refer to
its own existing evidence. This gateway generates a payment key inside each
invocation. Changing that public contract requires a separately versioned decision.

The remaining host/Inspector walkthrough, controlled HAProxy failover and later
operations assistant are separate work. No production deployment or hosted CI
execution is claimed. Read [WORKLOG.md](../WORKLOG.md) for exact final results.
