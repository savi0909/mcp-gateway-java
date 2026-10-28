# ADR 0014: Gate modern MCP migration on demonstrated compatibility

**Status:** Accepted for S0: retain pinned runtime; modern migration blocked
**Date:** 2026-10-28
**Deciders:** Project owner, with implementation compatibility evidence

## Context

The owner requested gaps and sprint planning toward a complete MCP gateway.
The repository pins Java 21, Boot 4.0.8, Spring AI BOM 2.0.1 and managed Java SDK
2.0.0, with executed `2025-11-25` protocol evidence. The proposed modern target
is `2026-07-28`; dated protocol revisions differ from library release versions.

Modern lifecycle changes affect the current session-decorating
`FilteredMcpTransport` and session-owner filters. Native federation also requires
a compatible outbound client. The official SDK
[roadmap](https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/main/ROADMAP.md)
and newer [2.2.0-M1 release notes](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.2.0-M1)
describe different delivery paths; the latter offers a modern server preview
with missing client/extension features. Neither proves compatibility with this
repository's starter, Reactor, JSON integration or admission guarantees.

## Decision after S0

S0 completed on 2026-10-28. Select option A: preserve Boot 4.0.8, Spring AI 2.0.1
and SDK 2.0.0 while continuing protocol-independent S1 work. No modern product
dependency combination is selected. The [executed findings](../S0_COMPATIBILITY_FINDINGS.md)
and [normative inventory](../MCP_2026_REQUIREMENTS.md) are the reproducible evidence.

After reviewing these findings, the owner explicitly chose to defer modern MCP
migration while building functional components. S1 is the selected next slice;
S2 audit lifecycle/recovery and S4 workload identities are the following
priorities, subject to their individual acceptance gates. Transport-independent
identity and registry work need not wait for S3. Revisit modern compatibility
before implementing modern native federation or committing to modern clients.
This changes implementation priority, not the requirements for any claimed
supported revision or the full enterprise release.

The isolated SDK 2.2.0-M1 modern servlet passed bounded server fixtures, but has
no modern Java client or modern WebFlux transport. AI 2.0.2 and 2.1.0-RC1 both
resolve SDK 2.1.0 and register legacy APIs; actual starter/SDK round trips
negotiated 2025-11-25. Conflicting duplicate method headers and custom parameter
header/body mismatches reached execution in the preview. Official Java client
initialization against the independent modern server failed with zero reads.
These findings prevent a ready S3/S7 migration recommendation.

Release-note Reactor milestone claims differ from the published POM and the
executed Boot dependency tree, which resolve Reactor Core 3.8.7 and Jackson 3.1.5.
The probes found no linkage failure; missing role/binding/validation support is
the demonstrated blocker, not an assumed Reactor/Jackson incompatibility.

## Compatibility gate

Run S0 in the [sprint roadmap](../GATEWAY_SPRINT_ROADMAP.md) before selecting a
modern dependency set. Preserve the pinned runtime until exact released artifacts,
source APIs, configuration metadata and real interoperability establish a viable
integration. Record inbound and outbound support separately. Use the official
SDK for each supported protocol era; do not emulate a new protocol through a
controller or handwritten JSON-RPC layer.

Prefer a tested stable compatible SDK/BOM/starter combination. A milestone may
be studied in an isolated experiment with its limitations recorded; successful
experimentation alone does not select it for the product. If no viable combination
exists, continue protocol-independent admission/audit/registry work and report
the precise dependency blocker. Recheck published artifacts before implementation.

## Options considered

| Option | Complexity / cost | Benefit | Consequence |
| --- | --- | --- | --- |
| A: Keep pinned runtime while investigating | Low immediate integration cost | Preserves current evidence and permits UC-01/workload domain work | Does not supply modern protocol support; dependency blocker stays visible |
| B: Adopt compatible official stable SDK through supported starter/BOM | Medium migration/testing cost; exact set unverified | Preferred maintainable route if both roles meet required behavior | Requires source inspection, admission migration and legacy regression evidence |
| C: Integrate official modern SDK directly or use a milestone experimentally | High integration and ongoing maintenance cost | May enable a bounded modern server experiment sooner | Missing client/headers/dependency compatibility may prevent federation or release; needs a separate supported binding strategy |

Selection: A after S0, then B if verified. Evaluate C only with an explicit
bounded experimental contract and documented support costs. Changing a property
or overriding the SDK version without compatibility evidence is insufficient.

## Trade-offs and migration boundaries

Preserve the foundation endpoint/profiles and legacy ownership controls.
Evaluate dual-era handling in one supported SDK integration versus separate
opt-in runtime configurations preserving the fixed endpoint in each deployment.
Choose the external coexistence contract after probing actual APIs; this proposal
does not authorize extra endpoints or arbitrary dependency overrides.

Move transport-independent trusted identity/admission contracts away from SDK
session subclasses when implementing S3. Stateless HTTP still needs durable
audit/policy/approval state and per-request authorization. Preserve filtered
discovery and direct-call checks with private context-aware caching.

Keep REST mapping/projection semantics intact. A new native adapter gets explicit
schema/content/capability validation rather than assuming legacy flat schemas
cover arbitrary MCP servers. Never retry an uncertain sensitive action because
a protocol or client library suggests reconnecting.

## Consequences

The [protocol acceptance plan](../MCP_PROTOCOL_ACCEPTANCE.md) remains Pending.
Legacy test success, isolated preview tests and upstream SDK conformance are
separate evidence. S0 is complete with a documented blocker; S3/S7 remain unready.
No runtime upgrade, new infrastructure, capability enablement or production
readiness is selected by this ADR. Recheck the gate when an official modern
client, supported modern Spring binding and required header handling are available.

## Action items and acceptance evidence

- [x] Enumerate scoped revisions/capabilities, normative obligations and planned tests.
- [x] Inspect exact published sources/metadata and resolved dependency trees.
- [x] Run the official modern server with HTTP fixtures and reproduce the official Java client's failure; record absent modern-client API as a blocker.
- [x] Exercise two caller contexts, header/body errors, SSE/disconnect and legacy SDK behavior; retain the two reproduced header gaps.
- [x] Record tested legacy starter sets, bounded direct-server set, coexistence limits and regression plan; no viable modern starter/client set found.
- [x] Record the scoped keep-current-runtime decision and following S1 task.

Executed evidence: 14 direct SDK experiment tests, one starter test for each of
AI 2.0.2 and 2.1.0-RC1, plus fresh product Wrapper verify (143 unit/startup and
38 integration tests), all zero failures/errors/skips. No official conformance
suite or modern SDK-client positive round trip ran. See the findings for exact
commands, source digests, reports and unverified boundaries.
