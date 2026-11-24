package com.example.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Reads a user-selected text document without trusting provider metadata or file size. */
suspend fun readContentUriText(context: Context, uri: Uri, maxBytes: Int): String =
    withContext(Dispatchers.IO) {
        require(maxBytes > 0) { "Maximum file size must be positive" }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not read file")
        input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (total > maxBytes - read) {
                    throw IOException("File exceeds the ${maxBytes / (1024 * 1024)} MB limit")
                }
                output.write(buffer, 0, read)
                total += read
            }
            String(output.toByteArray(), Charsets.UTF_8)
        }
    }
