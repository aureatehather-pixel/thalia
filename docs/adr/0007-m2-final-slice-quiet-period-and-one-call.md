# ADR 0007: M2 final slice, quiet period and one call at a time

Date: 2026-09-30
Status: Accepted and implemented. Verified on the JVM and Robolectric on 2026-09-30. Targeted validation passed on a Pixel 10 on 2026-09-30, on `42b48eb`: see [the device results](../m2-final-device-results.md). A real confirmed check-in was delivered on time on 2026-10-02, on a charging phone not in Doze: see [the real delivery results](../m2-overnight-device-results.md). Natural deep-idle and consecutive-day validation is untested, and was waived as a blocking requirement by the user on 2026-10-02.
Milestone: M2, final slice
Branch: `feature/m2-recurring-checkins`
Builds on: [ADR 0006](0006-m2-slice-2-durable-scheduling-and-recovery.md), whose atomic claim, reconcile path and lateness rule all stand, and the per-day confirmation and revisions recorded in [the slice 3 results](../m2-slice3-device-results.md).
Amends: ADR 0006's claim, which moved one occurrence to ringing with no regard for any other.

The product rule is that a quiet period strictly prevents ringing, and that Thalia is not an alarm clock: a call may only be confirmed outside the quiet period.
Until now there was no quiet period at all.
And although the call coordinator refused a second call while one was on the phone, the alarm path had already claimed the occurrence and started the foreground service by then.
Two alarms delivered together could both be claimed.

This slice closes both.
It adds a user-configurable quiet period, checked when a day is confirmed or moved and again when the alarm arrives, and it makes one call at a time a property of the stored record, enforced by the claim itself.
It does not add a settings screen, plan editing, automatic daily confirmation, or any network or AI.

## Decisions

### 1. A new terminal status, `SUPPRESSED`

A call that Thalia withholds on purpose is not a missed call.
Missed means the user had the chance and the call did not reach them, or it was lost.
Suppressed means Thalia decided, by rule, that it must not ring.

So `CheckInStatus` gains `SUPPRESSED`, reachable only from `SCHEDULED`, terminal, and never fired.
It needs a reason, and exactly two explain it:

| Status | Reason | Meaning |
|---|---|---|
| `SUPPRESSED` | `QUIET_PERIOD` | Its moment, or the moment its alarm arrived, was inside the enabled quiet period |
| `SUPPRESSED` | `CALL_IN_PROGRESS` | Another call was ringing or answered when its alarm arrived |

A suppressed occurrence ended without ever ringing, so it is recoverable in the sense of the revision rules: the day can be confirmed again as a new revision, at a time outside the quiet period.
Statuses are stored by name, so the new status needs no change to the occurrences table.
The reconciliation leaves it alone, as it does every terminal status.

`CALL_IN_PROGRESS` also remains a reason for `MISSED`, for one case only.
Every scheduled call goes through the claim, but a call started by a development button has no stored occurrence, so neither the store nor the claim can see it.
If such a call starts in the moments between an alarm's claim and its ring, the coordinator refuses the second ring as before, after the service has started, and that is recorded `MISSED`, `CALL_IN_PROGRESS`.
It cannot happen with scheduled calls alone.

### 2. The quiet period

`QuietPeriod(start, end, enabled)` is a daily stretch of wall-clock time, minute precision, from `start` included to `end` not included.
When `end` is earlier than `start` it spans midnight, so 22:00 to 07:00 covers 23:30 and 06:59 but not 07:00.
Equal start and end are refused, because they would be ambiguous between empty and the whole day.
It is read in the plan's zone, the zone the check-in times are in.

It is stored in its own one-row table, `quiet_period`.
A new install and an upgraded one both read the default, 22:00 to 07:00, switched off, so the upgrade changes nothing until the user turns it on.
Switching it off keeps its times.

### 3. Checked when confirming, and again when the alarm arrives

Confirming a day, and moving one, refuse a time inside the enabled quiet period and record nothing, like a slot switched off.
A move that is refused leaves the scheduled revision exactly where it was.

The alarm path checks again, because the user may change the quiet period after confirming.
The alarm stays armed, and the decision is made when it arrives, so the only thing that has to be right is the moment of delivery.
If the quiet period covers the occurrence's moment or the delivery moment, the occurrence is suppressed.
Checking both means an alarm delivered a little late cannot ring inside a quiet period that began after its moment.

The lateness rule still comes first.
An alarm more than two minutes late is recorded `TOO_LATE` whatever the quiet period says, so the two rules never both claim one occurrence.

### 4. One call at a time, enforced by the claim

The claim becomes one Room transaction with three outcomes:

- `CLAIMED`: the occurrence was scheduled and no other occurrence was ringing or answered. It is now ringing.
- `SUPPRESSED`: it was scheduled and another occurrence was ringing or answered. The same transaction ends it `SUPPRESSED`, `CALL_IN_PROGRESS`.
- `NOT_CLAIMED`: it was not scheduled. Nothing changed.

The claim's `UPDATE` also carries the rule in its own `WHERE`: it changes the row only if no occurrence at all is ringing or answered.
So even a caller that skipped the transaction could not put a second call on the phone.

Checking first and claiming afterwards would leave a gap in which two alarms each see a free phone and both claim.
Inside one transaction there is no such gap, and of any number of alarms delivered together exactly one rings.
The loser is decided before the call starter is touched, so it starts no foreground service and posts no notification, and nothing rings, vibrates, wakes the screen or shows full screen.

### 5. A call lost with its process cannot block the next one

With the one-call rule in the store, a row left ringing or answered by a process that died would suppress every later alarm until something reconciled.
Nothing reconciles when an alarm starts the process.

So before claiming, the alarm path ends lost calls by exactly the rule the reconciliation uses, now shared as `isLostCall`: nothing in this process is carrying it, and it was claimed more than 15 seconds ago.
A ringing one becomes `MISSED` and an answered one `FAILED`, both `INTERRUPTED`.
A call claimed a moment ago whose service has not started yet is inside the 15 seconds, so it still holds the phone.

### 6. A call the store cannot see

The alarm path also asks the coordinator whether this process has a call ringing or in progress.
If it has, the occurrence is suppressed `CALL_IN_PROGRESS` without reaching the claim.
This is what covers a development call started by a button, which has no stored occurrence.
For a stored call the claim would reach the same answer.

### 7. Development controls, the minimum for the device run

- **Quiet next 15 min** sets the quiet period from the current minute for 15 minutes and switches it on. That is longer than the ten-minute move, so a moved check-in falls inside it.
- **Quiet off** switches it off and keeps its times.
- A line shows the current quiet period.
- **Schedule two at once in 2 minutes** arms two development occurrences for the same instant, `dev-<millis>` and `dev-<millis>-b`, so a device run can make two alarms race.

The occurrence list shows `SUPPRESSED` and its reason like any other status.

## Consequences

- The quiet period is strict in the product's sense: nothing that comes due inside it rings.
  An occurrence it suppresses is kept, with its reason, and its day can be confirmed again at another time.
- Two calls can never hold the phone together through the scheduled path, however the alarms are delivered.
- A process death during a call no longer costs the next alarm.
- The database is at version 4.
  The migration adds one table and touches nothing else.

## Tests

- `QuietPeriodTest`: same-day periods, periods spanning midnight, both boundaries, a period ending at midnight, disabled periods, zones, and refused shapes.
- `CheckInStatusTransitionTest` and `ScheduledOccurrenceTransitionTest`: the new status, its only source, its two reasons, and that it is never fired.
- `OccurrenceStoreContractTest`, run on the in-memory store and on Room with real SQLite: a claim during a ringing or answered call is suppressed in the same step, a suppressed occurrence is never claimed afterwards, two claims at once have exactly one winner over 40 rounds, 24 claims at once leave exactly one call, and the quiet period is stored and read back.
- `FireSuppressionTest`: quiet-period suppression by the occurrence's moment and by the delivery moment, a quiet period set after confirming, a period spanning midnight, a disabled period, lateness first, a repeat delivery, a stored call and an unstored call in progress, two alarms delivered together over 40 rounds with exactly one call started, a lost call cleared, and a just-claimed and a long carried call both still holding the phone. The call starter, the only door to anything visible or audible, is asserted untouched for every suppression.
- `CheckInConfirmerTest`: confirming and moving into the quiet period refused, a refused move leaves the revision in place, midnight spans, and re-confirming a suppressed day.
- `CheckInDatabaseMigrationTest`: a version 3 file, written exactly as version 3 wrote it, migrates with every occurrence and the plan kept and the quiet period off, and stores a suppression afterwards.

## Pixel validation

The targeted checks passed on a Pixel 10 on 2026-09-30, on `42b48eb`.
They covered the Room v3 to v4 migration, a move into the quiet period refused, an alarm suppressed inside the quiet period, two alarms due at once with exactly one ringing, and an exact-alarm permission revoked past a due moment.
The results, evidence and limitations are in [m2-final-device-results.md](../m2-final-device-results.md).

Still to validate on the Pixel:

- **A quiet period spanning midnight.**
  It is covered on the JVM only, unless the phone's clock is moved as in slice 2's D5.
- **Overnight and deep-idle delivery.**
  A real check-in rang on time on 2026-10-02, but on a charging phone not in Doze: see [the real delivery results](../m2-overnight-device-results.md).
  Natural deep idle on an unplugged phone, and consecutive days, are untested.
  The user waived them as a blocking requirement on 2026-10-02, and they remain a known limitation.
