// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer.whiteboard

import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.protobuf.ByteString
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.coroutines.applicationScope
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.observability.ChangeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.util.UUID

data class WhiteboardBinding(
    val target: WhiteboardTarget,
    val draft: WhiteboardDraft?,
)

data class WhiteboardSaveError(
    val target: WhiteboardTarget,
    val message: String,
)

/**
 * A single writer serializes note/media updates across reviewer instances. Jobs outlive the
 * reviewer; the atomic local draft is the recovery journal if Android terminates the process.
 * Existing media is never overwritten or deleted, preserving other devices and collection undo.
 */
object WhiteboardPersistence {
    private val syncMutex = Mutex()
    val errors = MutableSharedFlow<WhiteboardSaveError>(extraBufferCapacity = 8)

    suspend fun bind(
        card: Card,
        store: WhiteboardDraftStore,
    ): WhiteboardBinding =
        withCol {
            val note = getNote(card.nid)
            val target =
                WhiteboardTarget(
                    note.id,
                    requireNotNull(note.guId),
                    card.ord,
                    note.noteTypeId,
                    note.notetype.fieldsNames,
                    media.dir.absolutePath,
                )
            val local = store.read(target)
            val filename = WhiteboardAttachment.find(note.fields, card.ord)
            val draft =
                when {
                    local?.dirty == true -> local
                    filename == null -> {
                        // A synced attachment was deliberately removed elsewhere.
                        if (local?.syncedFilename != null) {
                            store.restore(target, local.document.copy(strokes = emptyList()), null)
                        } else {
                            local
                        }
                    }
                    else -> {
                        require(WhiteboardAttachment.isSafeFilename(filename))
                        val file = File(media.dir, filename)
                        require(file.isFile) { "Sync media first to download this card's saved whiteboard. Your local draft is unchanged." }
                        require(file.length() <= WhiteboardDocument.MAX_BYTES)
                        val document = WhiteboardDocument.fromSvg(file.readText(Charsets.UTF_8))
                        store.restore(target, document, filename)
                    }
                }
            WhiteboardBinding(target, draft)
        }

    fun syncField(
        preferences: SharedPreferences,
        target: WhiteboardTarget,
    ): String? = preferences.getString(target.preferenceKey, null)?.takeIf { it in target.fieldNames }

    fun stopSync(
        preferences: SharedPreferences,
        target: WhiteboardTarget,
    ) {
        preferences.edit { remove(target.preferenceKey) }
    }

    /** Selecting a field explicitly authorizes replacing an existing saved version for this card. */
    suspend fun enableSync(
        preferences: SharedPreferences,
        target: WhiteboardTarget,
        field: String,
        store: WhiteboardDraftStore,
    ) {
        withCol {
            require(media.dir.absolutePath == target.mediaDirectory) { "The active collection has changed" }
            val note = getNote(target.noteId)
            require(note.guId == target.noteGuid && note.noteTypeId == target.notetypeId)
            require(field in note.notetype.fieldsNames) { "The selected field no longer exists" }
            store.acceptRemoteBase(target, WhiteboardAttachment.find(note.fields, target.ordinal))
        }
        preferences.edit(commit = true) { putString(target.preferenceKey, field) }
    }

    fun enqueue(
        target: WhiteboardTarget,
        store: WhiteboardDraftStore,
        preferences: SharedPreferences,
        initiator: Any?,
    ) {
        applicationScope.launch(Dispatchers.IO) {
            // Coalesce gestures. A dirty draft remains recoverable even if this delay is interrupted
            // by process death, and bind() queues it again when the card is opened.
            delay(600)
            try {
                syncNow(target, store, preferences, initiator)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Whiteboard sync failed; the local draft was retained")
                errors.emit(WhiteboardSaveError(target, e.localizedMessage ?: "Could not sync the drawing"))
            }
        }
    }

    suspend fun syncNow(
        target: WhiteboardTarget,
        store: WhiteboardDraftStore,
        preferences: SharedPreferences,
        initiator: Any? = null,
    ) = syncMutex.withLock {
        val fieldName = syncField(preferences, target) ?: return@withLock
        val draft = store.read(target)?.takeIf { it.dirty } ?: return@withLock
        val bytes = draft.document.toSvg().toByteArray(Charsets.UTF_8)
        require(bytes.size <= WhiteboardDocument.MAX_BYTES) { "Whiteboard is too large to sync" }
        withCol {
            require(media.dir.absolutePath == target.mediaDirectory) { "The active collection has changed; the local drawing is safe" }
            val note = getNote(target.noteId)
            require(note.guId == target.noteGuid && note.noteTypeId == target.notetypeId) { "The note has changed; the local drawing is safe" }
            val fieldIndex = note.notetype.fieldsNames.indexOf(fieldName)
            require(fieldIndex >= 0) { "The selected field no longer exists; choose a new sync field" }
            val remoteFilename = WhiteboardAttachment.find(note.fields, target.ordinal)
            require(remoteFilename == draft.syncedFilename) {
                "This drawing changed on another device. Your local draft is safe. Choose Save and sync to replace the other version."
            }
            val filename =
                if (draft.document.strokes.isEmpty()) {
                    null
                } else {
                    media.writeData("anki_whiteboard_" + UUID.randomUUID() + ".svg", ByteString.copyFrom(bytes))
                }
            val updated = WhiteboardAttachment.replace(note.fields, target.ordinal, fieldIndex, filename)
            if (updated != note.fields) {
                note.fields = updated.toMutableList()
                // Drawing Undo/Redo is handled by the whiteboard, without displacing review Undo.
                val changes = updateNote(note, skipUndoEntry = true)
                ChangeManager.publish(changes, initiator)
            }
            store.acknowledge(target, draft.revision, filename)
        }
    }
}
