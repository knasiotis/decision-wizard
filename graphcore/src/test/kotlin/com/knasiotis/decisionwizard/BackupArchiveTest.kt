package com.knasiotis.decisionwizard

import com.knasiotis.decisionwizard.library.BackupArchive
import com.knasiotis.decisionwizard.library.DwizCodec
import com.knasiotis.decisionwizard.library.DwizFormatException
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupArchiveTest {

    private fun named(name: String, id: String) =
        Fixtures.graph("a", Fixtures.node("a")).copy(graphId = id, name = name)

    @Test
    fun `round trips every graph`() {
        val graphs = listOf(Fixtures.example(), named("Second", "g-2"), named("Third", "g-3"))
        val restored = BackupArchive.read(BackupArchive.write(graphs))

        assertEquals(graphs.size, restored.size)
        assertEquals(graphs.map { it.graphId }.toSet(), restored.map { it.graphId }.toSet())
        assertEquals(Fixtures.example(), restored.first { it.graphId == Fixtures.example().graphId })
    }

    /** A backup has to be openable without this app, so it must be a real zip. */
    @Test
    fun `the archive is an ordinary zip`() {
        assertTrue(DwizCodec.looksLikeZip(BackupArchive.write(listOf(Fixtures.example()))))
    }

    @Test
    fun `entries are named after their graphs`() {
        val bytes = BackupArchive.write(listOf(Fixtures.example()))
        assertContains(entryNames(bytes), "internet-down.dwiz")
    }

    /** Two graphs can slug identically; duplicate zip entry names extract badly. */
    @Test
    fun `graphs with the same name get distinct entries`() {
        val bytes = BackupArchive.write(
            listOf(named("Internet down", "g-1"), named("Internet down", "g-2"))
        )
        val names = entryNames(bytes)

        assertEquals(2, names.size)
        assertEquals(2, names.toSet().size, "entry names must be unique: $names")
    }

    @Test
    fun `non-dwiz entries are ignored rather than fatal`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("not a graph".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("internet-down.dwiz"))
            zip.write(DwizCodec.encode(Fixtures.example()).toByteArray())
            zip.closeEntry()
        }

        val restored = BackupArchive.read(out.toByteArray())
        assertEquals(1, restored.size)
    }

    @Test
    fun `a zip with no graphs is refused`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("nothing here".toByteArray())
            zip.closeEntry()
        }
        assertFailsWith<DwizFormatException> { BackupArchive.read(out.toByteArray()) }
    }

    @Test
    fun `a plain graph file is not a backup`() {
        val single = DwizCodec.encode(Fixtures.example()).toByteArray()
        assertFailsWith<DwizFormatException> { BackupArchive.read(single) }
    }

    /** The single-graph path must say something useful when handed a backup. */
    @Test
    fun `importing a backup as a single graph explains itself`() {
        val backup = BackupArchive.write(listOf(Fixtures.example()))
        val e = assertFailsWith<DwizFormatException> { DwizCodec.decode(backup) }
        assertContains(e.message!!, "Restore")
    }

    @Test
    fun `backup file name carries the date`() {
        assertEquals("decision-wizard-backup-2026-08-23.zip", BackupArchive.fileName("2026-08-23"))
    }

    private fun entryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                names += e.name
            }
        }
        return names
    }
}
