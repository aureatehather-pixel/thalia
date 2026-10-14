# Thalia: release direction

Recorded: 2026-10-04
Status: **Owner-approved direction. Not implementation-ready scope.**

This records where the owner wants Thalia to go, release by release.
It is a direction, not a plan: it does not approve any integration, does not amend any ADR, does not change [the specification](product-spec-v2.md), and does not add to the acceptance of the current slice (ADR 0009, slice 3).
Each item below needs its own design and, where it crosses a boundary of the specification, an amendment to the specification or an ADR first.
Where this document and the specification disagree, the specification wins until it is amended; the disagreements are listed at the end.

Three kinds of statement exist in the documents, and this file keeps them apart:

| Kind | Where | Meaning |
|---|---|---|
| Approved product behavior | [specification](product-spec-v2.md) | What Thalia is, and what the milestones deliver. |
| **Owner-approved release direction** | this file | What the owner wants each release to aim at. Not yet scoped, designed or authorized to build. |
| Exploratory ideas | [future-directions.md](future-directions.md) | Ideas to consider. Not approved, not scheduled. |

## Purpose

Thalia reduces the mental workload and time spent coordinating routine life, protecting attention for study, technical growth, meaningful work and other user-chosen priorities.

## V1 target: strategic planning partner

- Daily planning, with a realistic, deterministic proposal the user approves.
- Outcomes recorded honestly, and carry-forward of what was not finished.
- Check-ins, opening and closing.
- Reflection that improves the next decision.
- Deterministic replanning when reality changes.
- Voice, with text as the fallback.
- An optional planning challenger with five levels, chosen by the user: sequence and dependencies; methods; substituting actions by their expected contribution to a goal; strategy, constraints and tradeoffs; clarifying or reframing the goal. Its rules are in [future-directions.md](future-directions.md): every proposal explains its rationale, assumptions, tradeoffs and uncertainty, and the original plan is preserved.

Most of this is already the specification's roadmap (M3 to M8). The five-level challenger is new to the roadmap; the specification only has the assistant "gently challenge weak assumptions" (section 15).

## V2: approved workflow priorities

In this order:

1. **Calendar coordination.**
2. **Context-aware research and document preparation.**
3. **Food planning, and one ordering-service pilot,** with budget constraints and approval of the exact final transaction.

### Research principles

- Start from the user's question, context, existing knowledge and familiar sources.
- Offer relevant unfamiliar sources and credible opposing perspectives.
- Distinguish four purposes: exploration, argument comparison, decision support and learning plans.
- Explain why sources were selected, cite evidence and expose uncertainty.
- Produce a useful synthesis rather than an unnecessary volume of material.

Alex O'Connor is an example of a familiar starting source for one user. He is not a universal preference and not an exclusive source.

### V2 foundation

- A dedicated automation account or workspace.
- Separate permissions for each product.
- Explicit promotion: work done in the automation space reaches the user's own accounts only when the user promotes it.
- Observable execution: the user can see what ran, with what, and what it did.
- Bounded standing permissions.

Account separation reduces exposure. It does not eliminate external consequences: whatever the automation does in the outside world still happens.

## V3: personal-use-driven recurring operations

Recurring operations, replenishment, travel and dependency tracking, driven by what the owner actually needs in daily use.
To explore: how routines adapt during holidays and exam periods.

## V4: exploratory

Subscription and bill visibility, and selected financial workflows.
Broad banking access is not assumed.

## Boundaries that stay

- AI proposes. Deterministic Kotlin validates the planning changes it supports. The original plan and the user's explicit approval remain.
- No autonomous project decomposition.
- Elena's business specification stays outside Thalia (see the cross-project note in [future-directions.md](future-directions.md)).

## What this document does not do

- It does not approve any integration, service, account, purchase flow or external API.
- It does not amend ADR 0009, or any other ADR, and it does not amend the specification.
- It does not expand slice 3 or any current milestone. The minimal loop and its acceptance are unchanged.
- It does not pick a release date, an order inside a version beyond the V2 priorities above, or a technology.

## Where this goes past the specification

These are conflicts, or gaps, found on 2026-10-04 against [the specification](product-spec-v2.md). None is resolved here. Each needs a decision, and an amendment to the specification or an ADR, before any work on the item starts.

| This direction | The specification says | Section |
|---|---|---|
| Food ordering pilot, bounded purchases | "does not initially ... make purchases or applications" | 3 |
| Research and document preparation from the web and other sources | "does not initially ... act as a general web agent" | 3 |
| Calendar coordination that changes events beyond Thalia's own blocks | External calendars are read-only to Thalia; Thalia writes only to its own secondary calendar "Thalia Plan" | 10 |
| A dedicated automation account or workspace | Use the user's existing Google account, with one Thalia-owned secondary calendar and narrow scopes | 10 |
| Bounded standing permissions, and V3 recurring operations and replenishment | Step 5 of the authority ladder is "later"; "purchases ... always require explicit approval" | 9 |
| V4 financial workflows, and V3 travel and dependency tracking | No financial or travel capability; external communication and purchases need explicit approval each time | 3, 9 |
| Context-aware research using the user's context | "smallest relevant context", privacy levels, and a record of what was sent externally | 8, 12 |
| Research and automation costs | USD 10 default monthly cap, warnings at 70% and 90%, explicit approval beyond the cap | 8 |
| Observable execution of automation | Material operations show a truthful state; there is no execution log for external actions | 16 |
| Five-level planning challenger in V1 | Not in the roadmap; only the character note in section 15 | 15, 18 |
| Calendar coordination listed as V2 priority 1 | Syncing approved Thalia blocks to the Thalia Plan calendar is in M7 and in the acceptance criteria of the complete loop (criterion 10), so it is part of the first release as written | 18, 19 |
| Automation that runs when the user is not present | "without becoming recursively autonomous"; no commercial backend or SaaS architecture is required | 2, 3 |

The last two rows are the most likely to need an early decision: what exactly is in V1 for the calendar, and whether the automation foundation needs anything that runs off the phone.

## Relationship to the exploratory document

[future-directions.md](future-directions.md) keeps its exploratory ideas exactly as they were, including the planning challenger, tool-assisted execution, the shared agent principle and the Elena note.
Where an idea there matches a direction here, this document is the owner's approved direction and that one is the earlier exploration; neither changes the specification.
