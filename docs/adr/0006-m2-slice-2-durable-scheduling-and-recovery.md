# ADR 0006: M2 slice 2, durable scheduling and recovery

Date: 2026-09-19
Status: Accepted, implemented, and validated on one physical Pixel 10 on 2026-09-29 and 2026-09-30. See [the device results](../m2-slice2-device-results.md).
Milestone: M2, second slice
Branch: `spike/voice-checkins`
Builds on: [ADR 0005](0005-m2-slice-1-real-alarm.md), whose decisions all stand, and [ADR 0002](0002-voice-checkins-foundation.md) decision 8, which put schedules and occurrences in Room from M2.
Amends: [ADR 0005](0005-m2-slice-1-real-alarm.md) decision 4, which said a dropped call leaves no record, and its consequence that an armed alarm is lost on reboot.

Slice 1 proved that Android can start the M1 call from an alarm with the app closed.
It left three things open.
Nothing was written down, so a dropped call left only a log line.
An armed alarm did not survive a restart of the phone.
And the app could not tell that its alarm had been lost.

This slice closes those three.
It adds a durable record of every scheduled occurrence and what became of it, and one path that brings that record and Android's alarms back into agreement after anything that can upset them.
It does not add the recurring 10:00 and 15:00 schedule, quiet hours, per-occurrence rescheduling, `WorkManager`, or any network or AI.

## Decisions

### 1. Room, through KSP, with the schema exported

The database is Room 2.8.5, processed by KSP 2.3.12.
Both work with the build's AGP 9 and Kotlin 2.4.20.
The schema is exported to `app/schemas` and committed, so a later migration can be checked against the exact schema that shipped.

The build's memory limits are set for a machine with about 3.5 GB, which is what WSL gets on this laptop.
The Gradle daemon has a 1536m heap and a 1g metaspace limit, the Kotlin daemon has a 768m heap, tasks run one at a time, and at most two workers run.
Room's processor and the Kotlin compiler together ran the daemon out of metaspace at the old 512m limit, so that limit was raised.
A 3g daemon with the Kotlin daemon beside it then exhausted memory and the kernel killed the build, so the heap was cut back.
The whole verification (both test suites, ktlint, Android lint and the debug APK) passes inside those limits, but only just: the machine's swap filled while it ran.

Room is tested on the computer with Robolectric, pinned to API 36, against real SQLite.
Robolectric needs the JUnit 4 runner, so the JUnit vintage engine runs beside the JUnit 5 tests on the same platform, at the version that matches.
On Java 17 and later Robolectric also needs one module export on the test JVM, which the build sets.

### 2. What is stored

Two tables.

| Table | Holds |
| --- | --- |
| `occurrences` | One row per occurrence: id, type, the moment it is due, its zone, its status, its reason, and the two moments the store keeps, fired and resolved. |
| `schedules` | The plan: one row per check-in slot, with its local time, its zone and whether it is on. |

Enums are stored by name and moments as epoch milliseconds, so the file can be read by eye and a later change has nothing clever to migrate.

The domain type is the authority on what a valid row is.
`ScheduledOccurrence` cannot be built in a shape that contradicts its status, so a stored row that does is refused when it is read rather than trusted.
A scheduled occurrence has not fired.
Ringing, answered, declined, completed and failed ones have.
A missed one may or may not have.
A resolved time is set exactly when the status is terminal, and a reason exactly when the status needs one.

The stored plan is created from the M0 default on first use, both slots on, in the device's zone at that moment.
The switch that keeps the feature off until the user consents belongs with the consent screen, which does not exist yet.
In this slice the plan has one effect: a slot that is switched off has its scheduled occurrences cancelled.
Nothing generates occurrences from the plan, so there is still no 10:00 and no 15:00.

### 3. The life of an occurrence is in `:core`

`CheckInStatus.canTransitionTo` holds the whole table, and a test asserts every pair against it.

| From | May become |
| --- | --- |
| Scheduled | Ringing, missed, cancelled |
| Ringing | Answered, declined, missed |
| Answered | Completed, failed |
| Completed, declined, missed, failed, cancelled | Nothing |

An occurrence changes only through `transitionTo`, which refuses an illegal move.
A call cannot be answered without ringing, and cannot be completed without being answered.

Missed, cancelled and failed must say why, from `OccurrenceReason`.
Each reason records what was observed and no more.

| Reason | Ends | Means |
| --- | --- | --- |
| `RING_TIMEOUT` | Missed | It rang for the full timeout and nobody answered. |
| `TOO_LATE` | Missed | Its alarm was delivered over two minutes after its moment. |
| `ALARM_MISSING` | Missed | Its moment had come and Android had no alarm registered for it. The cause is not established. |
| `OVERDUE` | Missed | Its moment passed by over two minutes and it never rang, although an alarm still appeared registered. The cause is not established. |
| `START_REFUSED` | Missed | Android refused to start the call service. |
| `INTERRUPTED` | Missed or failed | It was ringing or in progress when the process carrying it was lost. |
| `CALL_IN_PROGRESS` | Missed | Another call already had the phone. |
| `SESSION_FAILED` | Failed | The voice session could not run or broke. |
| `PLAN_DISABLED` | Cancelled | Its slot was switched off. |
| `NOT_PERMITTED` | Cancelled | It could not be armed, because exact alarms were not allowed. |
| `NOT_IN_THE_FUTURE` | Cancelled | It could not be armed, because its moment had passed. |
| `USER_CANCELLED` | Cancelled | It was withdrawn, or replaced by a newer one, before it rang. |

There is deliberately no reason that names a force-stop or a restart.
Each leaves the same trace, an alarm that is not there, and the record must not claim more than it knows.
A test asserts that a force-stop and a restart produce the same reason.

### 4. One atomic claim, made before anything rings

Every change to an occurrence is a single conditional `UPDATE` that names the status it expects to find and reports whether it found it.
SQLite decides one winner, so the store holds no lock of its own.
A caller that loses is told, and does nothing further.

The claim is the move from scheduled to ringing.
`CheckInFireHandler` makes it before it starts the call, and starts the call only if it won.
A second delivery of the same alarm, a delivery after the reconciliation marked the occurrence missed, and a delivery for an occurrence that was never recorded all lose the claim and ring nothing.

The order inside the handler is deliberate.
The lateness rule comes first, so an alarm over two minutes late is recorded missed as too late without being claimed.
The claim comes second.
If Android then refuses to start the call, the occurrence is recorded missed as start refused, rather than left claiming to ring.

Slice 1's rule that a debug alarm rings is unchanged, because the debug control now records its occurrence before arming.
An alarm for an occurrence with no record does not ring.
An alarm left armed by the slice 1 build across an update is such a case, and is dropped.

### 5. The call reports its outcome, in order

`CheckInCallCoordinator` tells a `CallOutcomeRecorder` at each moment that decides the outcome: answered, declined, missed, finished.
It never waits for it, so recording cannot slow or break a call.
A call with no stored occurrence, such as one started by a button, reports as normal and finds nothing to update.

`LedgerCallOutcomeRecorder` writes the reports through one queue and one worker.
Order matters here.
An unpaced call is answered and finished in the same instant, and a launch per report could apply the finish first and have it refused.
The moment of each report is read when it is made, not when it is written.

Two outcomes are recorded differently from how the screen words them.
A call that the system cut short is recorded failed as interrupted, where the existing screen status calls it completed.
A ring that cannot ring because a call is already in progress is recorded missed for that occurrence, and the call in progress is untouched.
The screen's own wording and status mapping are unchanged.

### 6. One reconcile path, for every trigger

`Reconciler.reconcile` is the only path that brings the stored occurrences and Android's alarms back into agreement.
It does the same work for every trigger, so it can run twice, or from two triggers at once, and land in the same place.
It never rings a call: it holds a store and an alarm scheduler and has no way to start one.

For each occurrence that has not ended:

| Situation | What it does |
| --- | --- |
| Scheduled, its slot switched off | Cancels it and disarms its alarm. |
| Scheduled, in the future | Arms its alarm again. Arming replaces, so nothing is doubled and a lost alarm comes back. Without the permission it is left scheduled and counted as unarmed. |
| Scheduled, due, no alarm registered | Missed as alarm missing. |
| Scheduled, over two minutes past due, an alarm still registered | Missed as overdue, and the alarm is disarmed so it cannot ring late. |
| Scheduled, within two minutes past due, an alarm registered | Left alone. Delivery is about to ring it. |
| Ringing or answered, nothing in this process carrying it | Missed or failed as interrupted. |
| Ringing or answered, claimed within the last 15 seconds | Left alone, because the call may not have started yet. |

Android offers no way to ask whether an alarm is registered.
`isArmed` answers by whether the alarm's `PendingIntent` still exists.
The device run showed Android cancels that on a force-stop, and it is dropped when the phone restarts.
A `true` is weaker than a `false`, because nothing rules out Android removing an alarm while keeping its intent.
That is why a future alarm is re-armed whatever `isArmed` says, and why the reason for an overdue one with an alarm still apparently registered is `OVERDUE`, which claims nothing about the cause.

Every move is a conditional update, so when the alarm receiver or the call gets to an occurrence first the reconciliation loses that move and records nothing for it.
A test stages exactly that race.

### 7. Six triggers, one receiver

| Trigger | Arrives as |
| --- | --- |
| App launch | `MainActivity` starting, not any component starting, so a cold alarm delivery does not also reconcile beside its own claim. |
| Reboot | `BOOT_COMPLETED` |
| Package replaced | `MY_PACKAGE_REPLACED` |
| Time changed | `TIME_SET` |
| Time zone changed | `TIMEZONE_CHANGED` |
| Exact-alarm permission granted | `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` |

A seventh, Reconcile now, is a debug button.

`SystemEventReceiver` handles the five system broadcasts.
It is not exported, because only the system sends them.
It starts no foreground service, so the limits on starting one from a boot broadcast do not apply.
Both receivers use `goAsync`, so the database work can run off the main thread while the broadcast stays open.

Only `BOOT_COMPLETED` is used, not the earlier locked boot broadcast, because the database is not readable until the first unlock.
A phone that restarts and is not unlocked has no alarms until it is.
That is a known limit, and whether it matters on the tester's phone is a device question.

A test reads the merged manifest and checks that each broadcast action equals the platform's own constant, that the receiver is declared for it, that it is not exported, and that `RECEIVE_BOOT_COMPLETED` and `SCHEDULE_EXACT_ALARM` are requested and `USE_EXACT_ALARM`, `INTERNET` and `RECORD_AUDIO` are not.

### 8. Kept as they were

`SCHEDULE_EXACT_ALARM`, no approximate fallback, `setExactAndAllowWhileIdle`, `targetSdk` 36, the two minute lateness rule, the device's zone, and the M1 call path all stand.
`RECEIVE_BOOT_COMPLETED` is the only permission added.

### 9. The debug screen shows what is stored

The list of stored occurrences replaces slice 1's in-memory "armed" card.
It is read from the database, so it is right after a swipe-away or a reboot.
Each row shows when the occurrence was due, its status and reason, its fired and resolved times, its id and zone, and, for a scheduled one, whether Android still holds its alarm.
The screen also shows the stored plan, and has a Reconcile now button and the last reconciliation's result.

Each debug occurrence now has its own id, `dev-` and the moment it was armed, so a finished one is never overwritten.
A new one replaces any earlier debug occurrence that has not rung.
Cancel cancels every debug occurrence still scheduled, and needs no memory of arming.

## What only a physical device can settle

- Whether `BOOT_COMPLETED` reaches a receiver that is not exported. It is a system broadcast, and the platform's permission check lets system senders through, but that is reasoning and not evidence.
- Whether the receiver, the database and the claim complete on a cold start after a reboot, and whether `goAsync` keeps the device awake long enough for a call to start.
- Whether the phone is unlocked in time. A reboot with no unlock before the due time is the case that decides whether the locked boot broadcast is needed.
- What revoking Alarms and reminders does to the alarm's `PendingIntent`. That decides whether an occurrence lost that way is recorded as alarm missing or overdue.
- Whether the time and time zone broadcasts arrive on a manual change, and what an alarm does when the clock is moved.
- Whether an armed alarm and its record survive an update of the app installed over the running one.
- Whether the alarm still fires on time from deep idle, which slice 1 left for an overnight run and this slice does not change.

## Consequences

- A dropped, refused, missed, declined, answered and completed occurrence now leaves a durable record, and the debug screen shows it.
- A restart, an update, or a change of the clock no longer silently loses a future alarm, provided the phone is unlocked and the app can run.
- An overdue occurrence is marked missed and never rung late, from every trigger.
- A force-stop is still invisible until the app next runs. Then a future alarm is restored, and an overdue one is recorded as alarm missing, which is accurate and names no cause.
- Nothing yet creates the daily occurrences, so a phone with no debug alarm has nothing to restore.
- The schema is now a contract. Any change from here needs a migration, checked against `app/schemas`.
