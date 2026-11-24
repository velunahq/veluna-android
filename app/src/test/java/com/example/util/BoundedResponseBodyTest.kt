package com.example.util

import com.google.common.truth.Truth.assertThat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class BoundedResponseBodyTest {
    @Test
    fun readUtf8AtMost_acceptsBodyAtLimit() {
        val body = "small".toResponseBody("text/plain".toMediaType())

        assertThat(body.readUtf8AtMost(5)).isEqualTo("small")
    }

    @Test
    fun readUtf8AtMost_rejectsOversizedBody() {
        val body = "too large".toResponseBody("text/plain".toMediaType())

        assertThrows(IOException::class.java) { body.readUtf8AtMost(4) }
    }
}
