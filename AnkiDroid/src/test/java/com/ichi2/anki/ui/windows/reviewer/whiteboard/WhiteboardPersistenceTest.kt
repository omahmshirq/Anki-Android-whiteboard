// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer.whiteboard

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class WhiteboardPersistenceTest : RobolectricTest() {
    override fun getCollectionStorageMode() = CollectionStorageMode.IN_MEMORY_WITH_MEDIA

    private fun drawing(color: Int = Color.RED) =
        WhiteboardDocument(100, 100, Color.WHITE, listOf(WhiteboardDocument.Stroke(color, 4f, false, listOf(listOf(10f, 10f, 90f, 90f)))))

    @Test
    fun createsReferencedMediaAndRestoresOnFreshStore() = runTest {
        val note = addBasicNote()
        val card = note.cards(col).single()
        val store = WhiteboardDraftStore(tempFolder.newFolder())
        val target = WhiteboardPersistence.bind(card, store).target
        store.save(target, drawing())
        WhiteboardPersistence.enableSync(getPreferences(), target, "Back", store)
        WhiteboardPersistence.syncNow(target, store, getPreferences())
        val updated = col.getNote(note.id)
        assertEquals("Front", updated.fields[0])
        assertTrue(updated.fields[1].startsWith("Back"))
        val filename = assertNotNull(WhiteboardAttachment.find(updated.fields, 0))
        assertTrue(filename in col.media.filesInStr(col.getCard(card.id)))
        assertTrue(File(col.media.dir, filename).isFile)
        assertFalse(requireNotNull(store.read(target)).dirty)

        val otherDevice = WhiteboardDraftStore(tempFolder.newFolder())
        val restored = WhiteboardPersistence.bind(col.getCard(card.id), otherDevice)
        assertEquals(drawing(), restored.draft?.document)
    }

    @Test
    fun siblingCardsRemainSeparateWhenOneIsCleared() = runTest {
        val note = addBasicAndReversedNote()
        val cards = note.cards(col).sortedBy { it.ord }
        val store = WhiteboardDraftStore(tempFolder.newFolder())
        val first = WhiteboardPersistence.bind(cards[0], store).target
        val second = WhiteboardPersistence.bind(cards[1], store).target
        store.save(first, drawing(Color.RED))
        WhiteboardPersistence.enableSync(getPreferences(), first, "Back", store)
        WhiteboardPersistence.syncNow(first, store, getPreferences())
        store.save(second, drawing(Color.BLUE))
        WhiteboardPersistence.syncNow(second, store, getPreferences())
        val fields = col.getNote(note.id).fields
        assertNotNull(WhiteboardAttachment.find(fields, first.ordinal))
        val secondFile = assertNotNull(WhiteboardAttachment.find(fields, second.ordinal))

        store.save(first, drawing().copy(strokes = emptyList()))
        WhiteboardPersistence.syncNow(first, store, getPreferences())
        val cleared = col.getNote(note.id).fields
        assertNull(WhiteboardAttachment.find(cleared, first.ordinal))
        assertEquals(secondFile, WhiteboardAttachment.find(cleared, second.ordinal))
        assertTrue(File(col.media.dir, secondFile).exists())
    }

    @Test
    fun concurrentRemoteChangeDoesNotOverwriteEitherDrawing() = runTest {
        val note = addBasicNote()
        val card = note.cards(col).single()
        val store = WhiteboardDraftStore(tempFolder.newFolder())
        val target = WhiteboardPersistence.bind(card, store).target
        store.save(target, drawing())
        WhiteboardPersistence.enableSync(getPreferences(), target, "Back", store)
        WhiteboardPersistence.syncNow(target, store, getPreferences())
        store.save(target, drawing(Color.BLUE))

        val remote = col.getNote(note.id)
        remote.fields = WhiteboardAttachment.replace(remote.fields, target.ordinal, 1, "anki_whiteboard_abc.svg").toMutableList()
        col.updateNote(remote)
        assertFailsWith<IllegalArgumentException> {
            WhiteboardPersistence.syncNow(target, store, getPreferences())
        }
        assertEquals("anki_whiteboard_abc.svg", WhiteboardAttachment.find(col.getNote(note.id).fields, target.ordinal))
        assertTrue(requireNotNull(store.read(target)).dirty)
        assertEquals(drawing(Color.BLUE), store.read(target)?.document)
    }

    @Test
    fun stoppingSyncKeepsLocalDraftWithoutChangingNote() = runTest {
        val note = addBasicNote()
        val store = WhiteboardDraftStore(tempFolder.newFolder())
        val target = WhiteboardPersistence.bind(note.cards(col).single(), store).target
        store.save(target, drawing())
        WhiteboardPersistence.enableSync(getPreferences(), target, "Back", store)
        WhiteboardPersistence.stopSync(getPreferences(), target)
        WhiteboardPersistence.syncNow(target, store, getPreferences())
        assertNull(WhiteboardAttachment.find(col.getNote(note.id).fields, target.ordinal))
        assertTrue(requireNotNull(store.read(target)).dirty)
    }
}
