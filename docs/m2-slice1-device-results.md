# M2 slice 1 device validation results: Pixel 10

Date: 2026-09-19
Milestone: M2, first slice
Decisions and design: [ADR 0005](adr/0005-m2-slice-1-real-alarm.md)
Branch: `spike/voice-checkins`

## The answer

The question for this slice was:

> Can a real exact alarm start the M1 call at the chosen time, with Thalia closed?

**Yes.**
On the tested Pixel 10, an alarm armed from the debug screen started the M1 call on time, with the app in the background and with the app swiped away and the phone locked.
Ringtone, vibration and full-screen call screen all came with it.
Cancelling an alarm, force-stopping the app and revoking the permission each stopped the call, as designed.

This is one device, one Android build and one run of each test, observed by one person.
It is a strong first answer for the short-idle case, and it says nothing about an overnight run or a reboot.

## What was tested

| Item | Value |
|---|---|
| Device | Pixel 10 (`frankel`) |
| Android | 17, API level 37, build `CP2A.260805.005` |
| App | `com.thalia.app.debug`, version `0.1-m1-debug` (code 1), target SDK 36 |
| Source | commit `4a28625`, built from a clean tree with `./gradlew clean :app:assembleDebug` |
| APK SHA-256 | `c4d2c8fe8467e47b34dc0aed5695c679a33c8b56e7ea8bf15c444b362cb8a685` |
| APK size | 30,133,924 bytes |
| Signing | the machine's auto-generated Android Debug key, APK signature scheme v2 |
| Install | `adb install -r` over the M1 debug build, same package and key |
| Connection | wireless debugging |

The APK on the phone was checked after install.
Its SHA-256 on the device matched the checksum above.
The same checksum came from two separate builds of the same commit, one from the uncommitted working tree and one from clean `HEAD`.

The phone's clock ran about three and a half minutes behind the computer's.
Every time below is the phone's clock.

## Results

Observations marked **tester** were reported by the tester.
Observations marked **phone** were read from the phone's own records over `adb`.

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | Permission unavailable at first, and the app says so | **Pass** | Tester: red "Scheduled calls off" card, "Exact alarms allowed" no, Schedule button greyed out, settings button present, top card unchanged. |
| 2 | Grant it, scheduling becomes available | **Pass** | Tester: card changed to "Scheduled calls possible" on returning, without restarting the app. Phone: `SCHEDULE_EXACT_ALARM` mode `allow`. |
| 3 | Unlocked, Home, on time, ringtone and vibration | **Pass** | Tester: on time, ringtone audible, phone vibrated, Decline stopped it. Phone: delivered 142 ms after the alarm's instant. |
| 4 | Swiped away, locked, cold call | **Pass** | Tester: full-screen call over the lock screen, ringtone heard, vibration felt, phone stayed locked behind the call, declined. Phone: new process, delivered 680 ms after the alarm's instant. |
| 5 | Schedule, cancel, nothing happens | **Pass** | Phone: Android recorded `alarm_cancelled` with 1m18s still to run. Tester: nothing happened. No delivery in the log. |
| 6 | Force-stop, negative control | **Pass** | Phone: process gone, package `stopped=true`, alarm cancelled (`pi_cancelled`). Tester: nothing rang. No delivery in the log. |
| 7 | Revoke the permission while armed | **Pass** | Phone: `exact_alarm_permission_revoked`, mode `deny`, alarm gone. Tester: nothing rang, and on reopening the card said "Scheduled calls off", the row said no and the button was greyed out. |
| 8 | Nothing left behind | **Pass** | Phone: after both calls, and again at the end, no Thalia notification, no running service, no held wake lock and no pending alarm. |

Test 8 was checked at 21:42:06 (baseline), 21:44:53 (after test 3), 21:48:35 (after test 4) and 22:04:17 (end).
Wake locks were read from the live list, not the history, following the correction recorded in the M1 results.

## Timings

Both figures come from the app's own clock, read in the receiver and in the service.

| | Test 3, warm | Test 4, cold |
|---|---|---|
| Alarm instant | 21:44:34.950 | 21:47:56.195 |
| Delivered after the instant | 142 ms | 680 ms |
| Ring command after delivery | 63 ms | 101 ms |
| Ring command after the instant | 205 ms | 781 ms |
| Process | 16583, alive in the background | 18468, new after the swipe-away |
| Phone state | unlocked, app in the background | locked, screen off, first minute of idle |

The alarm is limited by the 2 minute lateness rule from ADR 0005.
Both deliveries were far inside it.

## What the phone's own records showed

- The armed alarm was registered as `RTC_WAKEUP`, window 0, tag `*walarm*:com.thalia.app.action.CHECK_IN_ALARM`, `exactAllowReason=permission`.
- Android attached a 10 second temporary allowlist to the alarm's broadcast, with reason code 302. That fits the foreground service starting from the receiver without being refused, and both calls started.
- Removal history gave a distinct reason for each way an alarm ended: `alarm_cancelled` for the app's own cancel, `pi_cancelled` for the force-stop, and `exact_alarm_permission_revoked` for the revoke.
- At the cold call the phone's idle state was `QUICK_DOZE_DELAY` for deep idle and `INACTIVE` for light idle. It was asleep and locked, and not in deep Doze.

## Limitations

- One device, one Android build, one run of each test, and two measured deliveries.
- The cold call was in the first minute of idle. Deep Doze and an overnight idle are not tested.
- The receiver's dropped, refused and malformed outcomes never occurred on the device. The lateness drop, a refused service start and a payload with no id are covered only by JVM tests.
- No wake lock was taken by this slice. Its absence was checked after each call, not during the ring, so whether the alarm hand-over needs one is only answered for the short-idle case.
- The unlocked call in test 3 was not described by the tester beyond ringing and vibrating.
- The wait in test 5 was about 50 seconds past the alarm's time, not a full minute. Android had already removed the alarm, so the result stands.
- A fresh alarm was armed after the install every time. Whether an armed alarm survives `adb install -r` was not tested.
- The debug screen's memory of what is armed is this process's only, so it cannot show an alarm armed before the app was closed.
- Live log capture over wireless `adb` stopped when the phone slept. The cold call was read from the phone's own log buffer, which later rolled over.
- The tester turned Alarms & reminders back on after test 7.

## Findings

No defects in the app.

One finding for the next slice.
A force-stop cancels the alarm without any signal to the app.
After reopening in test 6 the screen read "Scheduled calls possible" and "Nothing armed", the same as a healthy phone.
Revoking the permission is visible on reopening, and a force-stop is not.
Detecting a lost alarm needs the stored occurrence record that the next slice adds.

## Still to test in later slices

- An overnight run: the real 10:00 and 15:00 check-ins on consecutive days, with the phone untouched and unplugged, measuring lateness from deep idle.
- Reboot: an armed alarm restored after an immediate unlock, the case where the phone is not unlocked until after the due time, and the case where the phone is off at the due time.
- A dropped late call and a refused service start, exercised on the device, with the ledger recording them.
- Duplicate prevention, once the occurrence claim exists.
- A lost alarm detected after a force-stop, and the notification for a revoked permission when the app is never reopened.
- Re-arming when the permission is granted again.
- An armed alarm across `adb install -r`, and across an app update.
- A time or time zone change while an alarm is armed.
- The full-screen intent withheld with a scheduled call, and Do Not Disturb with a scheduled call.
- An alarm arriving during a call already in progress.
- Battery bucket and app hibernation effects after days without opening the app.
- The ringtone under Android 17's background audio rules if `targetSdk` is ever raised to 37.
