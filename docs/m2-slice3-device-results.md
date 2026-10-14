# M2 slice 3 device validation results: Pixel 10

Date: 2026-09-30, 17:13 to 17:50 phone time
Milestone: M2, third slice: opening and closing check-ins, per-day confirmation, and per-occurrence revisions
Branch: `feature/m2-recurring-checkins`
Code on the phone: the app code of `102cd38`, which `16f90de` leaves unchanged (it adds only a test)
Fix that followed: `5e282d0`, verified on the JVM only and not yet on the phone

## The answer

The questions for this run were:

> Does the Room v1 to v3 migration keep every slice 2 record on a real phone, and do confirm, move, cancel and re-confirm keep exactly one live alarm per slot through a revoke of exact alarms and a reboot?

**Yes, for the cases tested.**
The migration kept all 13 slice 2 occurrences with their ids, statuses and times, and renamed the types and the plan.
The move retired the old revision and its alarm within 10 ms, every cancel removed exactly one alarm, and no capture ever showed Android holding more than one Thalia alarm.
A moved revision rang on time, 34 ms late, and the revision it replaced never rang.
After a revoke, a re-grant and a reboot, the one live revision was armed again each time.

The run also produced one finding that changed the code.
Right after exact alarms were revoked, the alarm's `PendingIntent` record was still present, and Thalia's `isArmed()` reads only that record.
`5e282d0` makes `isArmed()` also require the permission, and drops the record when arming is refused.

This is one device, one Android build and one run of each test.
No observations by the tester were saved, so every result below rests on what the phone recorded.

## What was tested

| Item | Value |
|---|---|
| Device | Pixel 10 (`frankel`), the same phone as slice 2 |
| Android | 17, API level 37, build `CP3A.260905.009` |
| App | `com.thalia.app.debug`, target SDK 36, installed over the slice 2 build with its data kept |
| Source | the app code of `102cd38` (identical in `16f90de`). The exact commit built and the APK checksum were not saved. |
| Database | Room schema version 1 before the install, version 3 after |
| Starting state | 13 terminal slice 2 occurrences, plan MORNING 10:00 and AFTERNOON 15:00, `Asia/Kuala_Lumpur`, exact alarms allowed, no Thalia alarm pending |
| Connection | `adb` from the WSL machine. The phone's `adbd` lines show a USB connection. |

The phone's clock ran 3 minutes 45 seconds behind the computer's, the same lag as in slice 2.
It is read from the alarm dumps, whose own `nowRTC` line gives the phone's time, against the computer's time of each capture.
Every time below is the phone's clock.

The debug screen's controls were used for every action.
**Move** puts a slot at ten minutes from now, truncated to the minute, and records revision 1 if the slot has none.
**Confirm** puts a slot at the plan's time.
**Cancel** withdraws the slot's scheduled revision.
A slot is one day and one type, and its id is `<date>-<type>`, with `-rN` for revision N from 2.

## Results

| # | Test | Occurrence | Result | Decisive evidence |
|---|---|---|---|---|
| M | Install over slice 2, migrate v1 to v3 | all 13 slice 2 rows | **Pass** | Database before: `user_version=1`, 13 rows, types MORNING. After: `user_version=3`, the same 13 ids, statuses, reasons and times, types OPENING, and each row its own slot at revision 1. Plan rows OPENING 10:00 and CLOSING 15:00, zone and enabled flags unchanged. No Thalia alarm pending at 17:15:01. |
| S3 | Move today's opening from nothing | `2026-09-30-opening` | **Pass** | Database: revision 1 SCHEDULED for 17:26:00. Alarm dump at 17:17:17: exactly one Thalia alarm, at 17:26:00, `exactAllowReason=permission`. |
| S4a | Move it again | `2026-09-30-opening`, `-r2` | **Pass** | Database: revision 1 CANCELLED `SUPERSEDED` at 17:17:53.361, `-r2` SCHEDULED for 17:27:00. Alarm history: `alarm_cancelled` at 17:17:53.371, 10 ms later. Alarm dump at 17:18:28: exactly one Thalia alarm, at 17:27:00. |
| S4b | The moved revision rings | `2026-09-30-opening-r2` | **Pass** | Phone log: `17:27:00.049 alarm delivered, starting call … latenessMillis=34`, ring command at 17:27:00.057. Database: MISSED `RING_TIMEOUT`, fired 17:27:00.034, resolved 17:27:45.331. Alarm statistics count one new Thalia wakeup, at 17:27:00. Revision 1 has no fired time. |
| S5 | Cancel and re-confirm, today and tomorrow | `2026-09-30-closing`, `2026-10-01-opening` to `-r4` | **Pass** | Database: today's closing (17:40) CANCELLED `USER_CANCELLED` at 17:30:33.130. Tomorrow's opening: revision 1 (17:40) cancelled 17:30:45.230, `-r2` (17:41) cancelled 17:31:01.459, `-r3` (10:00) cancelled 17:31:20.830, `-r4` (10:00) SCHEDULED. Each cancel has its own `alarm_cancelled` 5 to 7 ms later. Alarm dump at 17:31:37: exactly one Thalia alarm, 2026-10-01 10:00:00. |
| S6 | Revoke and re-grant Alarms & reminders | `2026-10-01-opening-r4` | **Pass** | See below. Database: `-r4` still SCHEDULED while revoked. One Thalia alarm pending again at 17:40:03. |
| S7 | Reboot with the revision armed | `2026-10-01-opening-r4` | **Pass** | Alarm dump 52.4 s after boot (17:41:41): no Thalia alarm. Alarm dump 65.6 s after boot (17:41:54): exactly one, 2026-10-01 10:00:00. Database: `-r4` SCHEDULED. |
| S8 | Cancel the live revision | `2026-10-01-opening-r4` | **Pass** | Database: CANCELLED `USER_CANCELLED` at 17:42:53.109. Alarm dump at 17:43:04: no Thalia alarm pending. |
| PI | Intent record across a revoke | `2026-10-01-opening-r5` | **Finding** | See [the armed-state finding](#the-armed-state-finding). |

The evidence files are labelled S3 to S8.
The install and migration are covered by the captures named `pre`, `post1` and `baseline`, and labels S1 and S2 have no files of their own.

## What each test proved

- **M.**
  The v1 to v3 migration keeps every stored occurrence on a real phone, with its id unchanged, so an alarm armed under an old id would still find its record.
  No alarm was armed across this upgrade, so that last point is not shown here.
- **S3 and S4a.**
  Move records revision 1 when the slot is empty, and moving a scheduled revision retires it as `SUPERSEDED` and arms the next one, with the old alarm cancelled 10 ms after the record.
- **S4b.**
  A moved revision rings on time from its own alarm, and the revision it replaced does not ring.
  The call nobody answered was recorded missed as `RING_TIMEOUT` after 45.3 seconds.
- **S5.**
  Cancel withdraws the revision and its alarm, a cancelled slot can be confirmed or moved again as the next revision, and the history is kept.
  Today's and tomorrow's slots are separate.
  After four revisions of one slot, Android held exactly one Thalia alarm.
- **S6.**
  Revoking the permission removes the alarm and stops the app, the revision stays scheduled and is counted unarmed at the next launch, and the re-grant arms it again.
  This repeats slice 2's D4 on a confirmed revision rather than a debug occurrence.
- **S7.**
  After a reboot, the live revision was re-armed between 52 and 66 seconds after boot.
- **S8.**
  Cancelling the live revision leaves no Thalia alarm behind.

## S6: revoke and re-grant

| Time | Evidence |
|---|---|
| 17:33:28.234 | The alarm service logged `Package com.thalia.app.debug … lost permission to set exact alarms!`, and the alarm history records the removal as `Reason=exact_alarm_permission_revoked`. |
| 17:33:28.236 | The activity manager killed Thalia's process 21183: `schedule_exact_alarm revoked`. |
| 17:34:15.602 | The tester reopened Thalia, in the new process 25624: `APP_LAUNCH: armed=0 restored=0 unarmed=1 changes=0`. |
| 17:38:46 | Alarm dump: no Thalia alarm pending. The database still holds `-r4` SCHEDULED. |
| 17:39:37.257 | The permission was granted again: `EXACT_ALARM_PERMISSION_GRANTED: armed=1 restored=1 unarmed=0 changes=0`. |
| 17:39:48.618 | `APP_LAUNCH: armed=1 restored=0 unarmed=0 changes=0`. |
| 17:40:03 | Alarm dump: exactly one Thalia alarm, 2026-10-01 10:00:00. |

## The armed-state finding

### What the phone showed

After S8, tomorrow's opening was confirmed once more as `2026-10-01-opening-r5`, which no saved database snapshot covers.
Three captures of the phone's `PendingIntent` records were then taken, named for the state of the permission at the time:

| Capture | Phone time, from the file time | Thalia's records |
|---|---|---|
| `pi-before` | about 17:47:47 | one: `PendingIntentRecord{222ee7a}`, `CHECK_IN_ALARM` for `thalia://checkin/2026-10-01-opening-r5` |
| `pi-revoked` | about 17:48:10 | the same record, `222ee7a` |
| `pi-granted` | about 17:49:35 | the same record, `222ee7a` |

`pi-before` and `pi-revoked` are byte-identical: no intent record on the phone, Thalia's or any other app's, changed between them.
The re-grant capture differs only in other apps' records, and Thalia's lines in it are identical.

Thalia's `isArmed()` answers by looking the record up with `FLAG_NO_CREATE`.
So with the permission revoked and the alarm removed, as S6 shows a revoke does, the record alone would have made `isArmed()` report an alarm that no longer existed.

### How strong this evidence is

- **The revoke itself is not in the saved evidence for this capture.**
  No log and no alarm dump were saved between 17:47 and 17:50.
  That the permission was off at `pi-revoked` rests on the capture's name.
  That a revoke removes the alarm rests on S6 and on slice 2's D4.
- **S6 points the other way, later.**
  With the pre-fix code, the re-grant at 17:39:37 counted `restored=1`, which it does only when `isArmed()` returned false.
  So in S6, six minutes after the revoke, the record was gone, although nothing in the code cancelled it.
- **Hypothesis, not established: Android cleans the record up on its own.**
  The hypothesis is that Android holds `PendingIntent` records only while something references them.
  After a revoke, the alarm that held the record is removed and the app's process is killed, so under this hypothesis the record lingers only until the system collects it.
  It would fit both observations: present within 23 seconds of the revoke, gone six minutes after it.
  Nothing in the evidence shows the cleanup happening, and how long a record can linger was not measured.

### What changed, and why the change is right either way

`5e282d0` changes two things:

- `ExactAlarmScheduler.isArmed()` returns true only while exact alarms are allowed.
  Without the permission no alarm can exist, so this is correct whatever the record does.
- When a reconcile's arming is refused for lack of permission, the reconciler cancels the record.
  A useless record then cannot look like a live alarm after the permission returns.

What the fix protects is the reason given for a missed occurrence and the debug screen's "armed" label.
With a lingering record, an occurrence that falls due while the permission is off would be recorded `OVERDUE`, "an alarm was registered and never rang", instead of `ALARM_MISSING`.
Nothing rings late either way.

`RevokedPermissionReconcileTest` pins the fix in eight cases against a platform that behaves as the phone did.
They pass, with the full suite: 74 `:core` and 327 `:app` tests, plus ktlint, Android lint and the debug build, on 2026-09-30.
The fix has not been installed on the phone.

## The remaining gap: revoked, then granted with no reconcile between

The fix drops the record only when a reconcile runs while the permission is off.
If the permission is revoked and granted again with no launch or other reconcile in between, the record can still be there when the permission returns, and `isArmed()` then reads true.

What follows depends on the due time:

- **Not yet due.**
  The re-grant reconcile arms the alarm again, which is the part that matters, but counts it as present rather than restored.
  The count is cosmetic.
- **Due during the gap, more than two minutes before the reconcile.**
  The occurrence is recorded MISSED `OVERDUE` rather than `ALARM_MISSING`.
  The unit test `a known limit - revoked and granted again with no reconcile in between and past due reads as overdue` pins this on purpose.
- **Due during the gap, less than two minutes before the reconcile.**
  The reconcile treats the alarm as live and late, and leaves the occurrence scheduled.
  The next reconcile, whenever one runs, records it `OVERDUE`.
  This case is inferred from the reconciler's rules and is not pinned by a test.

In every case nothing rings late and the occurrence ends missed.
Only the reason can be wrong.
Closing the gap needs a signal that survives the revoke, such as the reconciler remembering which occurrences it last armed, or Android reporting the removal.
Whether it matters depends on how long a record lingers.
S6 saw it gone after six minutes, and the cleanup hypothesis above would make that the usual case, but it is unproven.

## What was not tested

- **The fix on the phone.**
  `5e282d0` is verified on the JVM only.
- **A revoked permission kept off past a due moment,** with the old code or the new.
  `ALARM_MISSING` versus `OVERDUE` after a revoke remains a JVM result.
- **The revoked-then-granted gap above,** on the phone.
- **How long an intent record lingers after a revoke.**
  Seen present within 23 seconds and gone at six minutes, once each.
- **An alarm armed across the v1 to v3 upgrade.**
  No Thalia alarm was pending when the new build was installed.
- **Answering or declining a confirmed revision.**
  The only call that rang, S4b, was left to time out.
- **The plan's own times ringing.**
  Every revision that could ring was cancelled before its 10:00 or 17:40 moment, and nothing creates occurrences from the plan on its own.
- **Moving or cancelling a revision that is ringing or in a call,** confirming a slot that already rang, a slot switched off, and a move to a time already past.
  These refusals are covered by JVM tests only.
- **The reboot's own record.**
  S7 rests on two alarm dumps and the database.
  No application log line from the boot reconcile was saved, and whether the phone was unlocked before the re-arm is not recorded.
- **The APK that was installed.**
  Its checksum and build command were not saved.
- Everything slice 2 left open still stands: reboots with the phone locked or off at the due time, an overnight run from deep idle, a real app update, Do Not Disturb, a withheld full-screen intent, a call already in progress, a clock moved backwards, and battery and hibernation effects.

## State left on the phone

- **Tomorrow's opening, `2026-10-01-opening-r5`, may still be live.**
  After the re-grant its intent record was present, and no later capture shows it cancelled.
  If it is still scheduled, the phone will ring at 10:00 on 2026-10-01.
  Check it before then, from the debug screen or with `dumpsys alarm`.
- The permission Alarms & reminders was granted again at the end of the PI captures, by their name.
- The logs, the alarm dumps and the database were read over `adb`.

## Evidence

The evidence is kept off the repository, in `C:\Users\Vesyal\thalia-transfer-2026-09-30\m2-evidence\`, copied from `~/m2-evidence` on the WSL machine:

- read-only copies of the app's database at nine points, `pre` and `post1` for the migration and `s3` to `s8`, each saved twice under two names with identical content;
- full `dumpsys alarm` output at nine points, `baseline` and `s3` to `s8`;
- the full system log from 17:14:37 to 17:40:39 (`log-s6-full.txt`) and the app's own `ThaliaAlarm` lines for S4 and S6 (`log-s4.txt`);
- full `dumpsys activity intents` output before, during and after the PI revoke.

The dumps and the full log are unfiltered and name many other apps on the phone, so they are not for publishing as they are.
This document quotes only Thalia's lines from them.

The files were read from copies, so the originals are unchanged.
The transfer bundle's `SHA256SUMS.txt` covers the repository bundle and the patch, not the evidence.

## Findings

- **An intent record can outlive its alarm after a revoke.**
  `isArmed()` must not rely on the record alone, and after `5e282d0` it does not.
- **In S6 a revoke's intent record was gone six minutes later, with nothing in Thalia cancelling it.**
  That Android cleans it up on its own is a hypothesis.
  If it holds, the remaining gap is narrow, but its length was not measured.
- The v1 to v3 migration, the revision rules and the one-alarm-per-slot invariant held on the phone.
- The reboot re-arm landed about a minute after boot, as slice 2's D3 found for the boot receiver.

## Still to test

- Install `5e282d0` and repeat S6 with the permission kept off past the due moment, expecting `ALARM_MISSING`.
- Revoke and grant again without opening the app, past a due moment, to see the gap on the phone, and time how long the intent record lingers.
- Answer and decline a confirmed revision, and let a plan-time occurrence ring at 10:00 or 15:00.
- Arm a revision, then install an update over it.
- Everything in slice 2's list that is still open.
