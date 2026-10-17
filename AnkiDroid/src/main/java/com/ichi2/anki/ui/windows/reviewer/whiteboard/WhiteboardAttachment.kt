// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer.whiteboard

/**
 * Only the generated block for one card ordinal is replaced. User text, media and drawings for
 * sibling cards are preserved byte-for-byte. Filenames cannot contain paths or HTML.
 */
object WhiteboardAttachment {
    private val filenamePattern = Regex("""anki_whiteboard_[a-f0-9-]+\.svg""")
    private val imagePattern =
        Regex(
            """<img\b[^>]*\ssrc\s*=\s*["'](anki_whiteboard_[a-f0-9-]+\.svg)["'][^>]*>""",
            RegexOption.IGNORE_CASE,
        )

    fun find(
        fields: List<String>,
        ordinal: Int,
    ): String? {
        val blocks = fields.flatMap { blockPattern(ordinal).findAll(it).toList() }
        require(blocks.size <= 1) { "Multiple saved whiteboards found for this card" }
        return blocks.singleOrNull()?.let { match ->
            requireNotNull(imagePattern.find(match.value))
                .groupValues[1]
        }
    }

    fun replace(
        fields: List<String>,
        ordinal: Int,
        fieldIndex: Int,
        filename: String?,
    ): List<String> {
        require(fieldIndex in fields.indices)
        require(filename == null || filenamePattern.matches(filename))
        // Validate before removing anything, so a malformed block is never silently lost.
        find(fields, ordinal)
        val pattern = blockPattern(ordinal)
        val updated = fields.map { pattern.replace(it, "") }.toMutableList()
        if (filename != null) {
            updated[fieldIndex] +=
                """<!--ankidroid-whiteboard:$ordinal:start--><div class="ankidroid-whiteboard">""" +
                """<img src="$filename" alt="Saved whiteboard"></div><!--ankidroid-whiteboard:$ordinal:end-->"""
        }
        return updated
    }

    fun isSafeFilename(filename: String): Boolean = filenamePattern.matches(filename)

    private fun blockPattern(ordinal: Int): Regex {
        require(ordinal >= 0)
        return Regex(
            """<!--ankidroid-whiteboard:$ordinal:start-->.*?<!--ankidroid-whiteboard:$ordinal:end-->""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
