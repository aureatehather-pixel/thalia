# Thalia Voice Check-ins - Product Requirements Document

Status: v0.3, foundation (M0) implemented and approved. Product decisions partly superseded on 2026-09-29 by [Thalia Product Specification v2](docs/product-spec-v2.md); see below.
Scope: an **optional** feature of the Thalia Android app, single user, not a commercial product.

**Partly superseded 2026-09-29.**
[docs/product-spec-v2.md](docs/product-spec-v2.md) is the approved product direction, and wins wherever this document disagrees with it.
The technical constraints in section 3 and the direction in section 4 still apply, except where listed here.
This document is not rewritten; these assumptions no longer hold:

- **Morning at 10:00 and afternoon at 15:00 (sections 1, 8 and 10.4).** The specification has an opening and a closing check-in per active day, at times confirmed during the previous closing call (its sections 5 and 7). No confirmed occurrence means no ring.
- **"Not an alarm clock" (section 1), made precise.** **Decided 2026-09-29:** Thalia is still not an alarm clock. A call may only be confirmed outside the user's quiet period. If the user unexpectedly sleeps outside that period, Thalia may still ring, because sleep cannot be inferred reliably.
- **An optional feature, off by default, whose removal leaves a complete Thalia (the scope line, and sections 3 and 9).** The specification lists scheduling and placing the check-in calls among what Thalia does (its section 3), with a structured local fallback when AI or the network is unavailable. The live AI conversation remains optional.
  **Decided 2026-09-29:** the deletion test is superseded by this principle: Thalia's local data, alarms, planning and deterministic execution remain usable without an external AI provider. External AI is opt-in: it begins disabled, and explicit consent is required before any context is sent externally.
- **Free-tier cost (sections 3 and 10.1).** The specification sets a default USD 10 monthly cap on paid provider use, with warnings at 70% and 90% and explicit approval past 100% (its section 8).
- **The milestone table (section 5).** The specification's roadmap (its section 18) replaces it. M2 now also includes recurring opening and closing calls, per-occurrence rescheduling, quiet periods and confirmed-call safety. M3 is the live voice foundation, and context and memory move to M6.
- **Open questions answered by the specification (section 10).** Phone off past the lateness window: record missed, never ring late. Declined or missed calls do not repeat. Quiet periods strictly prevent ringing. Raw audio is not stored by default, transcript retention is optional, and structured summaries are preferred (its sections 7 and 12).

Voice Check-ins is part of Thalia, not a separate product or repository.
It is isolated by Gradle module, and Thalia with Voice Check-ins switched off is a complete v1.

Related documents in this repository:

- `PRD.md` - the Thalia product PRD. Section 5.6 places this feature; section 6.4 permits it.
- `docs/adr/0003-optional-ai-provider-policy.md` - why this feature belongs to Thalia, and the optional-AI rule that allows it.
- `docs/adr/0002-voice-checkins-foundation.md` - the M0 architecture decision.
- `docs/adr/0001-voice-checkins-spike-architecture.md` - the original module decision. Partially superseded by ADR 0003.
- `docs/voice-checkins.md` - what exists today and how to run it.

## 1. Product purpose

Thalia Voice Check-ins places two internet voice calls a day to the user's Pixel 10 and holds a short spoken conversation about their plans.

Defaults:

- Morning check-in at 10:00.
- Afternoon check-in at 15:00.
- Times are device-local wall-clock times, initially in `Asia/Kuala_Lumpur`.
- Either occurrence can be rescheduled by the user, independently of the other.

Two things this is deliberately not:

- It is not an alarm clock. It is not intended to wake the user, and it must not be built as though waking them were acceptable.
- It is not a nag. A missed or declined check-in is recorded and dropped. There is no retry, no escalation, and no second ring for the same occurrence.

## 2. Target experience

At the scheduled time the phone presents an incoming-call experience.

- Rings and vibrates according to the device's own settings, not according to settings Thalia invents.
- Shows a full-screen incoming-call interface where Android permits it, including over the lock screen.
- Identifies the caller as Thalia.
- Offers Answer and Decline.
- On answer, shows an active-call screen with a visible End Call control.
- Mute and Speaker controls are desirable later, not required for the first working version.
- Where Android restricts full-screen presentation, the feature degrades to a high-priority notification rather than failing silently or pretending it rang.

After answering, Thalia conducts a short live voice conversation informed by the user's current goals, schedule, project priorities, and what the previous check-in established.

## 3. Constraints

These bound every later decision.

- Android-first. Kotlin and Jetpack Compose. No iOS, no web, no desktop build of this feature.
- Personal use. One user, one device. Generality is not a goal and must not be paid for.
- Local-first data ownership. Schedules, goals, and check-in records live on the device.
- Optional, and removable. The feature is off until switched on, with the provider named and the data leaving the device described plainly. Thalia's core must stay complete and fully usable with it off. See `PRD.md` section 6.4.
- Graceful offline fallback. With no network or no credential, the feature says what is unavailable and why. It never fails silently and never pretends to have called.
- No required custom backend in the first version.
- No paid telephony. No PSTN, no phone number, no carrier minutes, no Twilio or Vapi style per-minute billing.
- This is an internet call, not a cellular phone call, and the product must never imply otherwise.
- Cost is bounded and visible. Internet calling avoids PSTN and carrier charges. The implementation should prefer a provider whose free tier covers personal use, make any possible cost visible, and keep the provider replaceable. It must not be described as free.
- No secrets in Git, at any milestone, in any form.
- The AI voice provider must sit behind an interface so it can be replaced without touching the call surface.
- Truthful UX. If Android has degraded the experience to an ordinary notification, the user is shown a notification, not a fake call screen.
- Android's background-execution, exact-alarm, notification, microphone, and full-screen-intent restrictions are handled explicitly, and their failure modes are visible to the user rather than silent.

## 4. Proposed technical direction

Provisional. Each of these is a decision to validate on the physical device, not a settled fact.

| Area | Direction |
| --- | --- |
| Language and UI | Kotlin, Jetpack Compose |
| Local storage | Room, for schedules, goals, and check-in records |
| Timing | `AlarmManager`, one exact alarm per occurrence |
| Alarm delivery | A `BroadcastReceiver` that starts the ringing surface |
| Ringing | A high-priority notification in the call category |
| Full-screen | A full-screen intent for the incoming-call screen, where allowed |
| Process lifetime | A foreground service held only while ringing or while a session is active |
| Audio | `AudioRecord` and `AudioTrack` for duplex capture and playback |
| AI voice | Gemini Live API over a WebSocket, from M3 |
| Secrets | Android Keystore backed local storage, entered at runtime, never committed |
| Swappability | A provider interface, so Gemini can be replaced |
| Background maintenance | `WorkManager`, for non-exact upkeep only, never for the call time itself |

Two of these need stating plainly because they are the parts Android controls, not the parts this code controls:

**Exact alarms are a permission, not an API call.**
From Android 12 an app cannot schedule an exact alarm without `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM`.
`USE_EXACT_ALARM` fits an alarm-clock style use case and is granted at install, but Google Play polices its use and the user can still be in a state where exact alarms are unavailable.
When exact alarms are unavailable the feature must show a visible degraded state, not quietly drift.

**Full-screen intents are a permission and a policy, not a guarantee.**
From Android 14 `USE_FULL_SCREEN_INTENT` is granted by default only to apps whose purpose is calling or alarms, and the user can revoke it.
Whether a locked Pixel 10 actually raises the call screen is the single riskiest assumption in this product, and nothing about it can be proven off-device.

## 5. Milestones

| Milestone | Outcome |
| --- | --- |
| M0 | Foundation and architecture. Domain model, provider contract, deterministic fake, tests, decision records. No Android. |
| M1 | A local fake incoming-call experience in the real `:app` module. Ringing, answer, decline, active-call screen and End Call, all driven by the fake provider, triggered by a development-only button. |
| M2 | Reliable scheduling. Exact per-occurrence alarms, reboot restore, per-occurrence rescheduling, visible degraded state when exact alarms are unavailable. |
| M3 | Live AI voice conversation. Real microphone capture, real playback, Gemini Live over a WebSocket behind the existing interface. |
| M4 | Local context and check-in memory. Goals, schedule and prior check-ins feed the conversation; transcripts are stored. |
| M5 | Scheduling controls and missed-call behaviour. User-facing schedule editing, do-not-disturb handling, and the no-harassment rules made real. |
| M6 | Architecture review: whether the module boundary still holds, what `:core` should give the rest of Thalia, and whether the provider seam survived contact with a real provider. |

## 6. Explicit non-goals for M0

M0 is foundation only. None of the following is implemented, and their absence is intentional rather than incomplete:

- Gemini, or any external AI provider.
- Microphone capture.
- Audio playback.
- WebSockets, or any network call.
- Real ringing.
- `AlarmManager` scheduling.
- Notification channels.
- Full-screen intents.
- Foreground services.
- Android permissions.
- Room, and therefore Room schema migrations.
- Any polished user interface.
- Backend services.
- PSTN, Twilio, or Vapi style calling.

## 7. What M0 delivered

- A Gradle build that any machine can bootstrap from the wrapper, with one plain Kotlin/JVM module, `:core`.
- A domain model for the two daily check-ins: `CheckInType`, `CheckInSchedule`, `CheckInPlan`, `ScheduledOccurrence`, `CheckInStatus`, and pure next-occurrence arithmetic.
- A provider-neutral voice contract: `VoiceSessionClient`, `VoiceSessionHandle`, `VoiceSessionConfig`, `VoiceSessionState`, `VoiceSessionEvent`, `VoiceSessionResult`.
- `FakeVoiceSessionClient`, a deterministic scripted implementation that needs no network, no key, no microphone and no timers.
- 34 JVM unit tests, plus ktlint, running on an ordinary machine with no Android SDK and no device.

Full detail is in `docs/voice-checkins.md`.

## 8. Success criteria

The feature is successful when, without the user doing anything on the day:

1. The phone rings at 10:00 and 15:00 local time, on consecutive days, including from a locked screen and after a reboot.
2. Answering opens a live spoken conversation with Thalia within a few seconds.
3. The conversation reflects what the user is actually working on, not generic prompting.
4. Declining or ignoring a check-in produces exactly one silent record and no further interruption that day.
5. Everything above happens with no backend, with no API key ever present in the repository, and with any provider cost visible rather than assumed away.

## 9. Relationship to the Thalia product PRD

Settled 2026-09-18. This section previously recorded an unresolved conflict; it now records how it was resolved.

The Thalia PRD's section 6.4 formerly stated that Thalia contains "no AI model, no LLM API integration, and no API key, ever".
That absolute restriction has been replaced. The governing rule is now:

> Thalia's core planning, goals, scheduling, and stored data remain local-first and usable without cloud AI. Optional features such as live Voice Check-ins may use a replaceable external AI provider with explicit user consent, locally protected credentials, and graceful offline fallback.

Voice Check-ins is the first feature to use that permission, and it must satisfy all four of its conditions.
The reasoning is in `docs/adr/0003-optional-ai-provider-policy.md`; the amended text is in `PRD.md` section 6.4.

Two consequences for this document:

- Voice Check-ins is a Thalia feature in the Thalia repository, isolated by Gradle module. It is not a separate product, and `:app` is the foundation of the real Thalia application rather than a spike surface.
- The test that keeps the rule honest is that **deleting this feature must leave a complete, working Thalia**. That is a constraint on every milestone from here, not a one-time check.

`PRD.md` section 5.5 keeps Voice Notes as a v1 placeholder with no real audio. That is unchanged and does not conflict: Voice Notes and Voice Check-ins are independent features, as that section now states.

## 10. Open questions

1. Whether Gemini's free tier actually covers two live voice sessions a day, indefinitely, for one user. This must be answered before M3, not discovered during it. If it does not, the answer is a different provider or a visible cost, not a quiet bill.
2. What the consent surface looks like, given the feature is off by default. Where the switch lives, what it says about the data leaving the device, and what happens to stored transcripts when it is switched back off.
3. What happens when a check-in is due during a real phone call, during do-not-disturb, or while the phone is off. Each has a defensible answer; none is decided.
4. Whether the afternoon check-in should adapt its time to the morning one, or stay fixed.
5. Whether transcripts are stored in full, summarised, or discarded after use. This is a privacy decision, and it belongs to the user, not to the implementation.
6. What Thalia's `applicationId` and minimum SDK should be for the long term. M1 chooses them, and under ADR 0003 they are the real application's, not a spike's.
