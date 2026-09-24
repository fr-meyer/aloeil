package org.aloeil.app

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException

/** Only unexpired CSVs in the dedicated private cache can be read by a chosen recipient. */
class AloeilFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Shared CSV is read only")
        val context = context ?: throw FileNotFoundException("Share context is unavailable")
        val name = uri.lastPathSegment ?: throw FileNotFoundException("Missing share name")
        if (!name.startsWith("aloeil-readings-") || !name.endsWith(".csv") ||
            name.contains('/') || name.contains('\\')) {
            throw FileNotFoundException("Invalid share name")
        }
        val folder = CsvShareCache.directory(context).canonicalFile
        val file = File(folder, name).canonicalFile
        if (file.parentFile != folder || !file.isFile) {
            throw FileNotFoundException("Temporary CSV is missing")
        }
        if (CsvShareCache.isExpired(file)) {
            file.delete()
            throw FileNotFoundException("Temporary CSV expired")
        }
        return super.openFile(uri, mode)
    }
}
