package com.example.localassistant.documents

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.FileNotFoundException

/** Grants refer to a single original, never to metadata or extracted document content. */
class OriginalDocumentProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val parts = uri.pathSegments
        if (mode != "r" || parts.size != 3 || parts[0] != "documents" ||
            !parts[1].matches(Regex("[a-f0-9]{64}")) ||
            parts[2] !in setOf("original.docx", "original.xlsx")) {
            throw FileNotFoundException("Only original documents can be opened for reading")
        }
        return super.openFile(uri, mode) ?: throw FileNotFoundException("Original document is unavailable")
    }
}
