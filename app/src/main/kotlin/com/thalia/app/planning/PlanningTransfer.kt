package com.thalia.app.planning

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.planning.ImportProblem
import com.thalia.planning.ImportRefusedException
import com.thalia.planning.PlanningArchive
import com.thalia.planning.PlanningCounts
import com.thalia.planning.PlanningExportContent
import com.thalia.planning.PlanningExportFormat
import com.thalia.planning.PlanningExportInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The encrypted export and import of planning data, without the screen. ADR 0009 decision 8.
 *
 * Reading the rows runs on [PlanningAccess]'s I/O dispatcher; deriving the key, encrypting,
 * decrypting and parsing run on [crypto]. Neither is ever the main thread. Nothing is written by
 * [open]: it only reads and validates, and the caller then restores or replaces through
 * [PlanningAccess].
 */
internal class PlanningTransfer(
    private val access: PlanningAccess,
    private val appVersion: String,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Instant = Instant::now,
    private val crypto: CoroutineDispatcher = Dispatchers.Default,
) {
    class Exported(
        val archive: ByteArray,
        val counts: PlanningCounts,
    )

    /** Thrown when the archive would be larger than an import accepts, so it could never be restored. */
    class ExportTooLargeException : Exception("the export would exceed ${PlanningArchive.MAX_ARCHIVE_BYTES} bytes")

    /** One consistent snapshot of every row, encrypted under [password]. */
    suspend fun export(password: CharArray): Exported {
        val snapshot = access.snapshot() ?: throw IOException("planning data is not open")
        return withContext(crypto) {
            val info = PlanningExportInfo(PlanningDatabase.SCHEMA_VERSION, appVersion, clock(), zone())
            val payload = PlanningExportFormat.encode(PlanningExportContent(info, snapshot))
            if (payload.size > PlanningExportFormat.MAX_PAYLOAD_BYTES) throw ExportTooLargeException()
            // An export this Thalia could not import back is refused now, not discovered when it is needed.
            PlanningExportFormat.decode(payload, PlanningDatabase.SCHEMA_VERSION)
            val archive = PlanningArchive.seal(payload, password)
            if (archive.size > PlanningArchive.MAX_ARCHIVE_BYTES) throw ExportTooLargeException()
            Exported(archive, snapshot.counts)
        }
    }

    /** The validated content of [archive], or an [ImportRefusedException] saying why not. Writes nothing. */
    suspend fun open(
        archive: ByteArray,
        password: CharArray,
    ): PlanningExportContent =
        withContext(crypto) {
            PlanningExportFormat.decode(PlanningArchive.open(archive, password), PlanningDatabase.SCHEMA_VERSION)
        }

    /** `thalia-planning-YYYYMMDD-HHMM.thaliaexport`, in local time. */
    fun suggestedFileName(): String =
        "thalia-planning-${clock().atZone(zone()).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.${PlanningArchive.FILE_EXTENSION}"
}

/**
 * The document the user picked, read and written through the Storage Access Framework. No storage
 * permission is needed. Blocking: callers run these on an I/O dispatcher.
 */
internal object PlanningDocuments {
    /** The document's bytes, refusing a document over the archive limit unread when its size is known. */
    fun read(
        resolver: ContentResolver,
        uri: Uri,
    ): ByteArray {
        val size =
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        if (size != null && size > PlanningArchive.MAX_ARCHIVE_BYTES) {
            throw ImportRefusedException(ImportProblem.TOO_LARGE, "document of $size bytes")
        }
        val input = resolver.openInputStream(uri) ?: throw IOException("the document could not be opened")
        return input.use(PlanningArchive::readBounded)
    }

    /** Writes [bytes] over the document, syncs it where the provider allows, and reads it back to check. */
    fun write(
        resolver: ContentResolver,
        uri: Uri,
        bytes: ByteArray,
    ) {
        // "wt" truncates; a provider that refuses it gets "w", enough for a document the provider reported empty.
        val output =
            runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(uri, "w")
                ?: throw IOException("the document could not be opened for writing")
        output.use {
            it.write(bytes)
            it.flush()
            (it as? FileOutputStream)?.fd?.sync()
        }
        val written = resolver.openInputStream(uri)?.use(PlanningArchive::readBounded)
        if (written == null || !written.contentEquals(bytes)) throw IOException("the written document does not read back the same")
    }

    /**
     * The document's size as the provider reports it, or null if it does not say. Used only as a
     * best-effort safeguard against overwriting: the provider, or another app, could still change the
     * document after this is read.
     */
    fun size(
        resolver: ContentResolver,
        uri: Uri,
    ): Long? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()
}

/** What became of an export into a picked document. Nothing here ever deletes a document. */
internal sealed interface ExportOutcome {
    data class Exported(
        val counts: PlanningCounts,
    ) : ExportOutcome

    /** The document was not empty, or its size was unknown, so nothing was written to it. */
    data object NotEmpty : ExportOutcome

    /** The export failed. The document is left where it is, and may hold an incomplete export. */
    data class Failed(
        val error: Exception,
    ) : ExportOutcome
}

/**
 * Exports into the document at [uri], which the user picked with `ACTION_CREATE_DOCUMENT`.
 *
 * The picker usually returns a new, empty document, but it can also return an existing one the user
 * chose to overwrite, and Thalia cannot prove which. So, as a best-effort safeguard, it writes only
 * into a document the provider reports as empty; and it never deletes a document, even after a
 * failure, because an empty or recently changed document is no proof that this export made it.
 */
internal suspend fun PlanningTransfer.exportToDocument(
    resolver: ContentResolver,
    uri: Uri,
    password: CharArray,
    io: CoroutineDispatcher = Dispatchers.IO,
): ExportOutcome {
    val size = withContext(io) { PlanningDocuments.size(resolver, uri) }
    if (size == null || size > 0) return ExportOutcome.NotEmpty
    return try {
        val exported = export(password)
        withContext(io) { PlanningDocuments.write(resolver, uri, exported.archive) }
        ExportOutcome.Exported(exported.counts)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ExportOutcome.Failed(e)
    }
}
