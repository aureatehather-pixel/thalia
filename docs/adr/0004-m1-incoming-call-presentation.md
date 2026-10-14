# ADR 0004: How M1 presents an incoming call

Date: 2026-09-19
Status: Accepted, implemented, and validated on a physical Pixel 10 on 2026-09-19. See [the results](../m1-device-results.md).
Milestone: M1
Branch: `spike/voice-checkins`
Builds on: [ADR 0002](0002-voice-checkins-foundation.md), whose decision 5 (a foreground service held only while ringing or talking) and deferred `ConnectionService` alternative this ADR carries out.
Amends: the foreground-service permission row of [ADR 0001](0001-voice-checkins-spike-architecture.md).

M0 built the domain and the provider contract.
M1 builds the first Android surface on top of them: a fake incoming call, triggered by a button, that must ring a locked phone.
This ADR records the decisions M1 made that a later reader could not recover from the code alone.

## Decisions

### 1. Notification plus full-screen intent, not `ConnectionService`

The call is a `CallStyle` notification with `CATEGORY_CALL` on a high-importance channel, carrying a full-screen intent to `CallActivity`.
ADR 0002 deferred Android's self-managed `ConnectionService` as the sanctioned but heavier alternative, and asked M1's findings to decide whether it is needed.
Nothing in M1 changes that.
If the physical test shows the full-screen surface does not reliably appear, `ConnectionService` is the escalation, and the result belongs in the PRD.

### 2. The foreground service type is `specialUse`

The service declares `foregroundServiceType="specialUse"` and holds `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`.
ADR 0001 listed `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
That was a proposal made before any Android code existed, and it is superseded here.

Why not the alternatives:

- **`mediaPlayback`** describes a service that plays media the user is consuming. M1 plays nothing through the media path. The ringtone is a ringing alert, not media.
- **`phoneCall`** requires `MANAGE_OWN_CALLS` or the default-dialer role, and a `ConnectionService` integration. This is an internet call with no telecom integration, and claiming the type would mean holding a telephony permission the app has no business holding.
- **`shortService`** needs no permission but is capped at three minutes and cannot start other foreground services. A real check-in would hit that cap, so choosing it now would only move the problem to M3.
- **`microphone`** is the honest type for a live conversation, but M1 opens no microphone. It belongs to M3.

`specialUse` needs no runtime prerequisite and states plainly what the service is.
The manifest carries the required `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explanation.
Revisit at M3, when a real conversation adds the microphone and the type set will need to include `microphone`.

`startForeground` passes the type explicitly from Android 14, and omits it below, because the constant does not exist on older releases and `minSdk` is 31.

### 3. One owner for the service's behaviour: `CallServiceController`

`CheckInCallService` is a thin shell around Android's lifecycle.
`CallServiceController` is a plain Kotlin class that decides what the service presents, and it is the only place that does.
It reaches Android through six calls on `CallServiceHost`: show the armed notification (decision 8), show the ringing notification, show the ongoing notification, start the ring alert, stop it, and shut down.

Two rules follow from how Android creates a service, and both are unit tested:

- **Nothing is presented until the first start command has been handled.** A service starts observing coordinator state in `onCreate`, before `onStartCommand`. At that moment the coordinator is idle, or still showing the previous call's ended screen. The first version read that as "the call is over" and stopped the service before it could ring.
- **A presentation is applied once, when it changes.** Transcript lines are new state but not a new presentation, so they no longer re-post the notification. That fixed a duplicate alert and a call timer that restarted on every line.

Stopping uses `stopSelf(startId)`, so a start command that arrives after a stop was requested keeps the service alive.
A command that leaves nothing to carry asks for a stop once per command, because a later start can cancel an earlier stop.
If the service is destroyed while a call is still ringing or active, the coordinator ends that call rather than leave a state claiming it is in progress.

### 4. The ringing is owned by the service, and the channel is silent

`CallRingAlert` plays the default ringtone in a loop, with the ringtone audio usage, and repeats a vibration pattern.
The notification channel has no sound and no vibration.

The alternative was a channel sound with `FLAG_INSISTENT`.
It was rejected because the sound would then be owned by Android's notification service, which silences it only as a side effect of a later update or cancel.
For a call that must go quiet the instant it is answered, declined or timed out, a sound that the app stops directly, in the same call that removes the notification, is the one that can be reasoned about and tested.
The ringtone usage means the ringer switch and Do Not Disturb still apply.

The trade is that the alert now plays even when the full-screen intent is withheld and only a heads-up appears.
That is the correct behaviour for the fallback: a call that rings is still a call.

### 5. A ringing call times out at 45 seconds and is recorded as missed

The coordinator owns the timeout, so it is testable on virtual time.
Answering or declining cancels it.
On expiry the call becomes `MISSED`, and the state change stops the alert, removes the notification and stops the service through the same path as a decline.

45 seconds is a little longer than a real phone, deliberately, so a tester who is slow to notice the call can still answer it.

### 6. Timing is injected into the fake, and the app paces it

`FakeVoiceSessionClient` takes a `lineDelay`, zero by default.
Unit tests and previews use zero and finish instantly.
The app passes six seconds, so a morning check-in is a half-minute call: long enough to read the timer, watch the transcript fill in, and press End Call.
End Call interrupts a wait at once rather than after it.
M0's statement that the fake has "no timers, no delays" is amended in `docs/voice-checkins.md`: it has none unless a caller injects one.

### 7. `compileSdk` 37, `targetSdk` 36

Compose 1.12 and `core-ktx` 1.19 are built against API 37 and will not compile against 36.
`compileSdk` only widens the API available at build time and changes no runtime behaviour.
`targetSdk` is what opts the app in to new runtime behaviour, and stays at 36, which is what the Pixel 10 shipped with.
Raising it is a deliberate follow-up after M1 has been validated on the device.

### 8. A development-only ten second delayed trigger, carried by the service and a wake lock

The Ring buttons ring at once, with the screen on, so they cannot test a call that starts while the phone is locked, which is the M1 question.
`DevTestRing` adds one in-memory, one-shot, ten second delay behind a **Ring in 10 seconds** button.
It is deliberately not scheduling: no `AlarmManager`, no `WorkManager`, no persistence, no recurrence.
Only one can be armed at a time, and it refuses to arm while a call is ringing or active.
The button and service arm command are gated by `BuildConfig.DEBUG`.

A coroutine delay counts uptime, and a phone with a dark screen and nothing holding it awake stops counting.
Android can also freeze an app with nothing visible.
So for the armed window only, the existing foreground service stays up, with a quiet notification on its own low-importance channel, a live countdown and a Cancel action, and holds a partial wake lock with a timeout.
The controller treats "armed" as a third thing the service carries, ranked below ringing and active, and the timer rings before it clears, so the service cannot stop in the gap between the two.
The wake lock is released when the call starts ringing, on cancel, and when the service is destroyed.
The armed notification is shown immediately and uses a separate ID from the incoming call.
The service posts the incoming call before removing the armed notification and releasing the wake lock.

The alternatives were `AlarmManager` and `WorkManager`, which would make this the first piece of M2 scheduling and would test alarm delivery instead of locked-screen presentation.
`WAKE_LOCK` is a normal permission, granted at install, and nothing in M2 depends on it.
The button, the timer, the armed notification and the wake lock all live under `devtools` or are named for the test, and can be deleted together.

## What only a physical device can settle

These are unchanged in kind from ADR 0002, and M1's code cannot answer them.

- Whether the full-screen surface appears over a locked Pixel 10, and how fast.
- Whether the looping ringtone and the vibration are audible and felt as a ringing phone, under the device's ringer and Do Not Disturb settings.
- Whether Answer from the lock-screen notification prompts for unlock in a way that still reads as answering a call.
- Whether the foreground service, notification and alert are all gone after every way a call can end.
- Whether the ten second delay really fires on time with the screen off. The JVM tests prove the timer and the service's continuity. The wake lock is what is meant to make it true on a device, and only the phone can confirm it.

## Consequences

- The service is small enough to read in a minute, and the behaviour that matters is tested on the JVM.
- The fake now has one injected knob. It is the only change M1 makes to `:core`, and it defaults to M0's behaviour, so every M0 test passes unchanged.
- The ring alert plays whether or not the notification's full-screen intent was granted. That is intended, and `docs/m1-device-validation.md` tests it.
- The channel is created silent. Android ignores changes to a channel's sound once it exists on a device, so a build installed over one that created the channel with a sound would keep that sound. No such build has been installed. If one ever is, uninstall it first.
