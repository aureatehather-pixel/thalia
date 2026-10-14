# M2 real delivery device results: Pixel 10

Date: 2026-10-02, the 10:00 opening check-in, evidence captured from 16:28 phone time
Milestone: M2, reliable local scheduling: the overnight run
Code on the phone: `42b48eb`, unchanged since the [final slice run](m2-final-device-results.md)
Design: [ADR 0005](adr/0005-m2-slice-1-real-alarm.md), [ADR 0006](adr/0006-m2-slice-2-durable-scheduling-and-recovery.md) and [ADR 0007](adr/0007-m2-final-slice-quiet-period-and-one-call.md)

## The answer

The question for this run was:

> Does a real confirmed check-in, left overnight, ring on time from deep idle on an unplugged phone?

**Partly.**
A real confirmed opening check-in was delivered 401 ms after its moment, raised the full-screen call, and ended correctly as missed when nobody answered.
The phone was charging at the time and not in Doze, so **delivery from natural deep idle on an unplugged phone was not tested.**

On 2026-10-02 the user waived the remaining natural deep-idle and consecutive-day coverage as a blocking requirement for M2.
It is not passed.
It stays a known limitation, below.

This is one device, one Android build and one occurrence.

## What was tested

| Item | Value |
|---|---|
| Device | Pixel 10, the same phone as every M2 slice |
| Android | 17, API level 37 |
| App | `com.thalia.app.debug`, target SDK 36 |
| Installed build | `42b48eb`. `lastUpdateTime` 2026-09-30 22:34:37 and `firstInstallTime` 2026-09-19 17:08:50, both unchanged since the final slice install. |
| Plan | OPENING 10:00 and CLOSING 15:00, both on, `Asia/Kuala_Lumpur`. Quiet period off. |
| Confirmed | Only the opening of 2026-10-02. No closing was confirmed for any day. |
| Last boot | 2026-10-01 05:20, after the battery ran out (`shutdown,battery`). There was no reboot on the night of the test. |

Nothing creates occurrences from the plan on its own, so the opening was confirmed by hand on the debug screen.
The database keeps no creation time.
Thalia was in the foreground on 2026-10-01 between 18:30 and 19:35, which is the likely window for the confirmation.

Every time below is the phone's clock.

## Result

| Item | Evidence |
|---|---|
| Occurrence | `2026-10-02-opening`, revision 1, the only occurrence added since the final slice run. 31 occurrences in total, none scheduled, ringing or answered. |
| Scheduled | 2026-10-02 10:00:00.000 |
| Fired | 10:00:00.401, **401 ms late** |
| Final state | **MISSED `RING_TIMEOUT`**, resolved at 10:00:46.212 |
| Alarm service | One Thalia wakeup since boot, `*walarm*:com.thalia.app.action.CHECK_IN_ALARM`, at about 10:00:00. No Thalia alarm pending afterwards. |
| Battery history | The alarm broadcast at 10:00:00.750, the full-screen intent woke the screen at 10:00:01.371, Thalia in the foreground from 10:00:01.419 and out of it at 10:00:46.256. |
| What the user saw | A missed check-in notification on waking at about 12:30. The ringing itself was not observed. |

The database, the alarm service and the battery history agree.
They differ by less than a second, which is the offset between their clocks.

## The phone's state before the alarm

From the battery history:

| Time, 2026-10-02 | Event |
|---|---|
| 00:00 to 07:06 | Frequent screen use. Deep Doze was entered many times, briefly. The longest stretch was 05:38 to 07:06. |
| 03:54 to 04:17, 05:03 to 05:14 | On AC power |
| 07:06:36 | Screen on, Doze ended |
| 07:22:29 | Plugged in. Screen off at 07:22:44. |
| 07:22 to 10:00 | Plugged in throughout. Doze was not entered again, as Android keeps it off while charging. The battery was full from 09:39. |
| 10:00:00 | The alarm was delivered with the phone charging, at 100%, not in Doze, about 2 h 37 min after the screen went off |
| 12:41:31 | Unplugged |

## What this proved

- A real occurrence, confirmed hours ahead and most likely the evening before, rang on time with the screen off for 2.5 hours.
- The full-screen call came up without the app in the foreground.
- An unanswered real check-in ends `RING_TIMEOUT` and leaves nothing armed or live.

## Known limitations

- **Natural deep idle on an unplugged phone is untested.**
  The alarm arrived on a charging phone that was not in Doze.
  `setExactAndAllowWhileIdle` is documented to fire in Doze, and ADR 0005 keeps `setAlarmClock` as the escalation if a later run shows lateness.
- **Consecutive days and the closing slot are untested.**
  One opening on one day rang.
  No 15:00 closing was confirmed.
- **No application log lines survive.**
  The phone's log buffers had rolled over by the capture, so there is no `ThaliaAlarm` line with the app's own lateness.
  The 401 ms comes from the database.
- **Whether the phone was locked at 10:00 is not recorded directly.**
  The screen was off and was woken by the full-screen intent.
- Everything earlier slices left open still stands: the reboot cases beyond the one tested, a real app update, Do Not Disturb, a withheld full-screen intent, a call already in progress, a clock moved backwards, and battery bucket and hibernation effects.

## Evidence

The evidence is kept off the repository, in `C:\Users\Vesyal\private evidence folder\m2-overnight-20261002\`:

- the original `checkins.db`, `-wal` and `-shm`, whose SHA-256 on the phone before and after the copy matched the local files, with `integrity_check` ok and `user_version` 4; only a separate copy was queried;
- the full battery history, captured with a 120-second dump timeout, after a first capture was cut off at 04:21 by the default timeout;
- `dumpsys alarm`, `deviceidle`, `package`, `battery` and `notification`, the standby bucket, uptime and boot reason, and the phone's log buffers;
- a first database capture that came back empty when wireless debugging dropped, kept in a folder labelled as failed and not used.

Everything was read over `adb`, without opening Thalia, so no reconcile ran.
The dumps and logs are unfiltered and name other apps on the phone, so they are not for publishing as they are.
This document quotes only Thalia's lines from them.
