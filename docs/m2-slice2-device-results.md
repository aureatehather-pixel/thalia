# M2 slice 2 device validation results: Pixel 10

Date: 2026-09-29 (S0 to D2b) and 2026-09-30 (D3 to D5b)
Milestone: M2, second slice
Decisions and design: [ADR 0006](adr/0006-m2-slice-2-durable-scheduling-and-recovery.md)
Branch: `spike/voice-checkins`

## The answer

The question for this slice was:

> Does the durable record, the atomic claim and the one reconcile path keep a scheduled occurrence and Android's alarm in agreement after a process death, an update, a force-stop, a reboot, a permission change and a clock or time zone change?

**Yes, for the cases tested.**
On the tested Pixel 10, every occurrence ended in a terminal, honest state, and nothing rang late, twice, or without a record.
An alarm armed before a reboot rang after it, from a fresh process, without Thalia being opened.
An alarm lost to a force-stop was recorded as missed on the next launch, and an alarm whose permission was revoked came back when the permission was granted.
A clock moved past an armed alarm's moment recorded it missed and rang nothing.

This is one device, one Android build and one run of each test, observed by one person.
It says nothing about an overnight run, deep Doze, or a phone that is not unlocked when an occurrence falls due.

## What was tested

| Item | Value |
|---|---|
| Device | Pixel 10 (`frankel`) |
| Android | 17, API level 37, build `CP3A.260905.009` |
| App | `com.thalia.app.debug`, version `0.1-m1-debug` (code 1), target SDK 36 |
| Source | commit `73dc3af`, built from a clean tree with `./gradlew clean :app:assembleDebug` |
| APK SHA-256 | `e6c7413f6db74ec64665246915bde1078ae4261df841b41eb5c7a6c9e2673995` |
| APK size | 30,957,716 bytes |
| Signing | the machine's auto-generated Android Debug key |
| Install | `adb install -r` over the slice 1 build (`c4d2c8fe…`) at 20:50:25, and again at 21:25:53 for D1, data kept both times |
| Permissions | `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `VIBRATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`. No `INTERNET`, no `RECORD_AUDIO`. |
| Starting settings | Exact alarms allow, full-screen intent allow, notifications granted, Do Not Disturb off, automatic time on, automatic time zone off, zone `Asia/Kuala_Lumpur` |
| Connection | wireless debugging |

The APK's SHA-256 on the phone matched the checksum above after install.
The phone's clock ran about 3 minutes 45 seconds behind the computer's.
Every time below is the phone's clock, and every timestamp in the database is the phone's own.

Every test used the development-only **Schedule in 2 minutes** control, so each occurrence's id is `dev-` and the moment it was armed in epoch milliseconds, and it is due exactly two minutes later.
The tests were run by the tester with the phone in hand, with the evidence read from the phone over `adb`.

Observations marked **tester** were reported by the tester.
Observations marked **phone** were read from the phone's own records: the app's Room database, `dumpsys alarm`, the system event log and the app's own `ThaliaAlarm` log lines.

## Results

| # | Test | Occurrence | Result | Decisive evidence |
|---|---|---|---|---|
| S0 | Install triggers a reconcile | none | **Pass** | Phone: database created with 0 occurrences and both plan rows. `20:50:26.441 reconciled: PACKAGE_REPLACED: armed=0 restored=0 unarmed=0 changes=0` in the new process 4476. |
| S1a | Process death, then the alarm | `dev-1790686662176` | **Pass** | Phone: the swipe killed pid 7315 (`am_kill … remove task`, 20:56:30). The alarm started a new pid 8590 at 20:57:42.244, `latenessMillis=598`, ring command 20:57:43.010. Database: DECLINED, fired 20:57:42.774, resolved 20:57:46.757. |
| S1b | Launch reconcile after process death | `dev-1790687108175` | **Pass** | Phone: the swipe killed pid 8590 (21:03:20). The reopen in pid 12333 ran `21:03:48.730 APP_LAUNCH: armed=1 restored=0`, with one alarm pending. `latenessMillis=23`. Database: DECLINED, fired 21:05:08.198, resolved 21:05:12.900. |
| S2 | Repeated reconcile, then Cancel | `dev-1790687395234`, `dev-1790687411224` | **Pass** | Phone: four `MANUAL: armed=1 restored=0 unarmed=0 changes=0` lines (21:07:59.170, 21:07:59.633, 21:08:12.511, 21:08:14.575), so a repeat changes nothing. Two `alarm_cancelled` removals at 21:08:06.123 and 21:09:24.596. Nothing rang at 21:10:11. Database: both CANCELLED `USER_CANCELLED`, no fired time, resolved 21:08:06.124 and 21:09:24.597. |
| S3 | Ring timeout | `dev-1790687837710` | **Pass** | Database: MISSED `RING_TIMEOUT`, fired 21:17:17.806, resolved 21:18:03.142 (45.34 s of ringing). Database only, because the 256 KB log buffer had rolled over and the live stream was down. |
| D1 | Reinstall while armed | `dev-1790688446022` | **Pass** | Phone: the update force-stopped and killed pid 12333 (`killDueToPackageUpdate`, 21:25:53). There is no removal entry, so the alarm survived. The new pid 21389 ran `PACKAGE_REPLACED: armed=1 restored=0` and `APP_LAUNCH: armed=1 restored=0`. `latenessMillis=119`. Database: DECLINED, fired 21:27:26.141, resolved 21:27:33.085. |
| D2a | Force-stop, reopen before due | `dev-1790688757695` | **Pass** | Phone: `am force-stop` at 21:30:56 left the pid gone, `stopped=true` and `pi_cancelled` at 21:30:56.300. The reopen in pid 22673 ran `21:31:00.817 APP_LAUNCH: armed=1 restored=1` and `21:31:00.818 BOOT: armed=1 restored=1`, with one alarm pending. `latenessMillis=56`. Database: DECLINED, fired 21:32:37.751, resolved 21:33:11.261. |
| D2b | Force-stop, reopen after due | `dev-1790688972604` | **Pass** | Phone: force-stop at 21:34:27 (`stopped=true`, `pi_cancelled` 21:34:27.806). Nothing rang. The reopen in pid 23876 ran `21:37:03.141 BOOT: … changes=1 [dev-1790688972604 SCHEDULED->MISSED ALARM_MISSING]`, then `21:37:03.142 APP_LAUNCH: … changes=0`. Database: MISSED `ALARM_MISSING`, no fired time, resolved 21:37:03.109. |
| D3 | Reboot while armed, unlocked before due | `dev-1790703258112` | **Pass** | See below. Database: DECLINED, due 01:34:18.112, fired 01:34:22.264, resolved 01:34:25.485. |
| D4 | Revoke and re-grant Alarms & reminders while armed | `dev-1790703962153` | **Pass** | See below. Database: DECLINED, due 01:46:02.153, fired 01:46:02.208, resolved 01:46:04.292. |
| D5a | Time zone change while armed | `dev-1790704561162` | **Pass** | See below. Database: DECLINED, due 01:56:01.162, fired 01:56:01.215, resolved 01:56:08.619. |
| D5b | Clock moved past the due time while armed | `dev-1790704798646` | **Pass** | See below. Database: MISSED `OVERDUE`, no fired time, resolved 02:58:00.845 on the moved clock. |

## What each test proved

- **S0.**
  The install reconcile runs and creates the database and the plan without ringing anything.
- **S1a.**
  An alarm armed before the process was killed still starts the call in a new process, and the claim, the ring and the outcome are all recorded.
- **S1b.**
  A launch after process death finds the alarm still armed and leaves it alone, and it then rings on time.
- **S2.**
  A reconcile can run repeatedly and changes nothing when nothing is wrong, and Cancel withdraws the occurrence with the reason `USER_CANCELLED` and no ring.
- **S3.**
  A call nobody answers is recorded missed as `RING_TIMEOUT` after the full timeout.
- **D1.**
  An update installed over the running app leaves the armed alarm in place on this Android build, and the update reconcile finds nothing to restore.
- **D2a.**
  A force-stop cancels the alarm, and the next launch restores it in time for it to ring.
- **D2b.**
  A force-stop cancels the alarm, and a launch after the due time records the occurrence missed as `ALARM_MISSING`.
  Nothing rings late, and the record claims no cause.
- **D3.**
  An alarm armed before a reboot rings after it, from a fresh process, when the phone is unlocked before the due time and Thalia is not opened.
- **D4.**
  Revoking the permission removes the alarm and stops the app, the occurrence stays scheduled and counted unarmed while revoked, and granting the permission re-arms exactly one alarm that then rings.
- **D5a.**
  A time zone change while armed runs a reconcile and does not move the alarm, because the moment is absolute.
- **D5b.**
  A clock moved past an armed alarm's moment records the occurrence missed as `OVERDUE` and never rings it late.

## D3: reboot

The test passed on process-start, alarm-statistics and Room evidence.
That the boot reconcile restored the alarm is a justified inference, and it is not read from an application log line.

The tester reported scheduling the occurrence, restarting the phone at once, unlocking it before the due time without opening Thalia, and declining the call.
The Schedule tap was at about 01:32:18.
That time is derived from the occurrence id (due moment minus the fixed two minutes) and was not logged directly, so it is context and not evidence.
The phone's records, in order:

| Time | Evidence |
|---|---|
| 01:33:12 | `LOCKED_BOOT_COMPLETED` |
| 01:33:16 | The user unlocked, and `BOOT_COMPLETED` was sent |
| 01:34:16.043 | The system event log records a new Thalia process, pid 14419, started for a broadcast to `SystemEventReceiver` |
| 01:34:18.112 | The occurrence's due moment |
| 01:34:22.3 | Android started the call service from the alarm's receiver, and posted the call notification |
| 01:34:25.5 | The tester declined from the notification, and the service stopped |
| after | `dumpsys alarm` lists one Thalia entry, one wakeup by `CHECK_IN_ALARM`, and no pending alarm |

The database holds the occurrence DECLINED, fired 01:34:22.264, resolved 01:34:25.485.
The call rang about 4.2 seconds after its due moment, far inside the two minute lateness rule.
Why it was 4.2 seconds and not tens of milliseconds is not established.
The receiver had only just run, and the phone was still busy after boot: its load average read 9.19 at 01:37:31, four minutes after the restart.

What was not retained is the application's own `BOOT: … restored=1` line.
The restart reset the phone's log buffer to 256 KB, and the buffer rolled over within about a minute of boot, so none of the app's `ThaliaAlarm` lines survived.
The events log, read afterwards, kept the process start, the foreground service events and the notification.

The inference is this.
An alarm does not survive a restart, which [ADR 0005](adr/0005-m2-slice-1-real-alarm.md) accepted as a slice 1 limit and ADR 0006 decision 6 records.
The only thing that started Thalia after boot was the boot broadcast to its receiver, and the alarm rang from that same process.
The alarm can only have been armed there, and the reconcile is the only code that arms one from that receiver.

### The boot receiver ran about 60 seconds after the broadcast

`BOOT_COMPLETED` was sent at 01:33:16.5, and Thalia's receiver process started at 01:34:16.0, about 59.5 seconds later.
The system log shows other apps' boot receivers being started across the same minute, so delivery was spread over more than a minute.
The cause of Thalia's delay is not established, and it sits on Android's side of the delivery.

Its consequence is direct.
An occurrence that falls due less than about a minute after the phone is unlocked is re-armed by the boot reconcile only just before it is due, or after it.
Here the receiver's process started about 2 seconds before the due moment, and the call still rang.
Had the receiver run after the due moment, the design has the same reconcile record it missed as `ALARM_MISSING` and ring nothing, as D2b showed for a lost alarm.
That is the valid alternative result.
This is a second reason, beside the locked-boot question in ADR 0006, why the boot case is not fully closed by one run.

## D4: revoke and re-grant

The occurrence `dev-1790703962153` was scheduled at 01:44:02.153, due 01:46:02.153, and the test held to a re-grant deadline of 60 seconds after scheduling.

| Time | Evidence |
|---|---|
| 01:44:04.317 | The alarm service logged `Package com.thalia.app.debug … lost permission to set exact alarms!`, and the alarm dump records the removal as `Reason=exact_alarm_permission_revoked` |
| 01:44:04.319 | The activity manager killed Thalia's process: `schedule_exact_alarm revoked` |
| 01:44:17 | No Thalia alarm pending. The occurrence was still SCHEDULED in the database. |
| 01:44:44.965 | The tester reopened Thalia: `APP_LAUNCH: armed=0 restored=0 unarmed=1 changes=0`. |
| 01:45:02.005 | The tester granted the permission again: `EXACT_ALARM_PERMISSION_GRANTED: armed=1 restored=1 unarmed=0 changes=0`. Exactly one Thalia alarm was pending. |
| 01:46:02.261 | `alarm delivered, starting call`, `latenessMillis=55`. The service started with the reason `ALARM_MANAGER_WHILE_IDLE`. |
| 01:46:04.294 | The tester declined from the notification, and the service stopped. |

### Android killed Thalia when the permission was revoked

Revoking Alarms & reminders does not only remove the alarm.
Android also stops the app's process, with the reason `schedule_exact_alarm revoked`.
The alarm's removal is recorded with a distinct reason, `exact_alarm_permission_revoked`, so the loss is visible in the alarm dump and not silent.
The first reconcile after that is the next launch, and it ran while the permission was still revoked.
It left the occurrence scheduled and counted it unarmed, and it did not mark it missed, because its moment had not come.

This answers one of ADR 0006's device questions.
What it does not answer is whether an occurrence lost this way would be recorded `ALARM_MISSING` or `OVERDUE` if the permission stayed off past the due moment.
This run re-armed the occurrence before then, so neither reason applied.

## D5a: time zone change

The occurrence `dev-1790704561162` was scheduled at 01:54:01.162, due 01:56:01.162.
The tester's first attempt to change the zone did not take, and the phone still reported `Asia/Kuala_Lumpur` about 50 seconds after Schedule.
The tester then changed it to `Asia/Tokyo`, with about 25 seconds left.
The app logged, printed in the phone's then current zone, `02:55:36.748 reconciled: TIMEZONE_CHANGED: armed=1 restored=0 unarmed=0 changes=0`.
That is 01:55:36.748 in Kuala Lumpur.
The alarm was delivered at 01:56:01.215, `latenessMillis=53`, on the same instant as if nothing had changed, and the tester declined.
The zone was then put back to `Asia/Kuala_Lumpur`, and that second `TIMEZONE_CHANGED` at 01:56:19.570 ran with nothing to arm.

## D5b: clock moved past the due time

The occurrence `dev-1790704798646` was scheduled at 01:57:58.646, due 01:59:58.646.
With automatic time off, the tester moved the clock forward by one hour.
The database shows the move landed about 2 seconds after Schedule, a figure derived from the resolved time and an assumed exact one hour shift.

| Time on the moved clock | Evidence |
|---|---|
| 02:58:00.845 | The database records the occurrence resolved, MISSED `OVERDUE`, with no fired time. |
| 02:58:00.869 | `reconciled: TIME_CHANGED: armed=0 restored=0 unarmed=0 changes=1 [dev-1790704798646 SCHEDULED->MISSED OVERDUE]` |
| 02:58:00.915 | Android delivered the alarm, `latenessMillis=3482269`, about 58 minutes late. The app logged `alarm delivered too late, dropped and not rung`, with `recorded=false`. |

Nothing rang.

### The reconcile and the alarm delivery raced, and both outcomes were correct

When the clock moved past the alarm's moment, Android delivered the alarm 46 milliseconds after the reconcile had already recorded the occurrence missed.
Two paths reached the same occurrence at once.
The reconcile won the conditional update, so it recorded `OVERDUE`.
The delivery then found its alarm over two minutes late, so it dropped the alarm without ringing, and it wrote no second record (`recorded=false`, which fits the occurrence already being missed).
The lateness rule and the atomic claim of ADR 0006 decisions 4 and 6 did the work they were built for.
The order of the two was luck, and the other order would have recorded `TOO_LATE` and no ring.

When the tester turned automatic time back on, the phone ran one more `TIME_CHANGED` at 01:58:39.267, with `armed=0 restored=0 unarmed=0 changes=0`, and its clock was correct.

## Also recorded

- **A stray cancelled occurrence.**
  The database holds `dev-1790688757458`, CANCELLED `USER_CANCELLED`, due 21:32:37.458, resolved 21:30:37.690.
  It comes from what looks like a double tap on Schedule during D2a.
  The second tap replaced the first 237 ms later, which is the designed behaviour.
  It is harmless, and it is one of the three cancelled occurrences in the database.
- **`restored` counted twice.**
  D2a showed `restored=1` from two concurrent triggers for the same alarm.
  Arming replaces, so there was still exactly one alarm.

## Nothing left behind

Checked at about 02:00 phone time, after D5b and after the settings were restored, with the permission and plan rows re-read at 02:06:

- No Thalia alarm is pending.
- No Thalia notification is posted.
- No Thalia service is running.
- No Thalia wake lock is held, read from the live wake lock list and not the history.
- The database holds 13 occurrences, and every one is terminal: 7 DECLINED, 3 MISSED and 3 CANCELLED.
  None is SCHEDULED, RINGING or ANSWERED.
- The plan rows are unchanged: MORNING 10:00 and AFTERNOON 15:00, `Asia/Kuala_Lumpur`, both enabled.
- The permission Alarms & reminders is granted, as at the start.

The same checks were made at 21:37:55 on 2026-09-29, after D2b, and at the start of D3 at about 01:29, when the state matched.

## Phone settings and the log buffer

The tester's settings were restored to what they were before D5: automatic date and time on, automatic time zone off, and the zone `Asia/Kuala_Lumpur`.
The phone's clock read 01:58:44 while the computer read 02:02:29, the same lag as before, so the phone's own time was correct.

The system log buffer was raised to 16 MB for the disruptive tests, with the tester's approval, and it was restored to 256 KB after D5.
The restore was read back with `adb logcat -g`, whose first line, the main buffer, reports 256 KiB.
The restart in D3 had already reset it once, which is why D3 lost the application's own log lines and D4 and D5 did not.

## Limitations

- One device, one Android build, one run of each test, one tester.
- **D3's application log is missing.**
  The pass rests on the process start, the alarm statistics and the database, and the restore by the boot reconcile is inferred, not read from a `BOOT: … restored=1` line.
- **No reboot with the phone locked at the due time was tested.**
  Whether a phone that has restarted and is not unlocked before an occurrence falls due needs the locked boot broadcast is not answered.
  Nor is a phone that is switched off at the due time.
- **The boot receiver's 60 second delay was seen once,** on a phone unlocked 62 seconds before the due moment.
  How long the delay is in other conditions was not measured.
- **The `BOOT` trigger on leaving a force-stop is inferred.**
  On leaving the force-stopped state the app received a `BOOT` reconcile next to `APP_LAUNCH` without any reboot.
  The platform log did not record the broadcast, so its cause is a guess.
  The two reconciles ran back to back and the outcome was recorded once.
- **S3 rests on the database alone,** because the log buffer had rolled over.
- **D5 moved the clock forward only.**
  A clock moved backwards while an alarm is armed was not tested, and neither was a zone change that crosses the due moment or an alarm armed in one zone and due in another with the plan in use.
- **Neither reason for a revoked alarm was tested past the due moment.**
  `ALARM_MISSING` versus `OVERDUE` after a revoke with the permission kept off is unresolved.
- **No overnight or deep Doze run.**
  The alarm was always armed for two minutes ahead and the phone was in its first minutes of idle.
  Slice 1's open question about a delivery from deep idle is unchanged.
- **No full-screen intent withheld, no Do Not Disturb, and no call already in progress** were tried with a scheduled occurrence.
- **Nothing generates occurrences from the plan.**
  Every occurrence here came from the development-only Schedule control, and no 10:00 or 15:00 occurrence was created.
- **The debug screen's "Last reconcile in this process" line shows only the latest reconcile.**
  In D2b it read `APP_LAUNCH … changes=0` although the `BOOT` reconcile that ran just before it had recorded the miss.
  The database was the reliable record.
  This is a possible later tweak to the debug screen.
- **The ended screen briefly flashes, unreadable, after Decline** in S1a.
  It is a minor visual rough edge and not a slice 2 defect.
- The tester's observations of the call screens were: the full-screen call over the lock screen when locked, a banner when unlocked with the screen on (as in M1), and, after S3's Missed call screen, Done returning to the lock screen.

## Evidence

The evidence is kept off the repository, in the tester's local state directory:

- a read-only copy of `checkins.db` from the end of the first session and from D3, D4 and D5;
- the occurrence and plan listing;
- Thalia-only `dumpsys alarm` extracts;
- the `ThaliaAlarm` lines the live stream caught, which have gaps where the connection dropped, and the filtered Thalia and boot events for D3, D4 and D5.

The log extracts are filtered to lines that mention Thalia or the boot broadcast.
A few of those lines also name other system components or apps, so the extracts are not for publishing as they are.
Unfiltered logs were read to check the results and are not kept with it.

## Findings

No defects in the app.

Four findings for the next slices:

- Android 17 kept an app's exact alarm through `adb install -r`, so the update reconcile is a safety net there and not a restorer.
- The boot receiver can run a minute after `BOOT_COMPLETED`, so an occurrence due soon after unlock is the case to watch, and it is a reason to run the phone-locked reboot case and to measure the delay on a phone under normal use.
- Revoking exact alarms stops the app, so the next launch and the permission-granted broadcast are the two ways back, and both worked.
- The debug screen's reconcile line and the double count of `restored` are cosmetic.

## Still to test in later slices

- The reboot cases beyond D3: unlocked after the due time, never unlocked, and switched off at the due time.
- A revoked alarm kept off past its due moment, and a notification for a revoked permission when the app is never reopened.
- A clock moved backwards and a zone change across a due moment, once daily occurrences exist.
- An overnight run: the real recurring check-ins on consecutive days, with the phone untouched and unplugged, measuring lateness from deep idle.
- An update installed as a real app update and not `adb install -r`.
- The full-screen intent withheld, Do Not Disturb, and an alarm arriving during a call already in progress.
- Battery bucket and app hibernation effects after days without opening the app.
