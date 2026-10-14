# ADR 0008: M3A, a manual Gemini Live feasibility spike

Date: 2026-10-01
Status: Accepted 2026-10-02 for M3A only. Spike complete 2026-10-02: manual Gemini Live feasibility demonstrated on the Pixel 10, with documented limitations, in [the device results](../m3a-device-results.md). The key was deleted.
Milestone: M3A, the first slice of M3, live voice foundation
Branch: a new M3A branch, created from `f4ed33e` after this ADR is approved
Device hold: lifted on 2026-10-02. It required the M2 overnight and deep-idle result to be recorded before any M3A APK was installed on the Pixel. The M2 result is recorded in [the real delivery results](../m2-overnight-device-results.md), and the user waived the untested natural deep-idle coverage as a prerequisite.
Builds on: [ADR 0002](0002-voice-checkins-foundation.md), whose provider seam this spike is the first real test of, and [ADR 0003](0003-optional-ai-provider-policy.md), whose consent, credential, replaceability and offline conditions all apply.
Amends: ADR 0003's credential condition, for this spike only and under the exception and exit condition in decision 4.

M0 built a provider seam, `VoiceSessionClient`, and every call since has run against a scripted fake.
Nothing has yet shown that a real live-voice provider fits behind that seam, or that the Pixel can carry a two-way conversation through it.
M3A answers that and nothing else.
It is a spike: its output is evidence and a decision about M3, not a feature.

## The question

> Can Thalia hold one manually triggered, two-way Gemini Live voice conversation on the Pixel through a replaceable provider?

"Two-way" means the user's speech reaches the model and the model's speech is heard, for several turns, on the phone's speaker.
"Through a replaceable provider" means the conversation runs behind the existing `VoiceSessionClient` contract in `:core`, unchanged.
"Manually triggered" means a button on the debug screen, never an alarm.

## Decisions

### 1. Gemini Developer API, Free tier, `gemini-3.8-live`

**Amended 2026-10-02.**
This decision first chose the paid tier.
The user authorised the Free tier for this spike instead, on the dedicated project `thalia-m3a-spike`, which has no paid billing.

The provider is the Gemini Developer API on the Free tier, where `gemini-3.8-live` is listed as free of charge.
On the Free tier, Google may use the content submitted and the responses to improve its products, and human reviewers may read them; Google's terms say not to submit sensitive, confidential or personal information.
The paid tier does not use content that way, and moving to it later needs paid billing and a new consent wording.
Only synthetic context is sent, as decision 5 requires.

The model is `gemini-3.8-live`, listed on 2026-10-01 as the stable default Live API model.
The model ID is one constant in the provider module.
It is checked against the official model list on the day implementation starts, and if it has changed, this ADR is amended before code is written.

### 2. A direct WebSocket, for M3A only

The provider speaks the documented Live API protocol over a WebSocket, through OkHttp, to the `v1beta` `BidiGenerateContent` endpoint.

Two alternatives were checked against their documentation on 2026-10-01 and rejected for this spike:

- **Firebase AI Logic.** Its Live API support is in Preview, it does not yet return usage metadata, which decision 7 preserves for comparison with Google's records, and its model list did not include `gemini-3.8-live`.
- **The Google GenAI Java SDK.** Its documentation describes no Live API support, and it is a server-side library.

This choice is for M3A only.
M3 proper decides its transport again, together with its authentication (decision 4).

### 3. Architecture: one new plain JVM module, and debug-only wiring

```
:core            unchanged. VoiceSessionClient, VoiceSessionConfig, the state machine and results.
:voice-gemini    new, plain Kotlin on the JVM. No Android.
  LiveProtocol                    encodes and decodes the Live API's JSON messages. Nothing else.
  GeminiLiveVoiceSessionClient    implements VoiceSessionClient over a WebSocket.
  AudioIo                         the interface the client needs from the platform:
                                  capture() as a flow of PCM chunks, play(chunk), flushPlayback(), release.
  LiveUsage                       the usage metadata received, exposed by this client only.
:app, src/debug  the only place the spike exists in the app.
  AndroidAudioIo                  AudioRecord and AudioTrack behind AudioIo.
  credential store, consent store, reservation ledger, and the "Live voice test" debug section.
  a debug manifest adding INTERNET, RECORD_AUDIO, ACCESS_NETWORK_STATE and MODIFY_AUDIO_SETTINGS.
```

`:voice-gemini` is a `debugImplementation` dependency of `:app`.
`ACCESS_NETWORK_STATE` is there because the start gate asks `ConnectivityManager` for a validated network.
**Amended 2026-10-02:** `MODIFY_AUDIO_SETTINGS` is added, debug only.
It is a normal permission, granted at install with no prompt, and the session needs it to switch the audio mode to `MODE_IN_COMMUNICATION` and to route communication audio to the speaker with the communication-device API.
It is justified by Android platform behaviour.
Without this permission, the requested communication mode would not take effect.
Actual voice-processing behavior remains pending Pixel validation, in the M3A Slice 3 acceptance run, acceptance criterion 11 below.
A release build contains no network code, no microphone code and none of those four permissions, and the existing manifest assertions keep holding for it.

The client is constructed with its model ID, its credential and an `AudioIo`.
None of those cross the `:core` contract, which is what keeps the provider replaceable.
Usage is exposed on the Gemini client itself, not on `VoiceSessionClient`; whether usage belongs in the contract is a question for M3 proper.

`VoiceSessionConfig` requires an occurrence id and a check-in type.
A manual spike session has no occurrence, so it passes a synthetic id of the form `m3a-manual-<epoch millis>` and `OPENING`.
**The synthetic id is permitted for M3A only.**
It is never written to the occurrences table, and nothing in M2's scheduling path ever sees it.

Audio:

- **Capture:** `AudioRecord` from the `VOICE_COMMUNICATION` source, 16 kHz, mono, 16-bit PCM, sent as realtime input with the mime type `audio/pcm;rate=16000`. An `AcousticEchoCanceler` is attached when the device reports one available.
- **Playback:** `AudioTrack` at 24 kHz, mono, 16-bit PCM, with voice-communication usage, on the speaker, holding transient audio focus.
- **Turn-taking:** the provider's automatic voice activity detection. When the server reports an interruption, playback is flushed and queued model audio is discarded.
- **First turn:** after the server confirms setup, the client sends one short text turn asking for a greeting, so that time to first audio can be measured.

The audio formats and message shapes are taken from the official Live API documentation, read again on the day implementation starts.

### 4. Security exception: a device-held API key, for this private spike only

The Live API's documentation recommends ephemeral tokens instead of standard API keys in production.
Ephemeral tokens are minted with an API key on a server, and M3A has no backend.
So M3A authenticates with a standard API key held on the phone.

This is an **explicit security exception**, granted for a private, debug-only spike on one device, and on these conditions:

- **A dedicated key**, created for M3A alone, in a dedicated Google Cloud project, restricted to the Generative Language API.
- **Entered at runtime** on the debug screen, in a password field.
- **Encrypted with a key held in the Android Keystore**, AES-GCM, into an app-private file. Backup is already disabled for the app. A Clear control deletes it.
- **Never in the repository, a build file, `local.properties`, `BuildConfig`, a log, a crash message, a `toString`, or a URL that is logged.** OkHttp gets no logging interceptor.
- **Debug builds only**, by decision 3.

**Exit condition: the key is rotated or revoked when M3A ends,** whether the spike passes, fails or is abandoned.
M3A is not finished until that is done and recorded in its results document.

**Production direct client authentication with a standard API key is forbidden.**
M3 proper, and any build that is not this private spike, must authenticate through ephemeral tokens minted by a backend, or through a Firebase path with App Check that has been approved in its own ADR.
Neither is designed here.

**Amended 2026-10-08 by [ADR 0013](0013-grounded-text-ai-preparation.md), for the text conversation only.**
The captain approved a device-held API key for the text conversation on this personal installation, owned and used by one person on their own phone, and for no other installation.
The prohibition above stands for voice, for the Live API, and for any build distributed to anyone else.
ADR 0013 states the conditions; they are this decision's conditions (runtime entry, Keystore-backed encryption, never embedded, logged or exported) without the debug-only restriction and without the exit-on-spike-end rule, replaced by the owner's own clear and rotate controls.

### 5. Consent

External AI is off until it is switched on, as ADR 0003 requires.

The debug section has a consent switch, off by default.
Its wording names the provider and says what leaves the phone: the microphone audio captured while a session is running, a fixed system instruction, one fixed synthetic context line, and the greeting request.
It says that the Free tier is used, that Google may use what is sent and the responses to improve its products and human reviewers may read them, and that nothing personal or confidential should be said during a test.
Consent is stored with the moment it was given and a version of the wording, and withdrawing it is one tap.

With consent off, no connection is opened, and the microphone is not opened.

The context sent is synthetic and minimal: no goals, plan, calendar, memory or other personal data.
The system instruction says the session is a connection test, asks for replies of one or two sentences, and asks the model not to request personal information.

Nothing from the conversation is stored.
Raw audio is never written anywhere.
The transcript, built from the provider's input and output transcriptions, lives in memory for display and is gone when the screen is left.

### 6. Lifecycle and cleanup

A session follows the existing state machine.

| State | Entered when |
|---|---|
| `CONNECTING` | The start gate passed, and the socket is opening and the setup message is sent. |
| `ACTIVE` | The server confirmed setup, and microphone capture has started. Never before both. |
| `ENDING` | Any end, below, has been requested. Capture stops at once. |
| `ENDED` | Everything in the cleanup list is done, and exactly one result has been emitted. |

How a session ends:

| Cause | Result |
|---|---|
| End pressed | `Cancelled(ENDED_BY_USER)` |
| Five minutes reached, the default `maxDuration` | `Cancelled(TIMED_OUT)` |
| The screen is left, or audio focus is lost | `Cancelled(ENDED_BY_SYSTEM)` |
| The handshake or setup is refused for authentication | `Failed(NOT_AUTHENTICATED)` |
| The network is lost | `Failed(NO_NETWORK)` |
| The server closes the session or announces it will | `Failed(PROVIDER_UNAVAILABLE)` |
| The microphone or playback cannot be opened | `Failed(AUDIO_UNAVAILABLE)` |

Five minutes is inside the documented connection lifetime of about ten minutes and the fifteen-minute limit for audio-only sessions, so M3A needs no session resumption.

**Cleanup always runs, on every path,** including when the collecting coroutine is cancelled:
stop and release the `AudioRecord` and any echo canceller, stop, flush and release the `AudioTrack`, close the socket, abandon audio focus, and restore the audio mode.
Every result keeps whatever transcript was captured.

Sessions run only with the debug screen in the foreground, which holds the screen on for the session.
There is no foreground service and no `FOREGROUND_SERVICE_MICROPHONE`.
Android does not let a backgrounded app keep the microphone without one, and leaving the screen ends the session instead.

**One call at a time.**
The spike refuses to start while a check-in call is ringing or active.
M2's claim does not know about a spike session, so a check-in that falls due during one could still ring.
That is handled by a visible warning on the debug section, saying that scheduled check-ins are not aware of the test, and by a Pixel precondition: no occurrence is scheduled while spike sessions are run.
There is no gate on upcoming occurrences.

The start gate refuses, with its reason shown, when: consent is off, no key is stored, microphone permission is not granted, there is no validated network connection, a check-in call is ringing or active, or decision 7's allowance cannot cover another connection attempt.

### 7. Budget: a USD 2 experiment allowance, charged per connection attempt

**Amended 2026-10-02.**
This decision first computed money from `usageMetadata`.
The official Live API documentation, read again on 2026-10-02, does not say whether successive `usageMetadata` reports are cumulative for the session or incremental, so no cost can be computed from them without guessing.
The allowance below replaces that calculation.

**Usage metadata is preserved, never turned into money.**
M3A does not calculate money by summing reports, and it does not treat the latest report as a total.
Every readable `usageMetadata` report is kept raw, in arrival order, for later inspection.
The duration of PCM sent and received is recorded **as a diagnostic only**, and is never used as a cost either.

**The allowance.**
M3A has an experiment allowance of **USD 2.00** in total.
It is a constant in the debug code, and no control in the app raises it, resets it or bypasses it.

- **USD 0.25 is reserved and permanently charged when each real Gemini connection attempt begins,** after the start gate has passed and before the socket is opened. The charge is written durably first, so an attempt that crashes or is killed still counts.
- **There is no refund** for a session that fails, is cancelled or is short.
- **A connection is refused** when the charge would take the total past USD 2.00. That permits at most eight connection attempts.
- The amounts are held exactly, in integer micros or exact decimals, never in floating point.

USD 0.25 per attempt is the bound decision 7 first derived for one five-minute session, at the per-minute paid audio rates on the pricing page on 2026-10-01: 2 × 5 × (0.005 + 0.018) = USD 0.23, rounded up.
`maxDuration` therefore stays at five minutes for M3A.

**This is an experiment allowance, not measured billing.**
It limits how many sessions the spike may attempt.
It does not know what Google charges, and it is not a provider-side hard cap.

During acceptance, the raw usage metadata and the reservation ledger are compared with the usage dashboard of the dedicated Google project.
That comparison is evidence for M3 proper, which must settle how live usage is measured before any production budget is enforced.

The reservation ledger is one JSON line per attempt in an app-private file, not Room, so the spike adds no database migration.
**Clearing the app's data resets it,** so the allowance is a best-effort local guard, not a hard cap.
The backstops stay outside the app: Google's own records and the mandatory rotation or revocation of the key in decision 4, plus a USD 2 budget alert, which notifies but does not stop spending, whenever the project has paid billing.

**On the Free tier, amended 2026-10-02.**
The durable reservation ledger, the eight-attempt maximum and the no-refund rule stay exactly as above.
Its amounts are experiment accounting only, not actual Google charges. Free-tier use is recorded, but no billing or dashboard comparison was performed, so actual charges are not independently verified.
The budget alert is deferred unless paid billing is enabled on the project, and it must be set up before any paid use.

## Tests

The automated tests cover what the feasibility question, cleanup, the gates and the allowance need.
The provider tests run on the JVM against a local WebSocket server, with a fake `AudioIo`.
The gate and manifest tests run in `:app`.

- **Protocol:** the setup message carries the model, audio as the response modality, the system instruction and both transcriptions. Captured audio is encoded as realtime input with the right mime type. The server messages the spike relies on are decoded: setup confirmed, model audio, interruption, turn complete, both transcriptions, usage metadata, and the notice that the server will close.
- **Happy path, two-way audio:** captured audio is sent, and model audio received is played.
- **State order:** `CONNECTING`, then `ACTIVE` only after setup is confirmed, then `ENDING` and `ENDED`, every move legal, with exactly one `Finished` and it last.
- **Barge-in:** an interruption flushes playback and discards queued audio.
- **User end:** End gives `Cancelled(ENDED_BY_USER)`, and capture, playback and the socket are all released.
- **Coroutine cancellation:** cancelling the collector releases capture, playback and the socket.
- **Gates:** a start is refused, with its reason, when consent is off, when no key is stored, when there is no network, when microphone permission is not granted, when a check-in call is ringing or active, and when the allowance cannot cover another attempt.
- **Authentication:** a 401 or 403 maps to `NOT_AUTHENTICATED`.
- **Network loss and provider closure:** a lost connection maps to `NO_NETWORK`, and a server closure, announced or not, maps to `PROVIDER_UNAVAILABLE`. Both keep the transcript and release everything.
- **Audio failure:** failing to initialise the microphone or playback, or capture failing during a session, maps to `AUDIO_UNAVAILABLE`, and releases everything.
- **Key redaction:** the key never appears in a request URL as logged, an exception message, an event, a result or any output the client writes.
- **Usage and allowance:** every readable usage report is kept raw and in order, partial and malformed reports never break a session, and nothing sums reports or treats the latest as a total. USD 0.25 is charged, exactly, before each connection attempt opens its socket, it is never refunded, and a ninth attempt is refused. PCM duration changes nothing.
- **Manifest separation:** the debug build requests `INTERNET`, `RECORD_AUDIO`, `ACCESS_NETWORK_STATE` and `MODIFY_AUDIO_SETTINGS`, and the release build requests none of them. Neither build requests a telephony permission, `FOREGROUND_SERVICE_MICROPHONE`, or the microphone foreground service type.

Every existing test, and ktlint, stays green.

## Acceptance criteria

All on the Pixel 10, with no occurrence scheduled.

1. **Preconditions.** The APK's signing certificate matches the installed app's, the install keeps the app's data, and the stored occurrences are unchanged by it.
2. **Gates.** With consent off, with no key, and with microphone permission denied, a start is refused with its reason, and no connection is made.
3. **Conversation.** A session reaches the model's first audio, holds at least three exchanges over about two minutes on the speaker, and the model does not respond to its own voice. Time to first audio is recorded.
4. **Barge-in.** Speaking over the model stops its audio promptly.
5. **Clean end.** After End, the microphone indicator is off, Android shows no recording by the app, and nothing of the session is left running.
6. **Offline.** In airplane mode a start is refused as having no network. Airplane mode during a session ends it as `NO_NETWORK` and releases the microphone.
7. **Bad key.** An invalid key ends as `NOT_AUTHENTICATED`, and the key does not appear in the phone's log.
8. **Leaving the screen.** Pressing Home during a session ends it as `ENDED_BY_SYSTEM` and releases the microphone.
9. **Usage.** At most eight connection attempts are charged, every attempt appears in the reservation ledger, and the raw usage metadata and the ledger are compared with the dedicated project's usage dashboard, with any disagreement recorded.
10. **M2 untouched.** A development check-in call still rings and runs with the scripted fake.
11. **Audio mode and routing.** During a session the audio mode is actually `MODE_IN_COMMUNICATION` and communication audio is actually routed to the built-in speaker, checked on the phone, for example with `adb shell dumpsys audio`, and not only from the screen's report. After the session ends, the prior mode and routing are back.
12. **Ledger durability.** The first reservation on the Pixel succeeds, including the file sync and the sync of the directories that hold the newly created ledger, and the ledger file is present with exactly one record afterwards. If the directory sync, or any other part of the reservation, fails, the screen shows the ledger refusal: record the failure and stop there, and do not press Start again, because each failed attempt may still be charged.
13. **Exit.** The key is rotated or revoked, and that is recorded.

## What would make the spike fail

Any of these answers the question "no", or "not like this":

- Two-way audio cannot be sustained for several turns on the Pixel.
- The model hears and answers its own voice on the speaker, and the platform echo cancellation does not stop it.
- Latency makes a turn unusable in conversation.
- The provider does not fit behind the unchanged `VoiceSessionClient` contract.
- The microphone, playback or socket can be left open after a session ends.
- The key leaks into a log, a message or the repository.
- Usage metadata is missing, or Google's records cannot be matched to the sessions, so what the spike cost cannot be established afterwards.
- The cost of a short session makes two calls a day impractical within the specification's USD 10 monthly cap.

A failure is recorded as a result, with its evidence, not worked around inside the spike.

## Exclusions

- Scheduled and incoming check-in calls. They keep the scripted fake.
- **A successful M3A does not authorize integrating Gemini into scheduled calls.** That needs its own design and approval in M3 proper, including production authentication under decision 4.
- Production memory, transcript storage and raw audio storage.
- Planning data, goals, calendar and any real personal context.
- Any backend, including a server for ephemeral tokens.
- Full M3 polish: a settings screen, the production consent flow, session resumption, background or screen-off sessions, and the local structured fallback.
- Release builds.

## Consequences

Positive.

- The provider seam meets a real provider before anything is built on it.
- The spike is fenced into debug builds and one module, so removing it is deleting a module and a debug source set.
- Cost and latency are measured rather than assumed, before M3 commits to a provider.

Negative and accepted.

- A standard API key lives on one phone for the length of the spike. Decision 4 bounds that and ends it.
- The allowance is local and charged per attempt, not measured. Clearing the app's data resets it, and it can be far above or below what Google bills. Google's own records and the key's rotation are the backstop, with a budget alert only if paid billing is enabled.
- A check-in falling due during a spike session could ring over it. The warning and the Pixel precondition cover the spike, and M3 proper must make the claim aware of live sessions.
- The protocol is `v1beta` and the model is new, so details can change between this ADR and implementation. They are checked again on the day.
