# Slice 4B device results: on-device voice input into an editable draft

Date: 2026-10-09 (phone time, Asia/Kuala_Lumpur, UTC+8)
Scope: the isolated `conversationCheck` build; first on the scripted conversation screen, then on the production screen (second section)
Build: `com.thalia.app.conversationcheck`, APK SHA-256 `e17a9899faa666e750b1448349cc62f0681772490199a0c6fa129d8b9df4f824`, updated in place with `install -r` and its data kept
Device: Pixel 10, Android 17
Evidence: `the author's private evidence folder (not published)` (the first run, with the build before language selection, is in `voice4b-device-20261009-1721/`)

## The answer

Does on-device speech recognition, with no network, put text into the message box as an editable draft, with Send explicit and approval by tap?

**Yes, within the bounded scope below.** Slice 4B is accepted on the scripted conversation screen and on the production conversation screen, both in the isolated `conversationCheck` app. Nothing about it is claimed beyond what follows.

## What was checked

| Check | Result | Evidence |
|---|---|---|
| In-place update, data kept; `com.thalia.app.debug` and `com.thalia.app.geminicheck` untouched | PASS | `lastUpdateTime` of both unchanged |
| Language selection | PASS | The phone's locale `en-MY` had no on-device speech. The app offered only `en-US` and chose nothing. The owner selected it |
| Permission denial | PASS | `RECORD_AUDIO` stayed `granted=false`, the screen said "The microphone permission was not granted. Typing still works.", and the records were unchanged |
| Typing fallback | PASS | A typed character appeared and was cleared, with the records unchanged |
| Offline recognition | PASS | Airplane mode on, Wi-Fi off, no active network (`17-offline-state.txt`, `21-offline-during-speech.txt`). "Write the weekly summary" appeared in the message box |
| Cancellation | PASS | Speak then stop left the draft unchanged and added nothing. A separate press that heard nothing ended with "Nothing was recognised. Try again, or type." |
| Speech changes only the draft | PASS | Before Send there was no "You" entry and no approval card |
| Send creates only a proposal | PASS | The approval card appeared, and the planning screen listed no action with that title |
| Approve creates exactly one action | PASS | Actions 9 to 10. A diff against the earlier baseline shows one added action, "Write the weekly summary", and every other key identical |
| App-owned storage | PASS | No audio-type files and no draft, speech or transcript files in the app's directories |
| Network state restored | PASS | Airplane mode 0, Wi-Fi 1, as before |

## What this does and does not show

- **The `/new ` prefix was typed by hand.** The scripted provider understands only slash commands, so the prefix shows the scripted boundary: a draft, an explicit Send, a validated proposal and a tap approval. It says nothing about how a real model would interpret spoken text.
- **Storage inspection covers app-owned storage only.** It does not assess what the platform speech service stores or processes. Offline success shows recognition worked with no network; it rests on the platform's on-device recognizer contract.
- **The planning database is encrypted,** so the inspection could not search it. The phrase appears in `logical.json`, which is the check tool's own export of the approved action.
- **One language, one device.** Only `en-US` on one Pixel 10 was exercised. Other languages and devices are not assessed.
- **No live requests.** No API key was held and no request was made to Google. Live model validation remains paused.

## Incidents during the run

- The first permission denial could not be confirmed (the prompt was answered by a touch on the phone before the script could verify it). The permission was revoked with `pm revoke` for the check app only, and the denial was repeated and verified by permission state and screen note.
- Wireless adb dropped several times. USB was used for the rest, and Wi-Fi and airplane mode were set by the owner and restored.
- The microphone permission is now granted for the check app only, by the owner.

## Production conversation screen (2026-10-09, evening)

Scope: the production `ConversationActivity` screen, reached through "Conversation (production, gated)" in the same isolated `conversationCheck` app. Build: APK SHA-256 `941d2430961f08eab5127332861149a7c6325f3b8126925147e45f105b76a9c3`, updated in place with `install -r`, data kept. Evidence: `the author's private evidence folder (not published)`. USB adb throughout. The fake transport was confirmed (`connection=FAKE`, synthetic key) before AI was turned on.

| Check | Result |
|---|---|
| Speech into a draft with AI off, offline (airplane on, Wi-Fi off, no active network) | PASS |
| Rotation keeps the draft and the chosen language | PASS (on a typed draft; the first attempt was void because the spoken draft had already been sent) |
| Listening stops on backgrounding, and no late result arrives | PASS (a quick Speak then Home with a 14 s wait; two earlier attempts were void because slow polling let the recognizer time out before Home) |
| Send clears the submitted draft | PASS |
| Zero transport requests with AI off | PASS (request log 5 before and after) |
| Planning records unchanged with AI off | PASS (identical to the baseline apart from `createdAt`) |
| Leaving with an unsent draft clears the draft, the language and the conversation | PASS |
| Fake-AI proposal from a spoken draft | PASS (log 5 to 6, "Waiting for your approval") |
| A newer draft typed while a proposal is pending survives approval | PASS |
| Approval creates exactly one action | PASS (10 to 11, one added action "Play the Lantern Festival", all else identical, no extra request) |
| App-owned storage sanity listing | PASS (no audio-type, draft, speech or transcript names; no new persistence path was added) |
| Network, rotation and AI settings restored; debug and geminiCheck untouched | PASS |

Limits, unchanged and added:

- The `/new ` prefix was typed by hand. The scripted fake reads only a leading slash command, so it shows the scripted boundary and says nothing about real-model behaviour.
- Recognition heard "Play the Lantern Festival" for a spoken "Plan the lantern festival". Draft review and editing exist for that reason.
- The language choice was `en-US`, the only installed variant offered for an `en-MY` phone, selected on the screen as in the prepared check.
- App-owned storage only; the platform speech service is unassessed.
- One language, one device, no live AI request.
- Found and fixed after the run: the note "Check the draft, edit it if needed, then press Send." stayed on screen after Send. It is now cleared on Send and covered by `VoiceDraftTest`. That fix is not in the tested APK and has had no device check.

## Implementation

`VoiceDraft.kt` holds the logic: a speech source contract, a draft controller, explicit language options, and the exact-tag install check. `OnDeviceSpeechSource.kt` (`conversationCheck` only) uses `createOnDeviceSpeechRecognizer`, re-queries support for the selected tag before the microphone opens, never calls `triggerModelDownload`, and keeps no audio. `RECORD_AUDIO` is declared only in the `conversationCheck` and `debug` manifests (debug had it from the Gemini Live spike). Focused tests: `VoiceDraftTest`, 8 tests.
