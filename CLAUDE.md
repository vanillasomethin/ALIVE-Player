# ALIVE Player — Claude Instructions

## Project Context
Android TV / Fire TV digital signage player. Kotlin, minSdk 26, Room, WorkManager, ExoPlayer/Media3, Glide.
Backend: `https://wearealive.in` (Next.js + Prisma + Postgres). Branch: `claude/device-claim-proof-of-play-997VY`.

---

## Working Rules

### Plan Mode
- Enter plan mode for any task requiring 3+ steps or architectural decisions.
- If something goes sideways mid-task: stop, re-plan, then resume.
- Write specs upfront to eliminate ambiguity before touching code.

### Subagents
- Use subagents to keep the main context window clean.
- Offload: research, file exploration, parallel analysis, build verification.
- One focused task per subagent.

### Verification Before Done
- Never call a task complete without proving it works.
- Diff actual vs expected behavior when relevant.
- Ask: *Would a staff engineer approve this PR?*
- Run tests / check logs / demonstrate correctness.

### Elegance Check (non-trivial changes only)
- Pause and ask: *Is there a more elegant way?*
- If a fix feels hacky: *Knowing everything I know now, implement the elegant solution.*
- Skip for simple, obvious fixes — don't over-engineer.

### Bug Fixing
- Given a bug report: fix it directly. No hand-holding required.
- Use logs, errors, and failing tests as the spec.
- Fix failing CI without being asked how.

### Self-Improvement
- After any user correction: update `tasks/lessons.md` with the pattern and a rule that prevents recurrence.
- Review `tasks/lessons.md` at the start of each session.

---

## Task Workflow
1. **Plan** — write `tasks/todo.md` with checkable items before any implementation.
2. **Check in** — confirm plan before starting (plan mode).
3. **Track** — mark items complete as you go.
4. **Summarise** — high-level summary of changes at each step.
5. **Document** — add a review section to `tasks/todo.md` when done.
6. **Capture** — update `tasks/lessons.md` after any correction.

---

## Core Principles
- **Simplicity first** — minimal code impact; touch only what's necessary.
- **No laziness** — find root causes; no temporary fixes; senior-engineer standards.
- **No speculation** — read the actual source before making assumptions about contracts or behaviour.
