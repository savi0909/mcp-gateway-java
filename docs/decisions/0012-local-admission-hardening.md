# ADR 0012: Bounded local admission and retained journal segments

Date: 2026-10-18. Status: selected for the owner-requested admission-hardening increment.

Preserve the UC-01 contract and pinned SDK. Use a dedicated bounded disk-work
scheduler, fresh authorization after queueing, and durable sanitized records for
HTTP rejections before tool dispatch. Rejection records contain generated IDs,
fixed reasons and endpoint categories, never raw headers, bodies or unverified
principal claims. Failure to record a rejection returns verification unavailable
and never continues the filter chain.

Retain the original policy.jsonl/audit.jsonl as first segments. At the segment
size boundary create sequential segments without overwriting or deleting prior
evidence. Each new audit segment starts with a forced lifecycle record identifying
the previous segment's digest. Startup validates contiguous segments and rejects
partial records, missing segments and broken digest links. An empty final segment
from an interrupted creation can be completed; a nonempty incomplete segment
requires operator recovery. No automatic replay of admitted business work occurs.

Each policy entry is already a complete immutable snapshot. Cache deserialization
of unchanged verified segment bytes; still read/hash the current authoritative
segment and reject changes to sealed segments. Restore validates all retained
history. This reduces repeated policy parsing, not a claim of constant-time
storage access or distributed durability.

Segments default to 16 MiB, with a 256 MiB per-journal retained-storage budget.
Capacity exhaustion remains fail-closed and is visible in local storage status.
Sealed segments are the local archive. Retain them for at least 90 days; this
increment never physically deletes records. Export remains tenant-authorized;
bounded pagination is required for large histories. Physical purge/off-device
archival and backup rollback protection remain explicit later operational work.
Do not call retention timestamps physical expiry, or claim indefinite availability
on a finite disk. No existing user files or evidence are removed by this work.

The single-writer lock and local session model remain. Power-loss durability of
directory entries, external tamper resistance, multi-replica state and production
capacity are not established. Independent tests must demonstrate segment rollover,
restart, capacity/failure behavior, rejection audit, bounded scheduling and current
revocation/catalog authorization through the running SDK server.

The layout is forward-readable from legacy journals, not backward-compatible
after rollover. Older binaries can ignore numbered files and read obsolete policy;
operators must not downgrade against a segmented directory. Restore requires the
complete stopped-writer directory. Cross-version migration/anti-rollback recovery
requires separate design and evidence.
