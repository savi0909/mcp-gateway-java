# MCP protocol and federation acceptance plan

Date: 2026-10-28. Status: **all modern rows Pending**.
This is a planning inventory for the proposed `2026-07-28` target, separate from
[foundation acceptance](ACCEPTANCE.md), [identity acceptance](IDENTITY_ACCEPTANCE.md)
and [catalog acceptance](CATALOG_ACCEPTANCE.md). Existing `2025-11-25` evidence is
preserved in those documents and WORKLOG. No modern protocol test has run here.

The rows group work; they are not an exhaustive transcription of the normative
specification. S0 must enumerate each applicable MUST and selected capability,
map it to a row and named test, and resolve any SDK gaps before migration.
See the [sprint roadmap](GATEWAY_SPRINT_ROADMAP.md) and
[proposed compatibility ADR](decisions/0014-modern-protocol-compatibility-proposal.md).

## Sources and support scope

Use the official dated [specification](https://modelcontextprotocol.io/specification/2026-07-28),
[versioning contract](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning),
[HTTP binding](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http),
[tools contract](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)
and [authorization contract](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization).
Official SDK conformance results do not replace tests of this gateway's filters,
adapters, proxying and identity/configuration integration.

Initial proposed scope is an authenticated tools gateway over Streamable HTTP.
Keep legacy behavior tested according to its own revision. Resources/prompts and
optional extensions require explicit selection before advertisement. The gateway
has two roles: inbound MCP server and outbound MCP client; applicable obligations
must be inventoried for both. A modern server-only SDK cannot pass outbound rows.

## Planned evidence matrix

| ID | Required observable evidence for the selected scope | Sprint | State |
| --- | --- | --- | --- |
| MP-01 | Exact Java/Boot/AI/BOM/SDK/client/transport versions, source API/property inspection and reproducible compile/run evidence; upstream prerelease limitations documented | S0 | Pending |
| MP-02 | Supported revisions/capabilities accurately advertised; mandatory modern discovery and independent version validation; unsupported-version and malformed-request outcomes before business dispatch | S3 | Pending |
| MP-03 | Required routing/version headers agree with request content; encoded names/custom parameters handled where applicable; missing/duplicate/invalid/mismatched input yields the defined rejection with zero business calls | S3 inbound; S7 outbound | Pending |
| MP-04 | Modern requests work without initialization/session continuity; per-request verified identities remain isolated; modern handling does not depend on or mint legacy sessions | S3 | Pending |
| MP-05 | Legacy initialization, session ownership and existing behavior remain verified; modern/legacy errors do not trigger unsafe fallback or accidental repeated business execution | S3, S7 | Pending |
| MP-06 | Deterministic caller-authorized tool pages with valid cache metadata; cache/cursor isolation by context and generation; revocation/removal enforced despite cached discovery | S3, S6, S10 | Pending |
| MP-07 | SDK-owned current result/error forms, precise IDs/integral values, validated schemas/content and sanitized execution errors; no invalid rewriting or protected output on denial | S3, S7 | Pending |
| MP-08 | JSON and incremental SSE behavior, finite message/stream/queue bounds, proxy flushing and physical disconnect cancellation; no modern resumption or replay assumption | S3, S7, S11 | Pending |
| MP-09 | Current Origin/authentication/resource-metadata behavior through the running server; selected OAuth client requirements through a real host/issuer; no inbound credential passthrough | S3, S5, S7 | Pending |
| MP-10 | Three independent upstream MCP servers discovered without executing business tools; unique deterministic public mappings retain upstream ownership, schema version and exact credential audience | S6, S7 | Pending |
| MP-11 | Supported schema dialect/reference/content handling is bounded; malformed/unsupported definitions cannot become active; malicious metadata/results cannot change authority, destination or disclosure | S6, S7, S9 | Pending |
| MP-12 | Atomic catalog/routing publication, pinned versions and fail-closed unavailable contracts; schema changes/removal/restart cannot mix generations or restore revoked authority | S6, S7, S10 | Pending |
| MP-13 | Both-hop deadlines/cancellation and exact attempt counters; retries only under a proven safe replay contract and fresh admission; lost response remains uncertain | S7, S8, S10 | Pending |
| MP-14 | Applicable official conformance suite plus independent client interoperability against the actual secured gateway; unsupported capabilities omitted; complete normative inventory linked to results | S3 server; S7 client; S11 final | Pending |
| MP-15 | When selected: change subscriptions/progress and other capability-specific behavior carry only authorized events, respect revocation and release bounded resources on disconnect | Selected extension sprint | Pending, conditional |
| MP-16 | When selected: resources/prompts and MRTR/Tasks use the correct revision/extension, tenant/owner state and fresh resume checks; no silent translation or bypass of enterprise approvals | Selected extension sprint | Pending, conditional |

MP-15/16 are conditional rows. Unselected capabilities remain unadvertised; their
absence need not block the explicitly scoped tools release. Required base-protocol
behavior and safe rejection of unsupported requests still belong to MP-02/14.
If the selected SDK cannot meet a core obligation, record a blocker rather than
claim conformance from an experimental partial implementation.

## Evidence recording rules

Use Pending until actual gateway evidence exists, Partial for incomplete rows,
and Verified only after every applicable subrequirement passes. Store normative
references, named tests, exact commands/artifact versions, expected versus actual
status/results and upstream request counts. Protocol raw-wire negative fixtures
may exercise malformed messages; runtime protocol handling remains SDK-owned.

Keep independent mock, local real IdP/host, official upstream conformance and
production evidence distinct. Use real Boot and official compatible clients for
positive interoperability; direct dispatcher tests are supplementary. Repeat
identity/catalog deny and outage tests after transport or upstream-client changes.
No row is verified by the October 28 regression run, which exercised the legacy
runtime only.
