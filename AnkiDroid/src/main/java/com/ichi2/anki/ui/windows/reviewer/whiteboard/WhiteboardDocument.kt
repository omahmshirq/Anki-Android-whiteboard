// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer.whiteboard

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/**
 * Portable stroke data. SVG metadata carries the editable document alongside the visible image,
 * so one normal Anki media reference is enough for syncing and media checking.
 */
data class WhiteboardDocument(
    val width: Int,
    val height: Int,
    val background: Int,
    val strokes: List<Stroke>,
) {
    data class Stroke(
        val color: Int,
        val width: Float,
        val eraser: Boolean,
        val contours: List<List<Float>>,
    )

    fun actions(
        viewWidth: Int,
        viewHeight: Int,
    ): List<DrawingAction> {
        val scale = min(viewWidth.toFloat() / width, viewHeight.toFloat() / height)
        require(scale.isFinite() && scale > 0)
        val matrix = Matrix().apply { setScale(scale, scale) }
        return strokes.map { stroke ->
            val path = Path()
            for (contour in stroke.contours) {
                path.moveTo(contour[0], contour[1])
                for (i in 2 until contour.size step 2) {
                    path.lineTo(contour[i], contour[i + 1])
                }
            }
            path.transform(matrix)
            DrawingAction(path, stroke.color, stroke.width * scale, stroke.eraser)
        }
    }

    fun toJson(): JSONObject =
        JSONObject()
            .put("version", 1)
            .put("width", width)
            .put("height", height)
            .put("background", background)
            .put(
                "strokes",
                JSONArray().apply {
                    strokes.forEach { stroke ->
                        put(
                            JSONObject()
                                .put("color", stroke.color)
                                .put("width", stroke.width.toDouble())
                                .put("eraser", stroke.eraser)
                                .put("contours", JSONArray(stroke.contours.map { JSONArray(it) })),
                        )
                    }
                },
            )

    fun toSvg(): String {
        val metadata = Base64.encodeToString(toJson().toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val definitions = StringBuilder()
        val content = ArrayDeque<String>()
        strokes.forEachIndexed { index, stroke ->
            val path = stroke.svgPath(if (stroke.eraser) Color.BLACK else stroke.color)
            if (stroke.eraser) {
                definitions.append(
                    """<mask id="e$index" maskUnits="userSpaceOnUse" x="0" y="0" width="$width" height="$height"><rect width="$width" height="$height" fill="white"/>$path</mask>""",
                )
                content.addFirst("""<g mask="url(#e$index)">""")
                content.addLast("</g>")
            } else {
                content.addLast(path)
            }
        }
        return """<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height"><metadata id="ankidroid-whiteboard-v1">$metadata</metadata><defs>$definitions</defs><rect width="$width" height="$height" fill="${background.rgb()}"/>${content.joinToString("")}</svg>"""
    }

    private fun Stroke.svgPath(color: Int): String {
        val data =
            contours.joinToString(" ") { contour ->
                buildString {
                    append("M${contour[0]} ${contour[1]}")
                    for (i in 2 until contour.size step 2) append("L${contour[i]} ${contour[i + 1]}")
                }
            }
        val opacity = Color.alpha(color) / 255.0
        return """<path d="$data" fill="none" stroke="${color.rgb()}" stroke-opacity="$opacity" stroke-width="$width" stroke-linecap="round" stroke-linejoin="round"/>"""
    }

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAX_POINTS = 200_000
        private const val MAX_STROKES = 5_000
        private val metadataPattern = Regex("""<metadata id="ankidroid-whiteboard-v1">([A-Za-z0-9+/=]+)</metadata>""")

        fun capture(
            actions: List<DrawingAction>,
            width: Int,
            height: Int,
            background: Int,
        ): WhiteboardDocument {
            require(width in 1..16_384 && height in 1..16_384)
            require(actions.size <= MAX_STROKES) { "Whiteboard has too many strokes" }
            var points = 0
            val strokes =
                actions.map { action ->
                    val measure = PathMeasure(action.path, false)
                    val contours = mutableListOf<List<Float>>()
                    do {
                        val length = measure.length
                        require(length.isFinite() && length <= MAX_POINTS)
                        val steps = ceil(length.toDouble()).toInt().coerceAtLeast(1)
                        points += steps + 1
                        require(points <= MAX_POINTS) { "Whiteboard is too large to save" }
                        val contour = ArrayList<Float>((steps + 1) * 2)
                        val position = FloatArray(2)
                        for (i in 0..steps) {
                            if (measure.getPosTan(length * i / steps, position, null)) {
                                contour.add(position[0])
                                contour.add(position[1])
                            }
                        }
                        if (contour.isNotEmpty()) contours.add(contour)
                    } while (measure.nextContour())
                    Stroke(action.color, action.strokeWidth, action.isEraser, contours)
                }
            return WhiteboardDocument(width, height, background, strokes)
        }

        fun fromJson(json: JSONObject): WhiteboardDocument {
            require(json.getInt("version") == 1) { "Unsupported whiteboard format" }
            val width = json.getInt("width")
            val height = json.getInt("height")
            require(width in 1..16_384 && height in 1..16_384)
            val array = json.getJSONArray("strokes")
            require(array.length() <= MAX_STROKES)
            var points = 0
            val strokes =
                (0 until array.length()).map { index ->
                    val stroke = array.getJSONObject(index)
                    val strokeWidth = stroke.getDouble("width").toFloat()
                    require(strokeWidth.isFinite() && strokeWidth > 0 && strokeWidth <= 16_384)
                    val contourArray = stroke.getJSONArray("contours")
                    require(contourArray.length() <= MAX_POINTS)
                    val contours =
                        (0 until contourArray.length()).map { contourIndex ->
                            val coordinates = contourArray.getJSONArray(contourIndex)
                            require(coordinates.length() >= 2 && coordinates.length() % 2 == 0)
                            points += coordinates.length() / 2
                            require(points <= MAX_POINTS)
                            (0 until coordinates.length()).map { coordinateIndex ->
                                coordinates.getDouble(coordinateIndex).toFloat().also {
                                    require(it.isFinite() && kotlin.math.abs(it) <= 65_536)
                                }
                            }
                        }
                    Stroke(stroke.getInt("color"), strokeWidth, stroke.getBoolean("eraser"), contours)
                }
            return WhiteboardDocument(width, height, json.getInt("background"), strokes)
        }

        fun fromSvg(svg: String): WhiteboardDocument {
            require(svg.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val metadata = requireNotNull(metadataPattern.find(svg)?.groupValues?.get(1)) { "Editable drawing data is missing" }
            val json = String(Base64.decode(metadata, Base64.NO_WRAP), Charsets.UTF_8)
            return fromJson(JSONObject(json))
        }

        private fun Int.rgb(): String = String.format(Locale.ROOT, "#%06x", this and 0xFFFFFF)
    }
}
