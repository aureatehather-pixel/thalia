# ADR 0001: Voice Check-ins spike lives in a standalone app, not a module inside Thalia

Date: 2026-09-18
Status: Accepted. Implemented by M0.
Milestone: M0 (Foundation and decision record)
Branch: `spike/voice-checkins`

Amended 2026-09-18, same day, after M0 was built.
Finding 4 and open decision 2 below are resolved and marked inline.
See [ADR 0002](0002-voice-checkins-foundation.md) for what M0 actually built.

**Partially superseded by [ADR 0003](0003-optional-ai-provider-policy.md), 2026-09-18.**
Two parts of this ADR no longer hold:

- **"Standalone, not a module."** Voice Check-ins is a Thalia feature in the Thalia repository, isolated by Gradle module rather than by being a separate product. The `:app` module is the foundation of the real Thalia application, not a throwaway spike surface. The reasoning below was correct for what it knew; ADR 0003 removed its premise rather than its logic.
- **Finding 3 and open decision 1, the PRD conflict.** Resolved. `PRD.md` section 6.4 has been amended: the core stays local-first, optional features may use a replaceable AI provider under stated conditions.

Everything else stands, including the `:core` module decision, the SDK and permission proposals, and the secret-handling rules.

**One proposal amended 2026-09-19 by [ADR 0004](0004-m1-incoming-call-presentation.md), on building M1.**
The foreground-service permission is `FOREGROUND_SERVICE_SPECIAL_USE`, not `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and the AGP version is now pinned.
Both are marked inline in the tables below.

## Context

The Voice Check-ins PRD v0.1 instructs the spike to inspect the existing Thalia Android project, its build configuration and its data model, and then decide whether the spike belongs inside Thalia as an isolated module or outside it as a temporary standalone app.

That inspection produced a result the PRD did not anticipate.

### Finding 1: there is no existing Thalia Android project

The `thalia-bot` repository at `~/tools/firstmate/projects/thalia-bot` contains two commits on `main` (`dab2a91`, `b44c67d`) and exactly one tracked file, `README.md`, whose body reads "This project is just getting started."

A recursive search of the entire home directory to depth 6 found no `settings.gradle`, no `build.gradle`, no `AndroidManifest.xml` and no Kotlin source file anywhere.

There is therefore no Android project, no build configuration and no data model to inspect, and nothing for a module to be a module of.

### Finding 2: the product PRD is real but unmerged

`PRD.md` exists only on the unmerged branch `fm/thalia-bot-docs-write-thalia-bot-prd-and-update-readme-w-a4`, alongside an HTML click-through prototype on `fm/thalia-bot-prototype-lavish-prototype-of-thalia-bot-s-main-jo-3c`.

That prototype's own PRD section states it "exists to validate interaction design, not to be extended directly into the real app."

So Thalia's design intent is recorded, but none of it is implemented.

### Finding 3: the two PRDs conflict on a firm decision

**Resolved 2026-09-18 by [ADR 0003](0003-optional-ai-provider-policy.md).**
Section 6.4 was amended, and the conflict no longer exists. The finding is preserved below as written, in the present tense it was written in.

Thalia's PRD Section 6.4 states that Thalia "contains no AI model, no LLM API integration, and no API key, ever, in v1 or as a default assumption for later", and Section 6 introduces itself as "the captain's own final decisions (recorded 2026-09-13), not open for reinterpretation".

Section 2 repeats this as a non-goal, and Section 5.5 places real audio capture, storage, playback and transcription explicitly out of scope for v1.

Voice Check-ins v0.1 requires the opposite on all three points: a Gemini Live API integration, a locally stored API key, and real microphone capture with transcription enabled.

This conflict does not block the spike, because Section 6.4 governs the Thalia application and the spike is a separate artifact.
It does bear directly on the value of the spike, because it may foreclose the M6 integration that the spike exists to inform.
It is raised as an open decision rather than resolved here.

### Finding 4: no build toolchain is installed

**Resolved 2026-09-18.**
The `sudo` obstacle turned out not to apply: a JDK and Gradle install fine per-user with no root at all.
JDK 21 (Temurin) and Gradle 9.7.1 now live under `~/.local/toolchains/`, and the repository carries a Gradle wrapper, so `./gradlew build` works.
`:core` is plain Kotlin/JVM, so M0 needed no Android SDK.
The Android SDK is still absent and is first needed in M1.
The original finding is preserved below as written.

Neither WSL nor Windows has a JDK, an Android SDK, Gradle, or `adb`.
Windows carries only an Oracle Java 8 JRE at `C:\Program Files\Java\jre-1.8` with no `javac`, which is both a runtime-only install and far below the JDK 17 that current Android Gradle Plugin versions require.
`sudo` in WSL requires a password, so no install can proceed without Vesyal.

Disk space is not a constraint: 945 GB free in WSL, 167 GB free on `C:`.

## Decision

The spike will be built as a self-contained standalone Android application on the `spike/voice-checkins` branch of the `thalia-bot` repository, not as a module inside a Thalia app.

Three parts to that decision.

**Standalone, not a module.**
A module must belong to a host project, and no host project exists.
Creating a Gradle multi-module Android build to host the spike would mean choosing Thalia's minimum SDK, dependency injection, navigation, Room setup and build conventions as a side effect of a three to five day spike.
That inverts the intended dependency order and would let throwaway code set the foundations of the real product.
The PRD anticipates the spike being discarded: Section 11 puts the first decision point at M3, and M6 says to integrate "only if the voice loop changes behaviour enough to justify its maintenance."
The core risk named in Section 15, reliable incoming-call presentation on a locked Pixel 10, is an operating system behaviour question that touches none of Thalia's data model or UI, so nothing about validating it needs Thalia's code to exist.

**In the `thalia-bot` repository, on a branch.**
A separate repository would detach the spike from this decision record and from the PRD it is testing.
Branch isolation already gives full separation: `main` stays untouched, and abandoning the spike is a branch deletion.
This also matches the PRD Section 14 instruction to create `spike/voice-checkins` inside the Thalia repository.

**Interfaces isolated in a portable pure-Kotlin module.**
PRD Section 7 requires the six named interfaces to stay independent so they can later move into Thalia.
They will live in a `:core` JVM-only Kotlin module that depends on neither the Android SDK nor Gemini.
This keeps the contracts portable, and it means the M0 exit test can run as ordinary JVM unit tests without an emulator or instrumentation.

### Proposed module layout

```
:core        pure Kotlin/JVM. The six interfaces, domain models, fake VoiceSessionClient. No Android, no Gemini.
:app         Android application. Ringing UI, alarms, permissions, Room, Gemini client. Depends on :core.
```

### Proposed SDK and toolchain versions

These are proposals, not yet verified against a real build, because no toolchain is installed.

| Setting | Proposed value | Reason |
| --- | --- | --- |
| `compileSdk` / `targetSdk` | 36 (Android 16) | Current stable, and the Pixel 10 ships Android 16. |
| `minSdk` | 31 (Android 12) | No legacy install base to support on a single-user personal app, and 31 gives modern full-screen-intent and exact-alarm behaviour without pre-12 compatibility branches. |
| JDK | ~~17~~ **21 (Temurin), installed per-user** | Current LTS, above the Android Gradle Plugin floor, and installable without root. |
| Kotlin / Gradle | **Kotlin 2.4.20, Gradle 9.7.1, both pinned and building** | No longer a guess; `./gradlew build` passes. |
| AGP | ~~Still unpinned~~ **9.4.1, pinned in M1** | Needed the Android SDK, which M1 installed. |

`minSdk 31` also matters for behaviour, not just tooling: `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` are Android 12 and 13 concepts, and the PRD requires a visible degraded-reliability state when exact alarms are unavailable.
**Corrected 2026-09-19 by [ADR 0005](0005-m2-slice-1-real-alarm.md):** this sentence originally called `USE_EXACT_ALARM` the "user-revocable successor" of `SCHEDULE_EXACT_ALARM`. It is the other way round. `SCHEDULE_EXACT_ALARM` is the one the user can revoke, and `USE_EXACT_ALARM` is granted at install and cannot be.

### Proposed permissions

| Permission | Milestone | Purpose |
| --- | --- | --- |
| `POST_NOTIFICATIONS` | M1 | Required from Android 13 to show the call notification at all. |
| `USE_FULL_SCREEN_INTENT` | M1 | Required from Android 14 to raise the incoming-call screen over the lock screen. |
| `VIBRATE` | M1 | Ring vibration. |
| `FOREGROUND_SERVICE` and ~~`FOREGROUND_SERVICE_MEDIA_PLAYBACK`~~ **`FOREGROUND_SERVICE_SPECIAL_USE`** | M1 | Foreground service held only while ringing or in an active session. Type decided in ADR 0004. |
| ~~`USE_EXACT_ALARM`~~ **`SCHEDULE_EXACT_ALARM`** | M2 | Exact next-occurrence alarms. `USE_EXACT_ALARM` is superseded by ADR 0005: it cannot be revoked, so the degraded state could never be seen or tested, and it claims an alarm-clock purpose the PRD says this is not. |
| `RECEIVE_BOOT_COMPLETED` | M2 | Restore schedules after reboot. |
| `RECORD_AUDIO` | M3 | Microphone capture, requested at answer time, never earlier. |
| `INTERNET` | M3 | Gemini Live WebSocket. |

No permission is declared before the milestone that uses it.
~~`SCHEDULE_EXACT_ALARM` is deliberately not paired with `USE_EXACT_ALARM`, since declaring both is contradictory and the alarm-clock framing fits `USE_EXACT_ALARM`.~~
**Superseded by [ADR 0005](0005-m2-slice-1-real-alarm.md):** `SCHEDULE_EXACT_ALARM` alone is declared, and `USE_EXACT_ALARM` is deliberately absent.

### Secret handling

No API key enters the repository, in any form, at any milestone.

- The key is entered by Vesyal at runtime in-app, and never checked in, hardcoded, placed in `gradle.properties`, or read from a tracked file.
- Storage uses `EncryptedSharedPreferences` backed by the Android Keystore.
- The key is excluded from logs, crash reports, exported summaries and structured session output, per PRD Section 12.
- `local.properties`, `*.keystore`, `*.jks` and local secret files are gitignored before any Android module is added.
- Through M2 there is no key at all, because the fake client needs none. The first key entry belongs to M3.

## Consequences

Positive.

- Thalia's real architecture stays unchosen until a product decision, not a spike, drives it.
- The riskiest assumption, locked-screen ringing, is reachable without first building Thalia.
- Abandoning the spike costs a branch deletion.
- The `:core` contracts stay compilable and testable on a JVM, so M0's exit test needs no Android device.

Negative and accepted.

- If the spike succeeds, the `:app` surface is rebuilt rather than moved when it enters Thalia. Only `:core` ports directly. This is the intended trade for not letting throwaway code set Thalia's foundations.
- ~~Two PRDs now describe one repository, and they conflict. Finding 3 must be resolved before M6, and ideally now.~~ **Resolved 2026-09-18 by ADR 0003, on approval of M0.** `PRD.md` was merged onto this branch and amended, so one repository now holds one consistent record.

## Open decisions

Both need Vesyal.

1. ~~**Does Voice Check-ins v0.1 supersede Thalia PRD Section 6.4?**~~ **Resolved 2026-09-18 by [ADR 0003](0003-optional-ai-provider-policy.md).**
   Section 6.4's absolute restriction is superseded. Thalia's core stays local-first and usable with no cloud AI; optional features may use a replaceable provider with explicit consent, locally protected credentials, and graceful offline fallback. `PRD.md` has been amended accordingly. The original question is preserved below as written.

   **Does Voice Check-ins v0.1 supersede Thalia PRD Section 6.4?**
   Section 6.4 forbids any LLM integration or API key in Thalia "ever", and calls Section 6 not open for reinterpretation.
   Voice Check-ins requires both.
   If 6.4 stands as written, the spike can still be built and used standalone, but M6 integration is foreclosed and that should be known before spending three to five days.
   If 6.4 is superseded, Thalia's PRD should be amended so the repository does not hold two contradictory records.

2. ~~**Approval to install the build toolchain.**~~ **Resolved 2026-09-18, no approval needed.**
   The JDK and Gradle were installed per-user under `~/.local/toolchains/`, which needs no `sudo`, and M0's tests are verified as passing.
   The Android SDK command-line tools, platform 36 and build-tools are still needed, but only from M1.
   That is a multi-gigabyte download and is worth raising then, not now.
