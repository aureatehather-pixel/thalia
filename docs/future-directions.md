# Thalia: future directions

Recorded: 2026-10-03
Status: **Exploratory. Not approved, not scheduled, and not part of any milestone or acceptance.**

These are ideas to consider after the current roadmap, not delivery commitments.
[The specification](product-spec-v2.md) remains the approved product direction, and wins wherever this document disagrees with it.
Several ideas below go past its current boundaries (section 3, "Thalia does not initially"): purchases, sending messages, and acting as an agent.
Adopting any of them needs an amendment to the specification or an ADR first, and the authority model (section 9) and the AI contract (section 8) still apply.

The owner's approved release direction (V1 to V4) is recorded separately in [release-direction.md](release-direction.md). It is a direction, not implementation-ready scope. The ideas in this file remain exploratory and are unchanged.

## Thalia

### Planning coordinated with tool-assisted execution

- Calendar changes, preparation for work sessions, appointments, errands, and bounded purchases.
- Selected actions delegated to agents, with the actual outcomes recorded against the plan.

### An optional planning challenger

The user chooses how deep it goes:

1. Sequence and dependencies.
2. Methods, including hybrid or alternative approaches.
3. Substituting actions by their expected contribution to a goal.
4. Strategy, constraints and tradeoffs.
5. Clarifying or reframing the goal.

### Rules for any proposal

- Each proposal explains its rationale, assumptions, tradeoffs and uncertainty.
- The original plan is preserved. The user accepts, modifies or rejects each proposal explicitly.
- AI proposes; deterministic Kotlin validates the changes it supports and applies them only after approval.
- No autonomous project decomposition and no silent goal changes.

## Shared agent principle

Every agent is defined by an outcome, its tools, bounded standing permissions, observable results, and stop conditions.

## Cross-project reference: Elena

Elena is a separate project, noted here only because it shares the agent principle above: prospect qualification, personalised outreach, follow-ups, reply handling, meeting preparation, proposals, delivery tracking and invoicing.
Its specification does not belong in this repository.
