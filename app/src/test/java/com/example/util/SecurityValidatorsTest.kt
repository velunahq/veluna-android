package com.example.util

import com.example.data.network.InnerTubeParsers
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

class SecurityValidatorsTest {

    @Test
    fun isSafeHttpsUrl_rejectsUnsafeSchemesAndControlChars() {
        assertThat(SecurityValidators.isSafeHttpsUrl("https://lh3.googleusercontent.com/abc")).isTrue()
        assertThat(SecurityValidators.isSafeHttpsUrl("http://lh3.googleusercontent.com/abc")).isFalse()
        assertThat(SecurityValidators.isSafeHttpsUrl("file:///etc/passwd")).isFalse()
        assertThat(SecurityValidators.isSafeHttpsUrl("content://media/external/audio/1")).isFalse()
        assertThat(SecurityValidators.isSafeHttpsUrl("javascript:alert(1)")).isFalse()
        assertThat(SecurityValidators.isSafeHttpsUrl("https://example.com/\nabc")).isFalse()
        assertThat(SecurityValidators.isSafeHttpsUrl("https://example.com/" + "a".repeat(2100), maxLength = 2048)).isFalse()
    }

    @Test
    fun isValidYouTubeAndPlaylistId_blocksPathTraversalAndInjections() {
        assertThat(SecurityValidators.isValidYouTubeId("dQw4w9WgXcQ")).isTrue()
        assertThat(SecurityValidators.isValidYouTubeId("trk_1")).isTrue()
        assertThat(SecurityValidators.isValidYouTubeId("../secret")).isFalse()
        assertThat(SecurityValidators.isValidYouTubeId("abc/def")).isFalse()
        assertThat(SecurityValidators.isValidYouTubeId("abc?x=1")).isFalse()
        assertThat(SecurityValidators.isValidYouTubeId("")).isFalse()

        assertThat(SecurityValidators.isValidPlaylistId("PL1234_abc-XYZ")).isTrue()
        assertThat(SecurityValidators.isValidPlaylistId("OLAK5uy_abc")).isTrue()
        assertThat(SecurityValidators.isValidPlaylistId("a")).isFalse()
        assertThat(SecurityValidators.isValidPlaylistId("PL123/../../etc")).isFalse()
    }

    @Test
    fun isValidHexColor_acceptsSixAndEightDigitHexOnly() {
        assertThat(SecurityValidators.isValidHexColor("#FFAA33")).isTrue()
        assertThat(SecurityValidators.isValidHexColor("#80ffaa33")).isTrue()
        assertThat(SecurityValidators.isValidHexColor("FFAA33")).isFalse()
        assertThat(SecurityValidators.isValidHexColor("#FFF")).isFalse()
        assertThat(SecurityValidators.isValidHexColor("#GGGGGG")).isFalse()
    }

    @Test
    fun isYouTubeStreamHost_checksExactDomainOrSubdomainOnly() {
        assertThat(SecurityValidators.isYouTubeStreamHost("rr1---sn-4g5e6ns7.googlevideo.com")).isTrue()
        assertThat(SecurityValidators.isYouTubeStreamHost("googlevideo.com")).isTrue()
        assertThat(SecurityValidators.isYouTubeStreamHost("music.youtube.com")).isTrue()
        assertThat(SecurityValidators.isYouTubeStreamHost("evil-googlevideo.com")).isFalse()
        assertThat(SecurityValidators.isYouTubeStreamHost("googlevideo.com.attacker.tld")).isFalse()
    }

    @Test
    fun safeUserErrorMessage_redactsUrlsPathsAndTokens() {
        val withUrl = IllegalStateException("Failed GET https://internal.example/api?token=secret123")
        assertThat(SecurityValidators.safeUserErrorMessage(withUrl, "Request failed"))
            .isEqualTo("Request failed")

        val withToken = IllegalStateException("Auth error AIzaSyDummyTokenValue12345")
        assertThat(SecurityValidators.safeUserErrorMessage(withToken, "Auth failed"))
            .isEqualTo("Auth failed")

        val withPath = IllegalStateException("java.io.FileNotFoundException: /data/user/0/com.example/files/a")
        assertThat(SecurityValidators.safeUserErrorMessage(withPath, "File error"))
            .isEqualTo("File error")

        val networkErr = IOException("Connection reset by peer: /10.0.2.2")
        assertThat(SecurityValidators.safeUserErrorMessage(networkErr, "Fallback"))
            .isEqualTo("Network error occurred. Please try again.")
    }

    @Test
    fun suspendRunCatching_rethrowsCancellationException() = runBlocking {
        var threwCancellation = false
        try {
            suspendRunCatching<Unit> { throw CancellationException("cancelled") }
        } catch (_: CancellationException) {
            threwCancellation = true
        }
        assertThat(threwCancellation).isTrue()
    }

    @Test
    fun upgradeThumbnailUrl_validatesHostStrictly() {
        val legit = InnerTubeParsers.upgradeThumbnailUrl(
            "http://lh3.googleusercontent.com/art=w120-h120-l90-rj",
            targetPx = 544
        )
        assertThat(legit).isEqualTo("https://lh3.googleusercontent.com/art=w544-h544-l95-rj")

        val spoofed = InnerTubeParsers.upgradeThumbnailUrl(
            "https://evil.example/googleusercontent.com/art=w120-h120",
            targetPx = 544
        )
        assertThat(spoofed).isEqualTo("https://evil.example/googleusercontent.com/art=w120-h120")

        val rejectedScheme = InnerTubeParsers.upgradeThumbnailUrl("file:///sdcard/cover.jpg")
        assertThat(rejectedScheme).isNull()
    }
}
