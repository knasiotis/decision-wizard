package com.knasiotis.decisionwizard.library

import com.knasiotis.decisionwizard.model.Graph
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A whole-library backup: an ordinary zip holding one `.dwiz` per graph.
 *
 * A plain `.zip` rather than another custom extension, because the point of a
 * backup is that it stays useful without this app — it can be opened on a
 * desktop, and a single graph pulled out of it and sent to someone as-is.
 *
 * Nothing here touches the Android framework; `java.util.zip` is plain JDK, so
 * this stays unit-testable on the JVM like the rest of :graphcore.
 */
object BackupArchive {

    /** Refuse implausible archives rather than unpacking whatever arrives. */
    private const val MAX_ENTRIES = 5_000
    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024

    fun fileName(date: String): String = "decision-wizard-backup-$date.zip"

    fun write(graphs: List<Graph>): ByteArray {
        val out = ByteArrayOutputStream()
        val used = mutableSetOf<String>()

        ZipOutputStream(out).use { zip ->
            graphs.forEach { graph ->
                // Two graphs can slug to the same name, and a zip with duplicate
                // entry names is a mess to extract.
                val base = ImportPlanner.uniqueName(DwizCodec.slug(graph.name), used)
                used += base

                zip.putNextEntry(ZipEntry("$base.$DWIZ_EXTENSION"))
                zip.write(DwizCodec.encode(graph).toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * Every `.dwiz` entry in the archive. Anything else is ignored, so a zip
     * that has picked up stray files still restores.
     */
    fun read(bytes: ByteArray): List<Graph> {
        if (!DwizCodec.looksLikeZip(bytes)) {
            throw DwizFormatException("That is not a backup file.")
        }

        val graphs = mutableListOf<Graph>()
        var seen = 0

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++seen > MAX_ENTRIES) {
                    throw DwizFormatException("That backup has too many entries to be genuine.")
                }
                if (entry.isDirectory) continue
                if (!entry.name.endsWith(".$DWIZ_EXTENSION", ignoreCase = true)) continue

                val content = zip.readNBytes(MAX_ENTRY_BYTES.toInt())
                graphs += DwizCodec.decode(content.toString(Charsets.UTF_8))
            }
        }

        if (graphs.isEmpty()) {
            throw DwizFormatException("That archive contains no graphs.")
        }
        return graphs
    }
}
