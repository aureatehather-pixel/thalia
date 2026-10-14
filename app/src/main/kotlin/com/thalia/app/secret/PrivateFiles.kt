package com.thalia.app.secret

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replace [file] with [bytes] so that a crash leaves either the old contents or the new, never
 * a mixture: the bytes go to a sibling file, are forced to the disk, and the sibling is then
 * renamed over [file] in one step.
 */
internal fun writeAtomically(
    file: File,
    bytes: ByteArray,
) {
    file.parentFile?.mkdirs()
    val staging = File(file.parentFile, "${file.name}.staging")
    FileOutputStream(staging).use { out ->
        out.write(bytes)
        out.fd.sync()
    }
    Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
