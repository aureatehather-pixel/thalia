# ADR 0002: Voice Check-ins foundation

Date: 2026-09-18
Status: Accepted, implemented. Decision 1's cost argument amended 2026-09-18 by [ADR 0003](0003-optional-ai-provider-policy.md).
Milestone: M0
Branch: `spike/voice-checkins`
Builds on: [ADR 0001](0001-voice-checkins-spike-architecture.md), for the `:core` module decision. ADR 0001's "standalone application" framing is superseded by ADR 0003: Voice Check-ins is a Thalia feature, isolated by module, and `:app` is the foundation of the real Thalia application.

**Product assumptions superseded 2026-09-29 by [Thalia Product Specification v2](../product-spec-v2.md).**
The decisions below stand.
Three assumptions in the text they are argued from no longer describe the product, and are left as written:

- The fixed 10:00 and 15:00 calls, in the context, decision 3 and "What must be proven". The specification has an opening and a closing call per active day, at times the user confirms (its sections 5 and 7). The argument for exact alarms is unchanged: the user still picks a time.
- The free tier as the cost bound, in decision 6 and "What must be proven". The specification sets a default USD 10 monthly cap with warnings and explicit approval (its section 8).
- The ordering in the last paragraph, where scheduling controls come after M3. The specification's roadmap puts recurring calls, per-occurrence rescheduling and quiet periods in M2 (its section 18).

ADR 0001 decided *where* this lives.
This ADR decides *what M0 builds* and, more importantly, what it deliberately does not.

## Context

### What the product is

Two internet voice calls a day to the user's Pixel 10, at 10:00 and 15:00 local time, each opening a short spoken conversation with an AI about their plans.
The phone should ring and present a full-screen incoming-call screen with Answer and Decline, then an active-call screen with End Call.
It is not an alarm clock, and a declined or missed call is dropped rather than retried.
Full detail is in `THALIA_VOICE_CHECKINS_PRD.md`.

### What the repository actually contained

Verified at the time of writing, not assumed:

- `origin` is the project repository (private at the time of writing), default branch `main`.
- `main` holds exactly one tracked file, `README.md`, reading "This project is just getting started."
- Two unmerged branches hold the wider product PRD and a Lavish HTML click-through prototype.
- `spike/voice-checkins` held one commit, ADR 0001, and nothing else.
- No `settings.gradle`, no `AndroidManifest.xml`, no Kotlin source anywhere in the repository or the home directory.
- No `AGENTS.md`, `CLAUDE.md` or `CONTRIBUTING.md` on any branch, so there were no repository coding conventions to inherit.

So there was no existing architecture to fit into.
That is a finding, not a licence: it means every convention M0 introduces will be inherited by everything after it, which argues for introducing as few as possible.

ADR 0001 also recorded that no JDK, Android SDK, Gradle or `adb` was installed anywhere, and that `sudo` needed a password.
That is now partly out of date. A JDK 21 and Gradle 9.7.1 were installed under `~/.local/toolchains/`, which needs no `sudo`, so `:core` builds and tests for real. The Android SDK is still absent and is only needed from M1.

## Decisions

### 1. The call is an internet call, not PSTN

There is no phone number, no carrier, and no per-minute billing.
Audio travels over the data connection to whichever AI provider is answering.

**Amended 2026-09-18 by ADR 0003.** The cost argument below originally concluded that this makes the feature free. It does not, and the corrected statement is:

> Internet calling avoids PSTN and carrier charges. The implementation should prefer a provider whose free tier covers personal use, make any possible cost visible, and keep the provider replaceable.

Avoiding PSTN removes a charge that is certain, permanent and scales with use. It does not remove provider cost, which depends on a free tier that is somebody else's decision to change. That is exactly why decision 6 below is a requirement rather than a preference.

PSTN was rejected for three reasons, in order of weight:

- **Cost.** Every PSTN route bills per minute forever. Two calls a day for one person is a permanent, small, recurring bill, and unlike a provider's free tier it cannot be avoided by switching implementation.
- **It buys nothing.** A PSTN call would still terminate in the same AI voice session. The phone network would be an expensive courier between two endpoints on the same device's data connection.
- **It hands control away.** The interesting product decisions - what the call screen says, what happens on decline, what Thalia already knows - all live on the device. Routing through a carrier puts a third party between the schedule and the conversation, and buys back none of that control.

The consequence is accepted and stated in the PRD: this will never ring when the device is offline, and the UI must never imply it is a real phone call.

### 2. Android owns scheduling and the call surface

The alarm, the ring, the call screen, and the session lifetime all live in the app on the phone.
No server decides when to call.

A push-triggered design would need a backend, which the PRD forbids in the first version, and it would make the most reliable moment of the day depend on network reachability and on Firebase's own delivery latitude.
A phone can ring itself accurately from a local alarm without any of that.

This also means the feature keeps working with no account, no server bill, and no third party holding the user's schedule, which is what local-first ownership actually buys.

### 3. Exact alarms, because the user picked a time

`AlarmManager.setAlarmClock` (or `setExactAndAllowWhileIdle`) is the mechanism, one alarm per occurrence.

The user said 10:00. Not "sometime mid-morning". A check-in that arrives at 10:47 because the device was idle is a different product: it interrupts whatever the user actually started doing at 10:20, which is precisely the harm the PRD's no-harassment rule exists to prevent.

Inexact alarms are subject to Doze, App Standby buckets and batching, and can be deferred by a long and unpredictable margin on a device that has been idle overnight. That is fine for a sync. It is not fine for something that rings.

The cost is a permission the user can take away.
`SCHEDULE_EXACT_ALARM` is user-revocable from Android 12; `USE_EXACT_ALARM` is install-granted for genuine alarm-clock and calling apps but is policed by Play policy.
M2 must therefore treat "exact alarms unavailable" as a first-class, visible state rather than an error to swallow.

### 4. WorkManager is not a substitute

`WorkManager` is the right tool for deferrable background work and the wrong tool for a specific moment.
Its minimum periodic interval is 15 minutes, its actual execution is subject to the same Doze and standby deferral as inexact alarms, and it explicitly does not promise to run at a time.

It has a real but narrow job here, from M2 onward: topping up the alarm horizon, cleaning up old records, re-arming after an update. Never the call time itself.

### 5. A foreground service, held only while ringing or talking

A foreground service is started when the phone begins to ring and stopped when the call surface goes away.
A second one, or the same one, covers the active session.
Nothing runs between check-ins.

Both windows genuinely need it: ringing must survive the user not looking at the phone, and a live duplex audio session must survive the screen turning off. Neither can rely on an Activity staying alive.

A permanently running service was rejected. It would show a persistent notification the user did not ask for and would burn battery for twenty-three-and-a-half hours a day to be ready for two moments. The alarm already solves waking up; a service is only needed once something is actually happening.

### 6. The AI voice provider sits behind an interface

`VoiceSessionClient` is the seam. Above it there are only product concepts: a check-in, a transcript, a state machine, a result. Below it lives whatever actually talks.

Three reasons, all of which apply now rather than hypothetically:

- **The provider is genuinely uncertain.** Gemini Live is the intended first implementation because it has a free tier, but whether that free tier survives two live sessions a day indefinitely is an open question in the PRD. If the answer is no, the provider changes and the call screen must not notice.
- **M1 needs to run before any provider exists.** The riskiest thing in this product is whether a locked Pixel 10 raises a full-screen call screen. That risk is reachable in M1 only if the call UI can be built against something that is not Gemini.
- **Real-time voice is untestable in a unit test.** A WebSocket carrying PCM cannot be asserted on. A scripted session can.

The interface deliberately excludes API keys, endpoints, model names, audio formats and buffers. Those are per-provider construction details. If any of them leaked into the interface, it would stop being swappable, which is the only reason it exists.

### 7. M0 ships a deterministic fake, and only a fake

`FakeVoiceSessionClient` plays a scripted conversation from memory. No network, no key, no microphone, no timers, no randomness.

It implements the whole contract, not a stub of it: the full state machine, all three result shapes, mid-session hang-up, and connection failure. That matters because M1 builds the entire call experience against it, so anything the fake cannot express is a hole M3 discovers late.

It has no delays. A fake that sleeps to feel realistic makes every test slow and flaky and buys nothing, since the realism that matters (audio latency, partial transcripts, dropped sockets) is exactly what a fake cannot simulate honestly anyway.

Determinism is what makes it usable in three places at once: unit tests, Compose previews, and a manual on-device walkthrough of the call UI before any provider exists.

### 8. Local-first storage, consistent with Thalia

Schedules, occurrences and check-in records belong on the device, in Room, from M2.

This is not a new decision. The wider Thalia PRD section 6.3 already commits to local-only storage with no backend and no account, and calls it firm rather than a default to revisit. Voice Check-ins inherits that unchanged.

M0 stores nothing at all. The domain model is defined; no persistence exists. Introducing Room now would mean choosing an entity layout before knowing what M2 needs to query, and then migrating it.

### 9. `:core` is plain Kotlin, and stays that way

`:core` depends on the Kotlin standard library and coroutines. Not on the Android SDK, not on a provider SDK, not on an HTTP client.

This is what lets M0's tests be ordinary JVM unit tests: 34 of them run in seconds with no emulator, no instrumentation, and no Android SDK installed on the machine. It is also what lets these contracts move into Thalia later, per ADR 0001, if M6 goes that way.

`java.time` is used rather than a hand-rolled time model. It is in the JDK, it is available on Android from API 26 and the project's floor is 31, and the alternative is reimplementing time-zone arithmetic, which is a reliably bad idea.

## Key Android restrictions and risks

Each of these is a thing Android controls, not a thing this code controls.

| Risk | Why it matters | When it can be proven |
| --- | --- | --- |
| Full-screen intent on a locked Pixel 10 | The whole target experience. If Android will not raise the screen, this becomes a notification product | M1, on the device, only |
| `USE_FULL_SCREEN_INTENT` revocable from Android 14 | The user can silently downgrade the feature | M1 |
| Exact alarm permission revocable, and Play-policed | Without it the call time drifts by an unpredictable margin | M2 |
| Doze and App Standby | Overnight idle is exactly the state the 10:00 check-in fires from | M2, after a real overnight |
| OEM battery management | Pixel is the mildest case, but background restriction still exists and is user-settable | M2 |
| Boot without reschedule | Alarms do not survive a reboot. Without `RECEIVE_BOOT_COMPLETED` the feature silently stops | M2 |
| `RECORD_AUDIO` denied or revoked mid-call | A call that answers and then cannot hear | M3 |
| Duplex audio with echo | `AudioRecord` plus `AudioTrack` at once, on speaker, without the model hearing itself | M3 |
| Gemini free-tier limits | A cost constraint, not a technical one, and the PRD makes it binding | M3, and worth checking before |
| Do-not-disturb and an in-progress phone call | Both are states the check-in can land in, and neither has a decided answer | M5 |

## Alternatives considered

**A Thalia Android app first, with this as a module.** Rejected in ADR 0001. There is no Thalia app; building one to host a spike would let throwaway code choose the real product's foundations.

**PSTN via Twilio or Vapi.** Rejected in decision 1. Permanent per-minute cost for a personal tool, and it buys no capability.

**A server that places the call.** Rejected in decision 2. Needs a backend the PRD forbids, and makes the most reliable moment of the day depend on push delivery.

**`WorkManager` for the call time.** Rejected in decision 4. Documented not to run at a time.

**Android's `ConnectionService` / `TelecomManager` self-managed calling.** Not rejected, deferred. It is the sanctioned way to present an internet call, and it gets correct interaction with real phone calls, Bluetooth and car audio. It is also a large API with real integration cost, and the PRD's risk is whether the phone rings at all. M1 should answer that with a notification plus full-screen intent, which is far less code; `ConnectionService` is the obvious escalation if that proves unreliable, and M1's findings should decide it.

**A KMP `:core` module.** Rejected. There is one platform. Multiplatform would add build complexity to buy portability nobody has asked for.

**Building M0 against Gemini directly, skipping the fake.** Rejected in decisions 6 and 7. It would put an untestable network dependency under every later test, need an API key before anything works, and block M1 on M3.

**No `:core` module, everything in `:app`.** Rejected. The tests would then need the Android SDK and an emulator, which on a machine with no SDK installed means M0 could not be verified at all.

**Storing session state as one enum on the occurrence.** Rejected. Ringing and answering are call-surface facts; connecting and talking are provider facts. One enum spanning both would couple the call UI to the provider, which is exactly what decision 6 exists to prevent. Hence `CheckInStatus` and `VoiceSessionState` as two separate machines, joined by one explicit mapping function.

## What must be proven before this feature expands

M1, M2 and M3 each answer a question that could kill or reshape the product. None of them can be answered from a laptop.

**M1 must prove the phone can ring convincingly.**
A locked Pixel 10 raises a full-screen call screen from a full-screen intent, it looks and behaves like a call, Answer and Decline work, and the active-call screen holds while the screen is off.
If it cannot, the honest product is a notification and the PRD's target experience needs rewriting, not working around.

**M2 must prove it happens reliably, unattended.**
Exact alarms fire at 10:00 and 15:00 on consecutive days including after an overnight idle and after a reboot, and a revoked exact-alarm permission produces a visible degraded state rather than silence.
If it cannot, this is not a check-in product.

**M3 must prove the conversation is worth answering, and affordable.**
Duplex audio works on the device without the model hearing itself, latency is low enough to feel like a conversation rather than a walkie-talkie, and two sessions a day stay inside the free tier.
If the free tier does not cover it, the cost rule in the PRD forces a different provider or a visible cost, which is precisely what decision 6 protects.

Only after all three should M4 and beyond add context, memory, and scheduling controls.
Adding those earlier would mean building features on top of a call that may not ring.
