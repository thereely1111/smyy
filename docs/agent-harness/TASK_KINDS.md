# Task Kinds

Classify every request before implementation.

## Investigation

Read-only exploration, comparison, impact analysis, or diagnosis.

**Minimum output:** findings, evidence, and recommended next step.  
**Documentation:** usually none, unless the result becomes a decision.

## Tiny

A localized, low-risk change with obvious verification.

Examples:

- Copy correction;
- Small style adjustment;
- Rename a local variable;
- Add or adjust one narrow test;
- Fix a trivial typo in code.

**Minimum workflow:** implement + run the narrowest relevant check.  
**Documentation:** no charter or spec.

## Bounded

A clear change confined to one module or a small known surface.

Examples:

- Fix one defect with a reproduction path;
- Add a field to one DTO and its mapper/test;
- Improve one endpoint's validation;
- Adjust one component's behavior.

**Minimum workflow:** task charter + verification + final summary.  
**Documentation:** `TEMPLATE_TASK.md`; spec only if behavior is non-obvious.

## Feature

A new capability or cross-module change requiring deliberate design.

Examples:

- New API and UI flow;
- New agent capability;
- New permission surface;
- New integration with an external system.

**Minimum workflow:** clarify → recon → spec → plan → vertical tasks → implement
→ verify → review.  
**Documentation:** task charter, `spec.md`, `plan.md`, `tasks.md`, progress/evidence.

## Release

A multi-session, high-risk, or deployment-affecting effort.

Examples:

- Schema model upgrade;
- Auth/permission model change;
- Runtime protocol change;
- Production rollout or migration.

**Minimum workflow:** full harness plus explicit ship authorization.  
**Documentation:** task charter, spec, plan, tasks, progress/evidence, decision
or ADR if durable.

## Rule

Use the lightest process that still makes the work resumable and verifiable.
Do not create ceremony for Tiny tasks, and do not run Feature/Release work as if
it were Tiny.
