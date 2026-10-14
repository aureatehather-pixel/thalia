package com.thalia.app.ai

import com.thalia.app.secret.writeAtomically
import java.io.File

/**
 * Two switches for the everyday conversation, kept in a small private file next to the AI files: whether Thalia reads its
 * replies aloud, and whether a finished spoken message is sent at once. Reading aloud starts on (it still needs a monthly voice limit); sending
 * after speaking starts OFF. Only the toggle ever writes `autoSend`, so a line for it in the file is the person's own choice;
 * an app that has no such line was never given one and gets the OFF default, even though an earlier build defaulted to on. Neither is a secret and neither approves
 * anything: a proposal is applied only by the Approve tap, however the message got there. An unreadable file means the defaults.
 */
internal class ConversationPrefs(
    private val file: File,
) {
    private val lock = Any()

    val speakReplies: Boolean get() = get("speak", true)
    val sendAfterSpeaking: Boolean get() = get("autoSend", false)

    /** True or false when the person has toggled it, null when it was never set. */
    val sendAfterSpeakingChoice: Boolean? get() = synchronized(lock) { read()["autoSend"] }

    fun setSpeakReplies(on: Boolean) = set("speak", on)

    fun setSendAfterSpeaking(on: Boolean) = set("autoSend", on)

    private fun read(): Map<String, Boolean> =
        try {
            if (!file.isFile) {
                emptyMap()
            } else {
                file
                    .readLines()
                    .mapNotNull { l ->
                        l.split('=', limit = 2).takeIf { it.size == 2 }
                    }.associate { it[0] to (it[1] == "true") }
            }
        } catch (e: java.io.IOException) {
            emptyMap()
        }

    private fun get(
        name: String,
        default: Boolean,
    ): Boolean = synchronized(lock) { read()[name] ?: default }

    private fun set(
        name: String,
        value: Boolean,
    ) = synchronized(lock) {
        val now = read().toMutableMap().apply { this[name] = value }
        writeAtomically(file, now.entries.joinToString("") { "${it.key}=${it.value}\n" }.toByteArray())
    }
}
