# ADR 0005: M2 slice 1, a real exact alarm starts the existing call

Date: 2026-09-19
Status: Accepted, implemented, and validated on a physical Pixel 10 on 2026-09-19. See [the results](../m2-slice1-device-results.md).
Milestone: M2, first slice
Branch: `spike/voice-checkins`
Builds on: [ADR 0002](0002-voice-checkins-foundation.md) decisions 3 and 4, and [ADR 0004](0004-m1-incoming-call-presentation.md) decisions 1, 7 and 8.
Amends: the exact-alarm rows and one sentence of [ADR 0001](0001-voice-checkins-spike-architecture.md), which are marked inline there.
Amended by: [ADR 0006](0006-m2-slice-2-durable-scheduling-and-recovery.md), which adds the durable record and the reboot restoration this slice left out. Decision 4's statement that a dropped call leaves only a log line, and the consequence that an armed alarm is lost on reboot, no longer hold.

M1 proved that a locked Pixel 10 raises a full-screen call for Thalia.
It started that call from a live process that a wake lock kept awake for ten seconds.
It said nothing about whether Android can start the same call at a chosen time, with the app closed.
This slice asks exactly that, and builds the smallest real alarm that can answer it.

The readiness review that preceded this slice listed six decisions for the captain.
This ADR records them, and the structure that follows from them.

## Decisions

### 1. `SCHEDULE_EXACT_ALARM`, not `USE_EXACT_ALARM`

The app declares `SCHEDULE_EXACT_ALARM`.
It does not declare `USE_EXACT_ALARM`, and the manifest says why.

Three reasons:

- **The degraded state must be reachable.** The PRD requires a visible degraded state when exact alarms are unavailable. Android grants `USE_EXACT_ALARM` at install and the user cannot revoke it, so with it declared that state could never occur on the phone, and the permission-denied run could never be performed.
- **It claims a purpose Thalia does not have.** `USE_EXACT_ALARM` is for alarm clocks and calendars, and Google Play limits its use accordingly. The PRD says this feature "is not an alarm clock".
- **The permission is the honest one.** A fresh install that targets Android 13 or later is not granted `SCHEDULE_EXACT_ALARM`. The user turns it on under Alarms and reminders, and can turn it off again. That is a state the app must be able to see and show.

ADR 0001 chose `USE_EXACT_ALARM` and described it as the "user-revocable successor" of `SCHEDULE_EXACT_ALARM`.
That description was backwards.
Android's documentation says `SCHEDULE_EXACT_ALARM` is the one the user can revoke.
ADR 0001 is corrected inline.

When the user revokes the permission, Android stops the app and cancels all its exact alarms.
Nothing in the process can warn at that moment, so the app can only report the state when it next runs.
The diagnostics screen re-reads the permission on every resume and says plainly when scheduled check-ins are off.
A notification for the case where the app is never opened again belongs to a later slice.

### 2. No approximate-alarm fallback

When Android will not allow an exact alarm, nothing is armed.
`ScheduleResult.NotPermitted` is the whole answer, and the screen says "Scheduled calls off".

A check-in that arrives at 10:47 because an approximate alarm drifted is the harm ADR 0002 decision 3 exists to prevent.
A visible "off" is better than a quietly wrong time.

### 3. `setExactAndAllowWhileIdle`, not `setAlarmClock`

This is a call, not an alarm clock.
`setAlarmClock` is documented to fire normally in Doze, but it is meant for alarm clocks and would present the check-in as the user's next alarm.
`setExactAndAllowWhileIdle` can fire in Doze at most once per nine minutes per app, which two check-ins a day never approach.

This is a choice to be tested, not assumed.
If an overnight run shows the alarm arriving late, `setAlarmClock` is the escalation, and the side effect on the system's next-alarm display is the cost to weigh then.
`setAlarmClock` is deliberately not used in this slice.

### 4. A check-in delivered over two minutes late does not ring

`CheckInFireHandler` compares the moment of delivery with the instant the alarm was armed for.
Exactly two minutes late still rings.
One millisecond over does not, and the call is dropped.
It is not retried and not rung later.

A phone that was in deep sleep, or an alarm that Android held back, must not turn a 10:00 check-in into one that interrupts what the user began at 10:20.
That is the PRD's no-harassment rule applied to delivery.

There is no rule for an alarm that arrives early.
`AlarmManager` does not deliver an alarm before its instant, so an early reading can only be clock skew, and ringing a call slightly early is a better error than losing it.

This slice keeps no record of a dropped or refused call.
The receiver logs it under the tag `ThaliaAlarm`, and the durable record arrives with the occurrence ledger in the next slice.
Until then a dropped call leaves no trace on the phone other than the log.

### 5. The device's current time zone

The debug occurrence is stamped with `ZoneId.systemDefault()` at the moment it is armed.
It is not fixed to Kuala Lumpur.

For the recurring schedule in a later slice, the plan's zone will follow the device rather than the fixed `CheckInPlan.DEFAULT_ZONE`.
That change to `:core` is not made here, because this slice has no recurring schedule to apply it to.
When it is made, the `CheckInSchedule` comment about staying at 10:00 in a fixed zone after travel will need to change with it.

### 6. `targetSdk` stays at 36

Android 17 tightens background audio for every app, and adds a stricter rule for apps that target 37.
An app that plays audio in the background needs a visible activity or a foreground service that is not a short service.
An app that targets 37 also needs that service to have while-in-use capability.
A service started from the background mostly does not get it, and the exemption for exact-alarm apps covers only `USAGE_ALARM` audio.
Playback that is refused fails silently, with no exception.

Thalia's ringtone uses `USAGE_NOTIFICATION_RINGTONE` and is now started, for the first time, from a service that an alarm started in the background.
At target 36 the base rule is met, because the service is a `specialUse` foreground service.
Whether the ringtone is audible from an alarm-started service is something only the device can say, and raising the target would put a second unknown on top of it.

ADR 0004 decision 7 said to raise the target after M1 was validated.
This slice defers that.
It is raised only after the scheduled call has been heard on the phone at target 36, and the ringtone's audio usage has been settled.

### 7. The call path is the M1 call path, with the occurrence id carried through

The alarm does not ring, vibrate or show anything.
It calls `CheckInCallService.startRinging(context, type, occurrenceId)`, which is the same door the Ring buttons use.

The id travels through the service intent, the `Ring` command and `CheckInCallCoordinator.ring`, and reaches `VoiceSessionConfig.occurrenceId` when the call is answered.
The buttons keep a fixed `dev-trigger` id.
The coordinator's constructor no longer takes an id supplier, because the id belongs to a call and not to the coordinator.

The coordinator records nothing about the id.
A ring that is ignored because a call is already in progress leaves that call's id untouched.

### 8. Policy in plain Kotlin, Android in two thin classes

| Class | Role |
| --- | --- |
| `AlarmScheduler` | The interface: `schedule(occurrence)` and `cancel(occurrenceId)`. |
| `ExactAlarmScheduler` | The policy. Refuses without the permission, refuses a moment that is not in the future, and treats a permission lost mid-call as a refusal. |
| `AlarmManagerBackend` | The Android half. One `PendingIntent` per occurrence, armed with `setExactAndAllowWhileIdle`. |
| `CheckInAlarmReceiver` | Not exported. Reads the intent, hands it on, logs the outcome. |
| `AlarmPayload` and `CheckInFireHandler` | What the alarm carries, and the lateness decision. |

Each occurrence has its own `PendingIntent`, told apart by the intent's data, `thalia://checkin/<occurrenceId>`.
Android matches a `PendingIntent` on its action, data and target, and ignores extras, so this gives every occurrence exactly one alarm.
Arming an occurrence again updates that `PendingIntent` and replaces the alarm.
Cancelling finds it with `FLAG_NO_CREATE`, so a cancel never creates one.

Everything that can be decided without Android is decided in the first four rows, and is covered by JVM tests.
That a real `PendingIntent` replaces an existing one is `AlarmManager`'s behaviour and is left to the device.

### 9. The debug controls

`DevScheduledRing` and its two buttons exist only for development, gated by `BuildConfig.DEBUG` in the same way as the ten second test.
**Schedule in 2 minutes** arms one real alarm through the real scheduler and receiver.
**Cancel scheduled alarm** disarms it.

Unlike the ten second test, nothing keeps the process alive.
The alarm belongs to Android, so the app can be swiped away and the call still has to arrive.

One fixed occurrence id, `dev-scheduled`, means one armed alarm at a time.
It is distinct from any real occurrence id, which is a date and a type.
The screen's memory of what is armed is this process's only, so **Cancel** always goes to the scheduler and never trusts it.

## What this slice deliberately does not do

- No Room and no stored occurrence ledger. A dropped or refused call is logged, not recorded.
- No reboot restoration and no `RECEIVE_BOOT_COMPLETED`. An armed alarm does not survive a restart of the phone.
- No recurring 10:00 and 15:00 schedule, and no plan.
- No quiet hours, no `WorkManager` and no `setAlarmClock`.
- No response to the exact-alarm permission being granted, because there is nothing stored to re-arm.
- No notification when a call cannot be started. The failure is logged.
- No change to `:core`.

## What only a physical device can settle

None of these can be answered from a laptop, and the JVM tests do not claim to.

- Whether Android allows a foreground service to start from an alarm's broadcast. Android's documentation exempts an app that "invokes an exact alarm to complete an action that the user requests", and whether this call counts is a matter for the device. If it is refused, the receiver logs it and no call rings.
- Whether the ringtone is audible when the service was started by an alarm. Vibration is not affected by the audio rules, so a call that vibrates and is silent would look correct in every log.
- Whether the CPU stays awake between the alarm's delivery and the call ringing. ADR 0004 decision 8 said nothing in M2 depends on the wake lock. That was a statement about the ten second test, and it is open again here. The `ThaliaAlarm` log lines carry timestamps at the receiver and at the service, so the gap can be read.
- Whether the full-screen call appears from a cold alarm on a deeply idle, locked phone. M1 tested it only from a live process.
- Whether the alarm survives swiping Thalia out of recents, and whether it is cancelled by a force stop.
- What revoking Alarms and reminders does to an armed alarm, and whether re-granting it needs anything from the app.
- Whether the alarm arrives on time after a long idle period, which needs an overnight run.
- Whether an armed alarm survives reinstalling the debug build over itself.

## Consequences

- The scheduled path and the button path share one call, so everything M1 validated still applies to it.
- The occurrence id now reaches the session. The ledger in the next slice has a real id to record against.
- An armed alarm is lost on reboot and on force stop, and this slice says so on the debug screen.
- A dropped or refused call leaves only a log line. That is acceptable for a debug slice and not for the feature.
- `USE_EXACT_ALARM` is now ruled out in the manifest, so it cannot be added later without reversing this decision.
