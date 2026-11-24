package com.example.data.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrobbleTest {

    @Test
    fun shouldScrobbleTrack_matchesLastFmSpec() {
        // Too short (<= 30s) or unknown duration (0s)
        assertThat(ScrobbleClient.shouldScrobbleTrack(durationSecs = 0L, heardSecs = 30L)).isFalse()
        assertThat(ScrobbleClient.shouldScrobbleTrack(durationSecs = 25L, heardSecs = 25L)).isFalse()
        assertThat(ScrobbleClient.shouldScrobbleTrack(durationSecs = 30L, heardSecs = 30L)).isFalse()

        // 31s track: requires >= 16s (half rounded up in integer arithmetic 16*2 >= 31)
        assertThat(ScrobbleClient.shouldScrobbleTrack(durationSecs = 31L, heardSecs = 15L)).isFalse()
        assertThat(ScrobbleClient.shouldScrobbleTrack(durationSecs = 31L, heardSecs = 16L)).isTrue()

        // 300s track: requires >= 150s
        assertThat(ScrobbleClient.shouldScrobbleTrack(durationSecs = 300L, heardSecs = 149L)).isFalse()
        assertThat(ScrobbleClient.shouldScrobbleTrack(durationSecs = 300L, heardSecs = 150L)).isTrue()

        // 600s track: 240s cap arrives before half (300s)
        assertThat(ScrobbleClient.shouldScrobbleTrack(durationSecs = 600L, heardSecs = 239L)).isFalse()
        assertThat(ScrobbleClient.shouldScrobbleTrack(durationSecs = 600L, heardSecs = 240L)).isTrue()
    }

    @Test
    fun parseLastFmResponse_classifiesTransientVsAuthErrors() {
        // Success
        assertThat(ScrobbleClient.parseLastFmResponse(200, """{"scrobbles":{}}"""))
            .isEqualTo(ScrobbleSubmitResult.Success)

        // Retryable Last.fm error codes (8, 11, 16, 29)
        for (code in listOf(8, 11, 16, 29)) {
            val res = ScrobbleClient.parseLastFmResponse(200, """{"error":$code,"message":"Temporary"}""")
            assertThat(res).isInstanceOf(ScrobbleSubmitResult.Failure::class.java)
            val fail = res as ScrobbleSubmitResult.Failure
            assertThat(fail.retryable).isTrue()
            assertThat(fail.code).isEqualTo(code)
        }

        // Fatal auth/key error codes (9, 14, 26)
        for (code in listOf(9, 14, 26)) {
            val res = ScrobbleClient.parseLastFmResponse(403, """{"error":$code,"message":"Invalid session key"}""")
            val fail = res as ScrobbleSubmitResult.Failure
            assertThat(fail.retryable).isFalse()
            assertThat(fail.code).isEqualTo(code)
        }
    }

    @Test
    fun parseListenBrainzResponse_mapsHttpStatusCorrectly() {
        assertThat(ScrobbleClient.parseListenBrainzResponse(200, """{"status":"ok"}"""))
            .isEqualTo(ScrobbleSubmitResult.Success)

        val unauthorized = ScrobbleClient.parseListenBrainzResponse(401, """{"error":"Invalid user token"}""")
            as ScrobbleSubmitResult.Failure
        assertThat(unauthorized.retryable).isFalse()
        assertThat(unauthorized.code).isEqualTo(401)
        assertThat(unauthorized.reason).contains("Invalid user token")

        val rateLimited = ScrobbleClient.parseListenBrainzResponse(429, """{"error":"Too many requests"}""")
            as ScrobbleSubmitResult.Failure
        assertThat(rateLimited.retryable).isTrue()

        val serverErr = ScrobbleClient.parseListenBrainzResponse(503, "")
            as ScrobbleSubmitResult.Failure
        assertThat(serverErr.retryable).isTrue()
    }

    @Test
    fun computeBackoffMs_growsAndCapsAtFourHours() {
        assertThat(ScrobbleClient.computeBackoffMs(1)).isEqualTo(15_000L)
        assertThat(ScrobbleClient.computeBackoffMs(2)).isEqualTo(60_000L)
        assertThat(ScrobbleClient.computeBackoffMs(8)).isEqualTo(14_400_000L)
        assertThat(ScrobbleClient.computeBackoffMs(20)).isEqualTo(14_400_000L)
        assertThat(ScrobbleClient.MAX_LASTFM_SCROBBLE_AGE_SECS).isEqualTo(14L * 24L * 3600L)
    }
}
