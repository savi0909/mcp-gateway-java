# ADR 0007: Enterprise MCP gateway and control-plane direction

**Status:** Accepted strategic direction; implementation pending
**Date:** 2026-10-08
**Deciders:** Project owner

**Sequencing update:** [ADR 0008](0008-uc01-admission-first.md) selects UC-01
admission before broad federation. This strategic architecture remains accepted;
its original federation-first order is refined by that prerequisite.

## Context

The owner requested adding an enterprise platform vision to project context and
the overall design process. The existing verified foundation adapts REST into
one MCP server. Native MCP federation and separate administrative control-plane
capabilities are future work.

## Decision

Use [the enterprise vision](../ENTERPRISE_MCP_VISION.md) to guide future planning:
four core subsystems (federation, identity, policy enforcement and resilient
execution), with observability and governance across them. Separate request
processing from configuration management; target validated retained snapshots
for temporary control-plane outages, with bounded staleness and revocation rules.
Keep the gateway useful independently of an LLM provider or agent framework.

Record enterprise phases E1-E4 separately from the completed foundation milestone.
Federation is the first major subsystem for a future implementation request.
Prioritize demonstrated system-wide guarantees over feature count. Server/tool
counts and concurrency ambitions remain design targets until measured.

## Consequences and boundaries

Shared agent instructions and the plan reference this context. This decision
authorizes documentation only: no new runtime features, infrastructure, protocol
upgrade, service operation or deployment. Preserve the original functional
specification, existing acceptance evidence and prior proposals. Technology
candidates and the newer protocol are subject to scoped compatibility decisions
before adoption; existing 2025-11-25 session semantics remain unchanged.

## Evidence

Documentation review and official 2026-07-28 protocol/versioned Tasks source
inspection only. No enterprise implementation, capacity or interoperability test
is claimed by this decision. Future phases require their own acceptance evidence.
