# Slice 4C: call handoff to the text conversation

Status: **accepted 2026-10-09 as a bounded call handoff, in the isolated `conversationCheck` app only (results below). Process death and restore is not verified.**

This is a call handoff, not a phone conversation. Answering a scheduled opening or closing call opens the existing text
conversation with the matching message in the box, unsent. Nothing is said, played or recorded. There are no spoken replies, no
call audio, no AI on the call itself and no new storage.

## Behaviour

1. A scheduled opening or closing call rings as before (M1, M2).
2. **Answer** on a scheduled call hands off. A call started by a development button (`dev-trigger`) keeps the simulated session.
3. If the phone is locked, Answer first asks to unlock. The call is answered only after the unlock succeeds. A cancelled or failed
   unlock leaves the call ringing (it can still time out as missed), opens nothing and sends nothing.
4. After an unlock, or at once on an unlocked phone, the call is recorded as answered and then completed, and the conversation
   opens in the app's own task. The conversation screen is never shown over the lock screen.
5. The conversation shows a "Call handoff" notice and the opening or closing message, **not sent**. The person reviews it, may edit
   it by typing or by the on-device speech draft, and presses Send. Sending follows the AI settings. Any proposal needs a tap.
6. Opening or closing the working day happens only through the day screen's own controls. The notice has an "Open the day
   screen" button. Answering, declining, timing out and leaving never touch working-day state: the call classes have no
   reference to planning.
7. Closing keeps unreported outcomes unknown. Nothing is inferred from answering or leaving.

## What COMPLETED means here

`COMPLETED` on a handed-off call means the call interaction ended normally, and nothing more. It does not mean the day was opened
or closed, a plan was accepted or an outcome was reconciled. The ended-call summary reads "Call handed to the conversation. Your
day was not opened or closed." (`CheckInStatus.COMPLETED` is documented the same way.)

## The call message is applied once

The message is put in the box only when the conversation screen is first made. Rotation keeps the ViewModel, so a user-edited
draft is never overwritten. A screen the system recreates after the process died starts with an empty box, and the message is not
applied again. The call activity ignores a repeated Answer, because it acts only on a call that is still ringing.

## Defect found on the Pixel and fixed

The first device run showed a closing call opening the earlier opening call's conversation screen (its old edited text and the
opening wording), because the handoff started a task with the app's default affinity and the system brought the existing task
forward, dropping the new intent. Each handoff now starts its own task (`NEW_TASK` with `MULTIPLE_TASK`), so a second call gets its
own screen and an earlier edited draft is left alone. `CallHandoffTest` covers the flags.

## Changes to earlier behaviour

Scheduled calls on the production wiring no longer start the simulated session. M1 and M2 device results describe the earlier
behaviour and stay valid for it. The development buttons keep the simulated session.

## Retention

No new persistence path. The draft is memory-only in the ViewModel. The occurrence row records `ANSWERED` then `COMPLETED`, as
before. Prior storage evidence applies unless the device check finds something new.

## External services and cost

None. The handoff sends nothing. A later Send follows the existing AI settings, notice and budget cap.

## Prepared isolated Pixel check (not run)

App: `com.thalia.app.conversationcheck` only, updated in place with `install -r` (the APK SHA-256 is recorded with the results).
USB adb. Debug and geminiCheck are not touched. No reset, uninstall, download or live request.

Baseline first: planning-record dump, the fake request-log count, and a copy of `checkins.db` with its WAL read locally to get the
occurrence statuses.

The check app has two buttons that arm one real scheduled call two minutes ahead through the production alarm path:
"Schedule opening call (handoff)" and "Schedule closing call (handoff)". If the call is suppressed (quiet period, alarm
permission), that is recorded as BLOCKED, not worked around.

| # | Check | Expected |
|---|---|---|
| 1 | Opening, phone unlocked: arm, wait, Answer | The conversation opens with the opening message unsent and the "Call handoff" notice. Request count unchanged. After leaving without Send, planning records equal the baseline (including working days and outcomes). The occurrence went `ANSWERED` then `COMPLETED` |
| 2 | Closing, unlocked: same | The closing message unsent, the notice says unreported outcomes stay unknown. Records unchanged after leaving; no outcome rows added |
| 3 | Day-control routing | "Open the day screen" opens the planning screen. The working day is unchanged until its own Open or Close control is pressed (optional last step, an explicit change to synthetic data) |
| 4 | Sending the unedited natural-language message | The scripted fake supports only slash commands, so the message gets its help text and **no proposal**. Verify this, do not assume it |
| 5 | A proposal | Edit to a scripted command (for example `/new` plus a unique title; the prefix is a scripted-provider limitation, not product behaviour), Send, review, tap approve. Exactly one action |
| 6 | Edit then rotate, and background then return | The edited draft survives; nothing is re-applied. Optional: `am kill` while backgrounded, then return: the box is empty and the notice is still shown |
| 7 | Decline and missed | A declined call opens no conversation (`DECLINED`); an unanswered one times out (`MISSED`); records unchanged |
| 8 | Locked phone, unlock cancelled | The call rings over the lock screen. Answer asks to unlock; cancelling it shows no planning content, opens no conversation, sends nothing; the call keeps ringing and the occurrence is not `ANSWERED` |
| 9 | Locked phone, unlocked by the owner | After the owner unlocks (the PIN is never typed by the driver), the conversation opens with the message unsent |
| 10 | App-owned storage listing | No audio, draft, transcript or speech names; no new files |

Locked-state steps read only window focus and keyguard state, never the lock screen contents. Network, rotation and screen-lock
settings are recorded and restored.

## Device results (2026-10-09, isolated `conversationCheck` app, Pixel 10)

Evidence: `the author's private evidence folder (not published)`. Three builds were used, all updated in place with `install -r`, data kept:

| Build | SHA-256 | Difference |
|---|---|---|
| 1 | `8a66a812…f14f` | The corrected notice wording. Installed first; its check screen had unreachable schedule buttons, so no call was armed on it |
| 2 | `b436c1a6…4a13` | The check screen made scrollable. Most runs, including the defect |
| 3 | `d08633e4…60fb` | The fix below. **Installed and final** |

Permissions had to be granted by the owner in system settings, for the check app only: "Alarms & reminders" (the first attempt returned `NotPermitted`, recorded as a `CANCELLED / NOT_PERMITTED` occurrence) and notifications (without them a scheduled call rang with vibration only, and three calls were missed without any way to answer).

### Defect found and fixed

On build 2, after one conversation was open, a second handoff was swallowed: the closing call showed the earlier opening conversation, with its old edited text and the opening wording. The conversation was started in a task with the app's default affinity, so the system brought the existing task forward and dropped the new intent. Build 3 starts every handoff in its own task (`NEW_TASK` with `MULTIPLE_TASK`). On build 3 a closing call and then a second opening call each got their own screen, the earlier closing conversation stayed open behind, and the correct wording and unsent message appeared each time.

### Results

| Check | Result | Build |
|---|---|---|
| Notice wording | PASS | 2, 3 |
| Opening, locked phone, owner unlocked: conversation opened with the opening message unsent | PASS | 2 only. **Not repeated on build 3** |
| Closing, unlocked, own fresh screen with the closing wording and unsent message | FAIL (the defect), then PASS | 2, then 3 |
| A second handoff gets its own task and an earlier conversation is untouched | PASS | 3 |
| Request count unchanged by a handoff | PASS | 2, 3 |
| Unedited natural-language message sent to the scripted fake | PASS: no proposal (the reply text was not captured by the UI dump) | 3 |
| One proposal with a unique title, tap approval | PASS: exactly one action | 3 |
| Day routing: "Open the day screen" showed "No working day is open"; its Open control was not pressed | PASS | 3 |
| Edit survives rotation | PASS | 2, 3 |
| Home, then return to a conversation | **NOT RUN** (no way back to the task from adb) | |
| Process death and restore | **NOT RUN** | |
| Decline: `DECLINED`, no conversation | PASS | 3 |
| Missed: `MISSED / RING_TIMEOUT` | PASS | 2, 3 |
| Locked phone, unlock cancelled: the call screen stayed up with the keyguard showing, no conversation opened, `MISSED` after 45 s | PASS | 3 |
| Planning records | PASS: equal to the baseline apart from the one approved action; unchanged by decline, missed and cancelled-unlock calls | 3 |
| Storage | PASS: no audio, draft, transcript or handoff file names; no new persistence path | 3 |

Answered handoff calls ended `COMPLETED`, which means only that the call interaction ended.

Limits: the `/new` prefix is a scripted-provider limitation; the request count rose from 6 to 8 only from the two deliberate fake sends; app-owned storage only; one device and one language.

### Task-lifecycle check on the final build (build 3, `d08633e4…60fb`)

| # | Check | Result |
|---|---|---|
| 1 | Edit a handoff draft, Home, return through Recents: the edited draft is still there and still takes typing | PASS (owner used Recents) |
| 2 | Three conversations open at once (closing marked `C-MARK`, an older opening marked `A-EDIT` and `B-MORE`, and a newer fresh opening): each keeps its own notice and draft, and no marker appears in another | PASS, **but not through Recents** (see below) |
| 3 | Close a conversation with Back, then a new scheduled handoff: a fresh, unsent message with none of the old markers | PASS (the task was closed with Back, not swiped away in Recents) |
| 4 | Locked phone, owner enters the PIN: the call screen showed over the keyguard, the conversation opened with the closing message unsent, the occurrence went `COMPLETED`, and the request count was unchanged | PASS on the final build |

How the checks differed from the plan:

- The owner's Recents showed only four icon-only cards, so the conversation tasks (further down the list) could not be reached by tapping. Step 2 was done by bringing each task to the front over adb (`am task lock <id>`). That pins the screen, disables Back until `am task lock stop`, and locks the phone when it is released, so each release needed an unlock. Step 3 closed the task with Back for the same reason. These are facts about the test method. The product's own Recents behaviour for older conversations is only partly exercised (step 1).
- Planning records after all of this are identical to the pre-lifecycle dump, and debug and geminiCheck were untouched. Network and rotation settings are unchanged, and screen pinning is off.

**Not verified:** process death and restore (the box stays empty after the system recreates the screen, by design, but this was not run on the device), and the product's behaviour with many tasks beyond what is described above.

### Observed outside this slice

With notifications denied, a scheduled call rings with vibration only and gives no way to answer. That is earlier M1 and M2 behaviour, and the check build has no prompt for the alarm or notification permission.
