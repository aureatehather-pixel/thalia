# Thalia Bot - Product Requirements Document

Status: Superseded as the current product direction on 2026-09-29 by [Thalia Product Specification v2](docs/product-spec-v2.md). Kept as the historical v1 record.

Originally: v1 pre-build. This PRD describes the product to be built next, informed by a validated interactive prototype (see "Prototype status" below).

**Superseded 2026-09-29.**
[docs/product-spec-v2.md](docs/product-spec-v2.md) is the approved product direction, and wins wherever this document disagrees with it.
This document is not rewritten, so the reasoning behind each earlier decision can still be read.
The assumptions below no longer describe the product:

- **The external Grok bridge as the planning mechanism (Sections 2, 4, 6.2 and 7).** The specification's AI contract (its section 8) has an in-app provider produce typed proposals, which local Kotlin validates and simulates and the user approves before anything is written. **Decided 2026-09-29:** the Grok bridge and the browser extension are retired, historical v1 architecture.
- **Five core areas (Sections 5 and 7).** The specification's information hierarchy (its section 4) is direction, areas, goals, projects, ideas, tasks and routines, events and time blocks, notes, check-ins, memory and insights, organised into Today, Plan, Life and Thalia screens (its section 13).
- **Goal types and project shape (Sections 5.2 and 5.3).** Goals carry a success condition, horizon and status rather than Daily, Weekly, Monthly and Milestone types. Projects are user-defined with explicit states, and Thalia does not decompose them.
- **No external calendar and no sync (Sections 2, 5.4 and 6.3).** The specification reads external Google calendars and syncs approved Thalia blocks to a dedicated Thalia Plan calendar, with Room remaining authoritative (its section 10).
- **No on-device replanning (Sections 2, 5.4 and 9).** The specification has a deterministic planning and replanning policy (its sections 5 and 6).
- **Voice Check-ins as an optional feature at 10:00 and 15:00 (Section 5.6).** The specification makes opening and closing check-in calls, at confirmed times, part of the daily operating loop, with a local structured fallback when AI or the network is unavailable (its sections 5, 7 and 16).
- **AI only at the optional edges, and the deletion test (Sections 6.4 and 7).** The specification's principle is "local-first, not AI-free": AI may understand and propose across Thalia, while deterministic code validates and executes.
  **Decided 2026-09-29:** the test that deleting every AI feature leaves a complete Thalia is superseded by this principle: Thalia's local data, alarms, planning and deterministic execution remain usable without an external AI provider.
  External AI is opt-in: it begins disabled, and explicit consent is required before any context is sent externally.
- **Undesigned notifications (Sections 6.5 and 9).** The specification's call and notification policy (its section 7) is the design.
- **Free providers only (Section 6.6).** The specification sets a default USD 10 monthly cap on paid provider use, with warnings and explicit approval past it (its section 8).

**Amended 2026-09-18.**
The absolute "no AI model, LLM API integration, or API key, ever" restriction recorded on 2026-09-13 has been superseded.
The superseding rule is in Section 6.4, and the reasoning is in [ADR 0003](docs/adr/0003-optional-ai-provider-policy.md).
Sections 2, 5.5, 6, 6.4, 6.6 and 7 carry the amendment.
The original wording is preserved inline and marked superseded wherever it was changed, because the reasoning behind it still governs Thalia's core.

## 1. Problem statement

The captain's plans go stale on a near-daily basis.
New information arrives constantly - an unplanned meeting, feeling unwell, a social invite, a task running long - and each time, he has to manually re-shuffle his calendar, tasks, and notes around it.
There is no single place that holds his life direction, goals, projects, schedule, and stray ideas together, so keeping all of it consistent lives in his head.

He wants a system that:

- Holds his calendar, task deadlines, and notes together instead of scattered across tools.
- Lets him react to a disturbance by describing it, rather than manually re-editing every affected item.
- Shows him exactly what would change before anything changes, so he never loses trust in the plan.
- Keeps his data under his own control, with no subscription and no backend to run or pay for.

## 2. Goals and non-goals (v1)

### Goals

- Give the captain one Android app that holds his life direction, goals, projects, schedule, and voice-note ideas.
- Let him externally reason through decisions with Grok, then bring a structured update back into the app as a single reviewable action.
- Guarantee that nothing is written to his data until he has reviewed and confirmed it, item by item.
- Keep Thalia's core deterministic, local-only, and free to build and run. (Amended 2026-09-18. The original said "the whole system". An optional AI-backed feature may involve a provider cost, which must be visible rather than assumed away; see Sections 6.4 and 6.6.)
- Prove the review-before-apply mechanic end to end, across all five areas, not just the schedule.

### Non-goals (v1)

- No AI model or API key in Thalia's **core**. Planning, goals, projects, schedule and stored data stay deterministic, local-first, and fully usable with no cloud AI. All natural-language reasoning for the Grok bridge happens in a separate Grok conversation, never inside Thalia.
  (Amended 2026-09-18. The original read "No AI model or API key inside the app", with no exception. Optional features may now use a replaceable external AI provider under the conditions in Section 6.4.)
- No backend, no server, no account system, no multi-device sync.
- No constraint-solver re-planning engine yet (see Section 5.4, Schedule).
- No real audio capture, transcription, or storage for Voice Notes (see Section 5.5).
- No push notifications requiring a server. Only on-device scheduled notifications are in scope, and only once a notification design exists (currently undesigned - see Open Questions).
- No import from external calendars (Google, Outlook, etc.).
- No desktop or web build of Thalia itself. The only non-Android surface is a small browser extension scoped narrowly to the Grok handoff (Section 6.2).

## 3. Persona

There is a single user: the captain.
This is a personal tool built for one person's own workflow, not a multi-tenant product.
Design decisions should optimize for his stated preferences (data ownership, no subscriptions, control over what gets applied) over generality or onboarding ease for a hypothetical other user.

## 4. The unifying mechanism: the Grok bridge

Thalia Bot does not embed an AI.
Instead, the captain reasons through decisions in a separate, ordinary Grok chat, using a reusable prompt/instruction he pastes in at the start of that conversation.
That instruction tells Grok two things: help him think it through conversationally, and keep decisions he has explicitly confirmed clearly separate from suggestions he has not yet approved.

The flow:

1. The captain talks through his day, a disturbance, or a decision with Grok, conversationally, using the reusable prompt.
2. When ready, he tells Grok to "export for Thalia."
3. Grok returns a single JSON payload, structured to a versioned Thalia schema, containing only the changes he has confirmed in that conversation (not raw suggestions).
4. The captain gets that JSON into Thalia - on Android, by pasting it directly; via the browser extension, by capturing it on desktop and handing it to the phone (see Section 6.2).
5. Thalia validates the payload against its schema. Invalid JSON, or a payload with no changes, shows an inline error and does not let the captain proceed past the paste step.
6. Thalia shows a review screen: every change grouped by which area it touches (Direction / Goals / Projects / Schedule), each with an editable representation and an include/exclude toggle, defaulted to included.
7. The captain edits or excludes individual items as needed, then confirms.
8. Only the confirmed subset is written to Thalia's local data. Nothing Grok proposed but the captain rejected or left unconfirmed ever touches the stored plan.

This mechanism is the single most load-bearing piece of the product. Every one of the five core areas is written into and updated through it. The review screen is the trust boundary between "an external AI suggested something" and "my data changed," and it must never be weakened to a single "apply all" action.

### 4.1 The JSON schema

Every payload must validate against a versioned Thalia schema before its contents can be reviewed. At minimum, the schema must support:

- A schema version field, so a future format change can be detected instead of silently misparsed.
- A `changes` array as the payload's core content; a missing or empty array is invalid.
- A human-readable `summary` line, shown verbatim to the captain as the change's stated rationale.
- Each change entry: which `area` it targets (`direction` / `goals` / `projects` / `schedule`; the prototype defaults an omitted area to `schedule` for backward compatibility with schedule-only payloads), a change `type` appropriate to that area (add / pause-or-remove / move / flag-conflict / log-entry, etc.), and the fields needed to render and edit that change type in the review screen.

The exact schema (field names, types, per-area change-type enums, versioning/migration policy) is not yet formally specified. See Open Questions.

## 5. The five core areas

The prototype (Section 8) is the current best specification of the intended interaction for each area below. Where this PRD references prototype behavior, that behavior is the target, not merely an illustration.

### 5.1 Direction

Purpose: hold the captain's current life-direction statement, and a history of how it has shifted over time, so past reasoning isn't lost when direction changes again.

Must do:

- Display a single current-direction statement, dated from when it took effect.
- Display a reverse-chronological timeline of past direction shifts, each entry showing what changed and why.
- Accept new direction entries only through a confirmed Grok-update change (`area: direction`, a log-entry type change): the entry is inserted at the top of the timeline, tagged with a provenance badge marking it as newly added via that update.
- Show provenance (a visible badge and a brief highlight) on any entry just written by a confirmed update, so the captain can see at a glance what an applied update actually changed.

Not yet decided: whether Direction ever gets a manual add/edit UI, or whether it stays update-only permanently (see Open Questions). Also open: whether Direction warrants being its own top-level area distinct from Notes/Voice Notes, a question the captain raised himself while reacting to the prototype.

### 5.2 Goals

Purpose: track goals across four time horizons so daily effort stays visibly connected to longer-term intent.

Must do:

- Group goals under four types: Daily, Weekly, Monthly, and Milestone.
- Show each goal's type and a progress/target indicator appropriate to its type.
- Support pausing a goal, rendered struck through rather than deleted, so paused intent stays visible and reversible.
- Accept goal changes (add, pause, progress update) only through a confirmed Grok-update change (`area: goals`), reviewed and edited like any other change before being applied.
- Carry provenance badges on goals just added or changed by a confirmed update, and suppress any redundant static status badge when a provenance badge is already showing the same state (a bug caught and fixed in the prototype - see Section 8).

Not yet decided: manual CRUD for goals (see Open Questions).

### 5.3 Projects

Purpose: hold multi-step bodies of work (including things outside "work" in the narrow sense, such as a hackathon) with their own milestones, tasks, notes, and deadlines, and connect each project back to the goal(s) it serves.

Must do:

- Represent a project as: a set of milestones (each completable), a set of tasks (each completable), freeform notes, an optional deadline, and links to zero or more goals.
- Let the captain navigate from a project's goal-link badge directly to that goal in the Goals area.
- Support a project like a hackathon as a first-class project like any other, not a special case.
- Accept project changes (new milestone, new task, marking something done, pausing a task, adding a note) only through a confirmed Grok-update change (`area: projects`).
- Carry provenance tags on any milestone/task just changed by a confirmed update.

Not yet decided: manual CRUD for projects (see Open Questions).

### 5.4 Schedule

Purpose: hold the calendar (fixed events) and tasks scheduled into work blocks, and let the captain resolve a disturbance to that schedule through the Grok bridge rather than manual re-editing.

Must do:

- Render a week view of tappable days; each day's schedule as a chronological agenda mixing fixed calendar events (visibly marked as fixed/locked) with work blocks containing scheduled tasks.
- Show a week-wide list of unscheduled tasks with deadlines, sorted by urgency, separately from the day agenda.
- Show paused/removed items in a collapsible section, struck through, with a restore action - pausing must never be indistinguishable from deletion.
- Accept schedule changes (add an event/task, pause/remove, move a task to a different day/block, flag a task as no longer fitting the week/conflicting) only through a confirmed Grok-update change (`area: schedule`).
- In the review screen, group schedule changes with conflicts and "no longer fits" flags surfaced first (worst news first), followed by additions, then moves, then pauses/removals - matching the prototype's ordering, which the captain has already reacted to positively.
- Let the captain edit inline before confirming: destination day/block, title, duration for moves and adds; a resolution choice ("keep flagged, decide later" vs. "defer to next week") for conflicts.
- After confirming, make the effect immediately visible: jump to the first day the update touched, mark every touched day with an indicator dot, and show a plain-language summary banner of exactly what was applied.
- Log every applied change (old state, new state, and the stated reason) so the captain has an audit trail of why his schedule changed - this is the ReplanLog concept from the research report, and it should exist from v1 even while the re-planning logic itself is simple.

Explicitly deferred past v1 (per the research report's recommendation, Section 3.2):

- A constraint-solver-based re-planning engine (OR-Tools CP-SAT or equivalent). v1 relies entirely on the Grok bridge plus manual review; there is no on-device automatic re-planning logic yet.
- Passive/background disturbance detection. All schedule changes originate from a captain-initiated Grok conversation and a manually pasted update.
- External calendar import (Google, Outlook, etc.).
- Recurring/habit scheduling beyond what a pasted update can express.

### 5.5 Voice Notes

Purpose: a minimal capture point for ideas spoken aloud, kept deliberately simple in v1.

Must do:

- A record control that starts and stops a recording session.
- A reverse-chronological list of past voice notes, each showing a timestamp and its captured content.

Note added 2026-09-18: Voice Notes is not Voice Check-ins.
Voice Notes is this dictation capture point, still a v1 placeholder with no real audio.
Voice Check-ins is the separate optional feature in Section 5.6 that places two spoken AI calls a day.
Voice Check-ins doing real audio does not pull Voice Notes into scope; the two remain independent.

Explicitly out of scope for v1 (this area is a concept placeholder, not a built feature yet):

- Real audio capture, storage, or playback.
- Real transcription. The captain's stated pain point ("frequent/persistent notifications so he doesn't have to hold his schedule in his head") implies Voice Notes should eventually surface a nudge to review captured notes, but no notification system exists yet to drive that (see Open Questions).
- Any connection from a voice note into Direction/Goals/Projects/Schedule other than the captain manually working it through the Grok conversation himself.

### 5.6 Voice Check-ins (optional, added 2026-09-18)

Purpose: two internet voice calls a day, at 10:00 and 15:00 local, each opening a short spoken conversation with an AI about the day's plans.

This is the first feature to use the optional external AI provider permitted by Section 6.4, and it is the reason that section was amended.
It is an **optional** area, not a sixth core area: Thalia with Voice Check-ins switched off is a complete v1.

Its own requirements, milestones, risks and open questions live in [THALIA_VOICE_CHECKINS_PRD.md](THALIA_VOICE_CHECKINS_PRD.md) rather than being duplicated here.
What belongs here is the boundary it must respect:

- It reads Goals, Projects and Schedule to inform the conversation. It does not become a second way to change them; anything it produces still arrives through the Section 4 review-before-apply mechanic.
- It carries the only network dependency and the only credential in the product, and both stay inside the feature.
- Turning it off removes the provider, the key and the network need entirely.

## 6. Platform and technical constraints

These are the captain's own decisions, recorded 2026-09-13 and amended 2026-09-18 where marked.
They are not open to reinterpretation by an implementer; they are changed only by the captain, explicitly, as 6.4 was.

### 6.1 Primary platform: Android

Thalia's main product is a native Android phone app. The captain carries a Google Pixel; Android is the only mobile platform in scope for v1. No iOS build, no desktop build of the app itself, no web app.

### 6.2 Browser extension: Grok-handoff only

A small, free desktop browser extension is in scope, scoped narrowly to moving data between the desktop Grok conversation and the Android app - for example, getting an exported plan snapshot to Grok, and getting the JSON Grok returns back to the phone. The extension is not a second implementation of Thalia and holds no independent app logic, data model, or UI beyond what the handoff requires.

### 6.3 Storage: local-only, no backend

All data (direction, goals, projects, schedule, voice notes, the change log) lives on-device. There is no server, no account system, and no synced backend of any kind in v1. This is a firm decision, not a default to be revisited opportunistically - any future sync capability would be a distinct, explicitly-scoped decision, not an incremental addition made without the captain's sign-off.

### 6.4 Local-first core, optional AI at the edges

**Amended 2026-09-18. This replaces the previous absolute restriction; the superseded wording is preserved at the end of this subsection.**

The governing rule is now:

> Thalia's core planning, goals, scheduling, and stored data remain local-first and usable without cloud AI. Optional features such as live Voice Check-ins may use a replaceable external AI provider with explicit user consent, locally protected credentials, and graceful offline fallback.

What that means in practice, and what an implementer must satisfy before shipping any AI-backed feature:

- **The core is untouched.** Direction, Goals, Projects, Schedule, Voice Notes, the change log, and the review-before-apply mechanic are deterministic local code. None of them may call a model, require a key, or degrade when offline. Removing every AI feature must leave a complete, working Thalia.
- **AI is opt-in per feature.** A feature using a provider is off until the captain turns it on, with the provider named and the data leaving the device described plainly. Consent is explicit, not a buried default.
- **Credentials are local and protected.** Any key is entered at runtime, stored in Android Keystore backed storage, never committed to Git, never logged, and never included in exports or crash reports.
- **The provider is replaceable.** Every provider sits behind an interface that carries product concepts only, so it can be swapped without touching the feature's UI.
- **Offline degrades gracefully and honestly.** With no network or no key, the feature says what is unavailable and why. It never silently fails, and never pretends to have done something it did not.

The Grok bridge in Section 4 is unchanged and remains the mechanism for planning-level reasoning. It is external by design, not by restriction.

Superseded wording, recorded 2026-09-13:

> Thalia contains no AI model, no LLM API integration, and no API key, ever, in v1 or as a default assumption for later. All natural-language reasoning happens in the captain's own separate Grok conversation. Thalia's own logic (data storage, validation, review-screen rendering, and whatever scheduling logic exists) is deterministic code only.

That wording is kept because its intent survives: the planning core must never depend on a model. What changed is that "ever" also foreclosed optional edge features like live voice, which was not the intent it was written to protect.

### 6.5 Notifications

Only on-device, locally-scheduled notifications are in scope (no push notifications, since there is no backend to send them from). The actual notification design - what triggers a notification, how "persistent/naggy until acknowledged" behavior should work within what Android's OS-level throttling allows - has not been designed yet (see Open Questions).

### 6.6 Cost constraint

Everything used to build and run Thalia Bot's core must be free: no paid frameworks, libraries, services, or subscriptions for the app, the extension, or any part of the Grok handoff. This applies to every future build and tooling choice, not just v1.

**Amended 2026-09-18, for optional AI-backed features only.**
An optional feature that uses an external provider (Section 6.4) must:

- prefer a provider whose free tier covers personal use;
- make any possible cost visible rather than assumed away;
- keep the provider replaceable, so a tier change is a swap and not a rebuild.

No such feature may introduce a cost to Thalia's core, and none may be on by default.

## 7. Success criteria for v1

v1 is successful if:

1. The captain can hold a real Grok conversation using the reusable prompt, export a Thalia update, and get it into the app via paste (phone) or the browser extension (desktop-to-phone handoff).
2. Every pasted update is validated against the versioned schema before review; malformed or empty payloads fail visibly and safely, never silently or by crashing.
3. The review screen groups every change by area, shows a plain-language description of each, and lets the captain edit or exclude any individual item before confirming.
4. Nothing is written to Thalia's stored data except what the captain explicitly confirmed in that review screen.
5. After confirming, the captain can immediately see what changed - in the affected area's view, with clear provenance marking - without hunting for it.
6. All five areas (Direction, Goals, Projects, Schedule, Voice Notes) exist in the app and can receive changes through the Grok-update mechanism, even where an area has no manual editing UI yet.
7. The app's core runs fully offline with no account, no backend call, and no API key anywhere in its code or configuration. (Amended 2026-09-18. The original omitted "core". An optional AI-backed feature may require a network and a locally stored key under Section 6.4, but v1 success is still judged on a Thalia that works with every such feature switched off.)
8. The captain actually uses it for at least one real day of planning and disturbance-handling, replacing (for that day) his prior ad hoc re-shuffling.

## 8. Prototype status

An interactive click-through prototype (built with Lavish, committed to this repository on its own branch/PR) validates the review-before-apply interaction across all five areas. It is the current best specification of the intended UX for:

- The grouped, worst-news-first review screen ordering.
- The include/exclude-then-confirm interaction with inline editing per change type.
- Provenance badges and highlight pulses marking freshly-applied changes.
- Auto-navigation to the first touched area/day on confirm, plus indicator dots on every touched tab/day.
- The pause-with-restore pattern (struck through, not deleted) for Goals, Projects, and Schedule items.
- A plain-language success banner summarizing exactly what was applied, with an area prefix for cross-area updates.

The prototype used in-memory JS state, no persistence, and no real scheduling engine; it exists to validate interaction design, not to be extended directly into the real app. See the prototype's own report for full detail on what was built and how it was verified: `data/thalia-bot-prototype-lavish-prototype-of-thalia-bot-s-main-jo-3c/report.md` (firstmate home, not this repo).

## 9. Open questions

These are genuinely undecided and should not be assumed one way or the other without the captain's input:

1. **Manual CRUD for Direction, Goals, and Projects.** The prototype deliberately left these three areas update-only (populated only through confirmed Grok updates, no manual add/edit screens), to prototype the interaction rather than the whole app. The real v1 needs to decide whether they ever get manual editing, or stay update-only permanently by design.
2. **The JSON schema's exact shape.** Section 4.1 states the minimum required structure observed from the prototype's sample payloads; the formal schema (field names and types, the full per-area change-type enum, versioning and migration policy for future schema changes) has not been specified yet and needs to be written before real implementation starts.
3. **Notification design.** What should trigger a local notification, and how a "persistent/naggy until acknowledged" pattern should work given that both Android and iOS increasingly throttle repeat notifications at the OS level. No design exists yet.
4. **Auto-jump behavior on confirm.** Whether jumping to the first touched day/area on confirm (current prototype behavior) is the right default versus staying put and only showing indicator dots.
5. **Default conflict resolution.** Whether an unresolved schedule conflict should default to "keep flagged, decide later" (current, safer default) or "defer to next week," especially once any on-device scheduling logic beyond pure pass-through exists.
6. **Whether Direction should be a top-level area at all**, versus folded into Notes/Voice Notes - raised by the captain himself while reacting to the prototype, not yet resolved.
7. **Whether and when to introduce any on-device scheduling logic** (even simple rule-based heuristics, short of a full constraint solver) versus relying on Grok's narration plus manual review indefinitely for the Schedule area.
