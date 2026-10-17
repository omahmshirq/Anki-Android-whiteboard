// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer.whiteboard

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class WhiteboardTarget(
    val noteId: Long,
    val noteGuid: String,
    val ordinal: Int,
    val notetypeId: Long,
    val fieldNames: List<String>,
    val mediaDirectory: String,
) {
    val key: String get() = digest("$mediaDirectory|$noteGuid|$ordinal")
    val preferenceKey: String get() = "whiteboard_sync_field_" + digest("$mediaDirectory|$notetypeId")
}

data class WhiteboardDraft(
    val document: WhiteboardDocument,
    val revision: Long,
    val syncedFilename: String?,
    val dirty: Boolean,
)

/**
 * Atomic, collection-scoped local drafts survive reviewer destruction and process death.
 * Completed gestures are committed before returning to the event loop, so switching card cannot
 * cancel a save. Storage failures are propagated to the UI instead of clearing the canvas.
 */
class WhiteboardDraftStore(
    private val directory: File,
) {
    fun read(target: WhiteboardTarget): WhiteboardDraft? =
        synchronized(diskLock) {
            val file = file(target)
            val backup = File(file.baseFile.path + ".bak")
            if (!file.baseFile.exists() && !backup.exists()) return@synchronized null
            require(file.baseFile.length() <= WhiteboardDocument.MAX_BYTES && backup.length() <= WhiteboardDocument.MAX_BYTES)
            val bytes = file.openRead().use { it.readBytes() }
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            WhiteboardDraft(
                WhiteboardDocument.fromJson(json.getJSONObject("document")),
                json.getLong("revision"),
                json.optString("syncedFilename").takeIf { it.isNotEmpty() },
                json.getBoolean("dirty"),
            )
        }

    fun save(
        target: WhiteboardTarget,
        document: WhiteboardDocument,
    ): WhiteboardDraft =
        synchronized(diskLock) {
            val previous = read(target)
            WhiteboardDraft(document, (previous?.revision ?: 0) + 1, previous?.syncedFilename, true)
                .also { write(target, it) }
        }

    fun restore(
        target: WhiteboardTarget,
        document: WhiteboardDocument,
        filename: String?,
    ): WhiteboardDraft =
        synchronized(diskLock) {
            WhiteboardDraft(document, (read(target)?.revision ?: 0) + 1, filename, false)
                .also { write(target, it) }
        }

    fun acknowledge(
        target: WhiteboardTarget,
        revision: Long,
        filename: String?,
    ) = synchronized(diskLock) {
        val current = read(target) ?: return@synchronized
        write(target, current.copy(syncedFilename = filename, dirty = current.revision != revision))
    }

    /** A deliberate Save and sync action authorizes replacing the current remote version. */
    fun acceptRemoteBase(
        target: WhiteboardTarget,
        filename: String?,
    ) = synchronized(diskLock) {
        val current = read(target) ?: return@synchronized
        write(target, current.copy(syncedFilename = filename, dirty = true))
    }

    private fun file(target: WhiteboardTarget): AtomicFile {
        check(directory.isDirectory || directory.mkdirs()) { "Could not create whiteboard draft directory" }
        return AtomicFile(File(directory, target.key + ".json"))
    }

    private fun write(
        target: WhiteboardTarget,
        draft: WhiteboardDraft,
    ) {
        val bytes =
            JSONObject()
                .put("document", draft.document.toJson())
                .put("revision", draft.revision)
                .put("syncedFilename", draft.syncedFilename ?: "")
                .put("dirty", draft.dirty)
                .toString()
                .toByteArray(Charsets.UTF_8)
        require(bytes.size <= WhiteboardDocument.MAX_BYTES) { "Whiteboard is too large to save" }
        val file = file(target)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    companion object {
        // Old and newly created reviewers may briefly have different store instances.
        private val diskLock = Any()
    }
}

private fun digest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
