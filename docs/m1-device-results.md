# M1 device validation results: Pixel 10

Date: 2026-09-19
Procedure: [m1-device-validation.md](m1-device-validation.md)
Milestone: M1
Branch: `spike/voice-checkins`

## The answer

M1 asked one question:

> Will a locked Pixel 10 raise a full-screen incoming-call screen for Thalia, and does that screen read as a call?

**Yes.**
On the tested Pixel 10, a call that starts while the phone is locked and dark turns the screen on by itself and shows a full-screen call screen over the lock screen.
The phone stays locked behind it, it starts on time, and the ringtone and vibration start together with it.

This is one device, run by one person, without a photo or recording.
It is a strong first answer, and it is not a statistical one.

## What was tested

| Item | Value |
|---|---|
| Device | Pixel 10 (`frankel`) |
| Android | 17, API level 37 |
| App | `com.thalia.app.debug`, version `0.1-m1-debug` (code 1), target SDK 36 |
| Source | commit `e864d83`, working tree clean |
| APK SHA-256 | `b971b39dafab8fffe0f4809e2f933158688d9b8e03aa718390974e5a4d2af149` |
| Signing | the machine's auto-generated Android Debug key |
| Install | fresh, with no earlier Thalia build on the phone |
| Connection | wireless debugging over Wi-Fi, so no USB cable was attached |

Before install, the APK was checked from the computer.
It declares only the permissions M1 needs: notifications, full-screen intent, vibrate, wake lock and foreground service.
It has no internet permission, and the source contains no networking or dynamic-code-loading calls.
It was built after the last change to any tracked file, from a clean tree.

Ringer on and Do Not Disturb off were the starting conditions, as the procedure asks.

## After validation

After the physical run, one copy-only correction was made to the app's diagnostics card.
Its fallback text no longer calls the notification a heads-up, because the observed behavior was a lock-screen notification.
That change is wording only, and it did not change call or notification behavior.
The checksum above and every result in this document are for the APK that was physically tested.
A build made after this correction has a different checksum.

## Results by step

Every observation below was reported by the tester unless it says it was read from the phone.

| Step | Result | Matches the procedure |
|---|---|---|
| 0. Permissions and diagnostics | All four diagnostic lines read yes. Full-screen intent was allowed without the tester enabling it. | Yes |
| 1. Unlocked, screen on | A banner at the top naming Thalia and the morning check-in, not a full-screen call screen. Ringtone and vibration both repeated. Decline stopped both at once and removed the banner and notification. | Partly. See "Where the procedure was wrong". |
| 2. Locked, screen off | The screen turned on by itself. A full-screen call screen appeared over the lock screen, the phone stayed locked behind it, it started on time, and ringtone and vibration started with it. | Yes |
| 3. Declined from the lock screen | Ringing and vibration stopped. The phone returned to the lock screen and stayed locked. No notification or service was left. | Yes |
| 4. Answered | Ringing and vibration stopped on Answer. Android asked for an unlock. The active-call screen showed the simulated conversation, one line every 6 seconds, and ended by itself with a recorded-as-complete check-in. The shade showed an ongoing call with a timer counting up. | Yes, with two details unconfirmed below |
| 5. Ended by hand | From the call screen and, on a separate run, from the shade notification: the call ended immediately and the ended screen said **You ended the call**. No notification was left after Done. | Yes, with one detail unconfirmed below |
| 6. Nobody answers | Unlocked and locked: the call rang for the full 45 seconds, then showed a full-screen **Missed call** recorded as missed. Done closed it and nothing was left. | Yes |
| 7. The honest fallback | With full-screen intent off, the card read **Degraded**. The phone rang and vibrated while the screen stayed dark. Waking the phone showed a notification with Answer and Decline on the lock screen. Answer worked and opened the active call. | Yes, with a wording difference below |
| 8. Ringer switch and Do Not Disturb | Vibrate-only: no ringtone, but vibration and a notification with Answer and Decline. Do Not Disturb, unlocked: the notification and a small vibration, no ringtone. Do Not Disturb, locked: the full-screen call screen and vibration, no ringing. | Recorded, as the procedure asks |
| 9. Foreground service behaviour | Cancel test call worked. After the calls that were checked, no Thalia notification, service or wake lock remained. | Yes |

Full-screen intent was turned back on after step 7, and the phone confirmed it is allowed.
Do Not Disturb was turned off again after step 8.

## What the phone's own records showed

These were read from the computer over the wireless connection, using the device's wake-lock, vibration, notification and service records.
Times are the phone's clock.

- The test wake lock `thalia:test-ring-armed` was held for 10.04 seconds and released in the same moment the ringing began. That agrees with the tester's report that it started on time.
- An unanswered call vibrated for 45.05 seconds. That agrees with the 45 second ring timeout.
- A call declined from the lock screen stopped vibrating about 2.8 seconds after it began ringing, when the tester declined it.
- After the checked calls, all three were empty: Thalia notifications, the running call service, and held Thalia wake locks.

## What was not confirmed

The tester gave partial answers on three details.
They are recorded as unconfirmed rather than passed.

- Whether the shade timer stayed steady when a new transcript line arrived. It was seen counting up, but not watched across a line.
- Whether the screen says plainly that the conversation is simulated. The tester saw the simulated conversation, and did not report the wording.
- Whether the ended screen kept the transcript lines already said after End Call. The tester answered before the question was explained.

Also not read back: the exact wording of the top card at step 0, and whether Extend Unlock was off during the locked runs.

## Where the procedure was wrong

These are differences between the procedure and what Android did.
The procedure has not been edited, and these are the corrections it needs.

1. **Step 1 should expect a banner on an unlocked phone.** The procedure expects a full-screen call screen with Thalia open and the screen on. Android shows an incoming call as a banner when the phone is in use. Full-screen takeover is for a locked or dark screen, which is what step 2 tests.
2. **The wake-lock check prints history on Android 17.** `dumpsys power | grep -i thalia` matches the phone's log of past wake locks, so it prints many lines even when nothing is held. Reading the live wake-lock list is the reliable check.
3. **The fallback notification does not turn the screen on.** Step 7 expects a persistent heads-up notification. With the screen dark, the phone rang and vibrated, and the notification was waiting on the lock screen once the phone was woken. The honest description is a lock-screen notification with Answer and Decline, and no screen wake.

## What this means for the design

- The PRD's central assumption, that a locked phone can show a full-screen call screen, held on this device with the permission left at its default.
- [ADR 0004](adr/0004-m1-incoming-call-presentation.md) decision 1 names `ConnectionService` as the escalation if the full-screen surface does not reliably appear. Nothing in these results calls for it. The call screen appeared in the first locked ring, in the locked missed call, and in the locked Do Not Disturb run.
- The honest fallback works as designed. Without the permission, the phone still rings and vibrates and offers Answer and Decline, but it does not light the screen. A user who has revoked full-screen intent could miss a check-in that only rings.
- On this phone, Do Not Disturb did not stop the call screen or the vibration on a locked phone. It held back only the ringtone. That is one setting on one device, and it depends on Do Not Disturb's own exceptions.

## What these results do not say

- They say nothing about M2. The test starts a call from a running process that a wake lock keeps awake for ten seconds. A scheduled alarm waking a phone in deep sleep is a different question.
- They cover one device, one Android version, and mostly one run of each step.
- Nothing about conversation audio, the microphone, or any AI provider was tested, because none of it exists in this build.
