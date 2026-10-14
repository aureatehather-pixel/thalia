# Slice 5: reminders and checkpoints, local results

Date: 2026-10-10. Branch `v1/slice2-day-loop`, working tree on top of `208162c`, **nothing committed, staged or pushed**. Design: [ADR 0014](adr/0014-reminders-and-checkpoints.md). Status: **Implementation and computer checks accepted; Pixel validation deferred.** Not run on a Pixel. Notification and alarm delivery are unverified.

## What exists now

- **Reminders (new):** one-shot, user-created, for an open Inbox item, an open action, or nothing (a checkpoint). Due between 1 minute and 7 days ahead, outside the quiet period, at most 20 waiting.
- **Time policy:** a fixed instant. A zone change does not move it; the screen shows it in the current zone and names the creation zone when it differs. Labelled "around HH:mm".
- **Scheduling:** inexact `setAndAllowWhileIdle`. No exact-alarm permission is used.
- **Delivery:** one quiet, generic notification ("Thalia reminder / Open Thalia to see it."). Not posted if more than 60 minutes late, if delivery falls in the quiet period, or if notifications are blocked. Each is recorded as an observed reason. Nothing is inferred about the phone.
- **State shown:** "Notification posted; response unknown" until the person presses Acknowledge or Dismiss. It never times out or retries. Cancel is available while waiting.
- **Checkpoint:** tapping its notification opens the planning screen scrolled to the current day's plan. It opens and closes no day.
- **Writes:** Acknowledge, Dismiss and Cancel write the `reminders` table only. Plan, capacity, outcome, action and Inbox rows are untouched (asserted in tests).
- **Storage:** planning schema 6 (`reminders` table, no foreign keys), export payload schema 6. Schema 1 to 5 payloads still restore.
- **Recovery:** the table is the truth. Reconciliation runs at launch, boot, package replace, time or zone change, and after every restore, replace or set-aside of planning data.
- **Existing, unchanged:** the two daily calls, `CheckInType`, M2's occurrence store and exact alarms, the conversation boundary **as it stood when this slice was accepted** (the model could not schedule a reminder). Since V1 R8 (2026-10-10, [NOW](NOW.md)) the conversation can propose a one-time reminder or checkpoint, which waits for an Approve tap and goes through the same service and rules; nothing else in this slice changed.
- **Changed wording:** the Inbox reminder hint now reads "This may be a reminder. Unless you set one under Reminders, nothing is scheduled for it."

## Deferred, as instructed

Snooze, voice-specific entry, configurable lock-screen text, recurrence, AI scheduling. Slice 4's qualified closure and the real-model validation are unchanged.

## Computer evidence (2026-10-10)

Run one Gradle task at a time, `./gradlew --stop` after each.

| Check | Result |
| --- | --- |
| `:core:test` | 282 tests, 0 failed (271 before this slice, 11 new) |
| `:app:testDebugUnitTest` | 825 tests, 0 failed (748 before, 77 new) |
| `:app:testReleaseUnitTest` | 745 tests, 0 failed |
| `ktlintCheck` (all modules) | clean, after `ktlintFormat` touched only files of this slice |
| `:app:assembleDebug`, `assembleRelease`, `assembleConversationCheck`, `assembleRecoveryCheck`, `assembleGeminiCheck` | all succeed |
| `:app:lintDebug` | **fails with the same 4 `NewApi` errors as the baseline.** Measured 2026-10-10: `lintDebug` on an isolated export of `208162c` (`git archive`, outside the working tree) reports the same 4 errors at `OnDeviceSpeechSource.kt` lines 35, 38, 38 and 39 (`checkRecognitionSupport`, `RecognitionSupportCallback` twice, `getInstalledOnDeviceLanguages`; minSdk 31 against API 33). They come from Slice 4B, are not introduced or exposed by Slice 5, and are left unfixed here. The baseline had 4 warnings; this slice's two `UseKtx` warnings were fixed, so it adds none. |
| `:voice-gemini` tests | not run |

What the new tests cover:

- **Transitions and invariants** (core): every legal move, every refused move, a record that contradicts its status cannot be built, resolved statuses have no way out.
- **Rules** (core): too soon, too far, quiet period, pending limit and their order; delivery inside, at the edge of and past the 60-minute window; window first, then quiet, then blocked.
- **Permission refusal** (service, adapters): notifications off or channel off at creation stores and arms nothing; turned off afterwards is recorded NOTIFICATIONS_BLOCKED; a platform failure to post is NOT_POSTED, never POSTED.
- **Scheduling, cancellation, recovery** (service with fake alarms and notifier): one alarm per reminder, re-arm replaces, cancel removes alarm first, a late delivery after cancel does nothing, a cancel racing a delivery leaves nothing posted, restart re-arms, an expired reminder is not posted late, reconciliation is idempotent, a stale alarm and an orphan notification are removed, a posted reminder keeps its notification and is never reposted, an unanswered reminder is unchanged after 400 simulated days.
- **Duplicate delivery:** posted once; the store move is a compare-and-set, so a stale or repeated move changes nothing and a resolved reminder cannot be revived.
- **Import and replacement:** a schema 6 round trip restores every status exactly, an unanswered one still unanswered; a schema 5 archive imports with none; restore, replace and set-aside each call the reconciliation hook once, with the store that now holds the data; a failing hook does not fail the import; after a restore old alarms go and resolved reminders stay resolved.
- **Deleted target:** an undone Inbox capture leaves its reminder as it was, still deliverable, with the plan data unchanged.
- **Migration 5 to 6:** built from the committed `5.json`, validated by Room against `6.json`, every logical record of every earlier table byte-equal, the new table empty.
- **Platform adapters** (Robolectric): per-reminder alarms, no exact-alarm permission needed, generic notification text with no actions or full-screen intent, one notification per reminder, low-importance silent channel.

## Not established by these tests

Local test success does not establish any of the following on the Pixel:

- That Android delivers the alarm, and how late (inexact, Doze).
- That the notification appears, its look on the lock screen and in the shade, and that tapping it opens the planning screen at the plan.
- The `POST_NOTIFICATIONS` prompt flow, and what a user sees after denying it.
- Reboot behaviour on the device, including that nothing is armed before the first unlock.
- The Compose screen itself (no UI test exists; it compiles and is untested by hand).
- Real SQLCipher storage of the new table, and the real Keystore path during an alarm delivery with the app not running.
- A real schema 5 installation migrating (the real debug installation is still schema 3 and does not have Slice 3 to 5 at all).
- Behaviour with more than one language, locale or time zone change on the device.

## Proposed device checklist (not run, no device actions taken)

Use an isolated check build with synthetic data, not `com.thalia.app.debug`, `geminicheck` or the existing `conversationcheck` data.

1. Install; open Planning; Reminders section shows "No reminders yet".
2. Set a checkpoint 2 minutes ahead with notifications not yet allowed: the permission prompt appears once. Deny: "Notifications for Thalia are off ... Nothing was scheduled", no row, no alarm (`adb shell dumpsys alarm | grep thalia`).
3. Allow, set it again. Lock the phone. Around the time one quiet notification appears with only generic text. Tap it: the plan is on screen, no day was opened or closed, the row says "Notification posted; response unknown".
4. Ignore a second reminder for over 10 minutes: still "response unknown", no second notification.
5. Acknowledge one, Dismiss another: statuses change, the notification goes, the Inbox item and plan are unchanged.
6. Set one 10 minutes ahead and Cancel it: no notification at the time, `dumpsys alarm` shows no Thalia reminder alarm.
7. Turn the app's notifications off after setting one; at the time it is recorded "Not posted: notifications were blocked".
8. Set one outside the quiet period, enable a quiet period over its time: recorded "fell in your quiet period".
9. Set one 5 minutes ahead and reboot, unlock before it is due: it still posts (note any delay).
10. Reboot and leave the phone locked past the 60-minute window, then unlock: "Not posted: the 60-minute delivery window had passed", nothing posts late.
11. Export, then import into a fresh check install: statuses are carried; a waiting one is armed, a resolved one stays resolved.
12. Change the time zone with a reminder waiting: it fires at the same instant and shows the zone it was set in.

Record the APK hash, device, build and `dumpsys` excerpts in `the author's private evidence folder (not published)` as for earlier slices.

## Limits worth knowing

- Reminders are a device-local notification, not a guarantee. A force-stopped app, an unconfigured battery policy or a phone off through the window means nothing is shown, and Thalia records only what it observed.
- Pending reminders are not covered by the recovery of the alarm itself: after a restart they come back at the next unlock and the next reconcile.
- No snooze. A person who wants it again sets a new reminder.
