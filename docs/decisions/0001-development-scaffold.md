# ADR 0001: One build and shared AI development guidance

- Date: 2026-10-07
- Status: accepted for the development scaffold

## Context

The repository originally contained three handoff documents and an archive, with no source/build/Git setup. The user requested development scaffolding and files for Codex and Claude Code. The handoff defines a separate local gateway, a single Maven module, Java 21 and a compatible stable Spring AI/Boot pair.

## Decision

Use Java 21, Spring Boot 4.0.8, Spring AI BOM 2.0.1 and Maven Wrapper 3.3.4 targeting Maven 3.9.11. Boot 4.0.8 and AI 2.0.1 were available in Maven Central, and the official AI compatibility page supports Boot 4.0.x. Keep the BOM-managed MCP dependency versions. Use group/package `dev.mcp.gateway` and artifact `mcp-gateway-server` unless an organization-specific namespace is supplied.

Keep project rules in `AGENTS.md` and import them from `CLAUDE.md`. Supply small tool-specific skill entry points referencing the same `docs/AI_WORKFLOW.md`; maintain one worklog and acceptance tracker. Do not change global tool settings, grant blanket permissions or install automatic execution hooks.

Create the Boot skeleton and restore the example catalog from the supplied ZIP. Keep MCP disabled until strict startup catalog validation and programmatic registration are implemented together. The skeleton must not be mistaken for a functional endpoint. Preserve the original handoff documents.

## Consequences

Both AI tools share the same constraints and resumption state. The development build can be verified independently of the coordinator and any model account. Remaining stages must replace the scaffold startup guard with real SDK discovery/invocation evidence. Proposed runtime properties currently serve as configuration guidance; typed binding and enforcement are future work.

The source namespace is a default, not a known publishing domain. No license or remote hosting choice has been invented. Hosted CI and live integration remain unverified until actually run.

## References

- `FUNCTIONAL_SPEC.md` sections 1-2 and 7-8
- `DESIGN.md` sections 3 and 10
- https://docs.spring.io/spring-ai/reference/getting-started.html
- https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/4.0.8/
- https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-bom/2.0.1/
