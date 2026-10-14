# Thalia

Thalia is a local-first Android planning app. 

The idea: a model proposes a change to your plan, but Kotlin code validates it and nothing is written until you tap **Approve**. Everything stays on the phone.

## What it does

- **Goals, projects and actions** with priorities and due dates, plus an Inbox that captures first and files later.
- **Day planning.** A working day with fixed commitments and capacity, a deterministic daily plan you can adjust, per-block include or leave out, then approve. Outcomes, carry-forward, closing the day and re-entry after a gap.
- **Four-tab shell** (Today, Plan, Life, Thalia) in Jetpack Compose, with Settings, a bundled accessible font, and dark and light themes.
- **Text conversation (the Thalia tab).** Typed requests become typed proposals. Only Approve applies a model-proposed change, including recording outcomes.
- **Undo** for approved plan changes and Inbox captures, where the stored state still allows it.
- **Export and restore** of all planning data as a password-encrypted archive (AES-GCM).
- **Optional AI, off by default.** External AI needs explicit consent and a key held on the device. A monthly budget is tracked as the app's own estimate, with a ceiling of USD 5 in code. It is not a provider-enforced hard cap.
- **Local reminders and check-in call scheduling** using exact alarms, only for confirmed occurrences.

## Architecture

| Module | Role |
|---|---|
| `core` | Plain Kotlin/JVM, no Android dependency. Planning model, planner, `PlanValidator`, `OperationValidator`, export format, provider contracts. |
| `app` | The Android app: Compose UI, Room storage encrypted with SQLCipher (key sealed by the Android Keystore), conversation session, alarms, AI gating and budget. |
| `intent-cases` | A provider-neutral fixture of eight intent cases, scored on meaning through the real parser and validator. |
| `openai-provider` | `ConversationProvider` over the OpenAI Responses API with an API key, plus a Google Cloud Text-to-Speech client. Plain JVM, tested against a local fake server. |
| `chatgpt-provider`, `chatgpt-plan` | Experiment: using a ChatGPT plan sign-in as a provider. See status below. |
| `voice-gemini` | Experiment: Gemini text and Live client. |

Rules the code is built around (see [docs/product-spec-v2.md](docs/product-spec-v2.md) and the [ADRs](docs/adr)):

1. **Local-first.** Room is the source of truth. Planning works offline with no model.
2. **AI proposes, Kotlin decides.** A provider returns a typed proposal; deterministic validators check it; the user previews and approves; it is then written in one transaction. A provider never writes to storage.
3. **Network is opt-in per build.** The `release` build type has no `INTERNET` permission and none of the provider modules. Provider code is compiled only into debug and the isolated check builds.

## Build variants

| Variant | Purpose | Network |
|---|---|---|
| `release` | The app without any provider code. | No `INTERNET` permission |
| `debug` | The author's daily build; includes the optional providers. | Yes |
| `conversationCheck` | **Keyless demo.** Isolated app id and storage, scripted provider, synthetic data. | `INTERNET` removed from the manifest |
| `recoveryCheck` | Isolated app for export, restore and migration checks. | No |
| `liveCheck` | Synthetic-data check of the OpenAI and speech route. Needs your own keys. | Yes |
| `geminiCheck`, `chatgptCheck` | Feasibility spikes, kept for the record. Need your own credentials. | Yes |

## Build and test

No API key, account or credential is needed to build or test. You need JDK 21 and the Android SDK with platform 37 (set `ANDROID_HOME`, or put `sdk.dir` in a git-ignored `local.properties`). Run Gradle tasks one at a time.

```bash
./gradlew ktlintCheck
./gradlew :core:test
./gradlew :intent-cases:test
./gradlew :openai-provider:test
./gradlew :chatgpt-provider:test
./gradlew :chatgpt-plan:test
./gradlew :voice-gemini:test
./gradlew :app:testDebugUnitTest
./gradlew :app:testReleaseUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:assembleConversationCheck
```

## Keyless demo

The demo is the `conversationCheck` build. It needs no key, no account and none of the author's data.

1. `./gradlew :app:assembleConversationCheck`
2. Install `app/build/outputs/apk/conversationCheck/app-conversationCheck.apk` on an emulator or device (Android 12 or newer). Its app id is `com.thalia.app.conversationcheck`, so it cannot touch any other install.
3. Open "Thalia conversation check", press **Seed synthetic data** (and **Seed Inbox captures**), then **Scripted conversation** or **Planning screen**.
4. Proposals are previewed; nothing is written until you approve. Scripted commands such as `/new Write the weekly summary` are available in the conversation.

**What it proves:** the real planning, validation, approval, undo and storage code paths work end to end on synthetic data, with the network permission removed. **What it does not prove:** the replies are fixed text from a scripted provider, so it says nothing about how any real model behaves. The APK build is checked as part of release verification, but installing it was not done for this release; the device results in [docs/](docs) were recorded earlier on a Pixel 10.

## Status

Evidence classes: **tests** (JVM and Robolectric unit tests, no device), **scripted** (scripted provider or fake transport), **device** (Pixel 10, isolated app, synthetic data), **live** (real provider, paid).

| Area | State |
|---|---|
| Planning loop, Inbox, undo, export and restore, encrypted storage | Built. Unit-tested; device results in the `docs/*-device-results.md` files. |
| Approve-only policy for model proposals | Built and unit-tested. |
| OpenAI route (reasoning plus Chirp 3 HD speech) | Checked live on a device with synthetic data in the `liveCheck` build (eight intent cases, spoken Approve flows). Needs your own keys. Not part of the `release` build. |
| Reminders and check-in call scheduling | Built, with exact-alarm device checks. Recurring opening and closing calls are not claimed as accepted. |
| Call handoff to the text conversation | Built as a handoff only. It is not a voice call. |
| On-device speech input into an editable draft | Built, bounded device checks with a scripted provider. |
| Gemini Live voice, Gemini text | Experiment. The live check was paused after server errors. |
| ChatGPT-plan provider | Experiment. The last proposal-behavior proof was not passed; see [docs/slice6-chatgpt-plan-proof.md](docs/slice6-chatgpt-plan-proof.md). |
| Spoken AI check-in calls | Not built. |
| Google Calendar sync | Not built. |
| Distribution (store, signing, auto-update) | Not built. |

## Documentation

- [docs/product-spec-v2.md](docs/product-spec-v2.md): the product specification.
- [docs/adr](docs/adr): architecture decision records.
- [docs/release-direction.md](docs/release-direction.md) and [docs/future-directions.md](docs/future-directions.md): direction and ideas, not commitments.
- `docs/*-device-results.md`: dated evidence logs from device checks.
- [PRD.md](PRD.md) and [THALIA_VOICE_CHECKINS_PRD.md](THALIA_VOICE_CHECKINS_PRD.md): early historical documents, partly superseded.

## License and notices

Thalia's source is released under the [MIT License](LICENSE). Third-party components, including the bundled Atkinson Hyperlegible Next font (SIL OFL 1.1), are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). If you redistribute a built APK, include that file and the font's license text.

## Contributing

This is a personal project. There is no contribution process.
