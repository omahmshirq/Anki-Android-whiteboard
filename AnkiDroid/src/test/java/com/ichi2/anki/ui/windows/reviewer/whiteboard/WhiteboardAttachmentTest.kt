// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer.whiteboard

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhiteboardAttachmentTest {
    @Test
    fun preservesExistingContent() {
        val fields = listOf("<b>Question</b>", """Answer<img src="original.png">""")
        val saved = WhiteboardAttachment.replace(fields, 0, 1, "anki_whiteboard_abc.svg")
        assertEquals(fields[0], saved[0])
        assertTrue(saved[1].startsWith(fields[1]))
        assertEquals("anki_whiteboard_abc.svg", WhiteboardAttachment.find(saved, 0))
    }

    @Test
    fun movingFieldPreservesSiblingDrawing() {
        val first = WhiteboardAttachment.replace(listOf("Question", "Answer"), 0, 1, "anki_whiteboard_abc.svg")
        val sibling = WhiteboardAttachment.replace(first, 1, 1, "anki_whiteboard_def.svg")
        val moved = WhiteboardAttachment.replace(sibling, 0, 0, "anki_whiteboard_123.svg")
        assertEquals("anki_whiteboard_123.svg", WhiteboardAttachment.find(moved, 0))
        assertEquals("anki_whiteboard_def.svg", WhiteboardAttachment.find(moved, 1))
        assertTrue(moved[0].startsWith("Question"))
        assertTrue(moved[1].startsWith("Answer"))
        assertTrue("anki_whiteboard_abc.svg" !in moved.joinToString())
    }

    @Test
    fun clearingRemovesOnlyGeneratedBlock() {
        val original = listOf("Question", "Answer")
        val saved = WhiteboardAttachment.replace(original, 0, 1, "anki_whiteboard_abc.svg")
        val cleared = WhiteboardAttachment.replace(saved, 0, 1, null)
        assertEquals(original, cleared)
        assertNull(WhiteboardAttachment.find(cleared, 0))
    }

    @Test
    fun imageAttributeChangesPreserveAssociation() {
        val saved = WhiteboardAttachment.replace(listOf("Question", "Answer"), 0, 1, "anki_whiteboard_abc.svg")
        val edited = saved.map { it.replace("<img src=", "<img class='example' src = ") }
        assertEquals("anki_whiteboard_abc.svg", WhiteboardAttachment.find(edited, 0))
    }

    @Test
    fun rejectsUnsafeNamesAndDuplicateBlocks() {
        assertFailsWith<IllegalArgumentException> {
            WhiteboardAttachment.replace(listOf("Question", "Answer"), 0, 1, "../other.svg")
        }
        val saved = WhiteboardAttachment.replace(listOf("Question", "Answer"), 0, 1, "anki_whiteboard_abc.svg")
        assertFailsWith<IllegalArgumentException> {
            WhiteboardAttachment.find(listOf(saved[1], saved[1]), 0)
        }
    }
}
