package com.knasiotis.decisionwizard

import com.knasiotis.decisionwizard.library.DwizCodec
import com.knasiotis.decisionwizard.library.DwizFormatException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DwizCodecTest {

    @Test
    fun `round trips the sample`() {
        val original = Fixtures.example()
        val back = DwizCodec.decode(DwizCodec.encode(original).toByteArray())
        assertEquals(original, back)
    }

    /** Regression: encodeDefaults once dropped these, breaking the sync story. */
    @Test
    fun `encoded file carries schemaVersion and revision`() {
        val json = DwizCodec.encode(Fixtures.example())
        assertContains(json, "\"schemaVersion\"")
        assertContains(json, "\"revision\"")
    }

    /**
     * v0.4 will put a zip behind this extension. Reading one now must say so
     * plainly rather than failing as malformed JSON.
     */
    @Test
    fun `a zip is refused with an explanation, not a parse error`() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(64)
        val e = assertFailsWith<DwizFormatException> { DwizCodec.decode(zip) }
        assertContains(e.message!!, "attachments")
    }

    @Test
    fun `zip detection needs the whole magic prefix`() {
        assertTrue(DwizCodec.looksLikeZip(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00)))
        assertTrue(!DwizCodec.looksLikeZip(byteArrayOf(0x50, 0x4B)), "too short to tell")
        assertTrue(!DwizCodec.looksLikeZip("{\"graphId\":\"g\"}".toByteArray()))
    }

    // Files are meant to be hand-editable, so tolerate what editors leave behind.
    @Test
    fun `tolerates a byte order mark and surrounding whitespace`() {
        val json = DwizCodec.encode(Fixtures.example())
        val messy = "﻿\n  " + json + "\n\n"
        assertEquals(Fixtures.example(), DwizCodec.decode(messy.toByteArray()))
    }

    @Test
    fun `rejects an empty file`() {
        val e = assertFailsWith<DwizFormatException> { DwizCodec.decode("   ".toByteArray()) }
        assertContains(e.message!!, "empty")
    }

    @Test
    fun `rejects arbitrary text`() {
        assertFailsWith<DwizFormatException> { DwizCodec.decode("hello".toByteArray()) }
        assertFailsWith<DwizFormatException> { DwizCodec.decode("{\"nope\":1}".toByteArray()) }
    }

    @Test
    fun `suggests a slugged file name`() {
        assertEquals("internet-down.dwiz", DwizCodec.suggestedFileName(Fixtures.example()))
    }

    @Test
    fun `file name survives punctuation and spacing`() {
        fun nameFor(n: String) =
            DwizCodec.suggestedFileName(Fixtures.graph(null).copy(name = n))

        assertEquals("router-v1-2.dwiz", nameFor("Router  v1.2"))
        assertEquals("graph.dwiz", nameFor(""), "a nameless graph still needs a filename")
        assertEquals("graph.dwiz", nameFor("///"), "punctuation alone leaves nothing to slug")
    }

    /**
     * Letters outside ASCII are kept on purpose. Restricting the slug to ASCII
     * would collapse a Greek or accented graph name to "graph.dwiz" every time,
     * which is worse than a filename with an accent in it.
     */
    @Test
    fun `file name keeps non-ascii letters`() {
        fun nameFor(n: String) =
            DwizCodec.suggestedFileName(Fixtures.graph(null).copy(name = n))

        assertEquals("café-wi-fi.dwiz", nameFor("  Café / Wi-Fi!  "))
        assertEquals("δίκτυο-κάτω.dwiz", nameFor("Δίκτυο κάτω"))
    }
}
