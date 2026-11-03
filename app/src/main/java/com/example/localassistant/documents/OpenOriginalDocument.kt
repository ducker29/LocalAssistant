package com.example.localassistant.documents

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Called from a UI coroutine: resolve the private file on IO, launch on the caller's UI context. */
suspend fun openOriginalDocument(context: Context, document: StoredDocument): String? {
    return try {
        val uri = withContext(Dispatchers.IO) {
            require(document.id.matches(Regex("[a-f0-9]{64}")))
            val file = File(context.filesDir, "documents/${document.id}/original.${document.format.name.lowercase()}")
            if (!file.isFile) throw IOException("Original document is unavailable")
            FileProvider.getUriForFile(context, "${context.packageName}.documents", file, document.name)
        }
        val mime = when (document.format) {
            DocumentFormat.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            DocumentFormat.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            clipData = ClipData.newRawUri("Документ", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Do not pre-query packages: ACTION_VIEW works without broad package-visibility permissions.
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        "Нет приложения для открытия ${document.format}. Установите совместимую программу, например Word или Excel. Извлечённые данные доступны в хранилище."
    } catch (_: IOException) {
        "Оригинал документа недоступен. Извлечённые данные можно открыть отдельно."
    } catch (_: SecurityException) {
        "Не удалось предоставить программе доступ к документу. Извлечённые данные можно открыть отдельно."
    } catch (_: IllegalArgumentException) {
        "Не удалось открыть оригинал документа. Извлечённые данные можно открыть отдельно."
    }
}
