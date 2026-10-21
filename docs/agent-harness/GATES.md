# Harness Gates

Gates prevent an agent from moving forward on an unconfirmed or unverified state.
They are intentionally lightweight; Tiny tasks usually only need the Completion
Gate.

## Workflow States

```text
CLARIFYING → SPEC_DRAFTED → SPEC_FROZEN → PLANNING → IMPLEMENTING
           → REVIEWING → VERIFYING → READY_TO_SHIP → SHIPPED
```

Exception states:

```text
BLOCKED
SCOPE_CHANGE_REQUIRED
REPLAN_REQUIRED
```

## 1. Entry Gate

Before implementation, confirm:

- Task kind is known.
- Goal is stated.
- Scope and non-goals are understood.
- Verification method is possible.
- For Feature/Release, the user has approved starting implementation.

If any item is missing, stop and clarify.

## 2. Spec Freeze Gate

For Feature/Release, the spec must contain:

- Background and goal.
- In scope.
- Out of scope.
- Rules and constraints.
- Boundary/exception behavior.
- Verifiable acceptance criteria.
- Open questions separated from confirmed facts.

Do not proceed while open questions affect data, permission, security, API, or
schema decisions.

## 3. Scope Change Gate

When new work is discovered during implementation:

```text
STOP → Classify:
  1. Follow-up task;
  2. Amend current spec after approval;
  3. Record but do not implement.
```

Do not silently expand the current task.

## 4. Circuit Breaker

Stop patching when:

- The same failure occurs three consecutive times;
- A fix causes unrelated regressions;
- A core assumption is disproven;
- Required information or access is missing.

Then:

1. Preserve the best known state.
2. Record the failure hypothesis and evidence.
3. Set `REPLAN_REQUIRED`.
4. Re-plan in a fresh context or with a narrowed problem.

## 5. Evidence Gate

Do not declare completion without:

- Relevant tests or verification commands;
- Build/type check when applicable;
- Migration compatibility check when applicable;
- A trace from each acceptance criterion to evidence;
- Explicit notes on anything not verified.

## 6. Review Gate

Feature/Release work needs two review passes:

1. Code quality: architecture, duplication, naming, maintainability, project fit.
2. Requirements coverage: check every accepted behavior against the spec.

## 7. Ship Gate

`READY_TO_SHIP` means verification passed.  
`SHIPPED` means the user or release owner explicitly authorized delivery/deploy.

Risk acceptance cannot turn missing evidence into verified evidence.
