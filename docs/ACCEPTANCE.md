# Milestone 1 acceptance evidence

`docs/FUNCTIONAL_SPEC.md` section 7 is authoritative. Mark a row `verified` only after an actual automated run demonstrates all parts. Use `partial` for limited scaffold/unit evidence and `pending` for missing tests. Record commands/results in `WORKLOG.md`.

The user subsequently authorized application allocation retries and a real local payment-demo variant, and paused automated tests. See ADRs 0002/0003. Live smoke evidence is recorded below without claiming the original worker milestone or final code is fully verified.

| ID | Evidence required | State | Current evidence / next test |
| --- | --- | --- | --- |
| AC-01 | Wrapper executable packaging and Boot startup without model credentials | Verified | Wrapper `verify`: 1 test, 0 failures; executable JAR startup smoke passed. This is Boot scaffold evidence only. |
| AC-02 | SDK negotiation and exact file-defined single-tool discovery | Partial | Live payments-profile SDK negotiated `2025-11-25` and discovered `create_sample_payment`; default-worker/schema/hint automated checks pending |
| AC-03 | Zero allocation on startup/discovery; no private discovery metadata | Partial | Gateway startup/discovery left zero persisted payments; full HTTP counters and public metadata assertions pending. Separate payment worker acquires its own lease on startup |
| AC-04 | Two calls, two requests, exact backend IDs, matching text/structured content | Partial | Two actual SDK calls returned distinct payment ID strings with matching text/structured content; database showed two payments/two IDs/two request keys. Exact HTTP attempt counters pending |
| AC-05 | Exact method/path/body/auth; GET without body | Partial | Executor request assertions passed in pre-override exploratory run; final catalog/payment/retry integration tests deferred |
| AC-06 | Unknown tool/invalid arguments make zero allocation requests | Pending | Protocol and gateway error assertions with counters |
| AC-07 | Sanitized HTTP errors, no followed redirects, no canary leakage | Partial | Pre-override status/redirect/canary assertions passed; focused stalled-body correction passed. Current retry policy/error SDK mapping needs final tests |
| AC-08 | Bounded disconnect/deadline, allocation outcome unknown; user-authorized automatic retries supersede original no-retry policy | Pending | Retry tests updated but not run after pause. Live success smoke did not exercise failures or retry recovery |
| AC-09 | Invalid/oversized replies rejected; nested pointer and large integer preserved | Partial | Pre-override executor matrix/large-integer assertions passed; current live numeric payment projection passed. Final automated verification deferred |
| AC-10 | Duplicate coordinator IDs returned unchanged | Partial | Pre-override executor duplicate fixture passed; no ID fabrication/cache added. Final SDK duplicate evidence pending |
| AC-11 | Fresh-context catalog edits work; running edit does not reload | Pending | External catalog + context lifecycle tests |
| AC-12 | Invalid/missing/unsupported/duplicate catalog prevents startup | Pending | Strict loader validation + failing Boot contexts |
| AC-13 | Origin allow/deny/absence correctly enforced before REST | Pending | Running transport requests + no-call counters |

Scaffold startup and local payment smoke success do not complete milestone 1. CI configuration is not a hosted CI run. Local Docker payment integration was checked; production coordinator/UUID integration and final Wrapper `verify` were not performed.

## User-directed worker API extension (ADR 0004)

| Evidence | State | Actual result / next action |
| --- | --- | --- |
| Compile/package multi-tool gateway and tests | Partial | Wrapper `verify -DskipTests` passed; both test runners skipped |
| SDK discovery of nine worker/namespace tools plus payment | Partial | Running combined-profile Boot and SDK client negotiated `2025-11-25`, listed all ten names; no tool invoked |
| All coordinator mappings, response fields, epochs and private backend routing | Pending | `CoordinatorToolsIT` implemented and compiled; execution paused |
| Unsupported catalogs, schemas, duplicate names and immutable metadata | Pending | `FileCatalogLoaderTest` implemented and compiled; execution paused |
| Argument/Origin enforcement and sanitized worker failures | Pending | Protocol tests implemented and compiled; execution paused |

The extension supersedes the original single-tool boundary for catalog version 3.
Versions 1 and 2 retain their original cardinality. Discovery/build evidence does
not mark full original AC rows or worker execution verified.
