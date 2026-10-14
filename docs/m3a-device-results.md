# M3A device results: manual Gemini Live on the Pixel 10

Date: 2026-10-02, 17:44 to 19:30 phone time
Milestone: M3A, the manual Gemini Live feasibility spike, Slice 3
Branch: `spike/m3a-gemini-live`
Design: [ADR 0008](adr/0008-m3a-manual-gemini-live-spike.md)

## The answer

The question for M3A was:

> Can Thalia hold one manually triggered, two-way Gemini Live voice conversation on the Pixel through a replaceable provider?

**Yes, with the limitations below.**
A session through the unchanged `VoiceSessionClient` seam held a two-way conversation on the speaker for 1 min 44 s, with at least three exchanges, prompt barge-in and no self-echo observed in that conversation.
The phone confirmed the communication audio mode and speaker routing, and every session released the microphone, playback, audio focus, audio mode and socket when it ended.

Two criteria were waived by the user and are unverified, not passed: a bad key, and losing the network during a session.
Time to first audio was not measured, Google's usage dashboard was not compared, and the scheduled-alarm path of M2 was not run on this build.

This is one device, one Android build, three connection attempts and one tester.

## What was tested

| Item | Value |
|---|---|
| Device | Pixel 10, the same phone as M1 and M2 |
| Android | 17, API level 37 |
| App | `com.thalia.app.debug`, target SDK 36 |
| Provider | Gemini Developer API, **Free tier**, model `gemini-3.8-live`, direct `v1beta` WebSocket |
| Project | `thalia-m3a-spike`, a dedicated project with a key restricted to the Generative Language API |
| First install | `29c2976`, APK SHA-256 `99424b63…c1cd`, at 17:50:47 over `42b48eb` |
| Build under test | `29c2976` with the Free-tier consent wording, committed with this document. APK SHA-256 `6ffc01e5d911bf01a56ca989d744d6d905d8f30d3bb7be43fd21273088d20f4c`, installed at 18:45:08 |
| Signing | Both APKs and the installed app: `fb3348fe12dac4e025cc4a768e63ed19db4a65c261e56ca2126da020706d31dd` |
| Consent wording | `m3a-consent-free-tier-2026-10-02` |

Both installs were in-place `pm install -r` updates of an APK pushed to the phone and checked by hash there.
A streamed `adb install -r` dropped over wireless debugging first and changed nothing.

Every time below is the phone's clock.
**System** marks evidence read from the phone: the app's files, Android's audio service, the system log and the socket table.
**Tester** marks what the user observed and reported.

## Results

| # | Criterion | Result | Basis |
|---|---|---|---|
| 1 | Preconditions | **Pass** | System: matching certificate, `firstInstallTime` unchanged, the check-in database byte-identical across both installs, no occurrence live |
| 2 | Gates | **Pass** | System: consent off and no key each refused with no reservation and no socket. Tester: microphone denied refused |
| 3 | Conversation | **Pass, except time to first audio** | System: a session of 1 min 44 s on the speaker. Tester: at least three exchanges, clear and usable audio, no self-echo heard. **Time to first audio was not measured** |
| 4 | Barge-in | **Pass** | Tester: speaking over the model stopped its audio promptly. Interruptions are not logged |
| 5 | Clean end | **Pass** | System: End and Home each released recording, playback, focus, mode and socket. Tester: the microphone indicator went off |
| 6 | Offline | **Start refused. In-session loss waived, unverified** | System: a start without a network was refused at no charge. The user waived the in-session test |
| 7 | Bad key | **Waived, unverified** | The user waived it. No attempt was made |
| 8 | Leaving the screen | **Pass** | System: Home ended the session `ENDED_BY_SYSTEM` 1.07 s after the screen paused |
| 9 | Usage | **Partial. Dashboard comparison not performed** | System: three ledger records, and raw usage for attempts 1 and 2 |
| 10 | M2 untouched | **Pass, through the M1 test ring** | System and tester: the scripted call rang and ran. The scheduled-alarm path was not run on this build |
| 11 | Audio mode and routing | **Pass** | System: `MODE_IN_COMMUNICATION` with the speaker as the communication device during the session, both restored afterwards |
| 12 | Ledger durability | **Pass** | System: the first reservation succeeded and left exactly one record |
| 13 | Exit | **Done** | Tester: the key was deleted in Google AI Studio. System: the app's sealed copy is gone |

## Gates

| Time | Check | Build | Evidence |
|---|---|---|---|
| 18:35:14 | Consent off, no key | `29c2976`, earlier wording | System: "Not started: Consent to the current wording has not been given." State IDLE, 0 of 8 attempts, no ledger file, no Thalia socket |
| 18:48:44 | Consent on, no key | Free-tier wording | System: "Not started: No API key is saved." State IDLE, 0 of 8, no ledger file, no Thalia socket |
| After 18:51:54 | Microphone denied | Free-tier wording | Tester: refused. Not captured on the phone |
| After 18:54:14 | No network | Free-tier wording | System: "Not started: There is no validated internet connection." The ledger stayed at one record. The exact time was not recorded |

## Sessions

Each session reserved its attempt in the ledger before connecting.

| Attempt | Reserved | Connected | Ended | Length | End reason |
|---|---|---|---|---|---|
| 1 | 18:53:56.016 | Not logged | Audio mode released 18:54:14.332 | About 17 s | `ENDED_BY_USER` |
| 2 | 19:05:13.625 | 19:05:13.733 | 19:06:57.248 | 1 min 43.6 s | `ENDED_BY_USER` |
| 3 | 19:13:23.927 | 19:13:24.147 | 19:13:34.545 | About 10 s | `ENDED_BY_SYSTEM`, after Home |

Attempt 1 ran because Wi-Fi stayed on in airplane mode, so it was not an offline test.
Its end reason was read from the screen, because its own log lines had rolled out of the log buffer before the capture.
Attempts 2 and 3 were captured live from the log.

### Attempt 2: conversation, routing and End

| Time | Event, all system |
|---|---|
| 19:05:13.631 | Connecting to `generativelanguage.googleapis.com` with model `gemini-3.8-live` |
| 19:05:13.733 | Connected |
| 19:05:14.180 | Audio focus requested, voice communication |
| 19:05:14.400 | Playback track created, `USAGE_VOICE_COMMUNICATION` |
| 19:05:14.422 | `MODE_NORMAL -> MODE_IN_COMMUNICATION`, owned by Thalia's uid |
| 19:05:14.963 | Session active, which means the server confirmed the setup |
| 19:05:15.102 | Recording started, `VOICE_COMMUNICATION` source |
| 19:06:56.767 | Recording stopped |
| 19:06:56.789 | Playback track released |
| 19:06:57.246 | Audio focus abandoned |
| 19:06:57.248 | Session ended: `ENDED_BY_USER` |
| 19:06:57.407 | `MODE_IN_COMMUNICATION -> MODE_NORMAL`, mode owner cleared |

Three snapshots of Android's audio service, at 19:05:17, 19:05:31 and 19:05:45, each showed the requested and actual mode `MODE_IN_COMMUNICATION` owned by Thalia, and the computed, applied and active communication device as the built-in speaker.
Before and after the session the mode was `MODE_NORMAL` with no owner, and the communication device was the earpiece, the phone's default.
The screen reported "speaker routed, echo canceller attached".

At 19:07:19 Thalia had no socket, no current player, no focus entry and no mode.
App-ops recorded 1 min 41.8 s of microphone use.
The tester reported at least three exchanges, prompt barge-in, clear and usable audio, and no self-echo.

### Attempt 3: Home

| Time | Event, all system |
|---|---|
| 19:13:24.866 | Session active, communication mode entered |
| 19:13:24.869 | Recording started |
| 19:13:33.479 | Home pressed: the live test screen paused |
| 19:13:33.896 | Recording stopped, 0.42 s after the pause |
| 19:13:33.907 | Playback track released |
| 19:13:34.543 | Audio focus abandoned |
| 19:13:34.545 | Session ended: `ENDED_BY_SYSTEM`, 1.07 s after the pause |
| 19:13:34.739 | `MODE_NORMAL` restored |

At 19:14:06 Thalia had no socket, no player and no mode, and was not in the focus stack.
The tester saw the voice stop and the green microphone indicator go off.

## Usage

The reservation ledger holds three records, each charging 250,000 micros: USD 0.75 of the USD 2.00 experiment allowance.
These amounts are experiment accounting only.
The spike used the Free tier of the Gemini Developer API.
No billing or usage dashboard was compared, so actual Google charges were not independently verified.

| Attempt | Raw `usageMetadata` reports kept | Audio sent and received, diagnostic only |
|---|---|---|
| 1 | 2 | 518,400 and 254,402 bytes |
| 2 | 8, 0 unreadable | 3,252,480 and 1,815,848 bytes |
| 3 | Not captured | Not captured |

The reports are kept raw, in arrival order, in the evidence.
Their values rose report after report, but the documentation does not say whether they are cumulative or incremental, and nothing here interprets them either way.
**The comparison with Google's usage dashboard was not performed.**

## M2 untouched

At 19:21:38 the M1 "Ring in 10 seconds" test armed a call.
The call screen opened at 19:21:48, the call service ran until 19:22:21, and the tester answered after unlocking and reported that the scripted call ran normally.
The check-in call path uses `FakeVoiceSessionClient`, and there was no Gemini log line, no Thalia socket and no change of audio mode during the call.

A "Schedule in 2 minutes" occurrence, `dev-1790940200691`, was recorded at about 19:21:20 and cancelled at 19:21:37.056 before it came due.
It is stored `CANCELLED USER_CANCELLED` with no fired time, and its alarm was removed.
The 31 earlier occurrences, the plan and the quiet period were unchanged row for row.

**The scheduled-alarm path, from alarm delivery through the claim to the call, was not run on this build.**
It last ran on this phone at 10:00 the same morning, on `42b48eb`: see [the M2 real delivery results](m2-overnight-device-results.md).

## Exit

The tester deleted the spike key in Google AI Studio.
That deletion is the tester's confirmation; it was not checked from the phone or from Google.
On the phone, `gemini-key.sealed` was absent at 19:29:49, and its folder was last modified at 19:29:10.996.
The key's contents were never read.
The ledger kept its three records, and `consent.properties` remains.

## Findings

- **The setup shape works.**
  `responseModalities` under `generationConfig`, as Google's reference has it, was accepted: attempts 2 and 3 logged the server's setup confirmation, and every attempt returned model audio.
- **The communication mode takes effect.**
  `MODIFY_AUDIO_SETTINGS` lets the session enter `MODE_IN_COMMUNICATION` and route to the speaker, and the echo canceller attached.
  No self-echo was observed in the tested conversation; that is one session in one room on one device, so echo is not ruled out in general.
- **Ending is clean on both paths.**
  End and Home each released everything within about 1.1 s.
- **The installed consent wording was wrong for the Free tier.**
  It promised paid-tier terms.
  It was corrected, and its version bumped, before any real connection.

## Limitations

- **A bad key is unverified,** waived by the user.
  Whether Gemini rejects a bad key at the handshake, giving `NOT_AUTHENTICATED`, or after it, giving `PROVIDER_UNAVAILABLE`, is still open.
- **Losing the network during a session is unverified,** waived by the user.
- **Time to first audio was not measured.**
- **Google's usage dashboard was not compared** with the ledger and the raw reports.
- **Barge-in, the microphone-denied refusal, the exchange count, audio quality and the absence of self-echo rest on the tester's observations.**
- **The scheduled-alarm path of M2 was not run on this build.**
- **Raw usage for attempt 3 was not captured.**
- One device, one build and one tester.

## Evidence handling

- No transcript or audio was saved and no screenshot was taken during or after a session.
  Screen state was read through an allowlist of status, attempt and usage lines.
- Saved logs are filtered to Thalia's own lines and audio-mode lines.
- The key was never entered, read or decrypted by the tooling, and the clipboard was not touched.
- Before any session, at about 17:52, the main log buffer was cleared once in error, losing roughly a minute of entries after the first install.
- At 17:52:43, consent to the earlier wording was switched on by a touch on the phone, not by the tooling.
  It was later withdrawn, and the version bump would have voided it in any case.

## Evidence

The evidence is kept off the repository, under `C:\Users\Vesyal\private evidence folder\`:

- `m3a-install-20261002`: the first install, its database backups and the consent-off gate;
- `m3a-free-tier-install-20261002`: the Free-tier install, its database backups and the no-key gate;
- `m3a-session-20261002`: attempt 1 and the offline refusal;
- `m3a-session2-20261002`: attempt 2, with the audio-service snapshots;
- `m3a-session3-home-20261002`: attempt 3;
- `m3a-m2-regression-20261002`: the M2 check, with database backups before and after;
- `m3a-closeout-20261002`: the key-file check and the final ledger.

The audio-service extracts and some log lines name other apps on the phone, so they are not for publishing as they are.
