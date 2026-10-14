# ADR 0003: Voice Check-ins belongs to Thalia, and optional AI is permitted at the edges

Date: 2026-09-18
Status: Accepted. Decided by the captain on approval of M0.
Supersedes: part of [ADR 0001](0001-voice-checkins-spike-architecture.md), and `PRD.md` section 6.4 as recorded 2026-09-13.
Amends: `PRD.md` sections 2, 5.5, 6, 6.4, 6.6 and 7, and `THALIA_VOICE_CHECKINS_PRD.md` sections 3, 5, 8 and 9.

**Widened 2026-09-29 by [Thalia Product Specification v2](../product-spec-v2.md).**
Decision 1 stands.
Decision 2 is restated by the specification as "local-first, not AI-free": AI may understand and propose across Thalia, not only in optional edge features, through a pipeline in which local Kotlin validates, simulates and executes, and the user approves consequential changes (its sections 2 and 8).
Core data and planning stay usable locally, and every AI failure falls back to local structured interaction (its section 16), which is the protection decision 2 exists for.
The specification also makes the check-in calls part of the daily loop rather than an optional feature.

**Decided by the captain 2026-09-29.**
Decision 2's exact deletion test ("delete every AI-backed feature and Thalia must still be a complete, working v1") is superseded.
It is replaced by this principle:

> Thalia's local data, alarms, planning and deterministic execution remain usable without an external AI provider.

Decision 2's consent condition stands and is stated precisely: external AI is opt-in, it begins disabled, and explicit consent is required before any context is sent externally.
The other three conditions of decision 2 stand unchanged.

Decision 3's cost statement is extended by a default USD 10 monthly cap, with warnings at 70% and 90% and explicit approval past 100% (its section 8).
For the text conversation, [ADR 0013](0013-grounded-text-ai-preparation.md) sets the monthly figure the captain approved on 2026-10-08, USD 5, in place of the default USD 10, with warnings at 70% and 90%. On 2026-10-08 the owner also set the product's whole recurring technology cost, AI and hosting together, at RM 40 to 50 a month, which supersedes the earlier V2 and V3 allowances; the app therefore accepts no AI budget above the USD 5 validation cap until that ceiling is divided in a recorded decision.
The text below is left as it was decided.

## Context

M0 delivered the Voice Check-ins foundation and, in doing so, surfaced a contradiction the repository could not hold indefinitely.

`PRD.md` section 6.4, recorded 2026-09-13, stated that Thalia contains "no AI model, no LLM API integration, and no API key, ever", and described section 6 as not open for reinterpretation.
Voice Check-ins requires all three.

ADR 0001, written before M0, resolved this by separation: the spike would be a standalone application, in this repository but not part of Thalia, so section 6.4 would not apply to it.
That resolved the contradiction on paper by leaving both documents true of different things.
It did not resolve the product question, and it deferred a decision that got more expensive the longer it waited: whether the work would ever come home.

M0 was approved with three decisions that answer it directly.

## Decisions

### 1. Voice Check-ins is part of Thalia, in one repository

Voice Check-ins is a Thalia feature, not a separate product.
It may live in isolated Gradle modules, and it should, but it does not become a second app, a second repository, or a permanently separate thing.

This reverses ADR 0001's "standalone application" framing while keeping everything else that ADR decided.
ADR 0001's reasoning was sound for what it knew: there was no Thalia app, and creating a multi-module Android build to host a three-day spike would have let throwaway code choose the real product's foundations.
The decision here removes the premise rather than the reasoning. The `:app` module M1 creates is now **the foundation of the Thalia application**, built as such from the first commit, not a spike surface to be rebuilt later.

The practical consequences:

- `:core` stays plain Kotlin and portable, exactly as ADR 0001 required. That decision stands unchanged.
- `:app` is real. Its package, its minimum SDK, its Compose setup and its build conventions are Thalia's, and they are chosen with that weight.
- Module isolation carries the separation that a separate repository would otherwise have carried. Voice Check-ins code stays behind its own boundary, and removing the feature stays a bounded operation.
- There is no M6 "integrate into Thalia" milestone any more, because there is nothing to integrate. What remains is an architecture review, which M6 keeps.

### 2. The core is local-first; optional features may use a replaceable AI provider

The absolute restriction is replaced by this rule:

> Thalia's core planning, goals, scheduling, and stored data remain local-first and usable without cloud AI. Optional features such as live Voice Check-ins may use a replaceable external AI provider with explicit user consent, locally protected credentials, and graceful offline fallback.

The old wording protected something real, and that part survives intact: the planning core must never depend on a model. A Thalia that cannot show you your schedule because an API is down is a worse product than one that never had AI in it.

What the old wording also did, unintentionally, was forbid optional features at the edges. "Ever" is a strong word to have spent on a boundary that was never the thing being protected.

The new rule keeps the protection and moves the line to where it was actually meant to be. It carries four conditions, and they are conditions rather than aspirations:

- **Consent is explicit and per-feature.** Off until switched on, with the provider named and the data leaving the device described plainly.
- **Credentials are local and protected.** Entered at runtime, Android Keystore backed, never committed, never logged, never exported.
- **The provider is replaceable.** Behind an interface carrying product concepts only. M0's `VoiceSessionClient` already is this.
- **Offline degrades gracefully and honestly.** Says what is unavailable and why; never silently fails, never pretends.

The test that keeps this honest: **delete every AI-backed feature and Thalia must still be a complete, working v1.** If that ever stops being true, this rule has been violated regardless of what any individual feature looks like.

### 3. Do not claim internet calling is free

The cost language is replaced by:

> Internet calling avoids PSTN and carrier charges. The implementation should prefer a provider whose free tier covers personal use, make any possible cost visible, and keep the provider replaceable.

ADR 0002 decision 1 argued against PSTN partly on cost, and the M0 documents then went further and described the result as free. That overreached.

What is actually true is narrower and still decisive. Avoiding PSTN removes a per-minute carrier charge that is certain, permanent, and scales with use. It does not remove provider cost, which is uncertain, depends on a free tier that is somebody else's decision to change, and is not under our control.

Stating it as free would have made a pricing change look like a bug rather than the foreseeable event it is. Stating it accurately means free-tier limits are a thing to check before M3 rather than discover during it, and means the replaceable-provider requirement in decision 2 is load-bearing rather than tidy.

## Consequences

Positive.

- The repository stops carrying two contradictory records of one decision.
- M1 builds the real Thalia application, so its work is not throwaway.
- The provider interface from M0 stops being a nicety and becomes a policy requirement, which makes it harder to erode later.
- Cost is stated in a way that survives a provider changing its pricing.

Negative and accepted.

- `:app` now carries real weight from its first commit. Choices made in M1 about package name, minimum SDK and build conventions become Thalia's, so they are decided rather than defaulted.
- The core must be provably usable with AI off. That is a constraint on every future feature, and it needs enforcing in review rather than trusting.
- Historical documents now carry superseded sections. They are marked inline rather than deleted, because the reasoning behind the original restriction still governs the core and is worth being able to read.

## Alternatives considered

**Leave section 6.4 as written and keep Voice Check-ins permanently separate.** This was ADR 0001's answer. Rejected by decision 1. It would mean maintaining two apps for one person, and the feature's whole value is that it knows what is in Thalia.

**Delete the old wording rather than mark it superseded.** Rejected. The reasoning behind it is still the reasoning that protects the core. A reader who finds only the new rule loses the argument for why the core boundary exists.

**Amend only the Voice Check-ins PRD and leave `PRD.md` alone.** Rejected. The contradiction lives in `PRD.md`; amending everything except the contradictory document leaves the contradiction. `PRD.md` was merged onto this branch specifically so it could be amended here.

**Allow AI anywhere in Thalia now that the restriction is lifted.** Rejected, and not what was decided. The rule is deliberately asymmetric: local-first core, optional edges. Decision 2's deletion test is what keeps that from drifting.
