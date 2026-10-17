// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer.whiteboard

import android.graphics.Color
import android.graphics.Path
import android.graphics.PathMeasure
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WhiteboardDocumentTest {
    @Test
    fun svgRoundTripPreservesEditableStrokes() {
        val path = Path().apply {
            moveTo(10f, 20f)
            quadTo(20f, 80f, 90f, 90f)
            moveTo(30f, 30f)
            lineTo(50f, 50f)
        }
        val erase = Path().apply {
            moveTo(20f, 20f)
            lineTo(50f, 50f)
        }
        val document =
            WhiteboardDocument.capture(
                listOf(DrawingAction(path, Color.RED, 4f), DrawingAction(erase, Color.BLACK, 12f, true)),
                200,
                300,
                Color.WHITE,
            )
        val restored = WhiteboardDocument.fromSvg(document.toSvg())
        assertEquals(document, restored)
        val actions = restored.actions(200, 300)
        assertEquals(Color.RED, actions[0].color)
        assertEquals(4f, actions[0].strokeWidth)
        assertTrue(actions[1].isEraser)
        assertEquals(2, restored.strokes[0].contours.size)
        assertTrue(kotlin.math.abs(PathMeasure(path, false).length - PathMeasure(actions[0].path, false).length) < 1f)
    }

    @Test
    fun restorationPreservesAspectRatio() {
        val document =
            WhiteboardDocument(
                200, 400, Color.WHITE,
                listOf(WhiteboardDocument.Stroke(Color.RED, 8f, false, listOf(listOf(0f, 0f, 100f, 200f)))),
            )
        val restored = document.actions(100, 300).single()
        assertEquals(4f, restored.strokeWidth)
        val position = FloatArray(2)
        val measure = PathMeasure(restored.path, false)
        measure.getPosTan(measure.length, position, null)
        assertEquals(50f, position[0], 0.1f)
        assertEquals(100f, position[1], 0.1f)
    }

    @Test
    fun erasersMaskOnlyEarlierInk() {
        val stroke = WhiteboardDocument.Stroke(Color.RED, 4f, false, listOf(listOf(10f, 10f, 90f, 90f)))
        val svg = WhiteboardDocument(100, 100, Color.WHITE, listOf(stroke, stroke.copy(eraser = true), stroke)).toSvg()
        assertTrue("""<mask id="e1"""" in svg)
        assertTrue("""mask="url(#e1)"""" in svg)
        assertTrue(svg.lastIndexOf("<path") > svg.lastIndexOf("</g>"))
    }

    @Test
    fun rejectsInvalidDocuments() {
        val base = WhiteboardDocument(100, 100, Color.WHITE, emptyList()).toJson().toString()
        assertFailsWith<IllegalArgumentException> {
            WhiteboardDocument.fromJson(JSONObject(base).put("version", 99))
        }
        assertFailsWith<IllegalArgumentException> {
            WhiteboardDocument.fromJson(JSONObject(base).put("width", 0))
        }
        val invalid =
            WhiteboardDocument(100, 100, Color.WHITE, listOf(WhiteboardDocument.Stroke(Color.RED, 1f, false, listOf(listOf(1f, 2f, 3f)))))
        assertFailsWith<IllegalArgumentException> { WhiteboardDocument.fromJson(invalid.toJson()) }
        assertFailsWith<IllegalArgumentException> { WhiteboardDocument.fromSvg("<svg/>") }
    }
}
