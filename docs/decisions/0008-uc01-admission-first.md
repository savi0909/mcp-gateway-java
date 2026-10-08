# ADR 0008: UC-01 admission before broader enterprise federation

**Status:** Accepted sequencing; architecture and implementation pending
**Date:** 2026-10-08
**Deciders:** Project owner

## Context

The owner supplied identity/authorization requirements v1.4 and requested updating
project documents before designing/implementing the recommended UC-01 slice.
The baseline requires separate application/human authority, catalog eligibility,
durable admission audit and bounded revocation. Existing foundation behavior is
not evidence for these stronger enterprise guarantees.

## Decision

Make [UC-01 admission](../UC01_ADMISSION_PLAN.md) the selected next implementation
slice: identity and tenant lifecycle, minimum approved/published/pinned catalog
eligibility and membership, delegated ordinary-read authorization, isolated
credentials, durable audit and revocation. Build and prove them as one increment.

This refines ADR 0007's federation-first sequence: broad remote-tool federation
remains the first major enterprise registry goal, after this admission prerequisite.
It also brings baseline admission audit and supported-context revocation forward
from the long-term control-plane roadmap. Preserve the strategic architecture.

Use the unchanged [v1.4 baseline](../requirements/Enterprise_MCP_Identity_Authorization_Requirements_v1.4.md)
and [separate IA acceptance tracker](../IDENTITY_ACCEPTANCE.md). Full first-release
completion requires every baseline MUST and all 32 acceptance criteria; UC-01
completion covers only its applicable behavior. Unsupported modes/actions stay
disabled. Obtain catalog requirements v1.3 before finalizing their external contract.

## Consequences

Future implementation starts with a separate architecture record for verified
identity mapping, persistent audit, admission consistency and revocation. This
sequencing ADR does not select vendors, token formats, database or protocol changes.
The current turn updates documentation only. Existing functionality, legacy demos,
supplied foundation documents and test evidence remain intact.

Read-only permission is the initial execution scope. Audit failure prevents
dispatch, and retained configurations cannot bypass the revocation bound. Later
approval-based mutations must not inherit generic replay of uncertain effects.

## Evidence

Requirements/documentation review only. All identity acceptance criteria are
pending; existing 121 unit/startup and 20 integration results remain historical
foundation evidence. No runtime or enterprise-release verification is claimed.
