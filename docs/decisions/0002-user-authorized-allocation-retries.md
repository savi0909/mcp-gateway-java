# 0002 - User-authorized automatic allocation retries

Date: 2026-10-07. Status: implemented, build-only verification; automated
verification deferred at the user's request.

## Context

The original milestone specification and stage 1B plan require one upstream
attempt without retries. During implementation the user explicitly clarified:
"it mean automatic reties for allocation". This instruction supersedes the
original no-retry requirement for this session. The user also requested pausing
tests and inspecting a local Docker coordinator for a UUID-generating API.

## Decision

Implement application-controlled allocation retries for connection/transport
failures, connect/read timeouts, HTTP 408, HTTP 429 and HTTP 5xx. Each attempt
reuses the same validated method, path, static JSON body and optional token.
Use Reactor's exponential backoff starting at 100ms and capped at 1s, with its
default jitter. There is no separate attempt-count cap; the existing total
upstream deadline bounds the entire subscription, including backoff and all
attempts. Cancellation and deadline expiration stop further local attempts.

Keep Reactor Netty's hidden connection-reset retry disabled and redirect
following disabled. Other HTTP statuses and invalid successful responses are
terminal failures. Retain sanitized error categories and unknown allocation
outcomes. Do not log upstream bodies, credentials or returned IDs.

## Consequences and evidence

One accepted call can now issue multiple upstream requests. A lost response can
cause multiple allocations at a non-idempotent backend; uniqueness does not
prevent this. An exhausted total budget returns `UPSTREAM_TIMEOUT` with
`allocationOutcome: unknown`. No exactly-once behavior is claimed.

The original no-retry acceptance assertions need revised evidence. Tests were
updated but not run after this instruction. Wrapper packaging with
`-Dmaven.test.skip=true` succeeded; this is compilation/packaging evidence only.

The inspected coordinator has no UUID-generating REST API. Its lease acquisition
returns an existing lease for repeated requests by the same instance. It has not
been installed as the gateway allocation binding. The user subsequently selected
the separate payment sample; its binding now uses a fresh UUIDv7 idempotency key
per invocation and reuses that key for every retry. See ADR 0003 and
[the actual contract](../REAL_COORDINATOR_CONTRACT.md).
