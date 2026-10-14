package com.thalia.app.conversationcheck

import com.thalia.app.conversation.ConversationPort
import com.thalia.app.planning.data.CreateActionResult
import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.OperationParser
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.RawCall
import com.thalia.planning.Action

/**
 * Stands in for a model on the device check. It sends nothing anywhere: each message is a command that becomes the
 * function calls a model could return, so every call still goes through the production parser, validator and session.
 */
internal class ScriptedProvider(
    private val faults: FaultSwitch,
) : ConversationProvider {
    override suspend fun respond(request: ProviderRequest): ProviderResponse {
        val words =
            request.turns
                .last()
                .text
                .trim()
                .split(Regex("\\s+"))
        val rest = words.drop(1)

        fun call(
            name: String,
            vararg args: Pair<String, Any>,
        ) = ProviderResponse.Reply(
            null,
            listOf(
                RawCall(
                    name,
                    args.joinToString(",", "{", "}") { (key, value) ->
                        val json =
                            if (value is List<*>) {
                                value.joinToString(
                                    ",",
                                    "[",
                                    "]",
                                ) { quote(it as String) }
                            } else {
                                quote(value.toString())
                            }
                        "${quote(key)}:$json"
                    },
                ),
            ),
        )
        return when (words.first().lowercase()) {
            "/ctx" -> ProviderResponse.Reply(request.contextText, emptyList())
            "/outcome" -> call(OperationParser.RECORD_OUTCOME, "action_ref" to rest[0], "result" to rest[1])
            "/new" -> call(OperationParser.PROPOSE_NEW_ACTION, "title" to rest.joinToString(" "), "reason" to "Scripted check.")
            "/change" -> call(OperationParser.PROPOSE_ACTION_CHANGE, "action_ref" to rest[0], "title" to rest.drop(1).joinToString(" "))
            "/day" -> call(OperationParser.PROPOSE_DAY_SELECTION, "action_refs" to rest)
            // The next active day. The model names only "next"; Kotlin works out the date.
            "/next" -> call(OperationParser.PROPOSE_DAY_SELECTION, "action_refs" to rest, "day" to "next")
            // Any other day value, to see it refused: /dayon 2026-10-14 a1
            "/dayon" -> call(OperationParser.PROPOSE_DAY_SELECTION, "action_refs" to rest.drop(1), "day" to rest[0])
            "/inbox" -> call(OperationParser.RESOLVE_INBOX_ITEM, "item_ref" to rest[0], "outcome" to rest[1])
            "/inboxprop" ->
                call(
                    OperationParser.PROPOSE_INBOX_RESOLUTION,
                    "item_ref" to rest[0],
                    "outcome" to rest[1],
                    "body" to rest.drop(2).joinToString(" "),
                )
            "/newfrom" -> call(OperationParser.PROPOSE_NEW_ACTION, "inbox_ref" to rest[0], "title" to rest.drop(1).joinToString(" "))
            "/bogus" -> call("delete_everything", "action_ref" to "a1")
            "/focus" ->
                ProviderResponse.Reply(
                    null,
                    listOf(RawCall(OperationParser.RECORD_FOCUS_CAPACITY, "{\"minutes\":${rest[0].toInt()}}")),
                )
            "/focusguess" ->
                ProviderResponse.Reply(
                    null,
                    listOf(RawCall(OperationParser.PROPOSE_FOCUS_CAPACITY, "{\"minutes\":${rest[0].toInt()}}")),
                )
            "/context" ->
                call(
                    OperationParser.PROPOSE_PROJECT_CONTEXT,
                    "project_ref" to rest[0],
                    "field" to rest[1],
                    "text" to rest.drop(2).joinToString(" "),
                )
            "/fixed" ->
                call(
                    OperationParser.PROPOSE_FIXED_COMMITMENT,
                    "start" to rest[0],
                    "end" to rest[1],
                    "title" to rest.drop(2).joinToString(" "),
                )
            "/fail" -> ProviderResponse.Failed(ProviderFailure.valueOf(rest.firstOrNull()?.uppercase() ?: "NETWORK"))
            "/throw" -> error("scripted provider failure")
            "/fault" -> {
                faults.armed = rest.firstOrNull()
                ProviderResponse.Reply("Fault armed for the next action creation: ${faults.armed}.", emptyList())
            }
            else ->
                ProviderResponse.Reply(
                    "Scripted provider. Commands: /ctx /outcome /new /change /day /next /dayon /bogus /fail /throw /fault /focus /focusguess /context /fixed /inbox /inboxprop /newfrom",
                    emptyList(),
                )
        }
    }
}

private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** One-shot faults for the next action creation, to see what an interrupted save looks like on the device. */
internal class FaultSwitch {
    /** "commit": the write commits, then throws. "blind": the same, and the read back that follows throws too. */
    @Volatile var armed: String? = null
}

/** The production port with [FaultSwitch] applied to action creation only. */
internal class FaultyPort(
    private val real: ConversationPort,
    private val faults: FaultSwitch,
) : ConversationPort by real {
    private var blindRead = false

    override suspend fun createAction(action: Action): CreateActionResult {
        val fault = faults.armed.also { faults.armed = null }
        val result = real.createAction(action)
        if (fault == "commit" || fault == "blind") {
            blindRead = fault == "blind"
            throw IllegalStateException("scripted: interrupted after commit ($result)")
        }
        return result
    }

    override suspend fun convertInbox(
        itemId: String,
        action: Action,
    ): com.thalia.app.planning.data.ConvertInboxResult {
        val fault = faults.armed.also { faults.armed = null }
        val result = real.convertInbox(itemId, action)
        if (fault == "commit" || fault == "blind") {
            blindRead = fault == "blind"
            throw IllegalStateException("scripted: interrupted after commit ($result)")
        }
        return result
    }

    override suspend fun inboxItem(id: String): com.thalia.planning.InboxItem? {
        if (blindRead) {
            blindRead = false
            throw IllegalStateException("scripted: read back failed")
        }
        return real.inboxItem(id)
    }

    override suspend fun action(id: String): Action? {
        if (blindRead) {
            blindRead = false
            throw IllegalStateException("scripted: read back failed")
        }
        return real.action(id)
    }
}
