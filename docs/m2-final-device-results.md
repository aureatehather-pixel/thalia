# M2 final slice device validation results: Pixel 10

Date: 2026-09-30, 22:34 to 23:18 phone time
Milestone: M2, final slice: quiet period and one call at a time
Branch: `feature/m2-recurring-checkins`
Code on the phone: `42b48eb`, which includes the armed-state fix `5e282d0`
Design: [ADR 0007](adr/0007-m2-final-slice-quiet-period-and-one-call.md)

## The answer

The questions for this run were:

> Does the Room v3 to v4 upgrade keep every record on a real phone, does the quiet period refuse and suppress calls with nothing shown, does the claim let exactly one of two simultaneous alarms ring, and does an alarm lost to a revoked exact-alarm permission end `ALARM_MISSING` without ringing late?

**Yes, for the cases tested.**
Every step passed on its first run.

M2's implementation is complete, and its targeted acceptance checks have passed on the phone.
Overnight and deep-idle validation was pending at the time of this run.
Its outcome, a real delivery on a charging phone with natural deep idle untested and waived, is in [the real delivery results](m2-overnight-device-results.md).

This is one device, one Android build and one run of each test.

## What was tested

| Item | Value |
|---|---|
| Device | Pixel 10, the same phone as slices 2 and 3 |
| Android | 17, API level 37 |
| App | `com.thalia.app.debug`, target SDK 36 |
| Source | `42b48eb`, clean working tree. The APK was built from it, and no source file changed after the build. |
| Install | `adb install -r` over the slice 3 build, from native Windows over wireless debugging |
| Database | Room schema version 3 before the install, version 4 after |
| Starting state | 21 terminal occurrences, plan OPENING 10:00 and CLOSING 15:00, `Asia/Kuala_Lumpur`, exact alarms allowed, no Thalia alarm pending |

Before the install, the APK's signing certificate was compared with the installed app's, and they matched.
The install kept the app's data, and its first-install time was unchanged.
Every time below is the phone's clock.

The debug screen's controls were used for every action.
**Quiet next 15 min** switches on a quiet period from the current minute for 15 minutes.
**Schedule in 2 minutes** and **Schedule two at once in 2 minutes** arm development occurrences, `dev-<millis>` and `dev-<millis>-b`, without checking the quiet period, so an alarm can arrive inside it.

## Results

| # | Test | Result | Decisive evidence |
|---|---|---|---|
| A | Install over slice 3, migrate v3 to v4 | **Pass** | Database: `user_version` 3 before, 4 after. All 21 occurrences and both plan rows are identical before and after. The new `quiet_period` table is empty, which reads as the default. The debug screen showed "off (22:00 to 07:00 when on)". No Thalia alarm pending. |
| B | Move a check-in into the quiet period | **Pass** | With the quiet period on from 22:38 to 22:53, **+10 min** on today's closing showed "Wed 30 Sep 22:50 is inside the quiet period. Nothing was recorded or moved." Database: still 21 occurrences. No Thalia alarm pending. |
| C | An alarm arriving inside the quiet period | **Pass** | Phone log: `22:45:00.906 alarm delivered and suppressed, nothing started … reason=QUIET_PERIOD latenessMillis=45`. Database: SUPPRESSED `QUIET_PERIOD`, no fired time. No Thalia service, no Thalia notification, and the phone stayed dozing from the lock at 22:43:05. The tester saw and heard nothing. |
| D | Two occurrences due at the same instant | **Pass** | Both due at 22:51:34.667. `-b` fired at 22:51:34.744 and, left unanswered, ended MISSED `RING_TIMEOUT` at 22:52:20.057. The other ended SUPPRESSED `CALL_IN_PROGRESS` at 22:51:35.013, never fired. The system log shows one call service start and one full-screen wake. |
| E | Clean up | **Pass** | No Thalia alarm pending, no Thalia service, no Thalia notification, no occurrence scheduled, ringing or answered, quiet period off. |
| F | Exact alarms revoked past the due moment | **Pass** | See below. |

## F: revoked past the due moment

This is the phone test of `5e282d0`, which slice 3's results left open.

| Time | Evidence |
|---|---|
| 23:04:41 | `dev-1790780801853` armed for 23:06:41.853. |
| 23:05:15.169 | The alarm service logged that Thalia lost permission to set exact alarms, and its history records the removal as `exact_alarm_permission_revoked`. Thalia's process was killed. |
| 23:06:41 | The due moment passed. No alarm was delivered, no call service started and nothing woke the screen. |
| 23:15:28.606 | Thalia opened with the permission still denied: `APP_LAUNCH: armed=0 restored=0 unarmed=0 changes=1 [dev-1790780801853 SCHEDULED->MISSED ALARM_MISSING]`. |
| 23:17:59.076 | The permission was granted again: `EXACT_ALARM_PERMISSION_GRANTED: armed=0 restored=0 unarmed=0 changes=0`. |
| 23:18:41 | Database: MISSED `ALARM_MISSING`, no fired time. No Thalia alarm pending. |

Nothing rang, on time or late, and the reason recorded is `ALARM_MISSING`, not `OVERDUE`.

## What each test proved

- **A.**
  The v3 to v4 migration adds the quiet period table and changes nothing else on a real phone.
  An upgraded install reads the quiet period as 22:00 to 07:00, switched off, so the upgrade changes no behaviour.
- **B.**
  Moving a check-in into the enabled quiet period is refused before anything is stored or armed.
  An earlier attempt on today's opening was refused as already confirmed, because that slot had rung in slice 3, so the quiet-period check was not reached there.
- **C.**
  The quiet period is checked again when the alarm arrives, and a suppressed occurrence starts nothing that can ring, vibrate, wake the screen or post a notification.
  Between B and C, **Quiet next 15 min** was pressed again, at 22:41, so C ran with the quiet period on from 22:41 to 22:56.
  The database read at 22:40:30, after B, holds 22:38 to 22:53, and the one read at 22:46:50, after C, holds 22:41 to 22:56, both switched on.
  That button is the only control that sets a 15-minute period starting at the current minute.
  The alarm at 22:45:00 falls inside both periods.
- **D.**
  Of two alarms delivered together, the claim let exactly one ring, and the other was ended in the same step with nothing shown.
  Earlier presses of the scheduling buttons in this step were cancelled by later ones, as designed, and never came due.
- **E.**
  The run left nothing armed or live behind.
- **F.**
  After `5e282d0`, an occurrence whose alarm was removed by a revoke is recorded `ALARM_MISSING`, and the re-grant does not arm or ring it late.

## Evidence limitations

- **Wireless debugging dropped twice while the screen was off,** at about 22:47:50 and 22:50:03, and the phone's debugging port changed.
  The continuous log capture has gaps there.
  The missing stretches were read back afterwards from the phone's log buffer.
- **Thalia's own log lines for D were not in the buffer when it was read.**
  D rests on the database and the system's log of service starts and wakes, which agree.
- **What the tester saw is recorded only for C.**
  For D, the tester reported missing the call.

## What was not tested

- **A quiet period spanning midnight.**
  Covered on the JVM only.
- **Revoking and granting again with no launch or other reconcile in between.**
  The gap described in [the slice 3 results](m2-slice3-device-results.md#the-remaining-gap-revoked-then-granted-with-no-reconcile-between) remains a JVM result.
- **A quiet period set after confirming a real day's check-in,** rather than a development occurrence.
- **Answering or declining a call that won a race.**
- **Overnight and deep-idle delivery,** and everything else slice 2 and slice 3 left open.

## State left on the phone

- Quiet period off, 22:41 to 22:56 when on.
- Exact alarms allowed.
- 30 occurrences, none scheduled, ringing or answered.
- No Thalia alarm pending.

## Evidence

The evidence is kept off the repository, on the validation machine.
It holds read-only copies of the app's database at each step, full alarm dumps, the phone's logs and the APK's checksum and certificate.
The dumps and logs are unfiltered and name other apps on the phone, so they are not for publishing as they are.
This document quotes only Thalia's lines from them.
