# Engineering Constitution

These rules apply to coding agents and reviewers working in this repository.

## 1. Intent before implementation

A non-trivial task must state what problem is solved, what is in scope, what is
out of scope, and how completion will be proven.

## 2. Repository is memory

If a decision, constraint, or project fact is important, it must live in the
repository. Chat history is conversation, not durable memory.

## 3. Minimal sufficient context

Read only the project map, relevant spec, and relevant code needed for the
current slice. Avoid loading the entire repository or unrelated historical
documents into context.

## 4. Preserve existing behavior unless the task says otherwise

Refactoring must not silently change external behavior. If behavior must change,
the change must appear in the spec and acceptance criteria.

## 5. Scope is a gate

New requirements discovered during execution become one of:

1. Follow-up task;
2. Revision to the current spec after approval;
3. Explicitly recorded but not implemented now.

They must not be silently absorbed into the current task.

## 6. No blind retry loops

If the same problem fails three consecutive times, or a fix begins breaking
unrelated behavior, stop patching. Record what was learned and set
`REPLAN_REQUIRED`.

## 7. Verification is evidence

Tests, build, type checks, migration checks, and manual verification must be
actually run when relevant. A claim of completion must reference real evidence.

## 8. Review has two passes

1. Code quality review: clarity, duplication, architecture fit, and maintainability.
2. Requirements coverage review: verify every accepted behavior and boundary.

## 9. Durable decisions are written down

Important architecture, security, data, compatibility, and workflow decisions
must be recorded in `DECISIONS.md` or an ADR.

## 10. Small, reviewable slices

Prefer vertical slices that deliver a demonstrable behavior. Avoid splitting
work as all models first, then all services, then all UI, then all tests.
