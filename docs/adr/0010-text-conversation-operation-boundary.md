# ADR 0010: The text conversation and its operation boundary

Date: 2026-10-06
Status: **Accepted 2026-10-06** as the text-operation foundation, on unit tests and an isolated Pixel check with a scripted provider (below). Real-model interpretation and provider readiness are not proven. No request to Google has been made.

## What this adds

A text conversation in which a model may propose operations over existing planning objects and Kotlin decides what each may do. It adds no screen beyond one small activity, no voice, no schema change and no new entity.

## The authorization contract

| Input | Treatment |
|---|---|
| The person states what happened to one listed action on today's approved plan (`record_outcome`) | Validated, then staged as the one pending proposal like any suggestion. Recorded through `recordOutcome` only when Approve is tapped, then with Undo. **Amended 2026-10-12: it was first written as applied at once; see the amendment below.** |
| The model suggests a new action, a change to an open action, or today's selection (`propose_*`) | A suggestion. Becomes the one pending proposal. Nothing is written until the person presses its approve button. A typed or spoken "yes" never approves (amended; see below). |
| A question the model needs answered (`ask_clarification`), or plain text | Shown. Changes nothing. |
| A bare "yes" with nothing pending | Answered locally, "Nothing is waiting for approval." Not sent to the provider, since it has no referent. |
| Anything else: an unknown function, an extra or mistyped argument, a reference the snapshot did not show | Refused whole, with the reason. |

Rules enforced in Kotlin, not in the prompt:

- The model sees short references (`a1`, `p2`) for one snapshot, never a stored id. It can name only what was shown.
- A conversation cannot touch status. Completed actions are not in the snapshot. An outcome is refused if the item already has one, if the action is not on today's plan, or if the action is closed. A repeated claim therefore never overwrites or reopens.
- Creating or changing an action never places it in today's plan. Selecting the day is a separate proposal.
- A day selection is previewed by `Planner.propose` and approved by `PlanningStore.approve`, which runs its own validation and staleness comparison. A refused empty selection, a plan with recorded outcomes (U1) and a missing window are refused before a preview exists. An overload is stated in the approve button's label.
- Staleness: an action change applies only if the stored action equals the one the proposal was made from (`changeAction`). A day selection whose data moved is planned again, shown as a new preview, and needs a new approval. A day that rolled over is refused.
- One proposal at a time. A newer one replaces the pending one and says so. A second proposal in one reply is refused.
- Interruption: an unapproved proposal lives in memory and is dropped on exit, so nothing is applied. If a write throws, the session reads the stored state before saying saved, not saved or unknown. Creation uses a fixed id and `createAction`, which refuses an existing id and an open action with the same title in the same project, so the same creation cannot happen twice, and a repeat after an interrupted-but-committed save is refused.
- Every result is labelled: Saved, Proposed (not applied), Not applied, or a Note for something uncertain.

Known residual risk: whether the model read the person's sentence correctly cannot be verified by Kotlin. The mitigations are the enumerated results, one unrecorded item at a time, a visible acknowledgement and one-tap Undo.

## Provider choice

Compared on 2026-10-06 for a text request with function declarations, manual handling of calls, and a later Live path.

| Option | What it gives | Cost to us | Evidence |
|---|---|---|---|
| Our own transport: one `generateContent` POST on the OkHttp that `:voice-gemini` already has | Text and function calls with the shapes we need. Live already works through the same module. | About 150 lines, no new dependency. We own schema drift. | Gemini REST reference, excerpt via fetch. Code and tests here. |
| `googleapis/kotlin-genai` 1.4.0 (official) | Typed text, tools and Live. Automatic function calling is optional and off unless enabled. JVM, Android API 21+, Ktor. | A Ktor dependency tree beside OkHttp, a mapping layer to `RawCall`, and nothing replaces the working Live code. Its README discourages embedding keys in public mobile apps. The docs fetched did not show a `toolConfig` example, and gave no Android engine guidance. | README, excerpt via fetch. Not built or measured. |
| Firebase AI Logic Android SDK (official) | Key held server side behind App Check. Manual function calling, `NONE`/`AUTO`/`ANY`. Live listed. | A Firebase project, `google-services`, App Check and a Firebase proxy between the phone and Gemini. A second data path and different billing and consent wording from the paid Gemini terms already chosen. | Firebase docs, excerpt via fetch. Not set up. |

Choice: keep our own transport. It is the least total code for one request and one reply, adds no dependency, keeps the working Live transport and its M3A acceptance untouched, and sits behind `ConversationProvider`, so replacing it is replacing one class. The key-on-device concern the SDKs raise is about public apps; this is a personal install, and the key is sent only in a header from the existing sealed store.

Revisit if: the app is ever distributed beyond the owner's phone (Firebase AI Logic), schema drift costs more than the code it saved, or streaming text is needed (an SDK then removes real code).

Mutation tools are never executed automatically. The adapter sends calling mode `AUTO`, returns calls as text, and never sends a function result back; Thalia reports results itself.

## Consent, key and cost: what is reusable

Sound to reuse in the wiring slice: `ApiKeyStore` with `KeystoreAesGcmCipher` (sealed key, redaction), and the consent store pattern (a stored consent to other wording counts as none). Not sound as they are: the consent wording (Free tier; paid terms need their own text and version), `ReservationLedger` and `StartGate` (fixed USD 0.25 per Live attempt and voice-specific inputs). These are in `src/debug`; the release-type target needs them in `main`.

**Built by [ADR 0013](0013-grounded-text-ai-preparation.md) (2026-10-08):** the key store and cipher moved to `main`, a paid-terms consent, a persistent budget ledger and a single gated provider replace the debug-only pieces for the text conversation. The M3A pieces stay in `src/debug` for the spike.

Until they existed, `ThaliaApplication.conversationProvider` is `UnavailableConversationProvider`: every send answers "AI is not set up" and no data leaves the phone. Real personal context must wait for billing-enabled paid terms (Gemini API terms: paid services are not used to improve Google's products, Free tier content may be, and human reviewers may read it; read 2026-10-06) and a request cap.

## Limitations

- No AI answers on any build yet. The Gemini adapter is tested only against a local fake server. The model id `gemini-3.8-flash` comes from Gemini's models page and has not been exercised.
- Pins: a day selection keeps today's existing pins for actions that stay, and cannot create pins.
- Changing a recorded outcome, dropping, moving or reopening an action, goals and projects are not conversation operations.
- The conversation is not stored. Context is bounded (40 actions, 15 projects, 8 goals) and omissions are counted.
- The screen is functional and unstyled.

## Acceptance evidence, 2026-10-06

**Automated.** `:core:test` 226 (1 skipped), `:voice-gemini:test` 42, `:app:testDebugUnitTest` 1,245, `:app:assembleDebug`, all passing before the bare-yes change. After it: the conversation tests (16) and `:app:ktlintCheck` pass. The session tests run the real session, port and planning store against a scripted provider.

**Device.** Pixel 10, `google/frankel/frankel:17/CP3A.260905.009/16091614:user/release-keys`, 2026-10-06 17:12 to 17:42 +08. Evidence is outside the repository, in `the author's private evidence folder (not published)`.

- Build: the `conversationCheck` build type, application id `com.thalia.app.conversationcheck`, with its own data directory, Keystore alias and planning files (`conversationcheck-planning`), the same pattern as `recoveryCheck`. Its launcher, synthetic seed and `ScriptedProvider` are in `src/conversationCheck` only; debug and release builds do not contain them. The scripted provider turns typed commands into the calls a model could return, and they pass through the production parser, validator, session and store. A one-shot `FaultSwitch` makes the next action creation commit and then throw (`commit`), or also fail the read-back that follows (`blind`).
- APK run: SHA-256 `ab4ffa36…ab35c`. Rebuilt after ktlint formatting only (`e5cad0fe…9759`) and not reinstalled. The bare-yes change came after the device run and rests on its unit test.
- Synthetic data only. The check app stored M2's default check-in rows in its own database to give planning a zone; no alarm was armed.

| Check | Observed |
|---|---|
| Stated outcome, then Undo | Saved at once ("Recorded … as done. The action is now closed"), shown in the planning screen; Undo restored "no result recorded" and an open action, and its button went away. |
| New action | Waited for approval; "It is not in today's plan". A double tap saved once. The same title proposed again was refused. Commit-then-throw reported "Added" from the stored state. Commit-then-throw with a failed read-back reported "Could not tell…", and a retry was refused as a duplicate. Each created action appeared once, outside today's plan. |
| Stale action change | After a manual edit in the planning screen (estimate 30 to 45), approval was refused, "is different from when this was proposed"; the manual value stayed. |
| Day selection | Planner preview, approval saved revision 1, "Calls are not changed". After a manual edit behind a second preview, approval gave "Not applied … Nothing was saved" and a new preview; approving that saved revision 2. |
| Leaving with a pending proposal | Back, then reopen: an empty conversation; the proposed action was never created. |
| Provider failure and unavailability | Network failure, a throwing provider and an unsupported operation were each labelled "Not applied" with nothing changed. The production screen answered "AI is not set up on this build…" and recorded nothing from "I finished alpha". A manual outcome then saved in the planning screen. |

Preserved: `com.thalia.app.debug` (last update 2026-10-05 06:12:34) and `com.thalia.app.recoverycheck` were not installed over, cleared or driven. The debug app's `planning.db`, `checkins.db` and their `-wal` files were unchanged since 2026-10-05, and it had no pending alarms before or after. Its process was started at 17:12:28, before the check's first command, from outside the check.

## Limits of this acceptance

- **Interruption was simulated, not caused.** The fault switch throws after a committed write inside the running process. Process death, power loss or a killed app during a write were not exercised. The duplicate guard is durable (a fixed id and an open-title check in one transaction), so a retry after process death would meet the same refusal, but that is inferred, not observed. An unapproved proposal is in memory only, so process death drops it, as leaving does.
- No real model has answered. Whether a model maps a person's sentence to the right operation and reference is unverified; the mitigations are the enumerated results, one item at a time, an acknowledgement and Undo.
- Provider readiness is not built: paid-terms consent text, a request cap, and a release-type key and consent store.
- The pending panel squeezes the conversation on a phone screen, so the latest status line can need scrolling. The screen is functional, not the accepted design.


## Amendment, 2026-10-12: only Approve applies a model-proposed change

The owner settled that only tapping Approve may apply any model-proposed change, including an outcome, a focus capacity or an
Inbox filing inferred or extracted from a spoken or typed message. This replaces the earlier "fact applied at once" treatment
above and in ADR 0012, and the earlier "a plain yes approves" rule. The code already behaves this way: a stated outcome, focus
capacity or Inbox filing becomes the one pending proposal (labelled "Record outcome", "Set focus", and so on), a bare yes is
answered locally and changes nothing, Dismiss drops it, and Undo is offered only after an Approve. Direct taps on a result button
in the planning screens are the person's own act and remain allowed. Regression tests: `ConversationSessionTest` and
`ConversationOutcomeApprovalTest`.
