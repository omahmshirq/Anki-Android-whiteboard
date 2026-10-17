// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer.whiteboard

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class WhiteboardDraftStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val target = WhiteboardTarget(1, "guid", 0, 1, listOf("Front", "Back"), "/collection.media")
    private val document =
        WhiteboardDocument(
            100,
            100,
            Color.WHITE,
            listOf(WhiteboardDocument.Stroke(Color.BLACK, 2f, false, listOf(listOf(0f, 0f, 20f, 20f)))),
        )

    @Test
    fun survivesReopeningWithoutMixingCardsOrCollections() {
        val directory = folder.newFolder()
        WhiteboardDraftStore(directory).save(target, document)
        val reopened = WhiteboardDraftStore(directory)
        assertEquals(document, reopened.read(target)?.document)
        assertNull(reopened.read(target.copy(ordinal = 1)))
        assertNull(reopened.read(target.copy(noteGuid = "other")))
        assertNull(reopened.read(target.copy(mediaDirectory = "/other.media")))
    }

    @Test
    fun acknowledgingOlderRevisionKeepsNewerEditDirty() {
        val store = WhiteboardDraftStore(folder.newFolder())
        val first = store.save(target, document)
        val updated = document.copy(strokes = emptyList())
        store.save(target, updated)
        store.acknowledge(target, first.revision, "anki_whiteboard_abc.svg")
        val latest = requireNotNull(store.read(target))
        assertEquals(updated, latest.document)
        assertTrue(latest.dirty)
        assertEquals("anki_whiteboard_abc.svg", latest.syncedFilename)
        store.acknowledge(target, latest.revision, null)
        assertFalse(requireNotNull(store.read(target)).dirty)
    }

    @Test
    fun clearingIsDurable() {
        val directory = folder.newFolder()
        val store = WhiteboardDraftStore(directory)
        store.restore(target, document, "anki_whiteboard_abc.svg")
        store.save(target, document.copy(strokes = emptyList()))
        val cleared = requireNotNull(WhiteboardDraftStore(directory).read(target))
        assertTrue(cleared.dirty)
        assertTrue(cleared.document.strokes.isEmpty())
        assertEquals("anki_whiteboard_abc.svg", cleared.syncedFilename)
    }
}
