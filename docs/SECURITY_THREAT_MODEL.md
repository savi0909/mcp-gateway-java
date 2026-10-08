# MCP foundation threat model

The secured mode protects one loopback MCP resource and admits requests to
unchanged coordinator/payment REST services. It is a bounded learning foundation,
not a deployment of enterprise identity, backend storage isolation or HA.

The next [UC-01 admission slice](UC01_ADMISSION_PLAN.md) has stronger v1.4
requirements and no implementation evidence yet. Its design must extend threat
coverage to app/human/delegation separation, catalog eligibility, durable audit,
revocation races and context-scoped credentials. Keep its
[identity acceptance evidence](IDENTITY_ACCEPTANCE.md) separate from this model's
verified foundation controls.

| Boundary / asset | Threat | Enforced control / evidence |
| --- | --- | --- |
| Caller/host → MCP HTTP | Missing, forged, expired or misdirected credentials | Boot-managed RS256 JWT validation; issuer, canonical resource audience, time claims, subject, tenant and access-token type; SecuredGatewayIT |
| Browser → MCP HTTP | Unapproved Origin | Origin filter runs before security/SDK execution; allowed localhost origins and absent Origin retain support |
| HTTP authentication → ASYNC handler | Lost/stale/cross-client identity | Server-only Caller attribute → SDK request transport context → handler; concurrent clients and forged `_meta` tests |
| Session access | Stolen SDK session ID or cross-subject reuse | Revalidate credentials on every HTTP method; bounded owner registry; foreign subject/tenant denied on POST/GET/DELETE |
| Tool selection | Read-only hints mistaken for permissions | Explicit read/write scopes checked inside handlers; metadata visibility grants no execution rights |
| Resource/namespace arguments | Tenant spoofing, foreign parents, forged owner UUIDs | Trusted immutable preauthorized resource relationships, region policy and subject-owned UUID pairs; denied requests make zero REST calls |
| REST → MCP results | Private fields, malformed/oversized data, precision loss | Advertised-field projection, exact integral parsing, bounded bodies, sanitized error text; baseline mock/SDK tests |
| Gateway → REST credentials | Confused deputy/token passthrough | Separate configured backend bearer tokens; caller JWTs never forwarded; test asserts downstream token and no leakage |
| Startup catalog/policy | Unsupported definitions or ambiguous ownership | Strict bounded one-time parsing and immutable records; unknown secured tool names, duplicate keys, overlaps and invalid relationships fail startup |
| Backend commit → lost response | Duplicate mutation or false rollback claim | One total retry deadline, stable per-invocation request key, mock logical-commit counters, unknown outcome and cancellation experiments |
| Logs and discovery | Token, claim, ID or body disclosure | Safe terminal correlation/tool/duration/category only; canary assertions; private bindings/policy excluded from tools/list |

The operator, policy/catalog files and issuer/JWKS configuration are trusted.
An operator who can change these can change the admission contract. The issuer
must issue `at+jwt` RS256 access tokens for this exact resource; token issuance,
browser authorization-code/PKCE flows and credential provisioning are outside
the gateway. No incoming token is treated as a downstream credential.

Authenticated callers share public discovery metadata. A subject must exist in
the claimed tenant's policy. Resource IDs are globally disjoint across tenants
within each resource kind. Registration is limited to preauthorized IDs, and
lease owner UUID pairs belong to the authenticated subject. This does not validate
backend storage tenancy or reconcile backend ownership outside the gateway.
Payment admission is a policy flag; the existing payment API stores no tenant
ownership. A trusted backend can return data for an admitted resource; independent
backend authorization/storage isolation remains a separate responsibility.

Token expiry is checked when HTTP requests authenticate and again when tools
start. Already-started work can finish after expiry. Session owners are bound to
issuer/subject/tenant, not a token string: the same owner can refresh credentials,
but current request scopes determine invocation permission. The registry and SDK
provider use 15-minute idle expiry and 256-session bounds. Restart discards all
sessions; no distributed session or revocation service is provided.

JWT/JWKS calls have bounded connection/response/total waits and bounded bodies.
Business requests retain the existing connection pool, body limit and total
deadline. Neither limiter is a full caller rate limiter. Signed JWTs can remain
valid until expiry; issuer-side revocation/introspection is not implemented.

Cancellation evidence distinguishes local subscription disposal and an actual
HTTP stream reset from merely cancelling a client future or gracefully deleting
a session. The pinned SDK does not guarantee outstanding-request cancellation
from graceful client closure. Physical disconnect can cancel local work, but
cannot undo a backend commit. No exactly-once promise is made.

See [ADR 0006](decisions/0006-secured-mcp-foundation.md),
[security run/evidence guide](SECURED_MCP.md), and
[baseline acceptance](ACCEPTANCE.md). Independent fixtures use synthetic data;
these controls have not been deployed to the running local demo or production.
