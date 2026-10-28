# ADR 0016: Offline, non-destructive audit checkpoints and gated restore

Date: 2026-10-28. Status: selected for the owner-requested local S2 increment;
storage contract awaits owner review before S4.

The [S2 requirements](../S2_AUDIT_LIFECYCLE_REQUIREMENTS.md) require recoverable
evidence without restoring revoked authority. Adopt the
[operational contract](../S2_STORAGE_CONTRACT.md): a private OS-authorized storage
operator controls every included scope, independent receipts and fresh recovery
authorization. Tenant audit-viewer or ordinary tool authority confers no maintenance
access. No lifecycle HTTP endpoint or new identity execution mode is introduced.

Use the existing single-writer locks with a stopped gateway, exclusive new archive
and restore destinations, per-file forced writes/read-back, versioned exact ordered
inventories, record counts and SHA-256 digests. Publish checkpoint completion last.
Do not truncate, compact, overwrite or delete history. Capture both journals at one
boundary, preserving all retained evidence and unresolved outcomes regardless of age.

Restore verifies an independently retained exact digest and expected revision.
A durable external intent fences even interruption before the first restored file.
Normal startup also requires a fresh, independently provisioned recovery
authorization outside restored/archive material. Its 30-second validity window,
digest and minimum authoritative revision prevent silent bootstrap/old-revision
startup when the trusted operator's current authority reports a newer revision.
This is explicit trusted deployment attestation, not an online control plane or
protection against a privileged operator lying about current authority. Each cold
restart of a restored store needs fresh authorization. Normal subsequent writes
remain authoritative and append-only; recovery never replays business actions.

Expose private byte/segment/headroom/free-space capacity signals and projections
from operator-observed rates; zero means insufficient data. Warn at 80%, critical
at 95% or less than one segment of filesystem space. Keep failure/lifecycle reserve
and production throughput/RPO/RTO unspecified: full audit storage fails closed,
including maintenance when it cannot force its own lifecycle evidence. No silent
quota increase, automatic archival fallback, deletion or business resumption.

Local mounted storage is the adapter boundary. Independent off-device durability,
monitoring/notification routing, OS ACLs, current-authority attestation and evidence
reconciliation are operator responsibilities. Tests inject failed and partial
writes rather than filling the host disk. Power-loss directory durability,
automatic expiry, clustered fencing and distributed rollback remain unproven.
S2 physical-expiry acceptance and full UC-01/U5 release remain incomplete.
Java/Boot/AI/SDK pins and deferred modern MCP migration remain unchanged.
