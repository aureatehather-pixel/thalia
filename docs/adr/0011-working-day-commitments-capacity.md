# ADR 0011: Working day, fixed commitments, capacity, closing and re-entry

Date: 2026-10-06
Status: **Accepted**, 2026-10-07, on automated, Pixel and schema-4 recovery evidence (below). Branch `v1/slice2-day-loop`. The working-day resolver below was written before its code, as the approval of slice 2 asked.

## Working day

### What it is

A **working day** is an explicit record: a label, when it was opened, and when it was closed. The label is an `ActiveDay`, the same type plans, items and outcomes are already keyed by, so nothing in the slice 3 machinery changes identity.

The label is always read from the clock at the moment of opening. It is never computed from another working day ("last closed plus one"), and nothing closes or opens a day on its own: no noon cutoff, no timeout.

Calendar values stay calendar values. Due dates, the date and time a person says, commitment instants and M2's call slots are resolved from the real clock and zone, never from the working day.

### Stored

`working_days`: `day` (the label, key), `openedAtMillis` (nullable: a row may exist only to hold a focus figure set before opening), `closedAtMillis` (nullable), `focusMinutes` (nullable), `closingNote` (nullable), `continuedOn` (nullable label: see "Continue").

At most one row is open (opened and not closed). The store enforces it inside the transaction that opens.

### The resolver

Inputs: now, the check-in zone, the rollover, the rows. `clockDay` is `ActiveDay.containing(now)`, as today. `calendarDate` is now's local date.

1. **An open row whose label is `clockDay` or later** is the current working day.
2. **An open row whose label is earlier than `clockDay`** is current only if `continuedOn == clockDay`. Otherwise the result is **Choose**: the person sees that the earlier day is still open and picks *Continue* it or *Start* a new one. Until they pick, nothing is recommended from the earlier day's plan and nothing is asked about it.
3. **No open row**: there is no current working day. Planning works as now, on `clockDay`; *Open* offers the day that opening would create or reopen.

**Opening** at `now`: the label is `clockDay`, unless that row is already closed and `calendarDate` is later than `clockDay`, in which case the label is `calendarDate`. If a row with that label exists and is closed, it is reopened (`closedAtMillis` cleared, its closing note kept); otherwise a row is created. Both readings are the actual clock, so no future date is manufactured.

**Start** (from Choose) closes the earlier row at `now` and then opens as above. Its unrecorded outcomes stay unknown, and nothing asks about them.

**Continue** sets `continuedOn = clockDay` on the open row. It is asked again only once the clock reaches a further day.

**Closing** sets `closedAtMillis`. It needs no outcomes and no note.

"Today", for whether an outcome can be recorded, is the later of `clockDay` and the current working day, so an early start recorded under the calendar date can take outcomes before the rollover.

### Examples (rollover 04:00)

| Situation | What happens |
|---|---|
| **Work continues through 05:00.** Opened Tue 6 Oct at 13:00, not closed; at 05:00 Wed `clockDay` is 7 Oct. | Rule 2: Choose, "Your working day of Tue 6 Oct is still open." *Continue Tue 6 Oct* keeps it current and sets `continuedOn = 7 Oct`, so it is not asked again on 7 Oct. Closing at 06:00 and opening at 13:00 then opens Wed 7 Oct. |
| **Close at 01:00, reopen at 03:30.** Tue 6 Oct closed at 01:00 Wed. At 03:30 `clockDay` is still 6 Oct, `calendarDate` is 7 Oct. | Opening: 6 Oct's row is closed and 7 Oct is later, so it opens **Wed 7 Oct**, the real calendar date. At 04:00 the clock agrees. Reopening 6 Oct is not offered; its outcomes stay recordable in the day screen. |
| **Close and reopen twice on one calendar date.** Opened 6 Oct 09:00, closed 13:00; open at 15:00; closed 23:00; open at 23:30. | At 15:00 `clockDay` and `calendarDate` are both 6 Oct and its row is closed, so it is **reopened**, keeping its plan, outcomes and note. The same at 23:30. One working day, closed and reopened; no new label. |
| **Return after three days without closing.** 6 Oct still open; now Fri 9 Oct 14:00. | Rule 2: Choose. Nothing from 6 Oct's plan is recommended and no catch-up is asked. *Start* closes 6 Oct at now and opens Fri 9 Oct; 6 Oct's unrecorded items stay unknown; its open actions stay in the Unfinished list. *Continue* is still possible. |

### Why this one

It is the smallest rule that keeps an explicit identity, uses only real clock readings, never closes anything by itself, and never shows an old plan as today's without saying so. The one prompt a person working past the rollover sees is the price of having no automatic cutoff.

## Fixed commitments and capacity

- A commitment is a title and two real instants, with the zone it was entered in. Entered by hand in Fixed commitments, or proposed in conversation and approved. A local time the clocks skip, or one that happens twice, is refused; an end at or before the start is refused, never moved to the next day (a manual entry gives an end date instead). Cancelling keeps the row.
- The planner's inputs carry the active commitments overlapping the active day (rollover to rollover) and the day's focus capacity. Occupied time is the **union** of pins and commitments inside the window, so overlaps count once (`Capacity.occupiedMinutes`); free time is the window minus it. A pin that overlaps a commitment is a warning.
- The target is 80% of free time, capped by the focus capacity when one is given. Focus is not clock time and never timeboxes anything.
- Staleness: commitments and focus are part of `PlanInputs`, which approval rebuilds and compares, so a moved, added or cancelled commitment, or a changed focus, refuses an approval even when free minutes come out the same.
- A capacity change after approval rewrites nothing. It shows in re-entry notices and in new previews.

## Re-entry

Computed when the working day is shown (and for the conversation's context); nothing is stored. `ReEntries.compute`:

- **Next** is the first item, in the approved order, whose action is open and whose result is none, unknown or partial. Completed, skipped and blocked items are not next. Nothing is inferred from elapsed time or queue position.
- **Time left** is the window from now to its end, minus commitments in that span. The **load** is the estimate of every available item.
- **One question**, about the earliest unknown item, only when the unknowns alone decide feasibility: the load does not fit if they are all still to do, and would fit if they were all done. Otherwise either answer leads to the same next step, so nothing is asked. No question once the window has ended.
- **Don't know** stops questions for the rest of that interaction (the screen's lifetime).
- Notices: work beyond the time left, the window ended, free time changed by commitments since approval, focus below the plan's queued work.
- During Choose, nothing from the earlier day is recommended or asked.

Dependencies and relevance beyond open/closed status are not modelled, so they never trigger a question.

## Closing

Close lists the plan's items with optional Done, Partial, Skipped, Blocked; after Partial, an optional remaining estimate edits the same action. An optional note is kept for the next opening, which shows the latest closing note. Closing needs no outcome and no note; anything unrecorded stays unknown; open actions stay available and nothing is put into the next day.

## Project context

Four optional fields: purpose, done when, constraints, blocker. Empty is unknown and is shown to the model as unknown. Entered by hand in the project editor, or proposed in conversation as the model's paraphrase, which always needs approval and applies only if the field still holds what it held when proposed.

## Conversation operations added

| Operation | Kind |
|---|---|
| `propose_project_context(project_ref, field, text)` | Proposal; stale-checked |
| `propose_fixed_commitment(title, start, end)` | Proposal; local `YYYY-MM-DDTHH:MM`, resolved in the planning zone; fixed id plus duplicate check, so a retry adds one; a time before the rollover is flagged |
| `record_focus_capacity(minutes)` | Fact the person stated; saved with Undo |
| `propose_focus_capacity(minutes)` | Inferred; needs approval |

The context adds the working-day sentence, today's commitments, focus, the last closing note, the re-entry line and each project's context fields.

## Storage, export and recovery

Planning schema 4 (`MIGRATION_3_4`): four nullable columns on `projects`, and the `commitments` and `working_days` tables. Payload schema 4 carries them; schemas 1 to 3 still decode, with empty commitments and working days and unknown context. Import refuses two open working days, a duplicate day or commitment id, and a commitment ending before it starts. `restoreIntoEmpty` writes the new rows.

The planning clock (`PlanningClock`) is read by the planning screens and the conversation. Production never moves it; the isolated `conversationCheck` build has +1h, +6h, +1d and Reset controls. M2, alarms and calls keep the system clock.

## Evidence, 2026-10-06

- `:core:test` 247 (1 skipped), `:voice-gemini:test` 42, `:app:testDebugUnitTest` 671, `:app:testReleaseUnitTest` 596, `ktlintCheck`, `:app:assembleRelease`, `:app:assembleConversationCheck`: all pass, in one sequential run.
- New tests: the four resolver examples (core and store), interval union and focus cap, commitment-only and focus-only staleness at the planner and the store, capacity change not rewriting an approved plan, re-entry materiality and "don't know", 3 to 4 migration comparing logical records column by column, schema 4 round trip and schema 3 import, store snapshot and restore of the new rows, the four conversation operations including a commitment save interrupted after commit.
- Migration acceptance is by logical records: the older migration tests now also compare each table's columns as of its own schema, not raw bytes.

## Evidence, device and recovery, 2026-10-06 to 2026-10-07

Device: Pixel 10, isolated `com.thalia.app.conversationcheck` only. Evidence: `the author's private evidence folder (not published)` and `the author's private evidence folder (not published)` (outside the repository, synthetic data, no credentials).

- Checks 1 to 6 passed: the in-place schema 3 to 4 migration (additions only), stale project-editor saves refused with the draft kept and the list reloaded, overnight continue, closing with a partial and an unknown outcome and a note, overlapping commitments counted once with the focus cap kept separate from free time, stale commitment and stale focus approvals refused, re-entry order with a targeted question, "Don't know" recording nothing, and a return after three days that asks first.
- Capacity wording: the preview, the buffer notice and the left-out reason now name the limiting factor, focus capacity or 80% of free time. `PlanPreviewTextTest` asserts both cases. Approved plans keep a neutral "over the target", because the approved snapshot does not store focus.
- Schema-4 export and restore: the isolated conversationcheck data was exported through the production export (password-encrypted, read back by the app) and restored into the isolated recoverycheck app with Replace, which set its previous planning files aside. A check-only "Dump logical records" control wrote each app's canonical snapshot, the export format's own encoding of the rows with fixed metadata. **The two canonical logical snapshots were byte-identical** (1 direction, 1 settings, 1 goal, 1 project with all four context fields, 6 actions, 3 plans, 10 plan items, 3 outcomes covering partial, explicit unknown and done, 3 commitments, 3 working days including a closing note and the stored open 10 Oct day). The source dump was unchanged afterwards. Database files were not compared, and are not expected to match.
- The "Dump logical records" controls exist only in the `conversationCheck` and `recoveryCheck` source sets. They are not in `main`, `debug` or `release`.
- The real `com.thalia.app.debug` installation was not touched.

## Limitations

- **Real-installation migration not run.** The 3 to 4 migration was run on the isolated check app only. The real debug installation stays at schema 3, with its M2 alarms and the preserved Stage G candidate, until Stage G is decided and an export is taken. Stage G is not assumed to have passed.
- **Call rows in recovery.** Opening-call rows are never exported. The restored app showed none, as intended, but the check did not seed call rows in the source to prove they are dropped.
- **A future-dated open working day** (10 Oct, with the real date 7 Oct) restored as stored. The behavior of the screens with a future-dated open day under the real clock was not exercised.
- The check screen's planning-clock label does not refresh until recomposed. The offset itself was correct.
- A manual project save writes all four context fields from the editor's copy, so a conversation change made while the editor was open would be overwritten; the screen reloads after every write, which narrows but does not close this.
- Re-entry treats a partial item's full estimate as remaining.
- Dependencies are not modelled; "relevance" is open versus closed only.
- The Choose prompt appears once per clock day for anyone working past the rollover; that is the cost of having no automatic cutoff.

