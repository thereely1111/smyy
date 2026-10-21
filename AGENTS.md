# Coding Agent Constitution

This repository uses a lightweight agent harness for AI-assisted development.
The goal is not to prevent exploration, but to make exploration safe, resumable,
and verifiable.

## Entry Rule

Before editing, classify the request as `Investigation`, `Tiny`, `Bounded`,
`Feature`, or `Release`.

- `Investigation` and `Tiny` may proceed directly with minimal documentation.
- `Bounded`, `Feature`, and `Release` must use the relevant harness files under
  `docs/agent-harness/`.
- If scope, acceptance criteria, or risk boundaries are unclear, clarify before
  implementing.
- Do not expand scope without explicit confirmation.

See [docs/agent-harness/TASK_KINDS.md](docs/agent-harness/TASK_KINDS.md) and
[docs/agent-harness/USAGE.zh-CN.md](docs/agent-harness/USAGE.zh-CN.md).

## Stable Context

For non-trivial work, read in this order:

1. [docs/agent-harness/CONTEXT.md](docs/agent-harness/CONTEXT.md)
2. [docs/agent-harness/GATES.md](docs/agent-harness/GATES.md)
3. The relevant feature spec, plan, and task list, if present.

Do not reconstruct project knowledge from chat history if the repository already
contains a stable answer. Repository facts win over chat memory.

## Hard Rules

1. Do not claim completion without real verification evidence.
2. Do not modify unrelated files while implementing a task.
3. Do not silently change public API, database schema, permissions, security
   behavior, or deployment behavior.
4. Do not invent acceptance criteria. If they are missing, ask or propose them
   explicitly before implementation.
5. Do not solve a failing task by repeatedly guessing. After three consecutive
   failed attempts, stop, record evidence, and set status to `REPLAN_REQUIRED`.
6. Do not treat task checkboxes as completion. Final verification must trace
   back to the spec and acceptance criteria.
7. Do not leave long-term decisions only in chat. Put stable decisions into
   [docs/agent-harness/DECISIONS.md](docs/agent-harness/DECISIONS.md) or an ADR.

## Preferred Workflow

```text
Clarify → Recon → Charter → Spec Freeze → Plan → Execute
        → Verify → Review → Integrate → Ship → Retro
```

Use the lightest workflow allowed by task kind. For `Feature` and `Release`,
create a task charter and explicit acceptance criteria before implementation.

## Completion Contract

For every non-trivial change, the final report must include:

- What changed.
- What did not change.
- How each acceptance criterion was verified.
- Commands executed and their results.
- Known limitations or follow-up tasks.
- Updated spec, progress, decisions, or lessons when relevant.

A completion claim without evidence is invalid.
