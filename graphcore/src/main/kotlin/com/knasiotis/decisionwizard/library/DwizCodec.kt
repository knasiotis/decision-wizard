package com.knasiotis.decisionwizard.library

import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.parseGraph
import com.knasiotis.decisionwizard.model.toJson

const val DWIZ_EXTENSION = "dwiz"

/** A file that is not a Decision Wizard graph, or is one this build cannot read. */
class DwizFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads and writes `.dwiz` files.
 *
 * The extension exists for tap-to-open association, not to describe the
 * contents: Android cannot route a `.json` file to a specific app because too
 * many apps claim that type.
 *
 * **v0.2 writes bare JSON.** The zip container only earns its place in v0.4,
 * when attachments exist — until then it would wrap a single file while
 * breaking the hand-editable constraint. The reader already distinguishes the
 * two by magic bytes, so v0.2 files keep importing forever and no second
 * extension is ever needed.
 */
object DwizCodec {

    /** Every zip begins with these four bytes. */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    private const val UTF8_BOM = '﻿'

    fun encode(graph: Graph): String = graph.toJson()

    fun decode(bytes: ByteArray): Graph {
        if (looksLikeZip(bytes)) {
            throw DwizFormatException(
                "This file has attachments bundled with it, which this version cannot open yet."
            )
        }
        return decode(bytes.toString(Charsets.UTF_8))
    }

    fun decode(text: String): Graph {
        // Files are meant to be hand-editable, so tolerate what a text editor
        // leaves behind: a byte-order mark and surrounding whitespace.
        val cleaned = text.removePrefix(UTF8_BOM.toString()).trim()
        if (cleaned.isEmpty()) throw DwizFormatException("The file is empty.")

        return try {
            parseGraph(cleaned)
        } catch (e: Exception) {
            throw DwizFormatException("This is not a Decision Wizard file.", e)
        }
    }

    fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] }

    /**
     * What to pre-fill in the system save dialog. The user can always override
     * it, so this only has to be reasonable, not unique.
     */
    fun suggestedFileName(graph: Graph): String {
        val slug = graph.name
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
            .take(60)
            .trim('-')

        return (slug.ifEmpty { "graph" }) + "." + DWIZ_EXTENSION
    }
}
