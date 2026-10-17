# Phase 0 repository assessment

Assessment date: 2026-10-17. Scope: the first-run Phase 0 requested by
`Enterprise_MCP_Gateway_Codex_Handover.md` in the owner's Downloads directory.
Phase 1 and Phase 2 are not authorized by this assessment.

## Evidence boundary and repository state

Assessed repository: `D:/ai-projects/mcp-gateway-server-java`, single Maven module
`dev.mcp.gateway:mcp-gateway-server:0.1.0`. Initial branch was `main`, clean at
`1e1c4fe9411321804d4019d19467e6951c7ba63d`, with `0 0` ahead/behind the local
`origin/main` reference. Origin is `https://github.com/savi0909/mcp-gateway-java.git`.
That comparison alone does not establish the current remote server state.

Recent history confirms distinct increments: foundation/tutorial work, enterprise
vision (`5db6e1d`), UC-01 planning (`e3794a6`), local admission implementation
(`b25a205`), then the UC-01 handover (`1e1c4fe`). The downloaded handover is context,
not a replacement for those decisions or their acceptance boundaries.

Reviewed root [AGENTS.md](../../AGENTS.md), [CLAUDE.md](../../CLAUDE.md),
[WORKLOG.md](../../WORKLOG.md), build/Wrapper/CI, Java source and test entry points,
catalogs, profiles, local Compose, foundation specifications, enterprise plans,
identity/catalog requirements and their evidence trackers. No nested AGENTS.md or
CLAUDE.md was found in the repository file inventory. External shortener services,
private configuration and live providers were not exercised. This is a scoped
architecture/evidence assessment, not a comprehensive security audit.

Only the two Phase 0 review documents are added. The handover's explicit
two-deliverable limit takes precedence over the routine WORKLOG/plan-update rule;
this assessment records the session evidence instead. Existing source, historical
documents and acceptance states are preserved. Completed review documents may be
committed and pushed under the standing repository instruction; that does not
authorize a harness, feature implementation, merge or deployment.

Status definitions used below:

- `VERIFIED_IMPLEMENTED`: source/configuration exists and relevant executed checks
  support the stated, bounded behavior; documentation existence is identified as such.
- `PARTIALLY_IMPLEMENTED`: a narrower implementation exists than the target requires.
- `NOT_IMPLEMENTED`: no implementation found in this repository's inspected inventory.
- `UNVERIFIED`: historical/external claim or operational property not re-established here.
- `BLOCKED`: a proposed next action requires an explicit approval or unresolved decision.

## Stack and structure

| Item | Actual evidence |
| --- | --- |
| Java / Maven | Java 21.0.9 (Azul), Maven 3.9.11; Wrapper 3.3.4 and distribution/JAR checksums in `.mvn/wrapper/maven-wrapper.properties` |
| Boot / AI / SDK | Boot 4.0.8, Spring AI BOM/starter 2.0.1, BOM-managed MCP SDK 2.0.0; `pom.xml` plus resolved dependency tree |
| Security / tests | Spring Security 7.0.7; Boot-managed JUnit Jupiter/Platform **6.0.3**, not the handover's suggested JUnit 5 |
| Main packages | `config`, `catalog`, `rest`, `mcp`, `security`, `admission`, `demo` under `src/main/java/dev/mcp/gateway` |
| Runtime | One Boot/WebFlux process; ASYNC Streamable HTTP MCP; fixed `/worker-coordinator/mcp`; loopback listener; default MCP disabled |
| Profiles | `payments` one tool; `workers` nine; `worker-payments` ten; `mock` independent demo backend; `secured` and `uc01` opt-in security/admission layers |
| Build checks | Surefire `*Test`, Failsafe `*IT`; executable Boot JAR; Windows/Linux matrix in `.github/workflows/verify.yml` |
| Local infrastructure | `compose.coordinator.yml` describes external coordinator/payment builds plus PostgreSQL; no gateway database, Kubernetes manifests or production deployment pipeline found |

Preserve this stack. The `spring-ai-model` transitive artifact does not imply a
model-provider starter, provider key or LLM dependency in gateway execution.
No gRPC library/proto, native upstream MCP adapter, Testcontainers, OpenTelemetry,
policy-engine integration or autonomous runner was found. Their absence is not a
reason to install them during harness bootstrap.

## Verified implementation inventory and target gaps

Source paths in this table are relative to `src/main/java/dev/mcp/gateway/`;
named tests are under the corresponding `src/test/java/dev/mcp/gateway/` packages.

| Proposed capability | Status | Supporting evidence and boundary |
| --- | --- | --- |
| Java MCP gateway / virtual REST tools | VERIFIED_IMPLEMENTED | `mcp/McpToolRegistrationConfiguration`, `rest/RestBindingExecutor`; `CatalogProtocolIT`, `CoordinatorToolsIT` run Boot plus actual SDK clients against independent HTTP mocks. |
| Startup tool/server registry | VERIFIED_IMPLEMENTED | `catalog/FileCatalogLoader`, immutable `ToolDefinition`/schemas; strict catalog versions 1/2/3, one server, v3 limit 1–32 tools; `FileCatalogLoaderTest`, `CatalogProtocolIT`. This is static registration. |
| General enterprise registry / federation | NOT_IMPLEMENTED | No native upstream discovery/router/refresh implementation; [enterprise vision](../ENTERPRISE_MCP_VISION.md) is future architecture. Native bindings and extra servers are rejected. |
| Native URL-shortener MCP example | UNVERIFIED | Historical external project in WORKLOG and [Inspector guide](../MCP_INSPECTOR.md); not a module or federated upstream here. No current external source/runtime validation in Phase 0. |
| HAProxy/NGINX and Inspector | UNVERIFIED | Historical shortener discovery through HAProxy recorded; no proxy config in this repository. Historical notes explicitly exclude incremental streaming/failover proof. Old NGINX Compose must not be assumed current. |
| gRPC virtual adapter | NOT_IMPLEMENTED | REST bindings only; no `.proto`, gRPC dependency or adapter found. |
| Multiple virtual endpoints | NOT_IMPLEMENTED | [ADR 0005](../decisions/0005-multiple-virtual-servers.md) and related handover are proposals. Current loader/transport enforce one endpoint. |
| MCP discovery, invocation, session ownership | VERIFIED_IMPLEMENTED | SDK owns protocol negotiation/envelopes/lifecycle; `security/SessionAdmissionFilter`, `admission/AdmissionSessionFilter`; protocol `2025-11-25` exercised. |
| Streaming, recovery and cancellation guarantees | PARTIALLY_IMPLEMENTED | `ResponseLossIT`, `CommittedCancellationTest` prove physical HTTP-reset/local disposal/deadline behavior. Graceful SDK closure alone is not cancellation proof; proxy failover, transparent stream replay and newer protocol/Tasks remain unverified/unimplemented. |
| Origin, input validation and sanitized results | VERIFIED_IMPLEMENTED | `mcp/McpOriginFilter`, `catalog/FlatObjectSchema`, REST projection; real-client negative counters and exact integral values. Discovery does not expose static REST bindings/tokens. |
| JWT validation and scopes | VERIFIED_IMPLEMENTED | `security/SecurityConfiguration`: RS256, issuer, audience, type, time and claim validation; protected-resource metadata; seven `SecuredGatewayIT` cases. The handover's illustrative JWT task duplicates existing work. |
| Complete OAuth2/OIDC integration | PARTIALLY_IMPLEMENTED | Resource-server JWT/JWKS boundary exists. Interactive login, deployed IdP, workload issuance, exchange and provider integration are not demonstrated. |
| Separate application/human/tenant identities | VERIFIED_IMPLEMENTED | `admission/AdmissionIdentity`, `AdmissionPolicy`, `AdmissionService`; active organization/tenant, registered application and authority/subject human checks; delegated ordinary-read tests in `Uc01AdmissionIT`. |
| Autonomous agent/workload identity | NOT_IMPLEMENTED | Only `HUMAN_DELEGATED` and `ADMINISTRATION` accepted. Agent-run IDs provide attribution, not authority. UC-03 is later approved-scope work. |
| Identity lifecycle and administration | PARTIALLY_IMPLEMENTED | Local `/control/changes`, explicit admin scopes/roles, pending enrollment, self-grant/self-activation denial and retired-ID protection. Organization/tenant topology and broader multi-tenant principal administration remain later. |
| Application catalogs, explicit membership and pinning | PARTIALLY_IMPLEMENTED | Local ordinary membership REQUEST/REMOVE, publication independent of membership, immutable contract fingerprint/origin/schema checks; `Uc01AdmissionIT`. Broad governance/migration/provider matrix is incomplete. |
| Per-user discovery and direct-call enforcement | VERIFIED_IMPLEMENTED | `admission/FilteredMcpTransport` uses public SDK session SPI for filtered tools/list; `AdmissionService` independently admits calls; same-app different-user discovery and hidden-call rejection tested. Ordinary internal reads only. |
| RBAC and contextual ABAC | PARTIALLY_IMPLEMENTED | Explicit administration roles and app/human/delegation/resource grant intersection exist. No general policy language/engine or arbitrary unsupported obligations; do not rename this a complete enterprise policy engine. |
| Sensitive-tool/action approvals | NOT_IMPLEMENTED | Sensitive membership remains PENDING; sensitive execution and mutations disabled in UC-01. No single-use approval consumption or authorized positive UC-02 path. |
| Downstream credential isolation | PARTIALLY_IMPLEMENTED | Exact org/tenant/app/human/server/purpose references, audience/expiry checks, no inbound-bearer forwarding/shared fallback; scoped-token mock evidence. Opaque token scope/issuer is trusted provisioning, not live downstream verification/exchange. |
| Approved third-party execution | NOT_IMPLEMENTED | UC-01 requires INTERNAL server trust and pinned origins; third-party-positive paths disabled. |
| Durable admission audit / outcome uncertainty | PARTIALLY_IMPLEMENTED | `AdmissionJournal` force-writes before dispatch; `AdmissionBoundaryTest` proves missing outcome/restart uncertainty without replay. Authentication/session failures before admission lack complete durable audit coverage. |
| Audit retention / export | PARTIALLY_IMPLEMENTED | Scoped audit export and 90-day visibility metadata exist. No controlled physical expiry, archival or compaction; each journal capped at 16 MiB. Full retention acceptance remains unmet. |
| Revocation and outage handling | PARTIALLY_IMPLEMENTED | Fresh subscription admission, synchronous local administrative changes, monotonic 30-second last-verified-policy fallback; rollback/conflict rejection in `VerifiedPolicyCacheTest`. No distributed revocation, workload/approval/provider coverage or power-loss/backup rollback proof. |
| Resilience, timeouts and retries | PARTIALLY_IMPLEMENTED | 16 active REST connections, 32 pending, total deadline/body limits; connector retry/redirect disabled. Legacy explicit retries reuse exact invocation values. UC-01 authorized GET uses one attempt. No general circuit breaking/health routing. |
| Rate limits, quotas and backpressure | PARTIALLY_IMPLEMENTED | Bounded REST pool/queue and 256 secured sessions are limits, not tenant/tool quotas. No inbound rate limiter or distributed quota accounting found. |
| Observability and traceability | PARTIALLY_IMPLEMENTED | Sanitized invocation logs and local scoped admission audit exist; no distributed traces, OpenTelemetry pipeline, tool metrics/SLO dashboards or validated usage accounting. |
| Control plane/data plane separation | PARTIALLY_IMPLEMENTED | Logical administration/admission boundary exists inside one process; no separately deployed control plane or versioned distribution service. Distinguish this product control plane from the proposed engineering runner. |
| Horizontal scaling / HA / multi-region | NOT_IMPLEMENTED | In-process sessions and single-writer journals; scaling aspirations are not measured capabilities. No safe clustered session/policy/audit design implemented. |
| Automated checks and CI definition | VERIFIED_IMPLEMENTED | Wrapper verify plus Windows/Linux CI workflow exist. Fresh local verification is below; no hosted CI run is claimed by this assessment. |
| Durable engineering backlog / task state | NOT_IMPLEMENTED | Existing milestone plans/worklog are human-readable sources. No machine task schema, dependency selector, claim lock or completion reconciliation. |
| Harness modes / fresh sessions / repair loop | NOT_IMPLEMENTED | CLI exists, but no orchestrator, validator, bounded batch, runner review pass, cancellation supervision or mocked runner tests. |
| Phase 1 implementation / pilot / batch | BLOCKED | Explicit phase gates in the supplied handover. Phase 0 approval permits only the selected Phase 1 scope; pilot and batch require later approvals. |

## Fresh verification

Executed from the repository root on 2026-10-17:

| Exact command/check | Result |
| --- | --- |
| `pwsh -NoProfile -File scripts/doctor.ps1` | Passed: Java 21 JDK, Wrapper files, Git/Codex/Claude available. |
| `.\mvnw.cmd -version` | Maven 3.9.11, Java 21.0.9 on Windows 11. |
| `.\mvnw.cmd -B -ntp '-Dgateway.build-directory=target/phase0-20261017' verify` | PASSED: 128 unit/startup tests and 34 integration tests, zero failures/errors/skips; executable JAR packaged. Finished 11:53:38 +05:30, Maven elapsed 2:00. Fresh output directory avoids old reports and the existing demo JAR. |
| `.\mvnw.cmd -B -ntp dependency:tree '-Dincludes=org.springframework.ai:*,io.modelcontextprotocol.sdk:*,org.springframework.security:*,org.junit.jupiter:*,org.junit.platform:*' '-DoutputFile=target/phase0-20261017-dependencies.txt'` | Passed; resolved versions match the table above. |
| `codex --version`, `codex --help`, `codex exec --help` | Passed; installed CLI 0.162.1. Help inspection only; no child agent or repeated Codex execution. |

Verification log: ignored `target/phase0-20261017-verify.log`. Fresh Surefire and
Failsafe XML reports live under `target/phase0-20261017/`. These are local fixture
results, not live IAM, business backend, capacity, Inspector UI or HAProxy evidence.
No SAST, vulnerability scan, production deployment or hosted CI result is claimed.

Surefire breakdown: `AdmissionBoundaryTest` 3, `VerifiedPolicyCacheTest` 4,
`FileCatalogLoaderTest` 3, `GatewayPropertiesTest` 38, `CommittedCancellationTest`
1, `RestBindingExecutorTest` 50, `RestBindingTest` 26, `ScaffoldStartupTest` 1,
`SecurityPolicyTest` 2. Failsafe: `Uc01AdmissionIT` 14, `CatalogProtocolIT` 4,
`CoordinatorToolsIT` 6, `ResponseLossIT` 3, `SecuredGatewayIT` 7. The successful
build includes expected negative-startup exceptions and deliberate connection-loss
warnings; the current-run XML reports and final summaries establish the outcome.

Both Phase 0 documents passed UTF-8, relative-link, balanced-fence, final-newline
and trailing-whitespace checks. XML totals were independently aggregated from
the fresh run. Both preserved requirements files match HEAD after normalizing
Git line endings; identity v1.4's working-file SHA-256 remains
`9e175a85312eb62794ce4b06338626c47c25b2173dbdf28e4e3ef4b64a205709`.

An initial exploratory read used nonexistent `.github/workflows/ci.yml`; the
inventory identified the real `verify.yml`, which was inspected. A Windows `rg`
glob-path error was corrected by searching directories/explicit files. Neither
was a build failure. No files were deleted, no `clean` was run and existing demos
were not restarted.

## Installed Codex configuration evidence

The local model cache timestamp was `2026-10-17T06:20:13.440625500Z`. Allowlisted
fields from local configuration show `model = "gpt-6-astra"` and
`model_reasoning_effort = "high"`. The cache lists `gpt-6-astra` and `gpt-6.1-sol`
as visible API-supported IDs, both supporting `high`; this is installed-runtime
evidence, not a guess from a display name. Authentication files and other private
configuration were not printed. No current-session model switch or new model
request is claimed. Cache discovery does not prove a future request will succeed.

CLI help confirms fresh `codex exec`, `--json`, stdin prompts, `--output-schema`,
`--output-last-message`, `--sandbox`, `--cd` and model/config overrides. It also
exposes worktrees and review, but their unattended recovery/approval behavior was
not tested. The future runner must probe its selected model and Windows sandbox
before a real task; fail closed on unsupported syntax or rejected configuration.

## Highest-priority discrepancies and risks

1. **Do not restart completed milestones.** M1/M2/M3/M4 in the generic handover
   overlap tested foundation and UC-01 work. Local proof does not complete full
   identity v1.4, catalog v1.3 or enterprise E1–E4.
2. **Do not fork requirements or evidence.** Preserve the supplied v1.4/v1.3 text,
   `docs/decisions/`, WORKLOG and the three acceptance domains. New generic identity,
   architecture and acceptance documents would create competing authorities.
3. **Separate external demos from implemented federation.** Shortener/HAProxy
   historical discovery is neither this gateway's native adapter nor proxy
   streaming/failover acceptance. Current runtime health is unverified.
4. **Do not postpone audit/revocation behind a broad roadmap.** U5 is partial;
   missing authentication audit and physical retention remain release gaps. The
   journals re-read policy history and serialize local admission/admin operations:
   throughput is unmeasured, and capacity exhaustion fails closed.
5. **Preserve retry contracts.** Legacy payment replay within one invocation is
   explicitly approved; UC-01 reads are single-attempt. Future approval-based
   uncertain mutations cannot inherit generic retries or automatic approval reuse.
6. **Harden the engineering trust boundary.** An implementation agent must not
   alter its validator, approval record, scope or completion status. A clean worktree
   and passing self-reported tests alone do not establish safe completion.
7. **Account for Windows and durable recovery.** A shell-only verify wrapper is
   insufficient here. Model configuration, process-tree cancellation, file locks,
   stale ownership and the Git-commit/status-update crash window require tests.
   Do not inherit this interactive session's unrestricted permissions as runner defaults.
8. **Repair stale navigation separately.** `docs/ACCEPTANCE.md` introduction still
   says UC-01 has no evidence/all identity rows pending; `docs/UC01_ADMISSION_PLAN.md`
   has pre-implementation wording in its extension-point section;
   `docs/DEPENDENCY_BASELINE.md` retains scaffold-era verification language;
   `docs/AI_WORKFLOW.md` has an old handoff snippet saying not to commit. Current
   code/latest worklog/ADR 0009 and standing owner instructions resolve these.
   Preserve dated historical evidence; fix present-tense navigation in approved scope.

## Requirements, proposals and decisions

**Confirmed:** pinned stack; unchanged coordinator; SDK-owned protocol; default
disabled MCP; local delegated ordinary-read scope; separate verified identities;
catalog pinning; audit before dispatch; 60-second revocation requirement;
15-minute delegation and 90-day audit baseline; all 32 identity criteria remain
the full-release gate. See [UC-01 plan](../UC01_ADMISSION_PLAN.md),
[identity acceptance](../IDENTITY_ACCEPTANCE.md) and [catalog acceptance](../CATALOG_ACCEPTANCE.md).

**Proposed defaults:** Python standard-library runner, JSON machine backlog,
PowerShell/POSIX verification entry points, one isolated task at a time, at most
three repairs, independent verification plus read-only review, bounded execution,
no automatic cleanup, promotion or batch. See the [refined plan](refined-autonomous-development-plan.md).

**Unresolved approvals:** accept the lean Phase 1 scope and canonical task-state
ownership; approve the later pilot separately; select any new identity/provider,
retention, clustering or protocol contract before its implementation. Existing
v1.4 policy defaults need not be reopened just to build a harness.

**Future work:** remaining U5 evidence and audit design; UC-03 then UC-02 and
approved-provider requirements; native federation, gRPC, rate limiting,
observability and enterprise deployment. None is made ready by this assessment.

Phase 0 ends with these findings and the proposed plan. Phase 1 has not started.
