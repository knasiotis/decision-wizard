package com.knasiotis.decisionwizard.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reading and writing through the Storage Access Framework. The app has no
 * storage permissions and never asks for any — every file it touches is one the
 * user picked in the system dialog, and the grant is per-Uri.
 */
class FileGateway(private val resolver: ContentResolver) {

    suspend fun read(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not open the file for reading.")
    }

    suspend fun write(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: error("Could not open the file for writing.")
    }
}
