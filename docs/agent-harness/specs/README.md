# Specs

This directory holds task-scoped specifications, plans, task slices, and progress
logs for `Feature` and `Release` work.

## Recommended Layout

```text
docs/agent-harness/specs/<feature-name>/
├── task.md
├── spec.md
├── plan.md
├── tasks.md
└── progress.md
```

Use short, stable, lowercase-hyphenated feature names, for example:

```text
record-center
permission-model-v2
deployment-hardening
```

## When to Create a Spec Directory

Create one for `Feature` and `Release` work when:

- the change crosses modules;
- it affects API, data, permission, security, deployment, or runtime protocol;
- it must survive another session or another agent;
- acceptance cannot be inferred from a short description.

Do not create a directory for `Investigation` or `Tiny` tasks.

## Lifecycle

- `spec.md` records confirmed behavior, boundaries, and acceptance criteria.
- `plan.md` records the implementation approach and impact.
- `tasks.md` records vertical slices and dependencies.
- `progress.md` records status, evidence, blockers, and replan notes.
- After delivery, either archive the spec or keep it as a long-term anchor, but do
  not leave it silently stale.
