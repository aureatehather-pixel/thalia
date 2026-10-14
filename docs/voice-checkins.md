# Voice Check-ins: where this stands

Read this first if you are picking up the Voice Check-ins work.

Branch: `feature/m2-recurring-checkins`.

The approved product direction is [Thalia Product Specification v2](product-spec-v2.md), approved on 2026-09-29.
Its roadmap (section 18) is the milestone plan below, and it wins wherever this document or an older one disagrees.

## Milestones

| Milestone | State |
| --- | --- |
| **M0**, foundation and architecture | Complete. The `:core` module: domain model, provider contract, deterministic fake. |
| **M1**, local incoming-call experience | Complete, and **validated on a physical Pixel 10 on 2026-09-19**: a locked phone raises the full-screen call screen. Results in [m1-device-results.md](m1-device-results.md). |
| **M2**, reliable local scheduling | **Complete. Targeted Pixel acceptance passed on 2026-09-30, and a real confirmed check-in was delivered on time on 2026-10-02.** **Slice 1 implemented and validated on a physical Pixel 10 on 2026-09-19:** a real exact alarm starts the M1 call, warm and cold. See [ADR 0005](adr/0005-m2-slice-1-real-alarm.md) and [the results](m2-slice1-device-results.md). **Slice 2 implemented and validated on a physical Pixel 10 on 2026-09-29 and 2026-09-30:** Room persistence, the occurrence record, an atomic claim, and one reconcile path for reboot, update, time and permission changes. See [ADR 0006](adr/0006-m2-slice-2-durable-scheduling-and-recovery.md) and [the results](m2-slice2-device-results.md). **Slice 3 implemented and validated on a physical Pixel 10 on 2026-09-30:** opening and closing check-ins, per-day confirmation, and per-occurrence move, cancel and re-confirm. See [the results](m2-slice3-device-results.md). **Final slice implemented and validated on a physical Pixel 10 on 2026-09-30, on `42b48eb`:** the Room v3 to v4 migration, the quiet period, one call at a time, and an exact-alarm permission revoked past a due moment. See [ADR 0007](adr/0007-m2-final-slice-quiet-period-and-one-call.md) and [the results](m2-final-device-results.md). **Real delivery on a physical Pixel 10 on 2026-10-02, on `42b48eb`:** a confirmed 10:00 opening fired 401 ms late and, unanswered, ended `RING_TIMEOUT`. The phone was charging and not in Doze, so natural deep idle was not tested. See [the results](m2-overnight-device-results.md). On 2026-10-02 the user waived the remaining natural deep-idle and consecutive-day coverage as a blocking requirement. Delivery from natural deep idle on an unplugged phone, and consecutive days, remain known limitations. Edge cases not yet run on the phone are known limitations, listed in each results document. |
| M3, live voice foundation | **M3A, the manual Gemini Live feasibility spike, complete on 2026-10-02:** a two-way session on the Pixel 10 through the unchanged provider seam, with documented limitations. See [ADR 0008](adr/0008-m3a-manual-gemini-live-spike.md) and [the results](m3a-device-results.md). The rest of M3 is not started. |
| M4, personal information foundation | **Not complete.** The minimal part is defined by [ADR 0009](adr/0009-minimal-planning-loop.md), accepted 2026-10-02. **Slices 1A and 1B, the encrypted information foundation and the encrypted export, import and recovery, are implemented and accepted on a physical Pixel 10 on 2026-10-03**; see [the results](m4-slice1-device-results.md). Full M4 (areas, ideas, notes, routines, events, the capture inbox, the Share Sheet, microphone capture) is not started. The minimal loop's slice 2 is accepted on the Pixel and slice 3 is not started; see [the slice 2 results](m4-slice2-device-results.md). |
| M5, planning and execution loop | Not complete. Its minimal part is ADR 0009's slices 2 and 3. **Slice 2, the daily proposal and approval, is accepted on a physical Pixel 10 (2026-10-03 to 2026-10-04), with limitations;** see [the results](m4-slice2-device-results.md). **Slice 3 is not started.** |
| M6, contextual Thalia and local memory | Not started. The occurrence record and plan are stored from M2 slice 2. |
| M7, calendar and ecosystem integration | Not started. |
| M8, reflection, trust, backup, polish, daily fortune, and the usage guide | Not started. |

M1 is implemented and has been run on a physical Pixel 10.
Its whole purpose was one question that only the device could answer: does a locked phone raise a full-screen call screen for Thalia.
The answer was yes.
The recorded results, and the three details that were not confirmed, are in [m1-device-results.md](m1-device-results.md).
That is one device and one tester, and it says nothing about M2.

## Where to read next

- What Thalia is, the daily loop the check-in calls serve, and the call policy: [the product specification](product-spec-v2.md), sections 5 and 7.
- Why the feature was first built, and its original requirements, partly superseded by the specification: `THALIA_VOICE_CHECKINS_PRD.md`. Its milestone table, section 5, is superseded by specification section 18, and Do Not Disturb and a call already in progress, which ADR 0002 gave to M5 under the old meaning, belong to the M2 hardening backlog.
- Why it belongs to Thalia and may use an AI provider at all: [ADR 0003](adr/0003-optional-ai-provider-policy.md).
- Why M0 is shaped the way it is: [ADR 0002](adr/0002-voice-checkins-foundation.md).
- Why M1 presents a call the way it does, including the foreground-service type: [ADR 0004](adr/0004-m1-incoming-call-presentation.md).
- The original module decision, partially superseded and amended: [ADR 0001](adr/0001-voice-checkins-spike-architecture.md).
- How to test M1 on the phone: [m1-device-validation.md](m1-device-validation.md).
- Why the first M2 slice schedules the way it does, and what only the device can settle: [ADR 0005](adr/0005-m2-slice-1-real-alarm.md).
- What the Pixel 10 showed for that slice, and what is left for later slices: [m2-slice1-device-results.md](m2-slice1-device-results.md).
- Why slice 2 stores what it stores, the life of an occurrence, and the one reconcile path: [ADR 0006](adr/0006-m2-slice-2-durable-scheduling-and-recovery.md).
- What the Pixel 10 showed for opening and closing confirmation and per-occurrence revisions, including the armed-state finding and what is still untested: [m2-slice3-device-results.md](m2-slice3-device-results.md).
- Why the final M2 slice adds a suppressed status, how the quiet period is checked twice, and how the claim keeps one call at a time: [ADR 0007](adr/0007-m2-final-slice-quiet-period-and-one-call.md).
- What the Pixel 10 showed for the quiet period, one call at a time, and an exact-alarm permission revoked past a due moment: [m2-final-device-results.md](m2-final-device-results.md).
- What the Pixel 10 showed for a real confirmed check-in left overnight, and why natural deep idle is still untested: [m2-overnight-device-results.md](m2-overnight-device-results.md).
- What the Pixel 10 showed for the encrypted planning store and its export and import: [m4-slice1-device-results.md](m4-slice1-device-results.md).
- What the Pixel 10 showed for the daily proposal and approval, the schema 2 migration and recovery, and what is still unverified: [m4-slice2-device-results.md](m4-slice2-device-results.md).

Voice Check-ins is an **optional** Thalia feature, isolated by Gradle module.
It is not a separate product.
Deleting it must leave a complete, working Thalia, and that is a standing constraint on every milestone.
**Superseded in part 2026-09-29:** the [specification](product-spec-v2.md) makes the opening and closing check-in calls part of Thalia's daily loop, with a local structured fallback when AI or the network is unavailable, so the calls are no longer an optional extra. The live AI conversation remains optional, and the module isolation stands.
**Decided 2026-09-29:** the deletion test is replaced by this principle: Thalia's local data, alarms, planning and deterministic execution remain usable without an external AI provider.
Thalia is still not an alarm clock: a call may only be confirmed outside the user's quiet period, and if the user unexpectedly sleeps outside that period Thalia may still ring, because sleep cannot be inferred reliably.

## What exists

Two Gradle modules.

### `:core` (M0)

Plain Kotlin on the JVM.
No Android, no provider SDK, no network client.

```
core/src/main/kotlin/com/thalia/voicecheckins/
  domain/
    CheckInType.kt          MORNING or AFTERNOON
    CheckInStatus.kt        what became of one occurrence, which statuses are terminal, and the legal moves between them
    OccurrenceReason.kt     why an occurrence was missed, cancelled or failed, in what was observed and no more
    CheckInSchedule.kt      CheckInSchedule (one slot) and CheckInPlan (the validated pair)
    ScheduledOccurrence.kt  one check-in on one day, its fired and resolved moments, and the only way to change it
  voice/
    VoiceSessionClient.kt   the provider seam: VoiceSessionClient and VoiceSessionHandle
    VoiceSessionConfig.kt   what a provider is told about one conversation
    VoiceSessionState.kt    IDLE -> CONNECTING -> ACTIVE -> ENDING -> ENDED, with legal moves
    VoiceSessionEvent.kt    StateChanged, TranscriptAppended, Finished
    VoiceSessionResult.kt   Completed, Cancelled, Failed, and the mapping back to CheckInStatus
    Transcript.kt           Speaker and TranscriptLine
    fake/
      FakeVoiceSessionClient.kt   the deterministic stand-in provider
```

### `:app` (M1, and M2 slices 1 and 2)

The Thalia Android application.
`minSdk` 31, `compileSdk` 37, `targetSdk` 36, Compose, Kotlin with warnings as errors.
The application id is `com.thalia.app`, with a `.debug` suffix on the debug build.

```
app/src/main/kotlin/com/thalia/app/
  ThaliaApplication.kt        owns the call coordinator, the database, the scheduler and the reconciler, creates the notification channel
  MainActivity.kt             the development screen: trigger buttons and diagnostics
  ui/ThaliaTheme.kt           a working Material 3 theme, not a brand
  voicecheckins/
    devtools/DevHomeScreen.kt The Ring buttons, the locked-screen test, and the permission and full-screen-intent diagnostics
    devtools/DevTestRing.kt   the development-only, one-shot, in-process ten second delay behind "Ring in 10 seconds"
    devtools/DevScheduledRing.kt  the development-only control behind "Schedule in 2 minutes": records an occurrence and arms its alarm
    data/
      CheckInDatabase.kt        the Room database: the occurrence and schedule tables and their DAOs
      RoomOccurrenceStore.kt    the store on Room. Every change is one conditional UPDATE, so the claim has one winner
    schedule/
      OccurrenceStore.kt        the durable record: insert, the atomic claim, guarded moves, the plan
      OccurrenceScheduler.kt    records an occurrence, then arms its alarm, and keeps the record true if arming is refused
      Reconciler.kt             the one path that brings the record and Android's alarms back into agreement. Never rings a call.
      SystemEventReceiver.kt    reconciles after a reboot, an update, a time or zone change, or the permission being granted
      AlarmScheduler.kt         the interface, and what arming an alarm can answer
      ExactAlarmScheduler.kt    the policy: refuse without the permission, refuse the past. No Android types.
      AlarmManagerBackend.kt    the Android half: one PendingIntent per occurrence, setExactAndAllowWhileIdle
      CheckInAlarmReceiver.kt   the non-exported receiver: reads the intent, starts the M1 call, logs the outcome
      CheckInFireHandler.kt     the lateness rule and the atomic claim: over two minutes late does not ring, and nothing rings twice. No Android types.
      AlarmPayload.kt           what an alarm carries to the receiver, and rejecting one that says nothing
    call/
      CallUiState.kt            Idle, Ringing, Active, Ended, and what the service should present for each
      CheckInCallCoordinator.kt the call state machine, the ring timeout, and the fake session. Reports outcomes. No Android types.
      CallOutcomeRecorder.kt    where the call reports answered, declined, missed and finished, written to the store in order
      CallServiceController.kt  decides what the foreground service presents and when it stops. No Android types.
      CheckInCallService.kt     the foreground service: a thin shell that forwards to the controller
      CallRingAlert.kt          the looping ringtone and repeating vibration
      ArmedWakeLock.kt          a timeout-bounded CPU hold, used only while a test call is armed
      CallNotifications.kt      the silent high-importance channel, the call notifications, the full-screen intent
      CallCapabilities.kt       what Android will actually allow, read at runtime
      CallActivity.kt           the incoming and active call screen, shown over the lock screen
      ui/CallSurface.kt         the call screens in Compose
```

The rule for `:app` is that anything that can be decided without Android is decided in a class that does not import it.
`CheckInCallCoordinator` and `CallServiceController` are that rule in practice, and they are why the M1 lifecycle is covered by ordinary JVM tests.

### Two separate state machines, on purpose

- `CheckInStatus` is the **call surface's** record. Scheduled, ringing, answered, declined, missed. Android owns these.
- `VoiceSessionState` is the **provider's** live state. It begins only after the user answers.

They meet at exactly one place, `VoiceSessionResult.toCheckInStatus()`.
Keeping them apart is what lets the AI provider be swapped without touching the call UI.
Do not merge them.

## What M1 does

A development-only screen has a **Ring morning** and a **Ring afternoon** button.
Tapping one starts the foreground service, which:

1. posts a call-category notification on a high-importance channel, with a full-screen intent;
2. starts the looping ringtone and the repeating vibration;
3. raises the incoming-call screen over the lock screen where Android allows it.

From there:

- **Answer** opens a fake session. The transcript fills in one line every six seconds, the notification shows a running call timer, and **End Call** hangs up at once.
- **Decline** ends the call and records it as declined. No session is opened.
- **Nobody answers** for 45 seconds, and the call is recorded as missed.
- Every one of those ends the same way: the ringtone and vibration stop, the foreground notification is removed, and the service stops itself. Nothing runs between calls.

### Starting a call while the phone is locked

The Ring buttons ring immediately, with the screen on, so locking the phone afterwards does not test a call that starts locked.
For that, the development screen has **Ring in 10 seconds**.
It arms one test call, held in memory, that rings ten seconds later.
It is not scheduling: no AlarmManager, no WorkManager, nothing stored, and it does not survive process death or service destruction.

A bare coroutine delay would not fire reliably with the screen off, because a sleeping phone's timers stop and Android can freeze an idle app.
While a test is armed, the existing foreground service therefore stays up, with a quiet **Test call armed** notification and a Cancel action, and holds a partial wake lock with a timeout.
The wake lock is released when the call starts ringing or the test is cancelled.
The service continues through the call, or stops on cancellation.
Only one test can be armed at a time.
The exact procedure is in [m1-device-validation.md](m1-device-validation.md).

When Android withholds the full-screen intent, a notification with Answer and Decline remains available, and the app says so on its diagnostics card.
How that notification is presented depends on the device state.
On the tested Pixel 10, with the phone locked and dark, it waited on the lock screen and did not turn the screen on.
It never pretends a call screen appeared.

## M2 slice 1: a real alarm starts the same call

The Ring buttons and the ten second test start a call from a live app.
M2 asks whether Android can start the same call at a chosen time, with the app closed.

The development screen has **Schedule in 2 minutes** and **Cancel scheduled alarm**.
Scheduling arms one real exact alarm through `AlarmScheduler`.
Nothing keeps the process alive after that: the alarm belongs to Android.
When it fires, `CheckInAlarmReceiver` starts the same `CheckInCallService.startRinging` door the buttons use, carrying the occurrence id.

The rules, all from [ADR 0005](adr/0005-m2-slice-1-real-alarm.md):

- Exact alarms need `SCHEDULE_EXACT_ALARM`, which Android does not grant to a fresh install. The screen shows **Scheduled calls off** and a button to the settings page when it is missing.
- There is no approximate fallback. Without the permission nothing is armed.
- An alarm delivered more than two minutes after its moment does not ring, and is not retried.
- The occurrence is stamped in the device's current time zone.
- An armed alarm does not survive a reboot or a force stop. That is deliberate for this slice.

This slice keeps no record of a dropped or refused call.
To read what happened, filter logcat on the tag `ThaliaAlarm`.
The receiver logs when the alarm was delivered and how late it was, and the service logs when it received the ring command, so the hand-over between them can be timed.

It was validated on a Pixel 10 on 2026-09-19.
The results, and what is still open for later slices, are in [m2-slice1-device-results.md](m2-slice1-device-results.md).

## M2 slice 2: durable scheduling and recovery

Slice 1 armed an alarm and forgot about it.
Slice 2 writes every occurrence down, and brings the record and Android's alarms back into agreement after anything that can upset them.
The reasoning is in [ADR 0006](adr/0006-m2-slice-2-durable-scheduling-and-recovery.md).

- **A durable record.** Room stores each occurrence with its status, its reason, and when it was due, fired and resolved, and stores the plan. The debug screen lists what is stored, read from the database.
- **The life of an occurrence.** It is scheduled, then ringing, then answered, declined or missed, and an answered one ends completed or failed. The legal moves are in `:core`, and an illegal one is refused.
- **One atomic claim.** Before anything rings, the occurrence is claimed in the store. Only one caller can win, so a second delivery of the same alarm rings nothing.
- **One reconcile path.** It runs when the app starts and after a reboot, an update, a change of the clock or time zone, or the exact-alarm permission being granted. It re-arms future alarms, marks overdue occurrences missed without ringing them, and clears calls that were lost with their process.
- **Honest reasons.** An occurrence whose alarm had vanished is recorded as alarm missing. Thalia does not claim a force-stop or a restart, because both leave the same trace.
- **What it does not do.** Nothing creates the daily opening and closing occurrences. That is later M2 work.

It has been validated on one Pixel 10, and the results are in [m2-slice2-device-results.md](m2-slice2-device-results.md).
The questions only the phone could settle are listed at the end of ADR 0006, and the results doc records which were answered and which remain open.

## What is fake

`FakeVoiceSessionClient` is the only implementation of `VoiceSessionClient` that exists.

It plays a short scripted conversation from an in-memory list. It has:

- no network, no WebSocket, no HTTP;
- no API key and nowhere to put one;
- no microphone, no speaker, no audio of any kind;
- no randomness;
- no timing of its own.

Timing is injected.
With the default `lineDelay` of zero, the whole script plays without waiting, which is what unit tests and previews want.
The app passes six seconds, so a person can watch the call.
Under a coroutine test scheduler the delay is virtual, so a paced session costs no real time in a test.
End Call interrupts a wait immediately rather than after it.

It implements the full contract, including mid-conversation hang-up and connection failure.

```kotlin
// A normal conversation, played instantly.
val handle = FakeVoiceSessionClient().start(
    VoiceSessionConfig(occurrenceId = "2026-09-19-morning", checkInType = CheckInType.MORNING),
)
handle.events.collect { event -> /* drive the UI */ }

// A conversation a person can watch: one line every six seconds.
FakeVoiceSessionClient(lineDelay = 6.seconds)

// The user pressing End Call.
handle.endSession()

// A provider that will not connect.
FakeVoiceSessionClient(
    FakeVoiceSessionClient.Behaviour.FailsWhileConnecting(FailureReason.NO_NETWORK),
)

// A fixed script, for a Compose preview.
FakeVoiceSessionClient(script = { listOf(TranscriptLine(Speaker.THALIA, "Preview line")) })
```

`handle.events` is a cold flow. Collect it once per handle.

## What does not exist

Nothing in this list is missing by accident.
See the PRD's non-goals, and the milestone table above for where each one arrives.

- **The recurring schedule.** Nothing creates the recurring opening and closing occurrences, there is no quiet period, no per-occurrence rescheduling screen and no `WorkManager`. The only thing that makes an occurrence is the debug **Schedule in 2 minutes**. The plan is stored, and switching a slot off cancels its scheduled occurrences, but nothing yet schedules them. That is remaining M2 work. The stored plan still has the original morning 10:00 and afternoon 15:00 slots from M0, which predate the specification's opening and closing calls.
  Moving `CheckInType` from `MORNING` and `AFTERNOON` to opening and closing, and the Room migration that needs, are unresolved implementation work, deliberately not started.
- **Audio and any AI provider.** No microphone, no `RECORD_AUDIO`, no `INTERNET`, no WebSocket, no Gemini, no API key. This is M3, the live voice foundation.
- **Transcripts and memory.** Room stores only the plan and the occurrence record, meaning when each check-in was due and what became of it. No transcript, goal or context is stored. Goals and other personal information arrive in M4, and check-in memory in M6.
- **Production UI.** The screens are a probe. The heading on the development screen says so.

`:core` must stay free of Android and of any provider SDK.
If something needs the Android SDK or a provider SDK, it belongs in `:app`.

## How to build and test

You need a JDK 21 and, for `:app`, the Android SDK with platform 37 and its build tools.
On this machine both were installed per-user and are not on the default `PATH`:

```bash
export JAVA_HOME="$HOME/.local/toolchains/jdk21"
export ANDROID_HOME="$HOME/.local/toolchains/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"
```

`local.properties` tells Gradle where the SDK is. It is gitignored and machine-specific.

```bash
./gradlew :core:test               # the :core unit tests, JVM only
./gradlew :app:testDebugUnitTest   # the :app unit tests, on the JVM. Includes Room and Robolectric.
./gradlew :app:lintDebug           # Android lint
./gradlew ktlintCheck              # style, both modules
./gradlew ktlintFormat             # fix most style complaints automatically
./gradlew :app:assembleDebug       # the debug APK
```

`:core` needs no Android SDK, and that is deliberate: M0's verification must run on any machine.
Run the tasks one after another, not from several terminals, and stop the daemons afterwards with `./gradlew --stop`.
`gradle.properties` keeps the daemons small on purpose, because WSL on this laptop has about 3.5 GB in total.
The `:app` tests that use Room run on the computer under Robolectric, which downloads a simulated Android the first time and needs a JVM flag the build already sets.
Both modules compile with Kotlin warnings as errors, so a warning fails the build.
That is intentional.

### The tests

| File | Covers |
| --- | --- |
| `core` `domain/CheckInPlanTest.kt` | The morning/afternoon pair and the invariants that keep it valid |
| `core` `domain/NextOccurrenceTest.kt` | Next-occurrence arithmetic, including the exact-fire-time and daylight-saving edges |
| `core` `voice/VoiceSessionStateTest.kt` | Which state transitions are legal, and that the illegal ones are refused |
| `core` `voice/FakeVoiceSessionClientTest.kt` | The fake's full behaviour: completion, hang-up, failure, determinism, and paced playback on virtual time |
| `app` `CheckInCallCoordinatorTest.kt` | Ringing, answering, declining, End Call mid-conversation, the ring timeout and its cancellation, interruption |
| `app` `CallServiceControllerTest.kt` | The service lifecycle: the creation-time race, one notification and one alert, every way the service stops, and staying up from armed through to ringing |
| `app` `DevTestRingTest.kt` | The ten second test delay: fires exactly on time and once, cannot stack, can be cancelled, never rings over a call in progress |
| `app` `CallCapabilitiesTest.kt` | What the diagnostics tell the user to expect for each combination of permissions, and what missing exact-alarm permission means |
| `app` `ExactAlarmSchedulerTest.kt` | The scheduling policy: the alarm asked for, refusal without the permission, refusal of the past, a permission lost mid-call, and cancelling without it |
| `app` `CheckInFireHandlerTest.kt` | The lateness rule to the millisecond: exactly two minutes rings, one millisecond over does not, and a refused start is never reported as ringing |
| `app` `AlarmPayloadTest.kt` | An alarm that cannot say what it is for is rejected |
| `app` `DevScheduledRingTest.kt` | The debug control: two minutes ahead, the device's zone, a `dev-` id, replacing an earlier one, refusals, and cancelling after a restart |
| `core` `domain/CheckInStatusTransitionTest.kt` | Every pair of statuses against the table of legal moves, that nothing leaves a terminal status, and that every reason belongs to a status |
| `core` `domain/ScheduledOccurrenceTransitionTest.kt` | Moving an occurrence through its life, the times and reasons each move stamps, and the record shapes that are refused |
| `app` `OccurrenceStoreContractTest.kt` | What every store must do, run against the in-memory double and against Room: the claim has one winner, moves are guarded, and the plan is kept |
| `app` `RoomOccurrenceStoreTest.kt` | Room on real SQLite: everything stored survives closing and reopening, and a row that contradicts its status is refused |
| `app` `OccurrenceSchedulerTest.kt` | The record is written before the alarm is armed, a refusal cancels the record with its reason, and cancelling |
| `app` `CheckInFireHandlerTest.kt` (duplicates) | The same alarm delivered twice or many times at once rings once, and a late, refused or unknown delivery rings nothing |
| `app` `ReconcilerTest.kt` | Every row of the reconcile table, the lateness boundary, idempotence, every trigger doing the same work, and a lost race recording nothing |
| `app` `RebootRestorationRoomTest.kt` | A whole reboot on a real database file: the database is reopened, the alarms are gone, and the future ones come back |
| `app` `SchedulingEndToEndTest.kt` | The real receivers and Room under Robolectric: an alarm claims and starts the service once, a boot restores, and every system event reconciles |
| `app` `PlatformWiringTest.kt` | The merged manifest: the receiver is declared for each system broadcast, is not exported, and the permissions are exactly the ones used |
| `app` `LedgerCallOutcomeRecorderTest.kt` | The call's outcomes reach the store in order, with the moment they were reported |
| `app` `ReconcileTriggerTest.kt` | Which system broadcast means which trigger |

Time in the `:app` tests is virtual.
A 45 second ring timeout and a half-minute call cost no real time.

## The M1 boundary

M1 is **a local fake incoming-call experience**, and nothing more.

In scope for M1, all implemented:

- A new `:app` Android module, `minSdk 31`, Compose.
- A call-category high-priority notification with a full-screen intent.
- The incoming-call screen: caller identity Thalia, Answer, Decline.
- The active-call screen with End Call, driven entirely by `FakeVoiceSessionClient`.
- A foreground service held only while ringing or while a session is active.
- The permissions those need, and only those: `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `VIBRATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` and `WAKE_LOCK`. The service type is declared in ADR 0004. `WAKE_LOCK` is used only for the ten second test delay.
- A manual trigger, a button, to make the phone ring now, and a development-only ten second delayed trigger to make it ring while locked.
- The honest fallback: if the full-screen intent is unavailable, show a notification and say so, rather than faking a call screen.
- A finite ring timeout, so an unanswered call cannot ring or hold the service forever.

Explicitly **not** in M1: anything under "What does not exist" above.

The point of M1 is to answer one question: **does a locked Pixel 10 actually raise a full-screen call screen?**
Everything else in M1 exists to make that question askable.

## Risks that only a physical device can settle

None of these can be resolved by writing more code on a laptop.

1. **Full-screen intent on a locked Pixel 10.** The entire target experience depends on it. From Android 14, `USE_FULL_SCREEN_INTENT` is granted by default only to calling and alarm apps, and the user can revoke it. If the screen will not raise, this is a notification product and the PRD needs rewriting. **M1 tests this.**
2. **Ringing that reads as a phone.** M1 owns a looping ringtone and a vibration pattern, played with the ringtone usage. Whether that is audible, felt, and correctly silenced by the ringer switch and Do Not Disturb is a device question. **M1 tests this.**
3. **Exact alarms.** `SCHEDULE_EXACT_ALARM` is user-revocable from Android 12. Without exact alarms a 10:00 check-in can arrive materially late, which changes what the product is. M2.
4. **Doze and App Standby.** The morning check-in fires out of exactly the overnight-idle state these systems target. Only a real overnight proves it. M2. A real check-in rang on time on 2026-10-02, but on a charging phone not in Doze (see [m2-overnight-device-results.md](m2-overnight-device-results.md)). Natural deep idle on an unplugged phone is untested, and the user waived it as a blocking requirement on 2026-10-02.
5. **Reboot.** Alarms do not survive one, so untested reboot handling means the feature silently stops. Recovery after a reboot, with the phone unlocked before the due time, was validated once on one Pixel 10 (see [m2-slice2-device-results.md](m2-slice2-device-results.md)). The restoration is supported by process-start, alarm-stat and Room evidence, but the `BOOT: … restored=1` app log line was not retained. Still untested: the phone staying locked past the due time, the phone being switched off at the due time, and repeated or overnight boot timing. M2.
6. **Duplex audio.** `AudioRecord` and `AudioTrack` running at once, on speaker, without the model hearing its own voice. M3.
7. **Gemini free-tier limits.** A cost question, not a technical one, but the PRD makes it binding. Worth checking before M3 rather than during it.

## The rule this feature lives under

**Widened 2026-09-29 by the [specification](product-spec-v2.md).**
This section records the M0 rule from `PRD.md` section 6.4 and ADR 0003.
The specification restates it as "local-first, not AI-free": AI may understand and propose across Thalia, not only in this feature, through the validated pipeline in its section 8, while core data and planning stay usable locally.
Its cost policy replaces the free-tier preference below with a default USD 10 monthly cap, warnings at 70% and 90%, and explicit approval past 100%.
The deletion test is superseded, decided 2026-09-29, by this principle: Thalia's local data, alarms, planning and deterministic execution remain usable without an external AI provider.
External AI is opt-in: it begins disabled, and explicit consent is required before any context is sent externally.
The four conditions below still apply to any provider use.

Thalia's core is local-first and works with no cloud AI.
Voice Check-ins is the one place that may use an external provider, and it owes four things in exchange, from `PRD.md` section 6.4:

- **Explicit consent.** Off until switched on, with the provider named and the data leaving the device described plainly.
- **Locally protected credentials.** Runtime entry, Android Keystore backed, never committed, never logged, never exported.
- **A replaceable provider.** `VoiceSessionClient` is that seam, and it is now a policy requirement rather than a convenience.
- **Graceful, honest offline fallback.** Say what is unavailable and why. Never fail silently, never pretend to have called.

On cost: internet calling avoids PSTN and carrier charges.
It is not free.
Prefer a provider whose free tier covers personal use, make any possible cost visible, and keep the provider replaceable.
Do not describe this feature as costing nothing.
