package com.thalia.app.ui

/**
 * The V1 user manual, as plain data so it is easy to update. To change it, edit the text below and nothing else.
 *
 * Conventions:
 * - `[[Label]]` marks a button, field or heading exactly as it appears in the app. It is drawn bold, and a test checks that every
 *   marked label still exists in the app's own strings, so a renamed control cannot leave the manual wrong.
 * - A [Block.Unfinished] block names something that is not built or not verified yet. Remove it when that stops being true.
 * - No provider price appears here on purpose: prices change, and the app shows its own estimate in AI settings.
 * - Write only what the app does today. Do not add imports, memory, caching, retries or any feature that is not built.
 */
internal sealed interface Block {
    data class Text(
        val text: String,
    ) : Block

    data class Bullets(
        val items: List<String>,
    ) : Block

    /** A small worked example with a short label such as "Example: a goal". */
    data class Example(
        val label: String,
        val lines: List<String>,
    ) : Block

    /** Something genuinely not finished, shown with its own marker. */
    data class Unfinished(
        val text: String,
    ) : Block
}

internal data class ManualSection(
    val id: String,
    val title: String,
    /** A short name for the contents row. */
    val chip: String,
    val blocks: List<Block>,
)

internal object ManualContent {
    private fun t(text: String) = Block.Text(text)

    private fun b(vararg items: String) = Block.Bullets(items.toList())

    private fun u(text: String) = Block.Unfinished(text)

    val sections: List<ManualSection> =
        listOf(
            ManualSection(
                "start",
                "Start here",
                "Start",
                listOf(
                    t(
                        "Thalia helps you plan one day at a time on this phone. Everything works by hand. AI is optional and off until you turn it on.",
                    ),
                    b(
                        "The four tabs: Today, Plan, Life and Thalia.",
                        "Settings is the profile button at the top right of every tab.",
                        "There is no first-run tour. This manual is the guide, and you can come back to it from [[How to use Thalia]] in Settings.",
                    ),
                    t(
                        "One rule matters most: only tapping [[Approve]] applies a change that the AI proposed. Typing or saying \"yes\" never does.",
                    ),
                ),
            ),
            ManualSection(
                "loop",
                "Your day: Today, Plan, Life, Thalia",
                "Daily loop",
                listOf(
                    b(
                        "Life holds what you know: your Direction, goals, projects and actions. Fill it in once, then adjust as things change.",
                        "Plan is where you choose what to do on a day, preview a plan, and approve it. Nothing is saved until you approve.",
                        "Today is the day in progress: what is next, what is earlier, the queue, and recording what happened.",
                        "Thalia is the conversation. Ask for something in your own words; it answers or proposes a change you can approve or dismiss.",
                    ),
                    t("A typical day:"),
                    b(
                        "Open Plan, pick the actions you want, and tap [[Preview plan]]. Read the explanation, then approve.",
                        "During the day, use Today. When a block is over, record what happened with the outcome buttons.",
                        "At the end, tap [[Close the day with Thalia]] on Today, or close the day by hand with an optional note.",
                    ),
                    t("You can do all of this without AI. The guided check-in and every button work with AI off."),
                ),
            ),
            ManualSection(
                "enter",
                "Entering what you know, by hand",
                "Entering data",
                listOf(
                    t(
                        "Thalia starts empty. Nothing is invented and nothing is imported automatically. You type your own words in Life, and later you can ask Thalia to help you draft.",
                    ),
                    b(
                        "Direction: one statement of where you want your days to lead. Life, Direction, [[Save direction]]. You can change it any time, or clear it.",
                        "Goals: an outcome you want, with what success looks like. Optional. Life, Goals, [[Save goal]]. A goal can be Active, Achieved or Dropped.",
                        "Projects: a body of work you name yourself, optionally linked to a goal. Life, Projects, [[Save project]]. A project has a state such as planned or active, and four optional notes: why it matters, done when, constraints and current blocker.",
                        "Actions: one finite thing. Life, Actions, [[Add an action]]. Set a priority (Must, Important, Normal or Optional), an estimate in minutes (5 to 720), and an optional project and due date.",
                    ),
                    Block.Example(
                        "Example: a direction",
                        listOf("Keep the week calm and finish the thesis."),
                    ),
                    Block.Example(
                        "Example: a goal",
                        listOf(
                            "Title: Submit the thesis",
                            "Success looks like: The final PDF is sent to my supervisor",
                            "Horizon: 2026-12-01",
                        ),
                    ),
                    Block.Example(
                        "Example: a project and its action",
                        listOf(
                            "Project: Thesis demo (goal: Submit the thesis)",
                            "Action: Write the results section, Important, 90 min, due 2026-10-20",
                        ),
                    ),
                    t(
                        "Use the [[Inbox]] row at the top of Life for anything half-formed. It reads, for example, Inbox · 3 items, and opens when you tap it. Capture it, and decide later whether it becomes an action, a note, an idea, or is archived or discarded.",
                    ),
                    t(
                        "A due date can be typed as yyyy-mm-dd or chosen with [[Pick a date]] when you edit an action, and [[Clear date]] removes it. Nothing changes until you save. An open action past its date shows Overdue by N days, and one due today through the next 3 days shows Due today, Due tomorrow or Due in N days. Both are words, not only colour.",
                    ),
                    t(
                        "Thalia can propose a new action or a project note from the conversation. It cannot create your Direction, goals or projects for you.",
                    ),
                ),
            ),
            ManualSection(
                "plan",
                "Planning a day",
                "Planning",
                listOf(
                    t(
                        "Plan shows a day with its time window, what it can hold, and the actions you can include. Use [[Earlier day]] and [[Later day]] to change day.",
                    ),
                    b(
                        "Window: the part of the day you plan inside, for example 12:00 to 22:00. Set the default in Life, Direction, planning settings.",
                        "Capacity: the free time in the window after fixed commitments and pinned items, and the target for flexible work. The target is a share of the free time, or your focus capacity if you set a smaller one for the day.",
                        "Scheduled blocks: items with a time. These are fixed commitments you add under Plan, and actions you pin with [[Pin HH:mm]].",
                        "Queue: everything else, in order and without times. The order is Must, then Important, then Normal, then Optional, then earlier due date, then oldest. Thalia does not assign queue times in V1.",
                    ),
                    t(
                        "[[Preview plan]] builds a draft in memory. The explanation card says why the plan looks like this: the capacity, the order, what was left out and why, and, against an approved plan, what moved. It is written by the app from the draft, not by the AI.",
                    ),
                    b(
                        "Each block can be set to Included or Left out. Leaving one out changes the draft and the explanation, and saves nothing.",
                        "[[Approve first plan]] or [[Approve and replace plan]] saves the draft as an approved plan. Until then nothing is saved.",
                        "[[Keep original]] discards the draft and keeps your current plan (it reads [[Discard]] for a first plan).",
                        "[[Adjust this plan]] starts a new draft from the approved plan. It is unavailable once an outcome is recorded for that plan; recorded outcomes are kept as they are.",
                        "[[Undo this approval]] appears right after a replacing approval. It writes a new revision that restores the earlier plan, and the undone revision stays in the history. It is not offered for a first plan, after a newer approval, or once an outcome is recorded.",
                    ),
                    Block.Example(
                        "Example: a short day",
                        listOf(
                            "Window 12:00 to 14:00. Team call 12:00 to 12:30 is a fixed commitment.",
                            "Pin Email Sam at 13:00. Leave the queue: Pay invoice, then Read paper.",
                            "Preview, read why, leave Read paper out, approve.",
                        ),
                    ),
                ),
            ),
            ManualSection(
                "outcomes",
                "Today, outcomes and closing a day",
                "Today",
                listOf(
                    t(
                        "Today lists what is happening now, what is next, and earlier blocks, which stay visible under \"Earlier today\". The flexible queue is shown in order, without times.",
                    ),
                    b(
                        "A block ending never marks work done. You record what happened: Done, Partial, Skipped, Blocked, or Unknown.",
                        "Fixed commitments take no outcomes, because they are not tasks.",
                        "You can set a focus capacity for the day under the working day controls ([[Save focus]]). It can lower the day's target.",
                        "Closing a day keeps anything unrecorded as unknown and leaves open actions open. You can add a note for the next opening.",
                    ),
                    t(
                        "If you tell Thalia an outcome in the conversation, it stays a proposal until you tap [[Approve]]. Tapping an outcome button yourself on Today is your own action and needs no approval.",
                    ),
                    t(
                        "Today asks about one thing at a time. The card at the top, \"how did it go?\", is about the latest block that ended with no outcome. The older \"Did it happen?\" question under the working day waits until that card is gone, and [[Don't know]] ends it for the day. Neither records anything until you tap a result.",
                    ),
                ),
            ),
            ManualSection(
                "calls",
                "Calls: defaults versus confirming",
                "Calls",
                listOf(
                    t("A call rings only when you explicitly confirm it. Saving a default time never schedules anything."),
                    b(
                        "[[Default opening and closing times]] in Settings are suggestions for future plans and closing calls. Changing them changes no approved plan, no confirmed call and no alarm. The opening default is offered as a button next to a plan's opening call time; it is filled in only when you tap it. The closing default is the starting time in the closing call control.",
                        "An approved plan can keep its own opening time. Approving only keeps the time. To make a call, tick and confirm it on the approved plan.",
                        "The closing call: in Settings, choose today or tomorrow, set a time, and tap [[Confirm closing call]]. Use [[Move to this time]] or [[Cancel closing call]] later. A day gets one call.",
                        "Calls need Android's Alarms and reminders permission. Without it nothing is scheduled, and the screen says so. A quiet period, if you set one in the check-in tools, refuses times inside it.",
                        "Answering a call opens the conversation with the closing message ready to review and not sent. Answering never closes your day, records an outcome or applies a change.",
                    ),
                    t(
                        "With AI off, the guided check-in covers the same steps with plain screens: outcomes, one separate question about anything still unrecorded (use [[Leave as unknown]]; it writes nothing), remaining time, a note, and the next day.",
                    ),
                    u("Scheduled calls have been checked on isolated test installs only."),
                ),
            ),
            ManualSection(
                "thalia",
                "The Thalia conversation and Approve",
                "Approve",
                listOf(
                    t(
                        "Type a message and tap [[Send]], or speak a draft first (see Voice). Thalia answers in words or proposes a change shown as a card.",
                    ),
                    b(
                        "[[Approve]] applies the proposal. [[Dismiss]] drops it. Nothing happens until you tap one.",
                        "Typing or saying \"yes\" changes nothing. A phrase such as \"ok approve it\" goes to the AI as an ordinary request and cannot apply anything either.",
                        "The app checks every proposal against your real data before showing it. If it cannot be applied, it says so and nothing changes.",
                        "After an approved change, a Saved row can offer Undo for a reminder, an outcome, an Inbox filing or a focus figure.",
                        "Filing an Inbox item from the conversation is a proposal too, and waits for [[Approve]]. The direct buttons on Inbox rows are your own actions.",
                    ),
                    Block.Example(
                        "Example: a proposal",
                        listOf(
                            "You: Make Call vendor important.",
                            "Thalia shows a card: Call vendor, priority Normal to Important.",
                            "Nothing changes until you tap Approve.",
                        ),
                    ),
                ),
            ),
            ManualSection(
                "ai",
                "Setting up AI",
                "AI setup",
                listOf(
                    t("AI is a convenience, not a requirement. To use it, open Settings and tap [[Open AI settings]]."),
                    b(
                        "Read what AI means here, then tap [[I understand. Turn AI on]]. You can tap [[Turn AI off]] at any time. The consent is asked here, when you enable AI.",
                        "Save your key in the key field on that screen, typing it yourself on the phone. It is encrypted with this phone's Keystore, never shown again, never exported and not part of a planning data export. [[Clear key]] deletes it.",
                        "Never paste a key into a chat, a message to Thalia or a document.",
                        "Pressing [[Send]] shares a snapshot of your planning data with the AI service named in AI settings. The screen says what and how much.",
                        "The monthly budget is the app's own estimate of requests. It is not the provider's invoice or a hard cap, and clearing the app's data resets it. The provider's own billing page and spend limit are the real limit, and those are yours to check.",
                    ),
                    t("With AI off, or with no key, nothing is sent and everything else still works."),
                ),
            ),
            ManualSection(
                "voice",
                "Voice: speaking, spoken replies and the monthly cap",
                "Voice",
                listOf(
                    t("Voice is separate from AI text. It has its own consent, key and limit, and typing always works."),
                    b(
                        "Speak: tap [[Speak]] in the conversation. Your words appear as a draft in the message box, marked Draft, and are not sent. Check or edit it, then tap [[Send]]. This uses the phone's on-device speech recognition when it offers it for your language, and says so when it does not.",
                        "Send after speaking: [[Send after speaking]] is off by default. When it is on, a finished draft is sent for you only if you have not edited it, and an empty transcript is never sent. Switch back with [[Wait for Send]]. Sending never approves anything.",
                        "Spoken replies: separate consent ([[I understand. Allow spoken replies]]), a separate speech key ([[Save speech key]]), and mute or unmute with [[Mute replies]] and [[Unmute replies]]. You can stop a reply while it speaks.",
                        "Monthly voice limit: set a number of characters with [[Set monthly voice limit]]. It starts unset, and until you set one spoken replies are off and text is unaffected.",
                        "The meter counts the characters the app submitted for speech this calendar month, in the phone's time zone. A submission counts in full, even if the request then failed. It is app-tracked usage, not the provider's billing.",
                        "[[Clear monthly cap]] unsets the limit and turns spoken replies off again. The month's count is kept, and a limit set later carries on from it.",
                    ),
                    t(
                        "If spoken replies are off, have no key or no monthly limit, or are over the limit, you get the reply as text and a short note. Nothing else changes.",
                    ),
                    u(
                        "Live spoken replies have not yet been checked against the real speech service on a phone. There is no retry and no stored audio, so every spoken reply is a new submission.",
                    ),
                ),
            ),
            ManualSection(
                "tokens",
                "Better AI results with fewer tokens",
                "Fewer tokens",
                listOf(
                    t(
                        "Every Send is a request. Tokens are the pieces of text an AI service counts, so more text sent and returned means more use. A few habits make each request count.",
                    ),
                    b(
                        "Ask for one thing at a time, in plain words, with the day, the action or the project named. \"Make Call vendor important\" beats \"fix my priorities\".",
                        "Give the context only you know, such as a deadline or a blocker, in one message instead of several.",
                        "Do not send the same request again while one is waiting. A repeated request is a new request.",
                        "Use the buttons for simple edits. Changing a title, priority, estimate or status in Life, recording an outcome on Today, or capturing a thought in the Inbox costs nothing and needs no approval.",
                        "Keep your data tidy. The snapshot includes your open actions, so dropping finished or dead ones keeps requests smaller, and clear names give the AI better material.",
                        "Use the conversation for what is hard by hand: drafting a plan question, sorting a messy Inbox, or thinking aloud about what to do next.",
                    ),
                    Block.Example(
                        "Example: one clear message",
                        listOf(
                            "Plan tomorrow: I have 3 hours after 14:00, the thesis results section is my priority, and Email Sam can wait.",
                        ),
                    ),
                ),
            ),
            ManualSection(
                "privacy",
                "Privacy, deleting and archiving",
                "Privacy",
                listOf(
                    t(
                        "Everything Thalia keeps is what you entered or approved, on this phone. Settings, [[Privacy and your data]] shows where each kind lives.",
                    ),
                    b(
                        "Direction: edit it or [[Clear direction]], with a confirmation.",
                        "Goals: edit, set to Achieved or Dropped to archive, or [[Delete this goal]] for good. A goal that a project still uses is not deleted. The message names the projects; open each and choose another goal or none first. Nothing is unlinked for you.",
                        "Filed notes, ideas, archives and discards: Life, Inbox, filed items. Edit a note, [[Put back in Inbox]], or [[Delete permanently]]. An item that became an action cannot be deleted, because the action came from it. Reminders pointing at a deleted item keep their own text.",
                        "Closing notes: Settings, Privacy and your data lists every one, newest first. Edit or Delete. Deleting erases only the note, not the day.",
                    ),
                    t("Deleting is permanent and cannot be undone. Archiving keeps the item, and you can put it back."),
                    u(
                        "Projects, actions, fixed commitments and approved plans cannot be deleted in V1. Close, drop, archive or cancel them instead.",
                    ),
                    t(
                        "Export and import are in Settings under Data. An export is encrypted with a password you choose, and it holds no key from this phone.",
                    ),
                ),
            ),
            ManualSection(
                "later",
                "Not built yet",
                "Not built",
                listOf(
                    t("These are planned for after V1 or still open, so do not expect them yet:"),
                    b(
                        "Calendar sync, a week view, Areas, a Routines feature and an Alignment view.",
                        "Automatic replanning when a block runs late, and a Running late outcome.",
                        "A first-week observation mode.",
                        "Automatic import from ChatGPT or any other assistant, and any long-term memory that Thalia builds by itself.",
                        "A colour picker beyond the four accents in Settings. Teal is the default.",
                    ),
                ),
            ),
        )
}
