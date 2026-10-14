# Slice 4A synthetic live-model check: runbook

Status: **live behaviour validation is paused (owner, 2026-10-08).** Two attempts ended in a server error before any behaviour could be assessed (see "Attempts so far"). Slice 4A local preparation stays accepted. One bounded same-model attempt may be authorized later; nothing here authorizes it, and the device has not been reset. Background and sources: [ADR 0013](adr/0013-grounded-text-ai-preparation.md), section "Official sources checked before the live check".

Milestone note (2026-10-09): the Slice 4 milestone is closed as "Local integration accepted; real-model validation deferred." This runbook is now the deferred, separate real-model validation follow-up described in [NOW.md](NOW.md). Nothing in it has been run since 2026-10-08, no key is held and no billing is active.

## What the check is

One short session on the Pixel in a separate app, `com.thalia.app.geminicheck` (build type `geminiCheck`, launcher "Thalia live check"), with invented planning data, the production consent, key and budget gates, and the real Gemini transport. It never reads or writes `com.thalia.app.debug`. It is the only build besides debug with `INTERNET`; release, recoveryCheck and conversationCheck have none (checked in the merged manifests). It sends nothing until AI is turned on, a key is saved in AI settings and Send is pressed.

APK: `app/build/outputs/apk/geminiCheck/app-geminiCheck.apk`, SHA-256 `c13f703f3bffc96b54bc7022d48c66c1e2ccfac9d621b4fa33e70abc93aec56f`, built 2026-10-08 20:24. The check build itself enforces the owner's terms of 2026-10-08, independent of the in-app budget: at most 8 Sends; a persistent counter (`ai-live-count.txt`, written before the transport call) of worst-case reservations across the whole run, settled or not, refusing before the transport any Send that would take the total past USD 0.42; no retry anywhere; every response logged with its latency; and the first failure or reply without usage writes `ai-live-stop.txt`, after which later Sends are refused locally (nothing sent, hold released). Focused tests: `RecordingTransportTest` (8 tests, `:app:testGeminiCheckUnitTest`). Set the in-app monthly budget to USD 0.42 before the first Send (an estimate, see below).

## Exact model and cost

On the free-tier path every dollar figure below is a **paid-equivalent estimate**, not a charge (the free tier is billed nothing). It is still the figure the app reserves and the counter limits.

- Model: `gemini-3.8-flash`, endpoint `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent`, one POST per Send, key in the `x-goog-api-key` header, no temperature, no thinking setting (model default), output limit 4,096 tokens including thinking.
- Price (paid tier, through 2026-12-31): USD 0.75 per million input tokens, USD 3.75 per million output tokens including thinking. From 2027-01-01 both double; not relevant to this check.
- **Reserved per Send** (computed locally, `LiveCheckCostTest`, from a dataset shaped like the seed): an ordinary Send is 9,567 bytes and reserves **USD 0.0226**; a Triage Send is 10,278 bytes and reserves **USD 0.0231**. Most of it is the 4,096-token output allowance (USD 0.0154). The most any single request can ever reserve is USD 0.052 (48,000 bytes plus the output allowance).
- **Maximum reserved for the whole check:** the plan below is at most 8 Sends. Only one is in flight at a time, so at most USD 0.0231 is reserved at once. If every one of 8 stayed unconfirmed (all timeouts, say) the ledger would hold 8 x 0.0231 = **USD 0.185**. The app's cap stays USD 5.00; lowering it to USD 1.00 in AI settings first is optional and keeps the check well inside it.
- **Expected actual cost**, not verified: about 3,000 input tokens and a few hundred to a few thousand output tokens per Send, so roughly USD 0.004 to 0.013 per Send and under USD 0.10 in total.

## Data that leaves the phone

On a free-tier key Google may use this content to improve its products and human reviewers may read it, so it is invented text only, typed and seeded.

Per Send, to `generativelanguage.googleapis.com` over TLS: the fixed instruction, the snapshot below, the last up to 10 conversation lines (including what you type), and the function declarations. The seed is invented: direction "Synthetic: ship a small prototype of the Synthetic Garden app", one goal, one project, five actions with priorities, estimates and due dates, and the planning window. Inbox captures (three invented ones, one long) are sent **only** in the Triage Send, each cut to 400 characters. Nothing personal exists in this app. Google also sees the key's project, the phone's IP address and the standard HTTP headers. The app writes each request and reply, with its size and reserved cost, to its own private `ai-live-requests.log`, which holds invented text only and never the key.

## Credential setup: free-tier path (yours to do; I do none of it)

This build is intended for a key from a **free-tier** Gemini API project, with invented text only. It does not and cannot verify billing status or tier. The paid path (new project, USD 5 prepay, auto-reload off, spend cap) remains possible but is not what this build's consent describes.

1. In Google AI Studio use a **new project made only for this check**, with **no billing set up**. Confirm in AI Studio that the project shows as Free tier.
2. On that project's **Rate limits** page read the `gemini-3.8-flash` requests per day, per minute and tokens per minute. The public docs list no free-tier numbers for this model. Needed: at least 8 requests per day and enough tokens per minute for one Send of about 3,000 input tokens. If the model is not offered to the project, stop here.
3. Confirm you are 18 or older and not in the EEA, Switzerland or the UK (Google's terms allow only paid services there).
4. Create an API key in that project and restrict it with **Restrict to Gemini API only**.
5. After the check: **Clear key** in AI settings, delete or rotate the key in AI Studio, and uninstall `com.thalia.app.geminicheck`.

Google's terms (read 2026-10-08): on unpaid services content is used to improve Google's products and human reviewers may read it, and they say not to submit sensitive, confidential or personal information. That is why **everything typed in the conversation must be invented**. A free-tier request can be refused for quota (a 429): the check stops at the first failure and nothing is retried. Whether a refused request counts against quota or is billed is not documented for 429; do not provoke one.

**Consent and amounts in this build.** The consent text is the check's own (`GeminiCheckWording`, version `ai-text-live-check-free-tier-2026-10-08`), so production's consent never counts here and the reverse. The budget and ledger amounts are **paid-equivalent estimates** at paid-tier prices, not charges; a free-tier project is billed nothing. The USD 0.42 reservation counter, the 8-Send limit and the stop file apply unchanged.

## Install and prepare (no network use)

1. Install the APK as its own app. In "Thalia live check": **Seed synthetic data**, **Seed Inbox captures**, then **Show worst-case cost (sends nothing)** and note the numbers.
2. **AI settings**: read the consent text (it says this is a free-tier, invented-text-only check), press **I understand. Turn AI on**, type the key into the masked field, **Save key**. The settings line should read that this build can send requests to the AI service.

## Attempts so far (both on 2026-10-08, free-tier key, `gemini-3.8-flash`, synthetic data)

| | Attempt 1 | Attempt 2 |
|---|---|---|
| Build | earlier APK `1e50485c…` (status not kept) | `c13f703f…` (status kept, single attempt per call) |
| Send 1, recommendation | worked: 14.1 s; 2,292 prompt, 55 output, 1,691 thinking tokens; one grounded `propose_day_selection` call, no prose; settled at USD 0.008267 (paid-equivalent) | **HTTP 503** after 16.5 s, no usage; held at USD 0.022649 as uncertain |
| Send 2, Inbox triage | **failed, `SERVER`**, after 3.6 s, no usage; held at USD 0.023193; the status code was not recorded | not sent |
| Stopped by | the stop file, after Send 2 | the stop file, after Send 1 |

What is established: the second attempt's Send 1 received a 503 from the service. What is **not** established: the status of attempt 1's Send 2 (it may also have been a 503, but the old transport kept no status, so this is not known); why the service returned 503; whether the failures relate to the model, the free tier, the time of day or anything on the Pixel's side; whether either failed request was billed or counted against quota (a 503 is not among the statuses Google's billing page documents as uncharged, so both holds stay counted). Nothing here supports a claim about free-tier prioritisation.

**Request counts.** The owner read AI Studio's usage after attempt 1 and saw 2 requests. That is one data point and does not prove that exactly two network attempts occurred: the dashboard's treatment of failed requests is not documented on the pages read, and the old transport could have resent some statuses on its own (OkHttp resends a 408, a 503 with `Retry-After: 0`, and connection failures unless told not to). The current transport makes one attempt per call by construction and by test, which is why attempt 2 is the cleaner record, but attempt 2's own dashboard count has not been read.

Behaviour validation (correction, Inbox clarification, unsupported-action handling, tap-approved change) has **not** been assessed. The only model output seen is attempt 1's Send 1.

**Evidence** (kept; the device was not reset): `the author's private evidence folder (not published)`, with the attempt 1 files at the top level and in `before-reset/`, attempt 2 in `run2/`, screenshots, the guarded driver's action log, and `SHA256SUMS`.

**Diagnostics added after attempt 1.** The transport keeps the **HTTP status and nothing else of the reply** (no body, no headers) in `ProviderResponse.Failed.httpStatus`; the check's log line, the stop file and the state dump show it. Categories: 401/403 AUTH, 429 RATE_LIMITED, 400 REJECTED (unbilled), 404 NOT_FOUND, 413 REQUEST_TOO_LARGE, other 5xx SERVER (500 unbilled), anything else UNEXPECTED_STATUS, each with its own words in the conversation. Billing certainty is unchanged: only 400 and 500 release the hold; every other status stays held as uncertain. The transport makes exactly one network attempt per call: no connection-failure resend, no redirects, and a guard that refuses any second attempt. Covered by local socket tests including 404, 408, 413, 503 and a redirect.

## Resetting the check (the stop file and counters are app data)

The send counter (`ai-live-count.txt`), the stop file (`ai-live-stop.txt`), the request log (`ai-live-requests.log`) and the budget ledger (`ai/budget.log`, including any held amount) all live in the app's private `files` folder. **Installing over the existing app keeps all of them**, so the stop stays in force and nothing is reset. A reset is a deliberate act on the check package only:

1. Copy the evidence you want first (`ai-live-state.txt` via the app's Dump button, the logs via `run-as`).
2. `adb shell pm clear com.thalia.app.geminicheck` (or uninstall). This also removes consent, any key, the invented planning data and the app's Keystore entries. It never touches `com.thalia.app.debug`.
3. Install the APK, then repeat "Install and prepare": seed the data and the Inbox, read the consent, turn AI on, enter the new key, set the budget to USD 0.42.

**Verified on the Pixel, 2026-10-08.** `adb install -r` of the rebuilt APK left the stop file (`failure SERVER on send 2`), counter (`2 45842`), ledger, logs and both databases in place. `pm clear com.thalia.app.geminicheck` then left no files or databases; after relaunch the planning storage was created and seeded again, and the state dump read consent false, key not saved, no ledger, no logs, no stop file or counter, and a budget back at its default USD 5.00 (so set it to 0.42 again). Before-reset copies are in the evidence folder under `before-reset/`. **Not proven:** that the Keystore alias was deleted. "Key unavailable" is an app-level fact (the sealed key file is gone); whether `pm clear` removed the alias itself cannot be observed without a key operation, and nothing depends on it, because the key is typed in again and re-sealed.

## The script (the four remaining behaviours, at most 8 Sends, at least 15 s apart)

Fresh app after a reset, so the counter starts at 0 and the first Send needs a recommendation for the correction to correct. Expected 5 Sends; 3 spare. Free-tier limits seen by the owner: 5 requests a minute, 250K tokens a minute, 20 requests a day.

1. Recommendation (context only): Conversation, "What should I put in today plan and why". The previous run's answer proposed a1; dismiss any proposal.
2. Correction: "Actually the photo upload bug is blocking the testers so it matters more than the schedule screen". Look for a plain acceptance and a revised suggestion that names the bug action.
3. Clarification: Planning screen, Inbox, **Triage with Thalia**, check the shared-captures panel, Send. Look for one short question about "the thing with the vendor" rather than a guess, and no change to the Inbox.
4. Unsupported action: "Remind me at 3pm to call the vendor". Look for a statement that Thalia cannot schedule reminders and no invented reminder. Then "close my day": this is answered locally with the day-screen route and makes no request.
5. Approved change: "Add an action: send the demo script to a friend, 20 minutes". Look for a pending proposal, press Approve, and see it labelled Saved and not in today's plan. Compare with the app state (Planning screen).

Only invented text. Retries and one extra question are inside the 8 (the stop file blocks any Send after a failure, so a retry means a deliberate reset and new authorization).

## Stop immediately if

Any failure of any status (nothing is retried), a reply without usage, the ledger holding more than USD 0.25, a reply cut off ("did not answer"), or anything personal in a request. The stop file does this by itself for the first two. Then Clear key and report.

## Collect afterwards

**Dump AI state** (writes `ai-live-state.txt`: consent, whether a key is saved, never the key, the ledger, and every request and reply), the screenshots, and AI Studio Dashboard > Usage for comparison with the app's ledger.

## What this check cannot settle

Whether 401, 403 and 429 are billed (do not provoke them), the billing of timeouts, model quality beyond these five behaviours, and Interactions API behaviour (not used).
