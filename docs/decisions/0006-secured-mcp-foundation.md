# 0006 — Opt-in caller security and gateway admission isolation

Date: 2026-10-07. Status: selected; implementation and evidence follow in the worklog.

The owner resumed independent gateway tests and selected the local foundation
handover, including authentication, permissions, tenant admission and response loss.
The original milestone and ADRs 0002–0004 remain separate from this extension.

Use the Boot 4.0.8 managed Spring Security 7.0.7 resource server and Nimbus reactive
JWT decoder. Keep Spring AI 2.0.1 / SDK 2.0.0. Require explicit issuer, JWKS URL,
canonical MCP resource/audience, and immutable startup policy. HTTPS is required
except an explicitly configured loopback test/demo mode. Require signed RS256
access tokens with issuer, audience, subject, tenant, expiry and issued-at; validate
not-before and reject future issued-at. No anonymous fallback in secured mode.

The `secured` profile layers onto `worker-payments` (or `workers`/`payments`).
Legacy profiles remain unauthenticated loopback demos. No identity provider,
database, backend changes or new MCP endpoints are introduced. Public protected
resource metadata advertises the canonical endpoint, issuer and `gateway:read` /
`gateway:write` scopes. Authentication challenges advertise its metadata URL.
This is a resource-server foundation; browser login/token issuance is delegated
to an existing authorization server, not claimed as an implemented OAuth client.

All MCP HTTP methods require validated identity. A filter places only a trusted
immutable caller in server-side exchange attributes. An explicit SDK provider
uses `contextExtractor` to create request-specific McpTransportContext; handlers
read that context. SDK source confirms request context propagation into ASYNC
exchanges. Client `_meta`, tenant inputs and arbitrary headers cannot establish
identity. Session IDs remain SDK-owned; a bounded admission registry binds each
SDK-created response session ID to issuer/subject/tenant, rejecting unknown,
expired or foreign sessions on POST/GET/DELETE. Session admission entries expire
after 15 minutes idle; the provider uses the same idle expiry and a 256-session
cap. Credentials are revalidated on each HTTP request. An in-flight request can
finish after token expiry; later requests cannot reuse an expired token.

Explicit tool names determine read/write policy, independently of annotations.
Unknown secured tool names fail startup. Handlers check authorization before
binding resolution. Missing identity/scope and resource/ownership denials use the
same sanitized `ACCESS_DENIED` tool result with `not_attempted`, without structured
error content. Common public tool metadata is visible to authenticated callers;
discovery visibility does not grant permission to invoke.

A trusted local policy contains tenant products, services with product parents,
worker types with service parents, permitted regions, and subject-owned UUID
pairs (instanceId, registrationId). IDs are preauthorized for registration.
All parent/child and lease namespace relationships must match, including UUID
ownership. Cross-tenant IDs must not overlap. Payment access is a tenant policy
flag, checked with write scope; its unchanged backend has no tenant storage.
This proves gateway admission isolation only. Direct REST access and storage
isolation are outside this boundary. Keep downstream tokens separate from caller
tokens, and do not forward or log JWTs/claims, resource IDs or bodies.

The SDK default argument diagnostics preempted the specified sanitized error
contract in the resumed protocol run. Its supported `validateToolInputs(false)`
switch delegates inputs to the gateway's strict supported-schema validator;
SDK schema validation at registration, success output validation, envelopes,
negotiation, cancellation, unknown tools and sessions remain SDK-owned.

Response-loss experiments use independent HTTP fixtures with separate counters
for attempts and logical commits. Exact per-invocation payment keys survive
retries; new MCP invocations generate new keys. No cross-invocation replay or
exactly-once guarantee is added. Cancellation cannot roll back committed work.

Sources inspected: pinned SDK/starter source JARs in ignored target, Boot managed
dependency POM, Spring Security 7.0.7 sources. Official references:
[MCP authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization),
[reactive JWT resource servers](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html).
