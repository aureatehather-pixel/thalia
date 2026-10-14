# Thalia Product Specification v2

Status: Product and design direction approved on 2026-09-29\
Primary device: Pixel 10\
Product type: Personal, local-first Android application

## 1. Product thesis

Thalia is a local-first personal operating and executive system that turns stable direction into realistic daily action and closes the loop through planning, execution, check-ins, replanning, memory, and reflection.

Thalia is not merely a calendar, task manager, chatbot, or voice assistant. It is the coordinating layer between what kind of life the user wants, what matters now, what can realistically fit today, what actually happened, and what should happen next.

## 2. Core principles

1. Local-first, not AI-free. Core plans, goals, schedules, notes, memory, and history remain usable locally.
2. AI understands and proposes. Deterministic Kotlin validates and executes. Room remembers.
3. The user remains the authority. Consequential changes require preview and approval.
4. Reality outranks the original plan. Replanning repairs the day without judgment.
5. Direction is stable; goals, projects, tasks, and plans may change more frequently.
6. Thalia minimizes friction without becoming recursively autonomous.
7. Reflection improves the next decision; it does not grade the previous self.
8. Scheduled does not mean completed. Intent, planned time, actual outcome, and learned interpretation remain separate.
9. Costs, permissions, degraded states, and external data disclosure must remain visible and controllable.
10. The product should feel calm, warm, precise, and trustworthy.

## 3. Product boundaries

### Thalia does

- maintain direction, areas, goals, user-defined projects, tasks, routines, events, ideas, and notes;
- construct and revise realistic daily plans;
- schedule opening and closing check-in calls;
- call the user locally at confirmed times;
- conduct short optional AI voice conversations;
- capture outcomes and relevant context;
- reconcile alarms and persisted occurrences after interruptions;
- sync approved Thalia time blocks to a dedicated Google Calendar;
- surface evidence-based patterns for user review;
- operate meaningfully offline.

### Thalia does not initially

- invent or decompose project roadmaps, milestones, or tasks on the user's behalf;
- act as a general web agent;
- send emails or messages;
- make purchases or applications;
- control other apps outside Android's documented APIs and user-granted permissions;
- silently rewrite major goals, projects, memories, or schedules;
- optimize engagement through streaks, guilt, or nagging;
- require a commercial backend or SaaS architecture.

## 4. Information hierarchy

### Direction

The durable life vector. It has no progress percentage or fixed deadline and changes rarely.

### Areas

Ongoing life domains such as learning, health, career, relationships, and administration.

### Goals

Desired outcomes with a success condition, time horizon, status, and optional relationship to an area.

### Projects

User-defined bodies of work. States: someday, planned, active, paused, completed, abandoned, and archived. Only active projects compete for focus time. Thalia remembers and schedules project work but does not automatically decompose projects.

### Ideas

Potential future work kept separate from committed projects.

### Tasks and routines

Tasks are finite actions. Routines are reusable templates with fixed, flexible-window, or optional behavior. A missed routine does not create an overdue pile.

### Events and time blocks

External events represent fixed commitments. Thalia time blocks represent approved planned work. Flexible tasks may remain in an ordered queue without precise times.

### Notes, check-ins, memory, and insights

Notes preserve user-authored information. Check-ins record conversations and outcomes. Memory preserves explicit and operational context. Insights are tentative interpretations supported by evidence and user review.

## 5. The daily operating loop

Thalia plans around an active day rather than assuming the day begins at midnight.

### Closing check-in

The closing call:

- reviews what happened;
- marks work completed, partial, running late, blocked, skipped, or unknown;
- classifies unfinished work;
- proposes the next active day's schedule;
- confirms likely sleep, wake, and quiet periods;
- proposes the next opening check-in time;
- asks the user to approve or adjust the plan.

### Opening check-in

The opening call:

- reconnects the user to direction and current priorities;
- checks actual wake time, energy, commitments, and changes;
- minimally recalibrates the approved draft;
- asks for approval when changes are consequential;
- launches the first clear action.

### During the active day

- Today is timeline-first, with a compact Now card above it.
- Focus mode is secondary and opens when the active block is tapped.
- Fixed commitments and important focus blocks receive times.
- Smaller work remains in a flexible ordered queue.
- A block ending does not imply completion.
- Unknown outcomes receive one quiet prompt, never repeated nagging.
- Replanning occurs only after a meaningful event.

## 6. Planning policy

- Plan about 80% of realistic capacity and retain roughly 20% as buffer.
- Default to no more than two primary projects in one active day.
- Priority levels: Must, Important, Normal, Optional.
- Placement order: sleep and quiet period; fixed commitments; travel, meals, and buffers; deadlines; pinned focus; active-project tasks; routines; flexible queue; recovery.
- Preserve fixed commitments, the current activity, must-do work, and user-pinned blocks.
- Minimize movement when replanning.
- Drop excess work back to the backlog instead of overloading the day.
- Show unchanged, moved, and protected blocks clearly before approval.
- Offer Approve, Adjust, and Keep original.

## 7. Call and notification policy

- Target two calls per active day: opening and closing.
- Calls are based on confirmed occurrences, not implicit recurring assumptions.
- No confirmed occurrence means no ring.
- A quiet period strictly prevents ringing, vibration, screen wake, and full-screen presentation.
- Declined or missed calls do not repeat or nag.
- If the phone is off past the valid lateness window, record the occurrence as missed rather than ringing late.
- Offline calls still appear and offer a structured local fallback.
- Default live conversation length: 3-7 minutes.
- Quick mode: 60-90 seconds. Deep mode: user initiated.
- Warn at 10 minutes and end at 15 minutes by default unless the user extends it.

## 8. AI contract

Thalia sends only the smallest relevant context required for the current interaction. A live provider session is not treated as the authoritative store.

Pipeline:

1. Natural-language request or check-in conversation.
2. AI produces a typed proposal.
3. Local schema and policy validation.
4. Deterministic simulation of consequences.
5. User preview and approval when required.
6. Atomic Room transaction.
7. Alarm and calendar side effects.
8. Audit record and undo where possible.

The AI never writes directly to Room or Google Calendar. Invalid or overbroad proposals are rejected locally.

The provider remains replaceable. Gemini Live is the intended first live-voice provider over WebSocket. If the provider fails or the budget is exhausted, Thalia falls back gracefully to local structured interaction.

### Cost policy

- Default monthly cap: USD 10.
- Warn at 70% and 90%.
- At 100%, require explicit approval before additional paid use.
- Thalia may suggest adding credits or increasing the cap but never does so automatically.

## 9. Authority model

Thalia progresses through a bounded authority ladder:

1. Observe.
2. Recommend.
3. Prepare a reversible proposal.
4. Execute after approval.
5. Later, execute narrowly pre-approved and reversible actions.

External communication, deletion, purchases, major goal changes, and other high-consequence actions always require explicit approval.

## 10. Local data and integrations

### Room

Room, backed by app-private SQLite storage, is the source of truth. It stores the structured local state and supports offline use. Sensitive fields or the database will be protected using Android Keystore-backed encryption. Tokens and secrets are excluded from logs and exports.

### AlarmManager

Exact confirmed call occurrences are scheduled through Android AlarmManager. WorkManager is limited to non-exact maintenance. Reconciliation after launch, boot, package update, clock change, timezone change, and permission changes must be idempotent.

### Google Calendar

- Use the user's existing Google account.
- Thalia creates and owns a secondary calendar named Thalia Plan.
- Room remains authoritative; Calendar is a synchronized representation.
- Approved focus blocks and commitments are written automatically.
- Drafts remain local until approved.
- External calendars are read-only to Thalia.
- Moving a Thalia event in Google is treated as an intentional override.
- Deleting a calendar block unschedules it but does not delete the underlying task.
- Offline mutations enter a visible pending-sync queue.
- Intended scopes: `calendar.events.readonly` for external events and `calendar.app.created` for Thalia-created calendars.

### Backup and portability

Begin with manual export and import. Later support encrypted backup files and optional encrypted cloud backup. Preserve a documented portable archive and user-controlled deletion. Never include provider keys or OAuth tokens in exports.

## 11. Capture and notes

Capture sources: text, microphone, check-in, Android Share Sheet, and later a widget or notification shortcut.

Categories: task, event, idea, note, memory, schedule change, and unclassified.

- Clear low-risk tasks, notes, and ideas may save immediately with Undo.
- Deadlines, events, schedule changes, and memories require confirmation when consequential.
- Ambiguous input goes to the inbox unless that would be harmful.
- Capture always works offline and preserves the original input.
- Inbox is a safety net, not a source of inbox-zero shame.
- Notes remain lightweight and authoritative; they never become tasks silently.

## 12. Memory, privacy, and reflection

Memory layers:

1. Explicit user-approved memory.
2. Operational state.
3. Check-in history.
4. Inferred patterns.

Inferred patterns remain tentative, include evidence and confidence, and cannot influence planning until approved. The user can inspect, edit, forget, reject, dismiss, or mark information as unusable.

Raw audio is not stored by default. Transcript retention is optional; structured summaries are preferred. Context disclosure uses ordinary, personal, sensitive, and external privacy levels. Logs are redacted and the app records which context categories were sent externally.

There is no productivity score. Insight categories include estimation, timing, capacity, alignment, friction, and routine behavior. Every insight provides evidence and Confirm, Reject, Dismiss, and Inspect controls.

## 13. Information architecture

### Today

Now card, active-day timeline, flexible queue, next check-in, progress, and contextual replanning. The closing-check-in bar remains fixed directly above the bottom navigation.

### Plan

Day and week planning, draft review, capacity, fixed events, focus blocks, queue, inbox, and sync state. The Approve tomorrow's plan action remains fixed directly above the bottom navigation.

### Life

Direction, areas, goals, projects, ideas, notes, and alignment.

### Thalia

Voice and text interaction, scheduled calls, check-in history, memory, insights, provider state, usage, and appearance.

Settings are reached through the top profile/control surface. Capture, search, undo, and microphone controls appear contextually.

## 14. Visual system

The approved direction is Pixel intelligence.

- Pixel-native, calm, compact, and intelligent.
- Strong typography and spacing with soft depth.
- Rounded Material-like surfaces without excessive cards.
- Timeline-first Today screen; Focus opens secondarily.
- Assistant visuals are concentrated in voice and check-in experiences.
- No generic purple-gradient AI styling, spaceship theatrics, or web-dashboard appearance.
- System, Light, and Dark modes.
- Default accent: Thalia Blue.
- Optional accents: Teal, Green, Amber, Coral Red, and Violet.
- Accent affects navigation selection, buttons, focus blocks, highlights, and voice indicators.
- Semantic success, warning, danger, and error colors remain independent of the selected accent.

The approved structural mockup is directional rather than final Figma-level specification. Final implementation should pass a Pixel screenshot-comparison review.

## 15. Assistant character

Thalia is warm, deliberate, curious, and precise: a calm executive companion with a feminine voice and subtle humor.

- short conversational turns;
- usually one question at a time;
- fully interruptible;
- gently challenges weak assumptions;
- never guilt-inducing, judgmental, clingy, or falsely congratulatory;
- explains why a proposal matters without overexplaining.

## 16. Failure and degraded-state contract

- No internet: local planning, data, alarms, and structured check-ins continue.
- AI unavailable: structured local fallback.
- Calendar unavailable: visible queued operations and retry.
- Authentication revoked: local data remains intact.
- Notification, exact-alarm, or full-screen permission unavailable: honest diagnostics and degraded presentation.
- Invalid AI action: reject before mutation.
- Sync conflict: preserve both facts and ask when intent is unclear.
- Reboot or app update: idempotent reconciliation.
- Phone off past due: mark missed, never ring late.
- Force-stop: explain limitations when the app is reopened.
- Database migration failure: never automatically delete user data.
- Cost cap reached: local fallback and explicit user choice.

Every material operation exposes a truthful state such as local, pending, syncing, synced, confirmed, or failed.

## 17. Onboarding

1. Explain local-first behavior and trust boundaries.
2. Request call-related permissions just in time.
3. Capture one direction and up to three current goals or projects.
4. Ask for likely wake and work timing.
5. Offer optional Google Calendar connection.
6. Produce the first concrete plan.
7. Confirm the first calls and quiet period.
8. Use a conservative observation mode during the first week.

A later How to Use Thalia guide will exist both in the repository and inside the app.

## 18. Roadmap

- M0 - Foundation and architecture: complete.
- M1 - Local incoming-call experience: complete and validated on Pixel 10.
- M2 - Reliable local scheduling: complete. Recurring opening and closing calls, the database migrations, per-occurrence rescheduling and cancellation, quiet periods, and one call at a time are implemented, and their targeted acceptance checks passed on Pixel 10 on 2026-09-30. On 2026-10-02 a real confirmed opening check-in was delivered 401 ms late on the Pixel, on a charging phone not in Doze. Delivery from natural deep idle on an unplugged phone, and on consecutive days, is untested and was waived as a blocking requirement by the user on 2026-10-02; it remains a known limitation. Edge cases not yet run on the phone, such as a quiet period spanning midnight, are known limitations recorded in the device results, not unfinished implementation.
- M3 - Live voice foundation. M3A, a manual Gemini Live feasibility spike, demonstrated a two-way session on Pixel 10 on 2026-10-02, with documented limitations; M3 is not complete. Further voice work, provisionally M3B, follows the minimal M4 and M5 loop: its first purpose is to make the check-in conversation a second producer of that loop's typed proposals, behind the same validation and approval. Its scope is defined when it starts.
- M4 - Personal information foundation. Minimal: direction, goals, projects and actions in an encrypted local store, with a user-triggered encrypted export and import (ADR 0009, slices 1A and 1B, accepted on Pixel 10 on 2026-10-03; M4 is not complete). Full: areas, ideas, notes, routines, events, the capture inbox, the Share Sheet and microphone capture.
- M5 - Planning and execution loop. Minimal: a deterministic daily proposal, approval, outcomes, carry-forward and the opening-call link (ADR 0009, slices 2 and 3). Full: the Today timeline, focus blocks with times, replanning after meaningful events, undo, and closing-call planning.
- M6 - Contextual Thalia and local memory.
- M7 - Calendar and ecosystem integration.
- M8 - Reflection, trust, backup, polish, daily fortune, and the usage guide.

Each vertical slice follows: design, implement, computer verification, Pixel validation, documentation, then commit.

## 19. Acceptance criteria for the complete product loop

Thalia is successful when the user can:

1. Define stable direction, current goals, and user-structured projects locally.
2. Capture work and context quickly without classifying everything upfront.
3. Receive a realistic proposed next-day plan from current commitments and priorities.
4. Review and approve that plan before it mutates schedules or calendar state.
5. Receive confirmed opening and closing calls without laptop or continuous Wi-Fi dependence.
6. Use a local fallback when AI or internet is unavailable.
7. See the active day clearly in a compact timeline-first Today view.
8. Record honest outcomes independently of scheduled blocks.
9. Trigger minimal, understandable replanning when reality changes.
10. Sync approved Thalia blocks to a dedicated Google Calendar without surrendering local ownership.
11. Inspect and control memories, inferred patterns, external disclosure, provider usage, and costs.
12. Recover predictably after reboot, update, process death, lost permissions, provider failure, or sync failure.

## 20. Deferred delight

Daily Fortune is an optional once-per-active-day card at the bottom of Today. It reveals a short item of curated wisdom when tapped, never sends a notification, never creates a streak, and may save favorites into notes. It is intentionally deferred until the core loop is trustworthy.

## 21. Recorded 2026-10-13: scope after V1, and V1 deviations

Added after the V1 scope reconciliation (`NOW.md`, "V1 scope reconciliation"). **Nothing above is rewritten**: every earlier description, acceptance criterion and open decision stays as written. This section only records that the items below are **not part of V1 and are not to be built yet**, and points at where each is already described.

### Recorded for after V1

| Item | Where it is already described | Open decisions that stay open |
|---|---|---|
| External calendar sync | Section 10 (Google Calendar), roadmap M7, acceptance 10 | Everything: scopes, the Thalia Plan calendar, conflict handling, whether it is in the release. The v1 `PRD.md` said "no calendar", which this specification superseded. |
| Week view | Section 13 (Plan: "Day and week planning") | Its design and placement. |
| Areas | Sections 4 and 13 (Life), roadmap M4 full | Their design; how they relate to goals. |
| Ideas | Sections 4 and 11, roadmap M4 full | Whether Ideas are a Life area apart from the Inbox items already filed as ideas. |
| Notes | Sections 4, 11 and 13 | Same question for notes filed from the Inbox. |
| Routines | Section 4 (reusable templates; a missed routine creates no overdue pile), roadmap M4 full | Their design and planner placement. |
| Alignment | Sections 12 (insight category) and 13 (Life) | Evidence model and the review controls. |
| Automatic replanning | Sections 5 and 6 (only after a meaningful event, minimal movement), acceptance 9, roadmap M5 full | When it triggers and what is offered. V1 has no repair offer after a late block. |
| Running late outcome | Section 5 (closing check-in lists "running late"), ADR 0009 | Its meaning for the plan and for replanning. V1 outcomes stay done, partial, skipped, blocked and unknown. |
| First-week observation mode | Section 17, item 8 | What "conservative" means. |
| Distribution preparation | `release-direction.md`, roadmap M8 | Shipped builds have no INTERNET permission today, an owner decision; release networking, signing and store requirements are undecided. |
| Selectable accent colours | Section 14 | A considered accent picker. Settings, Appearance already offers four accent chips; nothing more is built or planned for V1. Teal stays the V1 default. Which accents ship, and whether Thalia Blue ever becomes the default, are open. |
| Future colour reconsideration | Section 14 | The default accent (this section says Thalia Blue; the app currently defaults to Teal) and the dark palette stay as implemented until the owner reopens them. |

### V1 deviations from the text above

- **Queue times.** Section 4 says flexible tasks may stay in an ordered queue without precise times, and ADR 0009 decision 4 says queued actions get an order, not times. V1 keeps exactly that: queue items stay ordered and unscheduled, with duration and due date where available. Pinned items and fixed commitments keep their times. Section 5's line that important focus blocks receive times is met only by pinned items. No amendment reverses ADR 0009.
- **Onboarding.** Section 17 describes first-run steps. V1 has **no first-run tour**; consent is asked when AI is enabled. Section 17's "How to Use Thalia guide" is met in V1 by a user manual reached from Settings. The section 17 steps are preserved, not withdrawn.
- **Fixed commitments** take no task-completion outcomes, and past blocks stay visible.
- **Voice.** Automatic send after speaking is a toggle that defaults off. The monthly voice cap is configurable, starts unset, and is configured privately before voice is enabled; the meter counts characters submitted to Cartesia, including retries, and is app-tracked usage, not provider billing. Section 8's USD 10 default AI cap is unchanged and separate.
- **Defaults.** Settings defaults for opening and closing times affect future proposals; an approved plan may override them; changing a default never arms a call. Built as the stored check-in plan: the opening default is offered by a button next to a plan's optional opening call time (never filled in on its own), the closing default is the closing call control's starting time, and a changed default alters no approved plan and no confirmed call.
- **Inbox.** V1 places a compact, expandable Inbox in Life, collapsed to its item count.
- **Approval.** Only tapping Approve applies any model-proposed change, including outcomes and Inbox filing, as sections 2 and 8 already say; the "may save immediately with Undo" line in section 11 does not apply to a model-proposed filing (ADR 0010 and ADR 0012 carry the dated amendments). Direct manual controls perform their own explicit actions.
- **Privacy controls.** Direction can be edited and cleared; goals, filed notes and ideas (and archives and discards) and closing notes can be permanently deleted by a direct, confirmed tap, kept separate from archive. A goal still linked to a project is not deleted; the owner is told which projects block it and nothing is unlinked for them. A capture that became an action is kept. Plans, outcomes, projects, actions and commitments have no delete in V1. There is no new memory system, and the layers of memory in the earlier text (explicit approved memory, inferred patterns) remain unbuilt.
- **Getting known goals in.** V1 asks the person to enter Direction, goals, projects and actions by hand, with short empty-section hints, and later drafting help in conversation. There is no automatic import from ChatGPT or any other assistant.
- **Accent default.** Teal is the V1 default accent (section 14 says Thalia Blue); see the table above for selectable accents.
