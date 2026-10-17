// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer.whiteboard

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class WhiteboardUndoTest : RobolectricTest() {
    override fun getCollectionStorageMode() = CollectionStorageMode.ON_DISK

    private fun drawing(color: Int): WhiteboardDocument =
        WhiteboardDocument(
            100,
            100,
            Color.WHITE,
            listOf(
                WhiteboardDocument.Stroke(
                    color,
                    4f,
                    false,
                    listOf(listOf(10f, 10f, 90f, 90f)),
                ),
            ),
        )

    @Test
    fun repeatedSavesKeepTheEarlierUndoHistory() =
        runTest {
            val note = addBasicNote()
            val card = note.cards(col).single()
            val store = WhiteboardDraftStore(tempFolder.newFolder())
            val target = WhiteboardPersistence.bind(card, store).target

            val original = col.addCustomUndoEntry("Original edit")
            note.setField(0, "Updated question")
            col.updateNote(note)
            col.mergeUndoEntries(original)

            store.save(target, drawing(Color.RED))
            WhiteboardPersistence.enableSync(getPreferences(), target, "Back", store)
            WhiteboardPersistence.syncNow(target, store, getPreferences())
            store.save(target, drawing(Color.BLUE))
            WhiteboardPersistence.syncNow(target, store, getPreferences())

            assertEquals("Save whiteboard", col.undoStatus().undo)
            col.undo()
            assertNull(WhiteboardAttachment.find(col.getNote(note.id).fields, target.ordinal))
            assertEquals("Updated question", col.getNote(note.id).fields[0])
            assertEquals("Original edit", col.undoStatus().undo)
            col.undo()
            assertEquals("Front", col.getNote(note.id).fields[0])
        }

    @Test
    fun anInterveningOperationIsNeverMergedIntoAnAutosave() =
        runTest {
            val note = addBasicNote()
            val store = WhiteboardDraftStore(tempFolder.newFolder())
            val target = WhiteboardPersistence.bind(note.cards(col).single(), store).target
            store.save(target, drawing(Color.RED))
            WhiteboardPersistence.enableSync(getPreferences(), target, "Back", store)
            WhiteboardPersistence.syncNow(target, store, getPreferences())
            val firstImage = assertNotNull(WhiteboardAttachment.find(col.getNote(note.id).fields, target.ordinal))

            val separate = col.addCustomUndoEntry("Separate edit")
            val edited = col.getNote(note.id)
            edited.setField(0, "Updated question")
            col.updateNote(edited)
            col.mergeUndoEntries(separate)

            store.save(target, drawing(Color.BLUE))
            WhiteboardPersistence.syncNow(target, store, getPreferences())
            col.undo()
            assertEquals(firstImage, WhiteboardAttachment.find(col.getNote(note.id).fields, target.ordinal))
            assertEquals("Updated question", col.getNote(note.id).fields[0])
            assertEquals("Separate edit", col.undoStatus().undo)
        }
}
