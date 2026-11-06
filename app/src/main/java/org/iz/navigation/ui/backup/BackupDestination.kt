package org.iz.navigation.ui.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iz.navigation.data.DiaryBackup
import org.iz.navigation.data.PreparedDiaryBackup

internal class DiaryBackupExportOperations(context: Context, private val backup: DiaryBackup) : BackupExportOperations<Uri> {
    private val app = context.applicationContext

    override suspend fun prepareExport(includeHealth: Boolean) = backup.prepareExport(includeHealth)

    override suspend fun inspectDestination(target: Uri, title: String): (suspend () -> Boolean)? {
        val destination = BackupDestination.inspect(app, target, title) ?: return null
        return { destination.remove(app) }
    }

    override suspend fun savePrepared(prepared: PreparedDiaryBackup, target: Uri) = backup.savePrepared(prepared, target)

    override suspend fun discardPrepared(prepared: PreparedDiaryBackup) = backup.discardPrepared(prepared)

    override suspend fun cleanupAbandonedExports() = backup.cleanupAbandonedExports()
}

/** Proof captured before any write. A CreateDocument callback alone can be an overwrite selection. */
private class BackupDestination private constructor(
    private val uri: Uri,
    private val documentId: String,
    private val title: String,
) {
    suspend fun remove(context: Context): Boolean = withContext(Dispatchers.IO) {
        val document = readDocument(context, uri) ?: return@withContext false
        if (document.id != documentId || document.title != title ||
            (document.flags and Document.FLAG_SUPPORTS_DELETE) == 0 || !hasGrant(context, uri, WRITE_GRANT)
        ) return@withContext false
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    }

    companion object {
        suspend fun inspect(context: Context, uri: Uri, title: String): BackupDestination? =
            withContext(Dispatchers.IO) {
                if (!hasGrant(context, uri, READ_WRITE_GRANT)) return@withContext null
                val document = readDocument(context, uri) ?: return@withContext null
                if (document.title != title || document.bytes != 0L ||
                    (document.flags and Document.FLAG_SUPPORTS_WRITE) == 0
                ) return@withContext null
                BackupDestination(uri, document.id, title)
            }
    }
}

private const val WRITE_GRANT = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
private val READ_WRITE_GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or WRITE_GRANT

private fun hasGrant(context: Context, uri: Uri, flags: Int): Boolean =
    context.checkUriPermission(uri, Process.myPid(), Process.myUid(), flags) == PackageManager.PERMISSION_GRANTED

private data class DocumentInfo(val id: String, val title: String, val bytes: Long?, val flags: Int)

private fun readDocument(context: Context, uri: Uri): DocumentInfo? {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank() ||
        DocumentsContract.isTreeUri(uri) || !DocumentsContract.isDocumentUri(context, uri)
    ) return null
    val documentId = DocumentsContract.getDocumentId(uri)
    val columns = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS,
    )
    return context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
        val id = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
        val title = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
        val size = cursor.getColumnIndex(Document.COLUMN_SIZE)
        val mime = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
        val flags = cursor.getColumnIndex(Document.COLUMN_FLAGS)
        if (minOf(id, title, mime, flags) < 0 || !cursor.moveToFirst() ||
            cursor.getString(id) != documentId || cursor.isNull(title) || cursor.isNull(mime) ||
            cursor.getString(mime) == Document.MIME_TYPE_DIR || cursor.isNull(flags)
        ) return@use null
        val document = DocumentInfo(
            documentId, cursor.getString(title),
            if (size < 0 || cursor.isNull(size)) null else cursor.getLong(size),
            cursor.getInt(flags),
        )
        document.takeUnless { cursor.moveToNext() }
    }
}
