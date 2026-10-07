# 0003 - Opt-in MCP tool backed by the existing payment sample

Date: 2026-10-07. Status: implemented; build and local live SDK smoke passed;
automated verification deferred at the user's request.

## Context

The user replaced the mock-only stage request with inspection and Docker startup
of `D:/java-projects/distributed-coordinator`, then clarified that any suitable
sample API returning unique IDs was acceptable, including payments. The existing
coordinator leases worker slots; it has no per-request UUID API. The separate
worker sample exposes payment creation using its lease-aware numeric ID generator.

## Decision

Keep coordinator and worker source unchanged. Run them as separate Docker
services with separate coordinator/payments databases. Publish REST listeners on
loopback ports 9090 and 9091. Keep the gateway's default profile MCP-disabled and
preserve both original worker catalogs byte-for-byte. Enable the actual SDK
transport only with the opt-in `payments` profile and a validated catalog.

Add one empty-arguments tool, `create_sample_payment`, at the fixed logical MCP
endpoint `/worker-coordinator/mcp`. Its configured static amount is `12.34`.
Extract numeric `/id` using exact integral parsing and return only a string
`paymentId` in matching text/structured success content. Do not expose private
bindings, addresses, filenames or credentials through discovery.

Use catalog schema version 2 for this explicitly requested demo variant. Version
1 retains the original empty-input/workerId-output format; version 2 supports only
the empty-input/paymentId-output format and a `requestKeyField` equal to
`clientIdempotencyKey`. A POST body must omit that field. The gateway adds one
UUIDv7 request key per accepted subscription, outside the retried publisher, and
reuses the serialized body on every attempt. This generates request keys, not
returned payment IDs. New MCP invocations are not advertised as idempotent.

Keep one server/tool, strict field/schema validation, immutable startup metadata,
ASYNC SDK handlers, pre-execution Origin enforcement, one safe terminal log per
invocation and sanitized execution errors without structured error content. Do
not add general argument/body mappings or registration/lease tools.

## Evidence and limits

Wrapper packaging with `-Dmaven.test.skip=true` succeeded. An actual running
Boot gateway and compatible BOM-managed SDK client negotiated `2025-11-25` and
discovered exactly `create_sample_payment`. Discovery left zero persisted
payments. Two explicitly requested invocations created two distinct payment IDs
and request keys, with matching text and structured output. IDs were not printed
or recorded. The worker sample acquired its own coordinator lease at startup.

This is a local sample integration. It changes the original milestone's ID and
retry contract under explicit user instructions; it does not establish all
original acceptance criteria. Automatic retry/error/cancellation behavior,
invalid-catalog startup checks and Origin enforcement still require automated
verification when the user resumes tests. The live success smoke did not inject
failures or establish retry recovery. Wrapper `verify` was not run after the
pause. The standalone independent REST demo mock remains a later deliverable.
