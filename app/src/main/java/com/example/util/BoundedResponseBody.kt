package com.example.util

import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException

/** Reads a network response with a hard byte limit, even when Content-Length is absent or wrong. */
fun ResponseBody.readUtf8AtMost(maxBytes: Long): String {
    require(maxBytes > 0L) { "Maximum response size must be positive" }
    if (contentLength() > maxBytes) {
        throw IOException("Response exceeds the ${maxBytes / (1024 * 1024)} MB limit")
    }

    val buffer = Buffer()
    val responseSource = source()
    var totalBytes = 0L
    while (true) {
        val read = responseSource.read(buffer, minOf(8_192L, maxBytes - totalBytes + 1L))
        if (read < 0L) break
        totalBytes += read
        if (totalBytes > maxBytes) {
            throw IOException("Response exceeds the ${maxBytes / (1024 * 1024)} MB limit")
        }
    }
    return buffer.readUtf8()
}
