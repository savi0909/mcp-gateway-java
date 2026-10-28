# ADR 0014: Gate modern MCP migration on demonstrated compatibility

**Status:** Proposed; no dependency or runtime change selected
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

## Proposed decision

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

Recommendation: A during S0, then B if verified. Evaluate C only with an explicit
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

The [protocol acceptance plan](../MCP_PROTOCOL_ACCEPTANCE.md) starts Pending.
Legacy test success and upstream SDK conformance are separate evidence. S0 may
complete with a documented blocker, while S3/S7 remain unready. No runtime upgrade,
new infrastructure, capability enablement or production readiness is approved by
this proposed ADR.

## Action items and acceptance evidence

- [ ] Enumerate supported revisions, capabilities and applicable server/client MUSTs.
- [ ] Inspect exact published artifact sources/metadata and resolved dependency trees.
- [ ] Compile/run independent modern server and outbound-client probes through SDKs.
- [ ] Verify two caller contexts, malformed header/body rejection, streaming and legacy behavior.
- [ ] Record the compatible starter/BOM, coexistence strategy, risks and regression plan.
- [ ] Update this ADR with executed findings and the selected scoped implementation decision.

Evidence at creation: official-source and repository review plus October 28
legacy Wrapper regression only; no candidate upgrade or modern probe executed.
