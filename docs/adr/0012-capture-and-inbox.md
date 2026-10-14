# ADR 0012: Capture and Inbox

Date: 2026-10-07
Status: **Accepted**, 2026-10-07, on automated, Pixel and schema-5 recovery evidence. Branch `v1/slice2-day-loop`, after the Slice 2 checkpoint `0dc45e1`.

## What this adds

A place to put a thought down first and decide later. A capture needs only text. It is not an action, not a commitment, and never part of a day's plan. Resolving it is a separate, later step: make an action, keep it as a note or an idea, archive it, or discard it.

It adds one table, `inbox_items` (schema 5), one screen section, a small batch at closing, two conversation operations and one extension of an existing one. It schedules nothing: reminder delivery is a later slice.

## Data

One row per capture, in every state, kept for good:

| Column | Meaning |
|---|---|
| `id` | UUID. |
| `text` | What was entered, exactly: spaces, line breaks and Unicode as typed. Never changes. At most 10,000 characters; a longer text is rejected with its length, never cut. |
| `state` | `OPEN` (the Inbox), `ACTION`, `NOTE`, `IDEA`, `ARCHIVED`, `DISCARDED`. |
| `body` | The editable text of a note or an idea. Starts as `text` unless a rewrite was approved. Kept if the item goes back to the Inbox, so edited work is never lost. |
| `actionId` | Set exactly when the state is `ACTION`. |
| `resolvedAtMillis` | Set exactly when the state is not `OPEN`. |
| `createdAtMillis`, `updatedAtMillis` | The capture time and the last change. Preserved by every operation and by export and restore. |

The model enforces the invariants in its constructor, so a stored or imported row cannot break them: an `ACTION` has an action, an `OPEN` item is unresolved, a note or idea has a body.

There is no project, date, priority or classification on a capture. Notes and ideas are the same rows with a state, so no second entity exists.

## Store operations

Each is one transaction and enforces its own rules at the moment of writing.

- **captureItem.** A repeated save with the same id and text is `ALREADY_CAPTURED` and makes one item. The same id with other text is a `CONFLICT` and overwrites nothing. The screen keeps the id of a failed attempt, so a retry cannot make two.
- **undoCapture.** Deletes only a capture that is still exactly as captured: open, never filed, never edited. Once an item has been filed, edited or turned into an action, Undo leaves it and says so. Capture Undo cannot remove later work.
- **resolveItem** (note, idea, archive, discard). Changes state and, for a note or idea, the body. The same request repeated is `ALREADY_RESOLVED`; a different one for an item no longer open is `NOT_OPEN`, never a silent success.
- **reopenItem.** Guarded by the state the caller saw. An item that became an action is not reopened: its action stays behind, so putting the item back would leave two live things. Reversing a conversion is deferred.
- **editBody.** Notes and ideas only, guarded by the body the caller loaded (a stale read is refused). It never touches `text`.
- **convertToAction.** Creates the action and marks the item as having become it in one transaction. The action's id is derived from the item's id, so converting the same item always means the same action. The same conversion repeated is `ALREADY_CONVERTED` (the stored action matches, apart from having been finished since); a different one is a `CONFLICT`. The existing duplicate-title and project checks apply, and a refusal leaves the item open and creates no action. The action is not placed in any plan.

Discard is a state, not a delete: the row is kept and can be put back. There is no trash screen; discarded items sit under filed items, in a Discarded group.

## Authorization

| Input | Treatment |
|---|---|
| The person taps a choice on an Inbox row (note, idea, archive, discard) | A direct instruction. Applied at once, with Undo. No second confirmation. |
| The person types a note's new wording | Saved, guarded against a stale read. |
| The person converts an item | The screen shows the action that will be made before it is added, each value labelled: the title as the person's own, and priority, estimate, project and due date as Thalia's defaults. |
| The person plainly says what to do with an item in conversation (`resolve_inbox_item`) | Validated, then staged as the one pending proposal, with the original text unchanged. Applied only when Approve is tapped, then with Undo. Carries no body, so it cannot carry rewritten content. **Amended 2026-10-12: it was first written as applied at once; see the amendment below.** |
| The model chooses what to do with an item, or rewrites it (`propose_inbox_resolution`) | A suggestion. One pending proposal, applied only on approval, with the same one-at-a-time and staleness rules as ADR 0010. |
| An action from a capture (`propose_new_action` with `inbox_ref`) | A suggestion that shows the resulting action, each field marked suggested or default. Approval converts the item and creates the action in one transaction. |
| An unclear item | One `ask_clarification`. The item stays in the Inbox. |

Kotlin enforces these, not the prompt. The model sees `i1`, `i2` references for the open items of one snapshot (at most 10, oldest first, each cut for display at 400 characters; the stored text is never cut) and can name only what it was shown. Nothing in a conversation can schedule anything: the declarations contain no reminder or alarm operation, and a test asserts it.

The same limit applies as for `record_outcome`: whether the model read "save this as a note" correctly cannot be verified by Kotlin. The mitigations are that the original text is never changed, every conversational filing and every rewrite needs an Approve tap, and Undo is one tap after an approved or directly tapped filing.

## Interruption and retry

A direct filing or an approval that throws is resolved by reading the item back: the target state means saved, still open means nothing changed and approval can be repeated, anything else is refused. A conversion whose reply is lost after the commit is reported saved once, and a second approval is `ALREADY_CONVERTED`. A day that rolls over, an item filed by hand before approval, or an item gone from the Inbox refuses the approval and writes nothing. Triage that stops, fails or is dismissed leaves the items open.

## Reminder-like captures

Reminders are delivered in a later slice ([ADR 0014](0014-reminders-and-checkpoints.md) is that slice; the wording in this section and the hint below are as accepted for Slice 3, and the hint now reads "Unless you set one under Reminders, nothing is scheduled for it."). Until then, a capture that reads like one stays in the Inbox, visible, with no schedule. When its row is open, a conservative hint says: "This may be a reminder. Thalia does not send reminders yet, so nothing is scheduled for it." The hint is a short list of obvious words and phrasings (remind, don't forget, alarm, "at 3pm", "in 20 minutes"). It is not detection: it will miss some and flag some wrongly, which is why it only ever says "may".

## Screens

On the planning screen, directly under the working day: a capture box with Save (and Undo for the last capture), one **Triage with Thalia** button, and the open items, oldest first, five at a time with Show more. Each row is tappable; tapping opens its choices (Make an action, Note, Idea, Archive, Discard) and the reminder hint when it applies. Filed items are behind one collapsed line, with Notes, Ideas, Archived and Discarded groups; a note or idea can be edited and any filed item can be put back.

Triage opens the existing conversation with a suggested first message in the box. It is not sent until the person presses Send, so no request leaves the phone without a press.

### The triage path, inspected 2026-10-07

- **What the button does.** It starts `ConversationActivity` with one extra, a fixed sentence, which becomes the initial text of the message box. Nothing is read from the planning data and nothing is passed to a provider. Creating the screen builds a `ConversationViewModel` and a `ConversationSession`; neither has an initializer that calls a provider, a store or the network. A provider is reached in exactly one place, `ConversationSession.turnLocked`, which only `send()` calls, which only the Send button (or a typed plain message) calls. A bare "yes" is answered locally.
- **Which provider is wired.** `ThaliaApplication.conversationProvider` is `UnavailableConversationProvider` in every build. It returns `UNAVAILABLE` without sending anything, so even a pressed Send transmits nothing today, and the screen says AI is not set up. The `conversationCheck` build wires a scripted provider that sends nothing anywhere. `GeminiTextConversationProvider` exists in `voice-gemini`, which only the debug build type includes, and nothing in the app constructs it.
- **Network reach by build.** `INTERNET` is requested only by the debug build type, for the manual M3A Gemini Live spike, which is a separate screen with its own key and consent path and is not wired to the conversation. The release, `conversationCheck` and `recoveryCheck` manifests have no `INTERNET` permission (checked in the merged manifests), and `ReleaseIsolationTest` keeps the release classpath free of the spike, the provider and the network library.
- **Gates.** The consent, key and cost gates are not built yet, so the only thing keeping a live request blocked is that no live provider is wired. `GeminiTextConversationProvider` itself says gating belongs to its caller, and there is no caller. Wiring a live provider is therefore the moment those gates must exist, not a later hardening.
- **Test.** `ConversationProviderGateTest` asserts with a counting fake provider that opening a conversation (state read, dismiss, approve and undo with nothing pending) makes zero calls, that a bare "yes" makes none, that only a real message makes one, and that the unavailable provider answers `UNAVAILABLE` and the session reports it without a write.

No violation was found, and no code was changed for it.

When closing a working day, one optional line, "Look at the Inbox first (up to 5)", opens a batch of at most five items inline. Closing never asks for it and never requires the Inbox to be empty.

## Storage, export and recovery

Planning schema 5 (`MIGRATION_4_5`): the `inbox_items` table and nothing else; no existing table or row is touched. The payload is schema 5 with an `inboxItems` list and count; schemas 1 to 4 still decode with an empty Inbox, and a schema 4 payload that carries an inbox key is refused. Import validates states and invariants through the model, unique ids, that every converted item points at an exported action, and that two items do not claim one action. `restoreIntoEmpty` writes the rows with their times, and a database holding only an Inbox row is not empty.

## Evidence, 2026-10-07

Computer only so far. Sequential run: `:core:test` 265 (1 skipped), `:voice-gemini:test` 42, `:app:testDebugUnitTest` 711, `:app:testReleaseUnitTest` 636, `ktlintCheck`, `:app:assembleRelease`, `:app:assembleConversationCheck`, `:app:assembleRecoveryCheck`: all pass.

New tests: the item invariants and the reminder hint; schema 5 round trip, schema 4 still decoding, and each broken-row refusal; the 4 to 5 migration compared by logical records; store tests for verbatim text and the limit, retried capture, undo limits, retried and conflicting filing, stale body edits, a note's edit surviving reopening, atomic conversion including a rollback of both writes when interrupted, and snapshot and restore of every state; the encrypted-archive round trip and Replace with an Inbox; and the conversation boundary end to end: a conversational filing (then applied at once; now staged for Approve, see the amendment) with Undo and no second confirmation, an Undo that does not overwrite a later change, a rewrite waiting for approval, a conversion shown before approval and never planned, a reply lost after the commit, a failure before the commit, an item filed by hand before approval, an ambiguous capture left open, and a reminder-like capture left unscheduled.

## Evidence, device, 2026-10-07

Pixel 10, isolated `conversationcheck` and `recoverycheck` only, synthetic data; evidence in `the author's private evidence folder (not published)` (no credentials). The real debug app, its alarms and the Stage G candidate were not touched.

- **Upgrade.** The existing schema 4 storage upgraded in place to schema 5. The pre-upgrade dump was byte-identical to the accepted Slice 2 baseline, every section was identical afterwards, and the Inbox started empty.
- **Capture.** Seeded multiline and non-ASCII captures matched byte for byte. A capture typed with a line break saved, and Undo removed it while untouched. After a capture was filed as a note, its Undo answered "already filed or changed, so it stays".
- **Notes and ideas.** A note was edited, and an idea was filed. A note put back in the Inbox kept its original text and its edited body.
- **Archive, discard, recover.** An archive and a discard were made, and a discarded item was put back from the Discarded group.
- **Conversion.** The review showed the title as the person's and priority, estimate, project and due date as defaults. Approval gave one action each time, with plans and plan items unchanged. A conversion whose reply was lost after the commit (scripted fault) was reported saved once and left one action. The device cannot tell this path from a normal save, so the retry guarantee rests on the store and session tests.
- **Conversation.** A plainly stated filing applied at once with Undo and no approval (device evidence of the superseded behaviour; now it waits for Approve). A rewritten idea waited for approval, and dismissing it left the item open. A proposed conversion approved after the item had been filed was refused ("no longer in the Inbox") and made no action.
- **Closing.** Closing a working day with an open Inbox item succeeded, the optional batch listed the one item, and the item stayed open.
- **Export and restore.** A schema 5 export (8 actions, 9 Inbox items in all six states, two actions linked to their items) was restored into recoverycheck with Replace, which kept the previous files in a second set-aside folder. **The canonical logical snapshots were byte-identical**, and the source was unchanged afterwards. Database files were not compared.

## Limitations

- **Not run on the real installation.** The 4 to 5 migration ran on the isolated check app only. The real debug installation stays at schema 3 until Stage G is decided and an export is taken.
- **The Triage with Thalia button** was not pressed on the device. By inspection it only prefills the box, and the provider wired is unavailable (see above). The conversation was exercised through the scripted provider.
- **No live provider yet.** Real model behaviour on Inbox triage, including how a model reads "save this as a note", is unobserved. The consent, key and cost gates must exist before a live provider is wired.
- **Closing day state.** The check closed the synthetic 10 Oct working day, so that row is now closed in the isolated storage.
- **A conversion cannot be reversed.** An item that became an action stays resolved; undoing means editing or dropping the action by hand.
- **The model's reading of a plain instruction cannot be verified in Kotlin.** See above.
- **The reminder hint is crude.** It misses reminders phrased in other words and flags some that are not.
- **Filed items are not searched.** They are browsed by group, newest first.
- **A very long capture cannot become an action title directly.** The title is capped at 200 characters, so the person writes the action in their own words; the capture is kept as it was.
- **Creating an action from the Inbox always uses defaults** (normal priority, 30 minutes, no project). Details are changed under Actions.

## Amendment, 2026-10-12: a conversational filing waits for Approve

The "applied at once, with Undo" treatment of a filing that the person states in conversation (`resolve_inbox_item`) is wrong
since the owner's 2026-10-12 decision that only an Approve tap applies any model-proposed change (see the amendment in ADR 0010).
The code already behaves this way: a stated filing is the one pending proposal, a typed or spoken "yes" changes nothing, Dismiss
drops it, and Undo is offered only after an Approve. A direct tap on a choice in an Inbox row is the person's own act: it is still
applied at once, with Undo, and needs no second confirmation. The Evidence sections above keep what was observed on 2026-10-07
under the earlier rule and are not rewritten.
