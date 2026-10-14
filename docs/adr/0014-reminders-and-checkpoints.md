# ADR 0014: One-shot reminders and checkpoints

Status: **Implementation and computer checks accepted; Pixel validation deferred** (2026-10-10). Computer tests only; nothing here has been seen on a Pixel, and Pixel delivery is unverified. Branch `v1/slice2-day-loop`, after `208162c`.

The product defaults below (7-day horizon, 20 pending, 60-minute delivery window, quiet-period rule) are chosen for this slice. They are not inherited from the specification, which defines no reminders.

## What it is

A **reminder** is a one-shot notification the person asked for, at a time they chose, about an Inbox item, an action, or nothing in particular (a **checkpoint**). Thalia posts one generic notification and then waits. It never repeats, snoozes, escalates, asks a question, calls a model or changes any plan.

Deferred, on purpose: snooze, recurrence, voice-specific entry, configurable lock-screen text, and AI scheduling. The model still cannot schedule a reminder (ADR 0013).

## Time policy: a fixed instant

A reminder is stored as an **instant** (epoch milliseconds) with the zone it was created in, kept for display only.

- A change of **time zone** does not move it. "Around 15:00" set in one zone fires at that same instant after travel and is shown in the current zone, with the creation zone noted when it differs. This is the right behaviour for "remind me in an hour or at 15:00 today", and the wrong one for "every morning at 8", which is recurrence and is deferred.
- A change of the **clock** (TIME_SET) re-runs reconciliation. The alarm is rearmed for the same instant. If the clock was set forward past the instant, the reminder is past its delivery window or inside it, and is judged by the same rule as any late delivery.
- Daylight saving does not matter to an instant. A time that does not exist or exists twice on a given wall clock is resolved once, when the person enters it, by `ZonedDateTime.of` (the first occurrence, a gap moved forward); the stored value is then fixed.

## Scheduling: inexact, labelled "around"

`AlarmManager.setAndAllowWhileIdle`. No exact-alarm permission is needed, requested or consulted, so revoking "Alarms & reminders" changes nothing for reminders. Android may deliver later than asked and never earlier. The screens say "around 15:00".

## Delivery rule

When the alarm is delivered, in one transaction, only if the reminder is still SCHEDULED:

1. More than **60 minutes** after the instant: `NOT_POSTED / WINDOW_EXPIRED`. Nothing is shown late.
2. The moment of **delivery** falls in the user's quiet period (the M2 setting, read at delivery, on the wall clock of the zone the phone is in then): `NOT_POSTED / QUIET_PERIOD`. Creation refuses a due time inside the period for the same rule, so this fires only when delivery drifted into it or the setting changed.
3. Notifications for Thalia or the reminder channel are blocked: `NOT_POSTED / NOTIFICATIONS_BLOCKED`.
4. Otherwise the notification is posted, then the row is claimed `SCHEDULED` to `POSTED` by compare-and-set. Posting is idempotent for one reminder (same tag and id), so a duplicate delivery replaces rather than adds. If the claim fails because the reminder was cancelled or resolved meanwhile, the notification is withdrawn. If the platform throws while posting, the row becomes `NOT_POSTED / NOTIFICATIONS_BLOCKED`, never `POSTED`.

Reasons are things Thalia **observed**. It does not infer that the phone was off, only that the window had passed.

## State (planning.db, schema 6)

Table `reminders`: `id`, `targetType` (NONE, INBOX_ITEM, ACTION), `targetId` (null for NONE), `label` (a snapshot, at most 120 characters, shown only inside the app), `dueAtMillis`, `zone`, `status`, `reason`, `postedAtMillis`, `resolvedAtMillis`, `createdAtMillis`. No foreign key: a reminder never blocks, and is never changed by, what happens to its target.

| Status | Shown as | Next |
| --- | --- | --- |
| SCHEDULED | Waiting, around HH:mm | POSTED, NOT_POSTED, CANCELLED |
| POSTED | Notification posted; response unknown | ACKNOWLEDGED, DISMISSED |
| ACKNOWLEDGED | You acknowledged this reminder | none |
| DISMISSED | You dismissed this reminder | none |
| CANCELLED | Cancelled before it was due | none |
| NOT_POSTED | Not posted: and the observed reason | none |

POSTED never times out into anything. A reminder nobody answered stays "response unknown" indefinitely, with no retry and no second notification. Acknowledge means only that the person acknowledged the reminder. Neither Acknowledge, Dismiss nor Cancel touches an Inbox item, an action, a plan, capacity or an outcome. They write to `reminders` and nothing else.

## Limits

Due between 1 minute and 7 days ahead, outside the quiet period; at most 20 SCHEDULED at once; a label of at most 120 characters. A refusal says why and writes nothing.

## Targets

A target is chosen from open Inbox items and open actions. The label is copied, so the list reads correctly if the target later changes or goes (an Inbox capture can be undone; an action is never deleted). A reminder whose target is gone still fires and still shows its label, with "the original is no longer there". Nothing in the plan data is read or written to deliver or show a reminder.

A **checkpoint** has no target. Its notification opens the planning screen, which shows the current day's plan as it always does. Opening it opens and closes no day.

## Notification

Channel `thalia_reminders`, importance LOW: no sound, no vibration, no full-screen intent, no heads-up. The title and text are always generic ("Thalia reminder", "Open Thalia to see it"), so the lock screen shows nothing of the person's text. Tapping opens the planning screen and changes no state. There are no action buttons.

## Permission

The notification permission is requested when the person first sets a reminder, never at launch. If it is denied or the app's notifications are blocked, no reminder is created and the screen says so. If notifications are turned off after creation, delivery records `NOTIFICATIONS_BLOCKED`.

## Recovery

Alarms are derived state and the table is authoritative.

- The fire handler acts only on a row that is SCHEDULED, and claims it with a compare-and-set, so a duplicate or stale delivery posts nothing and cannot touch a resolved reminder.
- **Reconciliation** (launch, boot, package replaced, time set, zone change, and after any import or replacement) reads the table and: judges any SCHEDULED reminder past its window as `NOT_POSTED / WINDOW_EXPIRED`; arms the rest; cancels every alarm in a small registry of armed ids that is no longer SCHEDULED; and withdraws any reminder notification whose row is not POSTED.
- Cancelling a reminder cancels its alarm first-class, then records CANCELLED.
- Import and database replacement carry the table as it was exported. Resolved rows stay resolved and a POSTED row stays "response unknown". Only SCHEDULED rows are rearmed, by reconciliation, and no more than 20 can be imported.
- Reconciliation does not run while planning data is unavailable, and is skipped when no planning database exists. After a restart that is not yet unlocked, nothing is armed until the first unlock (the boot broadcast Thalia listens to is the post-unlock one).
- Import and replacement run reconciliation through a hook on `PlanningAccess`, inside its lock. A failure of the hook does not fail the import; the next launch or boot reconciles.
- A notification the person has not touched is withdrawn when its row is no longer `POSTED`, and is never reposted by reconciliation.

## Export and import

Payload schema 6 adds `reminders`. Schema 1 to 5 payloads still restore, with no reminders. A schema 5 file carrying a `reminders` key is refused. An import with more than 20 waiting reminders is refused. A reminder whose target is not in the file is accepted.

## Not established by computer tests

Whether Android delivers the alarm, how late, whether the notification shows on a Pixel, how the permission prompt behaves, what a reboot does on the device, and how the lock screen looks. See the proposed device checklist in the Slice 5 results note.
