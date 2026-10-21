# Agent Development Harness

This directory is the control plane for coding agents working
in a repository. It defines how requests are classified, planned, implemented,
verified, reviewed, and handed over.

## Purpose

- Turn vague requests into bounded, verifiable work.
- Give agents a stable repository map instead of relying on chat memory.
- Prevent scope creep and false completion.
- Preserve decisions and lessons across sessions.
- Keep vibe coding useful without making delivery chaotic.

## Core Files

| File | Use |
|---|---|
| [../AGENTS.md](../../AGENTS.md) | Coding agent constitution and entry rules |
| [USAGE.zh-CN.md](USAGE.zh-CN.md) | Chinese usage manual for this harness |
| [CONSTITUTION.md](CONSTITUTION.md) | Non-negotiable engineering rules |
| [CONTEXT.md](CONTEXT.md) | Stable project map and key facts |
| [TASK_KINDS.md](TASK_KINDS.md) | Task classification and minimum requirements |
| [GATES.md](GATES.md) | Workflow gates and stop conditions |
| [TEMPLATE_TASK.md](TEMPLATE_TASK.md) | Task charter template |
| [TEMPLATE_SPEC.md](TEMPLATE_SPEC.md) | Feature/change specification template |
| [TEMPLATE_PLAN.md](TEMPLATE_PLAN.md) | Implementation plan template |
| [TEMPLATE_TASKS.md](TEMPLATE_TASKS.md) | Vertical task slice template |
| [TEMPLATE_PROGRESS.md](TEMPLATE_PROGRESS.md) | Progress and evidence log |
| [DECISIONS.md](DECISIONS.md) | Stable project decisions |
| [LESSONS.md](LESSONS.md) | Repeated failure and correction patterns |
| [adr/](adr/) | Architecture decision records |
| [specs/](specs/) | Task-scoped specs, plans, tasks, and progress logs |

## Rule of Thumb

Use vibe coding for exploration. Use a spec when direction is confirmed.
Use the harness when the work must survive another session, another agent,
or production review.
