# ADR 0009: The minimal local planning loop

Date: 2026-10-02
Status: **Accepted 2026-10-02**, with the SQLCipher dependency, slice 1 split into 1A and 1B, and the export bounds and crash-safe replacement below. Slices 1A and 1B are implemented and accepted on the Pixel 10 on 2026-10-03 ([results](../m4-slice1-device-results.md)); slice 2 is implemented (`98dd3b4`) and accepted on the Pixel 10 between 2026-10-03 and 2026-10-04, with limitations ([results](../m4-slice2-device-results.md)); slice 3 is not started, and the minimal loop is not complete. A slice 3 amendment, approved by the owner on 2026-10-04, is under "Slice 3" below; slice 3 implementation is not started.
Milestones: the minimal part of M4, personal information foundation, and of M5, planning and execution loop
Builds on: [the specification](../product-spec-v2.md), sections 2 to 6, 8, 10, 12, 16 and 19, and the M2 scheduling in ADRs [0005](0005-m2-slice-1-real-alarm.md), [0006](0006-m2-slice-2-durable-scheduling-and-recovery.md) and [0007](0007-m2-final-slice-quiet-period-and-one-call.md)
Follows: [ADR 0008](0008-m3a-manual-gemini-live-spike.md), M3A, which is complete

## The question

> What is the smallest local loop in which the user writes down what matters, picks a day's work, approves a realistic plan, and carries what is unfinished forward, without any AI?

The specification names M4 and M5 but gives them no scope or acceptance criteria.
Nothing for direction, goals, projects, actions, plans or outcomes exists in the code.
The only Room data is M2's check-in plan, occurrences and quiet period, in `checkins.db`.

## The workflow

1. Write a direction statement.
2. Create a goal, and a project linked to it.
3. Capture actions by hand, under a project or standalone.
4. Select work for an active day and review a realistic, deterministic plan proposal.
5. Approve it, or adjust it first. Nothing takes effect before approval.
6. Record outcomes. Unfinished actions are offered, not forced, for a later day.

Underneath all six: an encrypted local store, and a user-triggered encrypted export that restores on any phone.

```
   Life                       Plan                                     Today
 +-----------+   +----------------------------------------+   +--------------------+
 | Direction |   |  select actions for active day D        |   |  record outcomes   |
 |   Goal    |-->|  (unfinished ones offered, unselected)  |-->|  done / partial /  |
 |  Project  |   |        |                                |   |  skipped / blocked |
 |  Actions  |   |        v                                |   |  / unknown         |
 +-----------+   |  deterministic planner, PlanProposal    |   +---------+----------+
       ^         |        |                                |             |
       |         |  preview: pinned, queue, left out,      |             |
       |         |  target, free time, call effect         |             |
       |         |   Adjust --> re-run  |  Approve         |             |
       |         +----------------------+-----------------+             |
       |                                v                                |
       |       planning.db: one transaction, plan approved,             |
       |       call status REQUESTED (if an opening time is set)        |
       |                                v                                |
       |       checkins.db: create-if-absent through M2's scheduler,    |
       |       call status recorded back in planning.db                 |
       +------------------ open actions stay in the backlog <-----------+

   planning.db --(user: Export, password)--> encrypted archive --(user: Import, password)--> planning.db
```

## Decisions

### 1. The minimal loop is three slices, not all of M4 and M5

| | Minimal loop, this ADR | Full M4 and M5, later |
|---|---|---|
| Information | Direction, goals, projects, actions | Areas, ideas, notes, routines, events, capture inbox, Share Sheet, microphone capture |
| Planning | Explicitly selected actions, pinned times, an ordered queue, the 80% target | Focus blocks with times, travel and meals, recovery, week planning |
| Approval | Approve, Adjust, or leave the previous plan in place | Undo, change preview against the previous plan, minimal-movement replanning |
| During the day | Outcomes recorded on a plain screen | Today timeline, Now card, Focus mode, one quiet prompt for unknown outcomes |
| Calls | Approval can confirm that day's opening call | Closing-call planning, the conversation feeding the loop |
| Portability | A user-triggered, password-encrypted export and import of planning data | Scheduled or automatic backup, cloud backup, portable archive of everything (M8) |
| AI | None | Optional typed proposals through the same validator |

**The minimal loop is complete when all three slices pass.**
M4 and M5 are complete only when the right-hand column is also done.

### 2. The minimum information model

| Entity | Fields | Notes |
|---|---|---|
| Direction | text, updatedAt | One current statement. Editing replaces it. |
| Goal | title, successCondition, horizon (optional date), status: active, achieved, dropped | The specification's goal shape. |
| Project | title, goalId (optional), state: someday, planned, active, paused, completed, abandoned, archived | All seven specification states are stored. |
| Action | title, projectId (optional), priority: Must, Important, Normal, Optional, estimateMinutes, dueDate (optional), status: open, done, dropped | Standalone when projectId is empty. Defaults: Normal, 30 minutes. |
| PlanningSettings | rollover, defaultWindowStart, defaultWindowEnd | Chosen during setup, editable later. |
| DayPlan | activeDay, revision, zone, rollover, windowStart, windowEnd, overloadMinutes, approvedAt | Never edited after approval, not even to mark it superseded: the latest revision of a day is the one in force, found by query. Keeps the window it was made with. **Amended 2026-10-04:** the opening time and the call status and detail are not in this row; slice 3 keeps them in a separate `plan_calls` table (see the slice 3 amendment). |
| PlanItem | planId, actionId, kind: pinned, queued, start (pinned only), minutes, position, placement, reason | Every selected action is kept, including those left out, with the reason. |
| Outcome | planItemId, result: done, partial, skipped, blocked, unknown, recordedAt, closedAction | One per item; recording again replaces it. **Amended 2026-10-04:** `closedAction` records whether this outcome is what closed the action, so correcting it never reopens a closure it did not make (see the slice 3 amendment). |

Every row has a random UUID, so an export restores with its references intact.
Ponytail lite: "running late" from specification section 5 needs a live Today view and comes with the timeline.
Fixed commitments such as a dentist visit are entered as actions with a pinned time; a separate events entity comes with full M4.

### 3. The active day and the planning window

**Definition.** Active day D runs from D at the **rollover time** up to, but not including, D + 1 at the rollover time, in the plan's zone.
The rollover is a setting, **default 04:00**, and is independent of the quiet period.

```
active day 2 Oct:   2 Oct 04:00  [------------------------------------)  3 Oct 04:00
                                        12:00            22:00    00:45
                                        (window)                  still 2 Oct
```

**Boundary behaviour.**

- 03:59 on 3 October belongs to active day 2 October. 04:00 on 3 October belongs to 3 October.
- Changing the rollover affects only plans made afterwards. An existing plan keeps its active day and window.
- Capacity is computed from the real elapsed time between the window's start and end in the plan's zone, so a daylight-saving change is counted correctly. A local time that does not exist on that date (spring forward) is refused by the validator rather than shifted. A local time that happens twice (fall back) is **always the first occurrence, the earlier instant**, for the window's ends and for pins alike, so a proposal never depends on which one the clock showed. The active day of a moment is read from the wall clock.

**The planning window.**

- It is chosen during planning setup, the first time the user plans a day. Setup **suggests 12:00 to 22:00**.
- That choice becomes the global default, editable later in settings.
- Each day's plan starts from the default and can be changed for that day only.
- An approved plan stores its own window. Changing the default never changes a historical plan.
- A window may cross midnight, for example 18:00 to 01:00, but must lie inside the active day.

**Active-day ids are not M2 slot dates.**
In code, an active day is its own type, `ActiveDay`, never a bare `LocalDate`, so it cannot be passed by mistake where M2 expects a calendar date.
M2 keys a check-in slot by the **calendar date** of the call and its type, for example `2026-10-03-opening`.
The opening call of active day D is placed on calendar date D.

**The zone.** A plan uses the zone of M2's stored check-in plan, so the plan and its call agree.
M2 created that zone from the device's zone on first use, and confirmed calls are fixed moments computed in it.

### 4. Selection and carry-forward

- **Only explicitly selected actions are planned.** The planner never adds an action by itself.
- **Unfinished actions are offered.** When planning day D, open actions whose last outcome was partial, skipped, blocked or unknown, or whose plan item has no outcome, are listed under "Unfinished", each with an Add control. They start unselected and unscheduled. **Amended 2026-10-04:** the list is the open actions that an earlier day's latest plan placed, as in slice 2; an action's status, not its outcome, decides whether it is open, and the last outcome only annotates it.
- No overdue count, red badge or carried-over total is shown.

Ponytail lite: no automatic suggestion from the backlog; add one, through the same proposal and approval, only if selecting by hand proves slow.

### 5. The planning rules: two limits, not one

The planner is a pure Kotlin function. The same inputs always give the same `PlanProposal`.

**Two different limits.**

| Limit | What it is | Exceeding it means |
|---|---|---|
| **Free time** | The window minus the minutes of pinned actions inside it. A physical fact. | The selected work **cannot happen** in this window. |
| **Flexible-work target** | 80% of free time, rounded down to whole minutes. A planning policy, from specification section 6. | The day loses part of its 20% buffer for overruns, transitions and interruptions. It may still physically fit. |

Pinned commitments are not reduced by the 80%: their time is known, so the buffer belongs to the flexible work.
The 80% is a constant in the minimal loop. Ponytail lite: a setting is added only if there is evidence it needs tuning.

**Pinned actions.**

- A pinned action keeps the start time the user gave it.
- A pinned action that starts before the window, ends after it, or overlaps another pinned action is a **blocking validation error**. The proposal names the items, and Approve is disabled until it is fixed.
- If pinned actions fill the window, free time is zero.

**The queue.**

1. Order: Must, Important, Normal, Optional. Within a priority: earlier due date first, then no due date, then oldest action first, then id.
2. **At most two projects.** Projects are counted in queue order across placed actions. A non-Must action from a third distinct project is left out with "project limit". Must actions are always placed, and their projects count towards the two. Standalone actions do not count.
3. Non-Must actions are placed in order while each fits within the **target**. One that does not fit is left out with "over target", and filling continues, so a later, smaller one can still fit.
4. Queued actions get an order, not times.

**Must work is always shown, and every Must item gets a placement label by where it ends, counting queue minutes in order:**

- **within target**: inside the 80% of free time;
- **in buffer**: beyond the target but inside free time;
- **does not fit**: beyond free time. There is no time for it in this window.

**Overload is explicit and stays unresolved until the user resolves it.**

- Must work beyond the target: the proposal shows "Must work uses N minutes of the 20% buffer", and every non-Must action is left out with "over target". This is a warning; Approve stays a single step.
- Must work beyond free time: the proposal shows **"N minutes of Must work do not fit in this window"**, lists those items as **does not fit**, and offers the user's fixes: widen the window, unpin or shorten something, lower a priority, or remove an item.
- Approving with work that does not fit needs a separate **"Approve with N minutes unresolved"**. The plan stores `overloadMinutes`, and the approved plan keeps showing "N minutes of Must work do not fit" with those items labelled. Thalia never presents them as scheduled, never drops them, and never says the day fits.

**Example.** Window 12:00 to 22:00 is 600 minutes. Pinned: a 14:00 dentist visit, 60 minutes. Free time 540; target 80% of 540 = 432; buffer 108.

| Selected action | Result | Target left |
|---|---|---|
| Dentist, pinned 14:00, 60 min | Pinned | 432 |
| Draft chapter 2, Must, 180, project A | Queue 1, within target | 252 |
| Review PR, Important, 90, project B | Queue 2 | 162 |
| Gym plan, Normal, 45, project C | Left out: project limit | 162 |
| Book flights, Normal, 30, standalone | Queue 3 | 132 |
| Tidy desk, Optional, 150, project A | Left out: over target | 132 |
| Call bank, Optional, 15, standalone | Queue 4 | 117 |

**Overload example.** Same window and pin. Must actions of 300, 150 and 150 minutes, 600 in all.
The first 300 is within target, the next 150 runs from 300 to 450 and so 18 minutes of it are in the buffer, and the last 150 runs from 450 to 600: 60 of its minutes lie beyond the 540 of free time.
The proposal shows "60 minutes of Must work do not fit in this window" and labels the last item **does not fit**.

**Slice 2 clarifications, recorded 2026-10-03.**

- **Eligibility follows the specification:** only an **active** project competes for focus time (specification section 4). A selected action is planned if it is standalone or its project is active. Any other state, or a project that cannot be found, leaves the action out with the reason "its project is not active", and it stays visible in the preview and in the approved plan. **Must priority never overrides eligibility:** a Must action in a project that is not active is not planned, and the preview flags the conflict ("A Must action is selected but its project is not active, so it is not planned"). It is a warning, not an error: the user can make the project active or unselect it, and the unplanned work is never shown as scheduled. It does not count as Must work that does not fit.
- **The project limit counts pinned actions first,** then the queue, so a committed pin is never displaced by a flexible action. Standalone actions never count.
- **Pins are values of one day's proposal,** never fields of an action. They are not remembered for another day; an Adjust starts from the pins of the approved revision.
- **An empty selection is a valid rest day,** approved only by its own explicit action ("Approve rest day"), never by the ordinary Approve. Leaving it, or Keep original, writes nothing. A rest day is a revision with no items and can be adjusted into a planned day and back. A selected action that is no longer open, or no longer exists, is a blocking error.
- **A day's window must lie inside the active day.** One that runs past the rollover is refused as empty rather than wrapped.

### 6. Approval, the opening call, and races

**What M2 actually does,** read from `CheckInConfirmer.kt`, `OccurrenceScheduler.kt`, `OccurrenceStore.kt` and `CheckInDatabase.kt` under `app/src/main/kotlin/com/thalia/app/voicecheckins/`:

| Call | Slot state for (type, calendar date) | Result |
|---|---|---|
| `CheckInConfirmer.confirm(type, date)` | any | Uses **M2's stored plan time**, never a time from the planning loop |
| `CheckInConfirmer.reschedule(type, date, time)` | no revision | Records revision 1 at `time` and arms it |
| | scheduled at `time` | `Unchanged` |
| | scheduled at another time | **Moves it** to `time` |
| | ended without ringing, including **cancelled by the user** | **Records a new revision**, because `isRecoverable` is any terminal status with no fired time |
| | ended after ringing | `AlreadyConfirmed`; one call per slot |
| | ringing or answered | `InProgress` |
| any | slot off, inside the quiet period, time not in the future, exact alarms not allowed | Refused; for "not allowed" a cancelled `NOT_PERMITTED` record is left |
| `OccurrenceScheduler.schedule(occurrence)` | id already recorded | `AlreadyRecorded`, nothing armed |
| | id free | Inserts the record, then arms the alarm; if arming is refused, the record is cancelled with the reason |

**The synchronization M2 guarantees,** each at the level of one database statement or transaction:

- `insert` uses `OnConflictStrategy.IGNORE` on the primary key, so a revision id is recorded at most once. Revision 1's id is the slot itself.
- `transition` is `UPDATE ... WHERE id = :id AND status = :from`, so a status change happens only from the expected status.
- `supersede` is one `@Transaction`: the old revision must still be scheduled and the new id free, or nothing changes.
- The claim that lets an alarm ring is one conditional `UPDATE`, inside `claimOrSuppress`'s transaction.

**What M2 does not guarantee:** there is no lock, mutex or transaction across a read followed by a later write.
`CheckInConfirmer.place` reads the slot, then acts on what it read, and so does any caller that reads first.

**So the earlier "read the slot, then `reschedule`" retry could race.**
If the user confirmed a call at another time, or cancelled one, between the read and the `reschedule`, then `reschedule` would see the new state and **move** that call or **revive** the cancelled day.
The window is small, but the earlier claim that the retry "never moves or revives a call" was too strong.

**The bounded approach: create-if-absent, for duplicates only.**

The planning layer never uses `reschedule` without the user present, and never for a slot that already has a revision.
To create a call it builds revision 1 with M2's existing `CheckInSchedule.occurrenceAt(date, time, revision = 1)` and calls M2's existing `OccurrenceScheduler.schedule(...)`.

**Verified in `OccurrenceScheduler.schedule`:** it calls `store.insert` first and returns `AlreadyRecorded` **before** calling `alarms.schedule` when the insert is ignored. An ignored insert therefore arms nothing. When the insert succeeds it arms the alarm; if arming is refused, it cancels the record with the reason.

**What this does and does not guarantee.**

- **Duplicate prevention: guaranteed.** Revision 1's id is the slot, and the insert ignores a taken id, so this path can only ever record a slot's first call. It cannot move, revive or duplicate a call recorded by any other path.
- **Current slot settings: not guaranteed.** `OccurrenceScheduler.schedule` does not check whether the slot is switched on, whether the time is inside the quiet period, or whether it is in the future; `CheckInConfirmer` checks those before calling it. The planning layer must repeat the three checks, and they are a read followed by a later write, so they can be stale:
  - the quiet period is checked again when the alarm arrives (`CheckInFireHandler`), so a stale quiet-period read cannot make a call ring inside it;
  - a time no longer in the future is refused by `ExactAlarmScheduler` when arming, and the record is cancelled with `NOT_IN_THE_FUTURE`;
  - **a slot switched off is not checked when the alarm arrives.** `CheckInFireHandler` checks lateness, the quiet period, a call in progress and the claim, but not `enabled`. Only `Reconciler` cancels a scheduled occurrence of a disabled slot, with `PLAN_DISABLED`, and only when a reconcile runs. A slot switched off between the planning layer's check and its insert would leave an armed call until the next reconcile.
- **A refused arming uses up revision 1.** If arming is refused, for example without the exact-alarm permission, revision 1 is left cancelled with `NOT_PERMITTED`, and create-if-absent can never create that slot's call again. Only the explicit user action, through `CheckInConfirmer.reschedule` (not `OccurrenceScheduler.reschedule`, which returns `NotScheduled` for a cancelled revision), can then record revision 2.

**Slice 3 blockers, recorded and not solved now. M2 is not changed by this ADR.**

1. **The disabled-slot gap.** Today nothing in the app can switch a slot off at runtime (`OccurrenceStore.savePlan` has no caller), so the gap is latent. Before slice 3 ships, either M2 re-checks `enabled` when the alarm arrives, as a separate approved M2 change, or slice 3 shows that no code path can disable a slot while a planning call is being created.
2. **The used-up revision 1** after a refused arming must be shown as NEEDS ATTENTION, with the explicit action as the only way forward, and tested.

**Amended 2026-10-04 (approved):** the slice 3 amendment records how each is resolved, with the code evidence and the owner's decisions U1 to U4. Neither blocker affects current behavior: nothing in the app can disable a slot, and nothing under `planning/` calls the scheduler.

**Slice 2 scope, recorded 2026-10-03.** Slice 2 approves **the plan only** and never schedules, moves or cancels a call. The opening time, the call-effect preview, `callStatus`, `callDetail`, the retry and everything in the call rows below arrive with slice 3, whose schema adds the call fields then; none are added speculatively now. The screen says so beside the approval: "Approving saves the plan only. No call is scheduled or changed." The plan's zone is read from M2's **stored** check-in schedule with the existing read-only query, never through `OccurrenceStore.plan()`, which creates the default plan when none exists. If no plan is stored, the stored slots are incomplete or name two zones, or the zone is unknown, planning shows that configuration problem and does not fall back to the phone's zone.

**What approval checks, in slice 2.** Three different things, in this order. They are not the same check.

1. **Independent validation (`PlanValidator`).** The proposal is judged on its own contents, without calling the planner, so a proposal from anywhere else, or altered after the planner made it, is caught. It checks that the selected actions are unique, open and exactly the items, with their estimates; that an action in a project that is not active is never placed and an eligible one is never excluded as ineligible; that pins match their pin values, lie inside the window in real elapsed time and do not overlap; that the window, pinned, free and target figures add up; that the queue is in order with positions in sequence and every placement label and exclusion follows from the running minutes; that no third project is queued for flexible work; that the overload is the Must work beyond free time; and that a rest day has no items. A proposal that breaks any of them is refused as malformed, with nothing written. This is a consistency check against the rules, not an oracle of the best plan, and it replays the same rules independently of the planner.
2. **Staleness.** Inside one `planning.db` transaction it reads the current actions, projects, rollover, default window and the day's latest revision, takes the user's choices from the proposal (day, selection, pins, window), runs the planner again, and requires the result to equal the proposal exactly. The proposal carries every dependency it was made from: each selected action in full with its creation time, each project's title and state, the rollover, the zone, whether the default window was used, and the revision it was based on. So a changed action, **even a renamed one whose schedule is unchanged**, a changed project title or state, a new rollover, a new default window (for a proposal that used it), a changed zone, or a revision approved since, is refused as changed, nothing is written, and the screen makes the preview again. This is a comparison, not a validation.
3. **Acknowledgements, then the write.** Must work that does not fit needs "Approve with N minutes unresolved", and a rest day needs "Approve rest day". Then the next revision and its items are written. A failure anywhere rolls back all of it.

**The zone and two databases.** M2's stored zone lives in `checkins.db` and the plan in `planning.db`. They are two databases, so the zone is read from M2 just before the approval transaction and is **not** part of it: there is no shared atomic transaction, and M2's zone could change between that read and the commit. The proposal records the zone it was made in, the approval compares it with the zone just read, and the approved plan stores it. What this does not give is a guarantee that M2's zone is still the same at the moment of commit. The effect of such a change is bounded: the plan keeps its own zone, nothing in slice 2 depends on M2 afterwards, and slice 3's call link re-reads M2 itself before creating anything. Two approvals racing for one day serialise on the transaction: the second sees the first's revision and is refused, and `(activeDay, revision)` is unique in the table as a last guard.

**Revisions and history.** Revision numbers are per active day from 1. Approving writes the next revision and its items in one transaction and **updates nothing**: an earlier revision, and its items, are never edited, not even to mark them superseded. The revision in force for a day is its highest, found by query. Each item keeps a snapshot of the action's title and priority and of its project's id, title and state as they were, so editing or closing either later does not change what was approved. The screen shows only the latest revision. "Keep original" discards the proposal and leaves the approved plan in force; with no plan yet it reads "Discard". There are no database triggers; the absence of any update path to the plan tables, a test that scans for one, and tests that compare the raw rows of an earlier revision after later approvals and edits, are used instead.

**The approval sequence.**

1. **Proposal.** In memory only. Leaving writes nothing, and any plan already approved for D stays in force.
2. **Adjust.** Changing the selection, a pin, an estimate, a priority or the window runs the planner again.
3. **Preview of the call effect,** read from M2 without changing it: "will confirm the opening call at 08:00 on Fri 3 Oct", "a call is already confirmed for this day at 10:00", "you cancelled this day's call", "a call already rang for this day", "inside the quiet period", or "the time has passed".
4. **Approve.** The validator runs again against the current database: every action still open and unchanged since the proposal. A stale proposal is refused and proposed afresh. Then **one transaction in `planning.db`** writes the next revision of the plan and its items for D, and sets the call status: **REQUESTED** with an opening time, otherwise **NONE**.
5. **Call.**
   - Slot empty: create-if-absent. `Scheduled` gives CONFIRMED; `AlreadyRecorded` re-reads the slot and gives CONFIRMED if it holds a scheduled call at the plan's time, otherwise NEEDS ATTENTION; a refusal before arming gives REFUSED with the reason, and a refused arming gives NEEDS ATTENTION, because revision 1 is now used.
   - Slot already holds a call: nothing is changed automatically. The screen shows NEEDS ATTENTION with M2's state, and offers, with the user present and an explicit tick, "Move the 10:00 call to 08:00" or "Confirm a new call for this day". Only that explicit action uses `reschedule`, and its result is shown as M2 reports it.

**The screen shows the two separately:** "Plan approved, 2 Oct, revision 1" and "Opening call: confirmed 08:00", or "Opening call: not confirmed, inside the quiet period". An approved plan never depends on its call.

**Crash windows between `planning.db` and `checkins.db`.** They are two databases, so there is no transaction across them.

| The process dies | State afterwards | Recovery |
|---|---|---|
| Before the plan commits | No plan, no call | The proposal is lost. The user plans again. |
| After the plan commits, before M2 records the call | Plan approved, REQUESTED, no occurrence | Retry on the next launch |
| After M2 records, before the alarm is armed | Plan approved, REQUESTED, occurrence without an alarm | M2's own reconcile arms it, on launch, boot, update, time change or permission grant |
| After M2 arms, before the call status is written | Plan approved, REQUESTED, call armed | Retry on the next launch finds it |

**The retry** runs on each launch, for approved plans still REQUESTED, and does exactly step 5's "slot empty" path: create-if-absent, then classify.
It never uses `reschedule`. REFUSED and NEEDS ATTENTION are not retried automatically; the plan screen offers "Try again" after the user fixes the cause. **Amended 2026-10-04:** the retry runs in the launch reconcile's own coroutine, after `reconcile` returns, and only for eligible plans (see the slice 3 amendment).

**This is not unattended recovery.** The retry runs only when the user opens Thalia. If the process dies in the second row and Thalia is not opened before the opening time, that call does not ring. The window is the moment between two local writes. Closing it fully would need a hook in M2's boot and update receiver, which this ADR does not add, to leave M2 unchanged.

### 7. Personal data: SQLCipher, keys, and failure

**Specification section 10:** Room is the source of truth; "sensitive fields or the database will be protected using Android Keystore-backed encryption"; tokens and secrets stay out of logs and exports; export starts as manual export and import, never including keys.

**Decision: a separate `planning.db`, encrypted with SQLCipher, its key wrapped by the Android Keystore.**

| Option | Meets section 10 | Cost |
|---|---|---|
| **SQLCipher, separate `planning.db`** (chosen) | Yes: the whole database is encrypted, with a Keystore-backed key | One native dependency; a second database |
| Android file-based encryption only | No | None now; section 10 deferred explicitly |
| Field-level AES-GCM on text columns | Partly | Custom crypto in every query; sorting and search break |

**Evidence.**

- **Pinned: `net.zetetic:sqlcipher-android:4.19.1`,** the latest release on Maven Central on 2026-10-02: its `maven-metadata.xml` lists 4.19.1 as both latest and release, last updated 2026-09-29. Primary sources: [Maven Central](https://repo1.maven.org/maven2/net/zetetic/sqlcipher-android/) and [the project README](https://github.com/sqlcipher/sqlcipher-android).
- Room opens it through SQLCipher's `SQLiteOpenHelper`, built by `KeepingSqlCipherOpenHelperFactory` as decision 7's findings explain, with the passphrase as bytes; the native library is loaded with `System.loadLibrary("sqlcipher")` before first use. It supports API 23 and up; Thalia's minimum is 31.
- **The only dependency added is that one artifact.** Its POM declares `androidx.sqlite:sqlite:2.7.0` at runtime, and `kotlin-stdlib`. Room 2.8.5 resolved `androidx.sqlite` 2.6.2 before. Gradle now resolves **both `androidx.sqlite:sqlite` and `androidx.sqlite:sqlite-framework` at 2.7.0, in the debug and the release runtime classpaths**, so M2's `checkins.db`, which opens through the framework helper, runs on 2.7.0 in both builds. M2's full debug and release unit-test suites pass on it; no M2 code changes. M2 on the new library ran on the Pixel on 2026-10-03: every M2 row was preserved and a scheduled call rang and completed.
- It supports 16 KB memory pages since 4.6.1; the older `android-database-sqlcipher` is end of life. Source: [Zetetic](https://www.zetetic.net/blog/2025/06/26/sqlcipher-for-android-16kb-page-size-support/).
- Its licence is BSD 3-Clause. Source: [LICENSE](https://github.com/sqlcipher/sqlcipher-android/blob/master/LICENSE).
- The added APK size and the open time are measured in slice 1, not assumed. Measured on 2026-10-03: the debug APK grew by 8,072,075 bytes (+7.7 MiB). The open time was not precisely measured: a first open that also creates the key and database was observed to take about 0.44 s, and when a reopen finished was not measured; the app has no timing instrumentation (see [the results](../m4-slice1-device-results.md)).

**Keys.**

- The database passphrase is 32 random bytes, generated once, wrapped with an AES-256-GCM Keystore key that needs no user authentication, and kept in `no_backup`.
- The Keystore wrapper follows M3A's pattern, in the main source set, without any control that deletes the key.
- `checkins.db` stays plain and untouched.

**Backup and device transfer.**

- `android:allowBackup="false"` is already set. Android's documentation says that on some manufacturers' devices this does not stop device-to-device transfer ([Android 12 behaviour changes](https://developer.android.com/about/versions/12/behavior-changes-12)).
- The rules name `planning.db` and its `-wal`, `-shm` and `-journal` files in the `database` domain, the app's `databases` directory, and the sealed key, its `.new` and both `.staging` names under `no_backup/` in the `root` domain, the app's data directory. A test checks that these are where production puts the files; whether Android applies them during a real transfer is not verified.
- A Keystore key never leaves the phone, so a transferred `planning.db` would be unreadable.
- `android:dataExtractionRules` therefore excludes `planning.db` and the wrapped key from both cloud backup and device transfer.
- Moving to another phone, or recovering after a reset, uses the export in decision 8, which does not depend on the Keystore.

**When the database cannot be opened.**

| Cause | What the user sees | What happens to the files |
|---|---|---|
| The Keystore key cannot unwrap the passphrase | "Planning data cannot be opened: the key on this phone is not available" | Kept, untouched |
| SQLCipher cannot read the file | "Planning data cannot be opened: the file is unreadable" | Kept, untouched |
| A migration fails | "Planning data needs an update that failed" | Kept; Room's destructive fallback is never enabled |
| The database is missing but its key, or a WAL, SHM or journal file, exists | "Planning data is missing" | Nothing is created |
| Sealing at first use, loading SQLCipher, or writing a file fails | "Planning storage could not be prepared on this phone" | Nothing is created as if complete |

- The planning screens show the error and its class. Check-ins and their calls keep working.
- Thalia never deletes, recreates or overwrites an unreadable planning database.
- "Set aside and start fresh", behind an explicit confirmation, **renames** the unreadable files into a dated folder, keeps them, and creates an empty database. It uses the same crash-safe set-aside as import, in decision 8, so it arrives with slice 1B. In slice 1A an unreadable database is surfaced and preserved, and nothing else.

**First creation, and the missing-file cases.** The passphrase is sealed to `planning-key.sealed.new`, the database is created and opened with it, and only then is the sealed key renamed to `planning-key.sealed`, atomically.
On every open:

| `planning.db` | `planning-key.sealed` | Meaning | Action |
|---|---|---|---|
| absent, and no WAL, SHM or journal | absent | First use, or a crash before the database existed | Create, as above. A leftover `.new` is replaced; no database used it |
| present | present | Normal | Open |
| present | absent, `.new` present | A crash after creating the database, before the rename | Finish the rename, then open |
| present | absent, no `.new` | The key is gone | KEY UNAVAILABLE; nothing is created or changed |
| absent | present, or a WAL, SHM or journal remains | The database is gone | DATABASE MISSING; nothing is created, so a lost database is never silently replaced |

Every exception while opening becomes a reported problem, never a crash: a Keystore that refuses to seal at first use, an encryption library that fails to load, and a file that cannot be written are STORAGE UNAVAILABLE, and nothing half-made is left as if it were complete.
- Logs carry only error classes, ids and counts, never titles, direction text or outcomes.

**Found while implementing and reviewing slice 1A.**

- **SQLCipher deletes a database it reports as corrupt, unless told not to.** Read from sqlcipher-android 4.19.1's bytecode: `SupportOpenHelperFactory` creates a `SupportHelper` that passes a **null** error handler to SQLCipher's `SQLiteOpenHelper`; `SQLiteDatabase` then installs `DefaultDatabaseErrorHandler`, whose `onCorruption` calls `deleteDatabaseFile`. Room's `Callback.onCorruption` is never called on that path. A file SQLCipher cannot read, for example because the passphrase does not match, can be reported as corrupt. So production does not use `SupportOpenHelperFactory`: `KeepingSqlCipherOpenHelperFactory` builds the same SQLCipher `SQLiteOpenHelper`, with the same defaults (no hook, minimum supported version 0, the passphrase kept for reopening), and an error handler that keeps the file.
- **Room's own callback deletes too.** `SupportSQLiteOpenHelper.Callback.onCorruption`'s default implementation deletes the file, and Room does not override it. `NonDestructiveOpenHelperFactory` wraps the factory so that this callback keeps the file for any helper that does call it, such as the framework helper the tests use.
- **What the tests prove, and what they do not.** On the JVM, a corrupt file opened through the framework helper behind `NonDestructiveOpenHelperFactory` is reported unreadable, and the database, key, `.new`, WAL, SHM and journal files are byte-identical afterwards, none created. The production SQLCipher helper is checked by reflection to hold the keeping handler and a minimum version of 0. **That SQLCipher on a device keeps a corrupt or wrongly keyed file was verified on the Pixel on 2026-10-03,** in the isolated check app: both were reported `DATABASE_UNREADABLE` and left byte-identical.
- **The newer-schema test uses a WAL-mode file.** Room switches a rollback-journal file to WAL before it checks the schema version, which rewrites header bytes 18 and 19. Every `planning.db` is made by Room in WAL mode, so the test builds its newer-schema file in WAL mode and checks that, after the refused open, the database, key and sidecar files are byte-identical and no sidecar was created. **Not verified:** a rollback-journal file, which Room would change in those two bytes; a WAL file holding frames not yet checkpointed; and SQLCipher's behaviour on the same case.

### 8. Encrypted export and import

**Purpose.** A user-triggered copy of all planning data that restores on any phone, without the original phone's Keystore.
It exists **before any real planning data is accepted**, so the user never depends on data that cannot leave the phone.
There is no cloud sync, no automatic or scheduled backup, and Thalia never uploads it.

**Where it goes.** The user picks the destination and source through Android's document picker (`ACTION_CREATE_DOCUMENT` and `ACTION_OPEN_DOCUMENT`): a local folder, removable storage, or a cloud folder the user chooses. No storage permission is needed.

**Archive contents.** One file, `thalia-planning-YYYYMMDD-HHMM.thaliaexport`:

| Part | Content | Encrypted |
|---|---|---|
| Header | Magic `THALIA-PLANNING-EXPORT`, format version, KDF name and parameters, salt, nonce | No; authenticated as associated data |
| Payload | A JSON document: format version, Room schema version, app version, creation time, the plan's zone, every planning row with its UUID, PlanningSettings, and a count per entity | Yes |

It never contains the database passphrase, any Keystore material, the M3A key, provider credentials, `checkins.db`, transcripts or audio.
M2's check-in records are not part of it: a restored plan's opening call is confirmed again on the new phone from the plan screen, not imported.

**Password handling.**

- The user sets a password for each export, typed twice, at least 12 characters. It is never stored, logged, shown again or kept after the screen closes.
- The key is derived with **PBKDF2-HMAC-SHA256, 600,000 iterations**, a random 16-byte salt, giving a 256-bit key. OWASP's [password storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html) recommends 600,000 iterations for PBKDF2-HMAC-SHA256.
- The payload is encrypted with **AES-256-GCM** and a random 12-byte nonce; the header is the associated data, so any change to it fails decryption.
- Both are in the Java standard library on Android. Ponytail lite: Argon2id is stronger against GPU guessing but needs a dependency; PBKDF2 needs none.
- A forgotten password cannot be recovered. The export screen says so.

**Bounds, checked before any expensive work.**

| Input | Accepted |
|---|---|
| Archive file | At most 8 MiB, read through a bounded stream; a larger file is refused unread |
| Header | At most 1 KiB, the exact magic, a known format version |
| KDF | Exactly `PBKDF2-HMAC-SHA256`; iterations from 600,000 to 2,000,000; salt exactly 16 bytes; key 256 bits. Anything else is refused **before** deriving, so a crafted file cannot make the phone spend minutes on one guess |
| Cipher | Exactly AES-256-GCM, a 12-byte nonce, a 128-bit tag |
| Decrypted payload | At most 16 MiB of JSON, parsed with a depth limit |
| Rows | At most 100,000 per entity |
| Text | The data model's limits: direction 2,000 characters, titles 200, success conditions 1,000 |

Exports are written with exactly 600,000 iterations; the upper bound only leaves room to raise it later.
Deriving the key, encrypting, decrypting and parsing run on a background dispatcher, never the main thread; the screen shows progress and can be left without leaving a partial file behind.

**Validation on import,** all of it before anything is written:

1. The magic and format version are recognised. A newer format is refused with "made by a newer Thalia".
2. Decryption with the password succeeds. A wrong password and a damaged file look the same to AES-GCM, and the message says "wrong password or damaged file".
3. The JSON matches the format: required fields, known enum values, valid dates and times.
4. References hold: projects to goals, actions to projects, plan items to plans and actions, outcomes to plan items.
5. The counts in the payload match the rows.
6. A Room schema version older than the app's is accepted and migrated by the same code as the database; a newer one is refused.
   **Schema 1 limitation, recorded during slice 1B:** schema 1 was the first, so slice 1B accepted exactly the current schema.
   Every future schema upgrade must, in the same change, add a tested migration of exports from each previously supported schema, with round-trip tests of a real export from the older version, before that version's exports may be accepted.
   **Slice 2 does this:** the payload is schema 2 and still restores a schema 1 export, migrated in memory to no plans. See "Two version numbers" below.

**Two version numbers, recorded 2026-10-03.** The **encryption envelope** (magic, header, KDF, cipher) has its own `format` version, `PlanningArchive.FORMAT_VERSION`, which stays 1 and changes only if the envelope's structure changes. The **payload** has its own `schemaVersion`, equal to the database schema (2 from slice 2), which changes whenever the data model does; it is what "raising the format version" means for slices 2 and 3. The payload also repeats `format`, which must equal the envelope's. A payload of schema 1 has no plans and counts without them; a payload of schema 2 has `plans` and `planItems`, with their counts. Import accepts schema 1 and 2, checks each against its own exact key set, refuses a newer schema as "made by a newer Thalia" and anything older than 1 as invalid, and migrates a schema 1 payload to an empty set of plans before it reaches the database. Export always writes the current schema; the writer refuses the old shape. Plans and items are checked on import: unique ids, unique `(day, revision)`, the revisions of a day being 1 to n with no gap, no status field, every item pointing at an existing plan, action and, if named, project, an action at most once in a plan, the data model's own limits, and the counts. Slice 3 will raise the schema to 3 in the same way and keep accepting 1 and 2; the slice 3 amendment defines schema 3.

**Restore behaviour.** Import never merges and never overwrites silently.

- **The planning database is empty,** on a new phone or after "set aside and start fresh": a preview shows the counts and the export's creation time. On confirmation, one transaction writes every row.
- **The planning database has data:** import is refused by default. The user may choose **"Replace current planning data"**, which shows the current counts beside the imported ones and needs the word REPLACE typed. Thalia then renames the current database files to a dated name, keeps them, creates an empty database, and restores into it in one transaction.
- If staging fails with an ordinary error, nothing has replaced anything yet, as described below.

**Crash-safe replacement.** An exception rolling back a transaction is not proof of recovery after the process dies, so replacement is built from steps that each survive a crash:

1. **Stage.** Restore into `planning-restore.db`, a new database with **its own new passphrase**, sealed with the same Keystore key into `planning-restore-key.sealed`. All rows go in one transaction. Then `PRAGMA wal_checkpoint(TRUNCATE)`, close, and sync the file and its directory, so the staged database is one complete file with no WAL to lose.
2. **Commit point.** Write the journal `planning-replace.journal` atomically and synced: the dated set-aside folder and the list of files to move. Until this file exists the live database has not been touched, and a crash leaves it as it was; leftover staging files are set aside on the next start, not deleted.
3. **Swap.** With every planning connection closed and planning access blocked, rename in a fixed order: the live `planning.db`, `planning.db-wal`, `planning.db-shm` and `planning-key.sealed` into the set-aside folder, then `planning-restore.db` to `planning.db` and `planning-restore-key.sealed` to `planning-key.sealed`. Each rename is atomic on one filesystem, and the directories are synced after the moves.
4. **Finish.** Delete the journal. Only the journal, which holds no user data, is ever deleted.
5. **Recovery.** Before planning data is opened, if the journal exists, Thalia completes the remaining renames in order. Each step checks whether its source still exists, so repeating it after a crash is safe.

- The live database's WAL and SHM always move with it while it is closed, so the set-aside copy keeps every committed transaction.
- The set-aside folder keeps its own sealed passphrase, wrapped by the same Keystore key, so the old data stays openable on this phone.
- Process-death recovery is tested by stopping the sequence after each step and then running recovery, not only by throwing an exception.
- Restored plans keep their windows, revisions and outcomes. Their call status becomes "not linked on this phone" until the user confirms a call. **Amended 2026-10-04:** call status is never exported, and an import or Replace writes `NOT_LINKED`, never `REQUESTED` (see the slice 3 amendment).

Ponytail lite: merging two sets of planning data needs conflict rules and is left out; replace is enough to move phones and to recover.

**Where it belongs.** Export, import and the crash-safe set-aside are **slice 1B**. Slices 2 and 3 extend the payload with their tables, raising the format version, and each repeats the round-trip test.

### 9. AI and voice later, through the same local operations

The pipeline from specification section 8 is built now, with the deterministic planner as its only producer:

`input -> typed proposal -> validator -> deterministic simulation -> preview -> approval -> one Room transaction -> side effects`

- A later AI request, or the M3B voice check-in, can only produce a `PlanProposal`, an `OutcomeReport`, or a suggested action, each through the same validator, preview and approval.
- Neither the provider nor the voice session writes to `planning.db` or `checkins.db`.
- **No autonomous project decomposition:** nothing creates or splits projects or actions on the user's behalf. An AI may suggest an action; only the user's save creates it.
- The loop works fully with no provider, no network and no key.

### 10. Where the code goes

Ponytail lite: the planner, the proposal types, `ActiveDay`, the validator and the export format go in a new `com.thalia.planning` package inside `:core`, which is plain Kotlin and tested on the JVM. A separate Gradle module is the heavier choice, worth it only if `:core` becomes a dependency problem.
The database, the Keystore wrapper, the document picker, the call link and the screens go in `:app`.
The screens are plain and functional. The Pixel visual system of specification section 14 comes with the Today timeline.

## Slices and acceptance criteria

Each slice is designed, implemented, verified on the computer, validated on the Pixel, documented, then committed, as specification section 18 requires.
Each slice's Pixel step first backs up `checkins.db` and confirms every M2 row and setting is unchanged afterwards.
**No real planning data is relied on until slices 1A and 1B pass, including the Pixel steps of slice 1B.**

### Slice 1A: the information foundation and encrypted storage

**In scope.**

- `com.thalia.planning` in `:core`: the Direction, Goal, Project and Action models, PlanningSettings, and their validation.
- In `:app`: `planning.db` with Room and SQLCipher; the Keystore-sealed passphrase in `no_backup`; the creation order and open-failure cases of decision 7; `dataExtractionRules`; plain screens to create and edit the direction, goals, projects, actions and planning settings, opened from the main screen.

**Not in scope.** Export and import, "set aside and start fresh", day plans, the planner, outcomes, the opening call, any change to M2 code, any network use.

**Acceptance criteria.**

1. Direction, goals, projects and actions can be created and edited, and survive closing and reopening the database.
2. A project can be linked to a goal, or to none. An action can be saved under a project or standalone, with only a title. A link to a goal or project that does not exist is refused. An action cannot be added to, or moved into, a completed, abandoned or archived project.
3. Validation refuses blank or over-long text, out-of-range estimates, and planning settings whose window is empty or leaves the active day.
4. Planning settings default to a 04:00 rollover; the window has no value until the user sets it.
5. The production database is built with SQLCipher's `SQLiteOpenHelper` through `KeepingSqlCipherOpenHelperFactory`, whose corruption handler keeps the file, and without any destructive migration fallback.
6. Each open case in decision 7 behaves as its table says, and for every failure the files are byte-identical before and after.
7. `dataExtractionRules` exclude `planning.db`, its WAL and SHM, and the sealed key from cloud backup and device transfer.
8. Nothing in the planning code logs titles, text or settings.
9. M2's tests, the release isolation tests and ktlint pass with the new dependency.

**Evidence levels.** On the computer, the database runs on Robolectric's SQLite with a fake sealer, because neither SQLCipher's native library nor the Android Keystore runs there. Real encryption, the Keystore, the APK size change and the open time are checked on the Pixel in slice 1B, before real data is accepted.

### Slice 1B: encrypted export and import, and recovery

1. Export and import as in decision 8, through the document picker, with every bound in its table enforced and tested.
2. JVM tests: no passphrase, key or credential in an archive; a wrong password, a changed header, a changed payload, a truncated file, an oversized file and out-of-range KDF parameters are each refused with nothing written.
3. Import into an empty database; a refused import into a non-empty one; "Replace" keeping the old files; a broken reference and a newer format refused; a round trip giving identical rows by UUID and field.
4. The crash-safe replacement and "set aside and start fresh", tested by stopping after each step and running recovery.
5. **Recovery without the original Keystore, in an isolated check app, never against the installed Thalia.** Clearing `com.thalia.app.debug`'s data, uninstalling it, or running `connectedAndroidTest` against it would destroy M2's records: Gradle's connected test tasks uninstall the app under test after the run by default ([nowinandroid #1531](https://github.com/android/nowinandroid/pull/1531), [Gradle forums](https://discuss.gradle.org/t/how-can-i-run-espresso-tests-without-uninstalling-apk-after/15492)).
   - A separate build type, `recoveryCheck`, with its own application id, `com.thalia.app.recoverycheck`, and so its own data directory, files and Keystore keys. Its planning files and key alias are its own; it never reads or writes `com.thalia.app.debug`.
   - In it: create planning data, export it with a password to a file kept outside that app's data, then clear **only `com.thalia.app.recoverycheck`'s data**, which also removes its Keystore keys, then import with the password and compare every row.
   - Then uninstall only `com.thalia.app.recoverycheck`. Before and after, `com.thalia.app.debug`'s `checkins.db` and `planning.db` are backed up and compared, and must be unchanged.
   - The same app checks, on real SQLCipher, that a corrupt file and a file opened with the wrong passphrase are reported and kept, byte-identical.
   - It runs **the same production planning-storage code**: `PlanningDatabaseOpener`, `KeepingSqlCipherOpenHelperFactory`, `KeystorePassphraseSealer`, `PlanningStore` and the export code. Only the file names and the Keystore alias differ, so slice 1B makes the sealer's alias a constructor parameter, which today is fixed.
6. On the Pixel, with the real `com.thalia.app.debug` updated in place and its data kept: `planning.db` cannot be opened as plain SQLite; every row and setting in `checkins.db` is unchanged; a scheduled M2 call, on the upgraded `androidx.sqlite` 2.7.0, still rings with the scripted fake through its alarm; the APK size change and the database open time are recorded.

**Real personal planning data is accepted only after slices 1A and 1B both pass, including their Pixel steps.**
Neither slice completes M4; full M4 keeps the scope in decision 1.

### Slice 2: the daily proposal, adjustment and approval

**Implemented and verified on the computer 2026-10-03 (`98dd3b4`); accepted on the Pixel 10 between 2026-10-03 and 2026-10-04, with limitations** ([results](../m4-slice2-device-results.md), [NOW](../NOW.md)). Two day-switch defects found on the device were fixed afterwards and retested; the fixes were uncommitted when this status was written. Slice 3 is not started and the minimal loop is not complete. The clarifications under decisions 3 to 6 and 8 apply. Criterion 6's "adjusting" is the same screen creating the next revision; "Keep original" leaves the approved plan in force. Criterion 4's Unfinished list, with no outcomes until slice 3, offers open actions that an earlier day's latest approved plan placed, pinned or queued, unselected.

1. Planning setup asks for the window, suggesting 12:00 to 22:00, and saves it as the default; the default and each day's window can be edited; an approved plan keeps its window when the default changes.
2. JVM tests cover both examples in decision 5, overlapping pins, a pin outside the window, a window full of pins, the target and free-time labels, the project limit, ties, and repeat runs giving identical proposals.
3. JVM tests cover the active day: 03:59 on 3 October is active day 2 October, 04:00 is 3 October, a window crossing midnight, a rollover change leaving existing plans where they were, and a time inside a daylight-saving gap being refused.
4. Only selected actions appear in a proposal. Unfinished actions are listed, unselected.
5. Leaving a proposal writes nothing. Approving writes the plan in one transaction; a test that fails the write midway finds the previous state, never part of the new one.
6. A stale proposal is refused at approval and proposed again. Adjusting after approval writes a new revision and keeps the old one.
7. Work that does not fit needs "Approve with N minutes unresolved", and the approved plan keeps showing the unresolved minutes and items.
8. The export payload gains plans and items; the round-trip test passes.

### Slice 3: outcomes, carry-forward and the opening call

Slice 3 adds, in its own schema 3 and export migration, the outcomes and the opening time, call status and detail, in two new tables, `plan_outcomes` and `plan_calls`; slice 2 stores none of them. It also refines the Unfinished list with recorded outcomes.

1. Each item of an approved plan can be recorded done, partial, skipped, blocked or unknown. Items left unrecorded count as unknown. Nothing prompts more than once.
2. Done closes the action. Any other result leaves it open, and it is offered, unselected, when a later day is planned.
3. The preview states the call effect from M2's real slot state.
4. Plan approval and call confirmation are shown separately, and every outcome of create-if-absent maps to CONFIRMED, REFUSED or NEEDS ATTENTION, tested against M2's in-memory occurrence store.
5. Race tests: a confirm, a move or a cancel injected between the planning layer's read and its write never results in a moved, revived or duplicated call; the planning layer records NEEDS ATTENTION instead.
6. Tests of the four crash windows in decision 6 show the retry confirming at most once and never calling `reschedule`.
7. A move or a new call for a day that already has one happens only through the explicit, ticked action.
8. The export payload gains outcomes and call status; the round-trip test passes; restored plans show "not linked on this phone".
9. On the Pixel, over one real day: a plan approved the evening before with an opening time, the opening call ringing at that time with the scripted fake, outcomes recorded, the next day's planning screen offering the unfinished actions, and M2's existing rows unchanged.

Items 4 to 9 above are **replaced by the amendment's acceptance criteria** below as approved on 2026-10-04.

### Slice 3 amendment, approved 2026-10-04

**Status: approved by the owner on 2026-10-04, with decisions U1 to U4 below. Slice 3 implementation has not started.** It changes no decision above. It settles what decisions 4, 6 and 8 left open for slice 3, corrects the data model rows for `DayPlan` and `Outcome`, and replaces slice 3's acceptance items 4 to 9 with the testable ones below. It does not touch closing calls, voice, autonomous decomposition or any M2 code.

**Sequence, unchanged:** (1) this amendment; (2) outcomes and carry-forward in `:core`; (3) storage, one schema 3 finished before any build containing it is installed on a device; (4) the outcomes screen; (5) the call link; (6) Pixel acceptance.

#### Outcomes, and how they relate to an action's status

**The rule: an action's `status` is the only record of whether it is open. An outcome is history, and may carry one marker, `closedAction`, meaning "this outcome is what closed the action".** Nothing derives status when it is read.

**Who writes status.** Verified in the code on 2026-10-04: production code writes an action's status in exactly two places. `PlanningStore.saveAction`, reached only from the Actions editor, rewrites the whole row, status included, and keeps no record of why. Slice 3's outcome recording is the second. (Import and Replace restore whole snapshots.)

**Why `closedAction` alone cannot tell them apart.** The manual path stores only a status. If an outcome closes an action (`closedAction = true`), and the user then reopens it and closes it again in Actions, the status is `done` again and the marker is still true, exactly as before the manual edits. Nothing in the row says the second closure was the user's. So the marker is reliable only together with this rule:

**Manual-write rule.** In the same transaction, `saveAction` compares the saved status with the stored one. If they differ, it sets `closedAction = false` on every outcome of that action. An edit that leaves the status as it was touches no outcome. After this, `closedAction = true` means the action's status has not been changed by hand since that outcome set it, so the action is `done`.

**Invariants** (also checked on import): `closedAction` is true only on a `DONE` outcome; at most one outcome per action has it; and it is true only while the action is `done`.

**Recording a result** is one transaction with its status effect; a refusal writes nothing. Eligibility is unchanged: a `pinned` or `queued` item of the latest revision of a plan whose active day is today or earlier. Left-out items and earlier revisions cannot have one. An item with no outcome counts as unknown.

- **`done` on an `open` action:** the action becomes `done` and the outcome stores `closedAction = true`.
- **`done` on an action that is already `done`, or is `dropped`:** accepted as history with `closedAction = false`, however the action got there (closed by hand, or by another outcome). The status stays as it is. A repeated `done` is never refused and never takes the marker from another outcome (U3).
- **Any other result, including `UNKNOWN`:** recorded; no status change.

An outcome is an **effective DONE** while its current row says `DONE`. A correction replaces the row, so a result corrected away no longer counts.

**Correcting an outcome** replaces its row in one transaction. Corrections are always available. `UNKNOWN` is a valid correction and acts as a reset: the item then counts as having no recorded outcome (U4).

- **A marked closing outcome (`closedAction = true`) corrected to anything but `done`:** its marker is cleared, and the action is reopened **only if no other outcome of that action is an effective DONE**. If one remains, the status is preserved. No marker moves to another outcome. A manual status change needs no separate check here: it has already cleared the marker, so a status the user set by hand is never reached by this rule.
- **Any other outcome corrected** (its marker is false): no status change.
- **A correction to `done`:** the recording rules apply.

**Known consequence, documented (U3).** When another effective DONE keeps the action closed, correcting that remaining DONE later does not reopen the action, because it never held the marker; the action stays `done` until the user reopens it in Actions. This is deliberate: a rule that reopened on a result it cannot tell is stale would override the user's status, and a manual reopen is always available. The outcomes screen explains it in one short line whenever a correction away from `done` leaves the action `done`: "The action stays done. Reopen it in Actions if it is not."

**Adjusting after outcomes (U1, approved).** A new revision of a day, by Adjust or by approving again, is refused while the day's current revision has an **effective recorded outcome**: a row whose result is not `UNKNOWN`. The refusal is a plain message and loses nothing. Correcting every outcome back to `UNKNOWN` lifts it, so no delete is needed (U4). Keep original is unaffected.

**Unfinished membership, exact.** On planning day D, an action is offered if and only if both hold: its status is `open`, and it has a `pinned` or `queued` item in the **latest revision** of some **earlier** active day. Outcomes never filter the list.

| Result recorded on its latest earlier item | Offered? |
|---|---|
| `done` | No while the action is `done`, which a `done` outcome closes. Yes if the user reopened it by hand, because the status decides |
| `partial`, `skipped`, `blocked` | Yes: the action is still open |
| `unknown`, or no outcome | Yes |

An action placed on several earlier days appears **once**. Its annotation, "last time: <result>" or "no result recorded", comes from its item in the latest of those days. Older days' results are not shown. No count or badge, as decision 4 requires.

**Which earlier rule this keeps and changes.** Kept: slice 2's membership (open actions an earlier day's latest plan placed), and decision 4's intent that partial, skipped, blocked, unknown and unrecorded actions are offered, and that `done` closes. Changed: decision 4 filtered by the last outcome. It is now filtered by status, so the one case that differs is an open action whose last outcome is `done` because the user reopened it: it is offered, where decision 4's text would have hidden it. The comment on `unfinishedFor` that anticipates an outcome filter is updated when it is implemented.

| Sequence | Marker | Action | Result |
|---|---|---|---|
| Record `done` on an open action, then correct it to `partial` | true, then cleared | open, `done`, open | Closed, then reopened |
| Close it by hand, then record `done`, then correct to `skipped` | false throughout | `done` throughout | Stays closed: the closure was the user's |
| Record `done`, then drop it by hand, then correct to `partial` | cleared by the drop | `dropped` throughout | Stays dropped. Reopening and closing it again by hand also clears the marker, so it stays `done` |
| Repeated `done`: day 1 closes it, day 2 is recorded `done` as history, then correct day 1 to `partial` | day 1 cleared | `done` | Stays `done`: day 2's `done` remains effective |
| Then correct day 2 to `partial` | false | `done` | Stays `done`, with the one-line note; the user reopens it in Actions |
| `done` on the only `done` item, then corrected to `UNKNOWN` | cleared | `done`, then open | Reopened; the item counts as having no outcome |
| Every outcome of the day is `UNKNOWN` | none | unchanged | A new revision is allowed again |

#### Minimal data contracts (schema 3, additive)

`MIGRATION_2_3` creates two tables and changes no existing table or row. The existing plan tables stay immutable.

| Table | Columns | Rules |
|---|---|---|
| `plan_outcomes` | `planItemId` (primary key, references `plan_items`), `result` (`DONE`, `PARTIAL`, `SKIPPED`, `BLOCKED`, `UNKNOWN`), `recordedAtMillis`, `closedAction` | One row per item; correcting replaces it. `closedAction` follows the invariants above, and a status-changing manual save clears it. |
| `plan_calls` | `planId` (primary key, references `day_plans`), `openingTime` (`HH:mm`), `status`, `detail` (nullable code), `updatedAtMillis` | Written in the approval transaction only when the plan has an opening time, with status `REQUESTED`. **No row means no opening call: `NONE`.** `openingTime` is never changed; a new time is a new revision. Only `status`, `detail` and `updatedAtMillis` change. |

- **Status** is one of `REQUESTED`, `CONFIRMED`, `REFUSED`, `NEEDS_ATTENTION`, `NOT_LINKED`.
- **Detail** is one of `SLOT_OFF`, `ZONE_CHANGED`, `TIME_NOT_ALLOWED`, `IN_QUIET_PERIOD`, `TIME_PASSED`, `PERMISSION_REFUSED`, `OTHER_TIME_CONFIRMED`, `CALL_CANCELLED`, `CALL_ENDED`, `CALL_IN_PROGRESS`, `M2_UNAVAILABLE`. It never holds free text, titles or M2's message.
- **The stored status is a log of what the link last did. It authorizes nothing.** The screen's preview reads M2's slot read-only each time and shows that live state beside it.
- The `DayPlan` row's `openingTime`, `callStatus` and `callDetail` in the data model table are these columns, not columns of `day_plans`. Plans approved in slice 2 have no `plan_calls` row.
- Room's schema file `3.json` is committed with the migration, and the migration is tested on a real schema 2 database built from `2.json`.

#### The call link

Applies to the opening call only, for the latest revision of active day D with an opening time T, in the plan's zone. It is in `:app`, built on M2's existing public types, and changes none of them.

**Preconditions, checked in this order, nothing recorded in M2 if one fails (status `REFUSED`):**

1. M2's stored schedule is complete and names one zone, equal to the plan's zone (`ZONE_CHANGED`, `M2_UNAVAILABLE`).
2. T is at or after the rollover and before midnight on calendar date D (`TIME_NOT_ALLOWED`).
3. The opening slot is switched on (`SLOT_OFF`).
4. T is not inside the stored quiet period (`IN_QUIET_PERIOD`).
5. T is in the future (`TIME_PASSED`).

All are read-only, and none uses `OccurrenceStore.plan()`, which creates a default plan.

**Create-if-absent.** Read the slot's latest revision (`store.slot(...)`).

| The slot holds | The link does | Result |
|---|---|---|
| nothing | `OccurrenceScheduler.schedule(revision 1 at T)` | |
| | `Scheduled` | `CONFIRMED` |
| | `AlreadyRecorded` (another path got there first) | classify the slot as the rows below |
| | `NotPermitted` or `NotInTheFuture` (arming refused, revision 1 now cancelled) | `NEEDS_ATTENTION`, `PERMISSION_REFUSED` or `TIME_PASSED` |
| a scheduled call at T | nothing | `CONFIRMED` |
| a scheduled call at another time | nothing | `NEEDS_ATTENTION`, `OTHER_TIME_CONFIRMED` |
| a cancelled or missed call that never rang | nothing | `NEEDS_ATTENTION`, `CALL_CANCELLED` |
| a call that rang, or ended after ringing | nothing | `NEEDS_ATTENTION`, `CALL_ENDED`, with no action offered: one call per slot |
| a ringing, answered or claimed call | nothing | `NEEDS_ATTENTION`, `CALL_IN_PROGRESS` |

Only an empty slot is ever written, and only by revision 1's insert, which ignores a taken id, so a duplicate is impossible whatever interleaves. The link never moves, cancels or revives a call.

**Explicit recovery.** For `OTHER_TIME_CONFIRMED` and `CALL_CANCELLED` the screen offers "Move the call to T" or "Confirm a new call for this day". Each needs the user to tick a box and press the button, and each calls **`CheckInConfirmer.reschedule(OPENING, D, T)`**, never `OccurrenceScheduler.reschedule`, which returns `NotScheduled` for a cancelled revision. M2 itself moves a scheduled call or records the next revision of a recoverable one. M2's result is mapped and shown: `Confirmed`, `Rescheduled` or `Unchanged` give `CONFIRMED`; `NotPermitted` gives `NEEDS_ATTENTION`, `PERMISSION_REFUSED`; `InQuietPeriod`, `TimePassed` and `SlotOff` give `REFUSED`; `InProgress` and `AlreadyConfirmed` give `NEEDS_ATTENTION` with M2's state. This is the only code that ever reschedules, and it never runs without the user present.

**"Try again"** for a `REFUSED` plan re-runs create-if-absent only. It never reschedules.

**Used-up revision 1 (blocker 2).** Resolved by the `NEEDS_ATTENTION` rows above and the explicit recovery, which are the only way forward. M2's `place` already recovers a recoverable slot, so nothing in M2 changes. Slice 3's create-if-absent meets `AlreadyRecorded` there and must not try to recover on its own.

**Disabled-slot gap (blocker 1), resolution.** M2 is not changed. Verified in the code on 2026-10-04: `CheckInFireHandler` does not check a slot's `enabled`; `OccurrenceStore.savePlan` and `CheckInPlan.withEnabled` have no non-test caller; the only other writer to the schedule rows is `insertMissing`, which writes the enabled default; the stored rows are both enabled. So no code path can disable a slot while a call is being created, and the precondition above (3) is the only check needed. This is a statement about the code at that date, recorded as review evidence, not enforced by a test that reads source. **Standing precondition:** no change that can switch a slot off, or that restores or imports schedule rows, may be introduced until M2 re-checks `enabled` when the alarm arrives, as a separate approved M2 change with its own device validation. **Owner decision U2, approved 2026-10-04:** M2 stays unchanged in slice 3, and the verification and this precondition stand. A compensating cancel in the planning layer and an M2 change now were considered and not chosen.

#### Retry after reconciliation

M2's launch reconcile runs in `MainActivity.onCreate` as `thalia.ioScope.launch { thalia.reconcile(ReconcileTrigger.APP_LAUNCH) }`. The retry runs **in that same coroutine, after `reconcile` returns**, so the occurrence rows and alarms are already reconciled when it reads them. It runs when the user opens Thalia and not from an alarm start or a boot, so it is not unattended recovery, as decision 6 says.

- **Eligible:** the latest revision of a plan whose active day is today or later, with status `REQUESTED`. Each is processed once per launch. If T has passed by then, precondition 5 records `REFUSED`, `TIME_PASSED`, so no plan stays `REQUESTED` indefinitely.
- **What it does:** the create-if-absent path above, nothing else. A `CONFIRMED` result after M2 had already armed the call (crash windows 3 and 4) is recorded without writing to M2.
- **Never processed automatically:** `CONFIRMED`, `REFUSED`, `NEEDS_ATTENTION`, `NOT_LINKED`. Never reschedules, revives or cancels, whatever M2 holds. (Amended 2026-10-04: `REFUSED` and `NEEDS_ATTENTION` may have their displayed status reconciled, below.)

**Amended 2026-10-04: crash model and status reconciliation.** Narrow, and it changes no decision above.

- **Only `REQUESTED` permits automatic create-if-absent processing.** `REQUESTED` is written only by the user's tick and confirm (`authorizeOpeningCall`). It is committed in its **own** planning transaction, **before** any M2 effect, so that a failure after the M2 effect leaves `REQUESTED` durable for the launch retry, as decision 6's crash windows require. An implementation must not put the authorization in the same transaction as the M2 effect, because a rollback would then discard the authorization and leave M2 changed with the plan saying `NOT_LINKED`.
- **The M2 effect runs under a planning transaction fence,** which holds out every other planning write from the read of the authorizing state (latest revision, call status, day by the clock now) to the recorded result, inside the `PlanningAccess` lock that Import, Replace and set-aside also take. The order is always that lock, then one planning transaction, then M2, and M2 never reaches planning. The fence is not an atomic commit across the two databases: after an M2 effect and before the planning commit, a failure leaves M2 changed and the planning status as it was.
- **The status may then be stale** after an explicit recovery or a "Try again" whose planning commit was lost: `NEEDS_ATTENTION` or `REFUSED` while M2 already holds the call at the plan's time. **Read-only reconciliation:** at launch after the retry, and when the plan screen sees that state, the link re-reads the latest revision, the stored call, the day and M2 under the fence. If the call is `REFUSED` or `NEEDS_ATTENTION`, the plan is the latest revision of a day that is today or later, every precondition above holds, and the slot's newest revision is **scheduled at the plan's time**, it records `CONFIRMED`. Otherwise the status stays as it was. It writes nothing to M2, never creates, moves, revives or cancels a call, and never grants or uses an authorization. `NOT_LINKED` rows, including every imported one, are never touched by it.
- **`CONFIRMED` means the live slot matches the intended time, not that this plan created the call.** Another path, an earlier revision of the plan or the user's own confirmation may have created it.
- **Expiry.** A `REQUESTED` call whose active day has passed can no longer ring, so it is recorded as `REFUSED`, `TIME_PASSED`, without any M2 write.

#### Export and import

Payload schema 3 adds `outcomes` (`planItemId`, `result`, `recordedAtMillis`, `closedAction`) and, on each plan, `openingTime` (a string or null), with counts. **It does not carry `status`, `detail` or `updatedAtMillis`, which are facts about this phone's M2.** This replaces "the export payload gains ... call status" in slice 3 item 8.

- **Import and Replace write `NOT_LINKED`** for every plan that has an opening time, and no `plan_calls` row for one that has none. Nothing is written to `checkins.db`, and `NOT_LINKED` is never `REQUESTED`, so the retry cannot create or move a call from imported data. A call exists on the importing phone only after the user ticks and confirms it, through the explicit recovery above, for a day that is today or later. For a past day the screen shows `NOT_LINKED` with no action.
- **Schemas 1 and 2 still import:** migrated in memory to no outcomes and no opening times. Every future schema must again add a tested migration from each earlier one, as decision 8 requires.
- **Validation added:** result is one of the five; each outcome's item exists and is not left out; one outcome per item; `closedAction` only with `DONE`, at most one per action, and only where the imported action is `done`; the counts match; `openingTime` is `HH:mm` if present.
- **The `recoveryCheck` fingerprint** covers outcomes (with `closedAction`) and each plan's `openingTime`, and excludes call status, detail and time, which are not exported. Equality of a seeded fingerprint after restore is therefore a fidelity check of the exported fields, and a separate check shows every restored plan with an opening time as `NOT_LINKED`.

#### Acceptance criteria, replacing items 4 to 9

Every test asserts behavior through an executable interface, against M2's `InMemoryOccurrenceStore` and recording doubles for the scheduler and alarms. **None reads source text.** (The slice 2 test that scans for an update path to the plan tables predates this rule and is not extended.)

1. **Outcomes.** Every row of the transition table above, as separate sequences: repeated `done`, then correction of the closing result; then correction of the remaining `done` afterward; `done` to `UNKNOWN`; every outcome `UNKNOWN` permitting a new revision again; and manual status edits keeping their authority over a later correction. A manual save that changes the status clears the marker and one that does not change it leaves it; a repeated `done` is accepted as history and moves no marker; a marked closing outcome corrected away from `done` reopens the action only when no other effective DONE remains. After random sequences of record, correct, manual save, manual reopen and manual drop, the three invariants hold and a closure made by hand is never reopened by an outcome. Recording on an earlier revision, a left-out item or a future day is refused. A new revision is refused while any outcome other than `UNKNOWN` is recorded and allowed again once all are `UNKNOWN`. Unfinished membership matches the table above, including one action placed on several earlier days.
2. **Migration and export.** `MIGRATION_2_3` on a real schema 2 database leaves every row identical; a schema 1, 2 and 3 export imports; the round trip holds; restored plans are `NOT_LINKED`, and a restore never writes to `checkins.db` or arms an alarm.
3. **Create-if-absent.** Every row of the classification table maps as stated, and the call counts are exactly as written (one `schedule` call at most, never `reschedule`, `cancel` or `supersede`).
4. **Races.** With a hook between the link's read and its insert, each of these happens and the result is `CONFIRMED` or `NEEDS_ATTENTION`, never a changed call: another path records revision 1 at T (one occurrence in total); at another time (not moved); a user cancel lands first (not revived); a move lands first (not moved back); the slot is switched off, the quiet period is set or the time passes between the checks and the insert (the existing fire-time and arming checks decide, and the link records what M2 reports).
5. **Crashes.** The process is stopped after each durable step of decision 6's four windows, then the retry runs: the slot ends with exactly one occurrence, the status is `CONFIRMED`, and the scheduler double saw no `reschedule`.
6. **Retry.** Runs only after the launch reconcile returns, only for eligible plans, once each; never for `CONFIRMED`, `REFUSED`, `NEEDS_ATTENTION` or `NOT_LINKED`.
7. **Explicit recovery.** Without the tick nothing is called; with it, `CheckInConfirmer.reschedule` is called once and M2's result is mapped as stated. A used-up revision 1 moves to revision 2 only this way.
8. **Pixel, over one real day.** Before the build is installed: an export, an M2 snapshot, and the real app's planning screens read. After the in-place update: M2 identical, planning data identical, and the pre-upgrade export imports into `recoveryCheck` with the same counts. Then a plan with an opening time approved the evening before, the scripted call ringing at that time, outcomes recorded, and the next day offering the unfinished actions.
9. **M2 comparison permits only the authorized change.** Against the pre-approval snapshot, the logical diff must show: `schedules`, `quiet_period` and `room_master_table` identical; every earlier `occurrences` row identical in every column; and exactly one added row, the opening slot's revision 1 at the plan's time, whose later changes are only the ring and its end. Exactly one pending alarm for that occurrence before the ring and none after, with none from any other source. Any other difference fails the acceptance. A second snapshot after the recovery round trip shows no M2 change at all.

#### Owner decisions

All approved on 2026-10-04.

- **U1:** refuse a new revision of a day whose current plan has an effective recorded outcome. Outcome correction stays available.
- **U2:** leave M2 unchanged; keep the no-disabling-path precondition; require M2's fire-time `enabled` check before any disabling is introduced.
- **U3:** a repeated `done` is accepted as history; it is never refused, and closure ownership never moves. Correcting the marked closing outcome reopens only if no other effective DONE remains and no manual status change has cleared its marker; otherwise the status is preserved and that marker is cleared. After another DONE has kept the action closed, correcting the remaining DONE may need a manual reopen, which the outcomes screen says in one short line.
- **U4:** an explicit `UNKNOWN` is a valid correction and a reset. It counts as no effective recorded outcome for U1.

## Known limitations of the minimal loop

- **Opening calls before midnight only.** The opening call of active day D must be at or after the rollover and before midnight, on calendar date D. An opening between midnight and the rollover, such as 02:00, would fall on M2's slot for calendar date D + 1, and the minimal loop refuses it.
- **The late-night closing collision.** The minimal loop confirms only openings. If closing calls are linked later, a closing at 00:30 for active day D and one at 23:30 for D + 1 share M2's slot for calendar date D + 1: while the first is scheduled, `reschedule` for the second would move it; once it has rung, the second is refused. A later closing link must solve this before it ships.
- **Travel.** Plans and calls use M2's stored zone. Confirmed calls are fixed moments in that zone. When the phone's zone differs, the plan screen says so; nothing is rebased, and M2 is not changed.
- **No unattended call recovery,** as decision 6 explains.
- **Export is manual.** Data changed since the last export is lost if the phone is lost. There is no reminder, schedule or cloud sync.
- **A forgotten export password cannot be recovered.**

## Milestone clarifications

Applied on acceptance, in specification section 18 and `voice-checkins.md`:

- **M3, live voice foundation.** M3A is complete. M3 is not. Further voice work, provisionally M3B, follows the minimal M4 and M5 loop. Its first purpose is to make the check-in conversation a second producer of the loop's typed proposals and outcome reports, behind the same validator and approval. Its scope is defined when it starts.
- **M4, personal information foundation.** Minimal: direction, goals, projects and actions in an encrypted local store, with a user-triggered encrypted export and import (ADR 0009, slices 1A and 1B). Full: areas, ideas, notes, routines, events, the capture inbox, the Share Sheet and microphone capture.
- **M5, planning and execution loop.** Minimal: the deterministic daily proposal, approval, outcomes, carry-forward and the opening-call link (ADR 0009, slices 2 and 3). Full: the Today timeline, focus blocks with times, replanning after meaningful events, undo, closing-call planning.
- **The voice-checkins PRD's milestone table** (`THALIA_VOICE_CHECKINS_PRD.md`, section 5) is superseded by specification section 18; its M4, M5 and M6 meanings no longer apply.
- **Do Not Disturb and a call already in progress,** which ADR 0002 assigned to M5 under the old meaning, move to the M2 hardening backlog.

## Consequences

Positive:

- The loop is useful with no AI, no network and no account, and its data can leave the phone safely.
- One validator and one approval path exist before any AI or voice producer.
- M2's code, database and migrations do not change.

Negative and accepted:

- A native dependency and a second database, for encryption that meets specification section 10.
- No transaction across the two databases. The call status, create-if-absent and the separate display make the gap visible and safe; they do not remove it.
- The export is only as current as the user's last export.
- The screens are functional, not the final design.

## Approvals and blockers

- **Accepted 2026-10-02,** with the SQLCipher dependency pinned at `net.zetetic:sqlcipher-android:4.19.1`.
- **Slices 1A and 1B:** accepted on the Pixel 10 on 2026-10-03, including the Pixel steps of slice 1B ([results](../m4-slice1-device-results.md)). Remaining limits, none blocking: real power loss, other document providers, real cloud backup and device transfer, and the alarm with the phone locked or after a reboot on this build.
  **Personal planning data is permitted after Slice 1 acceptance,** with the encrypted export available and the limitations above documented. Editing the synthetic direction left in the real app is housekeeping, not an additional acceptance requirement.
- **Slice 3:** the two blockers in decision 6, the disabled-slot gap and the used-up revision 1, must be resolved before it ships. The slice 3 amendment, approved by the owner on 2026-10-04 with decisions U1 to U4, says how.
