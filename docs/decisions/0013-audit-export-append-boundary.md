# ADR 0013: Audit export cursors bind an append boundary

Date: 2026-10-18. Status: selected U5 correction within package A.

Timestamp-only pagination can include its own later access records when the clock
repeats or moves backward. A deterministic probe reproduced an export of two
records continuing through four new access records without reaching its end.

Keep `records`, `snapshotAt`, `nextCursor`, and the existing query parameters.
Capture the number of organization/tenant-scoped records on the first page,
before appending its access audit. Encode this boundary and the last returned
scoped append position in the opaque 64-character lowercase hexadecimal cursor,
with a 192-bit digest binding those positions, scope, timestamp and anchor record.
Eight hexadecimal digits per position suffice: journal storage is capped at
1 GiB, so even the smallest JSON-line records cannot exhaust the positive int
range. Positions count only the authorized scope, not other tenants' activity.

Later pages cannot include append positions beyond that boundary. They still
verify current reviewer authority, match the anchor, apply the timestamp filter
and current retention visibility, and durably record access before responding.
Allow a valid continuation timestamp to exceed the current wall clock after a
clock correction; initial requests still reject future timestamps. Require both
the cursor and its returned timestamp on continuation. Persist no export-session
state: retained journals allow continuation across service/journal reopen.

The digest is a consistency check, not a signature or permission credential.
Every request performs authorization; a cursor cannot grant cross-tenant access.
Older in-progress hash-only cursors must restart export after this correction.
No HTTP fields or stored journal formats change. Physical retention operations,
distributed snapshot semantics and workload execution remain outside this fix.

Evidence must cover frozen/backward time, later appends, invalid/rebound cursors,
scope, revoked reviewer authority, current expiry, reopen and real HTTP pagination
after SDK execution. U5 and full-release acceptance remain Partial.
