package com.thalia.app.chatgptcheck

import com.thalia.chatgptplan.ProofRecord
import java.io.File

/** Evidence lines in this app's private files: `adb run-as` can read them. Every line goes through the credential redactor first. */
internal class EvidenceLog(
    private val file: File,
) {
    val path: String get() = file.path

    @Synchronized
    fun append(record: ProofRecord) {
        file.parentFile?.mkdirs()
        file.appendText(record.line() + "\n")
    }

    @Synchronized
    fun tail(count: Int): List<String> = if (file.exists()) file.readLines().takeLast(count) else emptyList()
}
