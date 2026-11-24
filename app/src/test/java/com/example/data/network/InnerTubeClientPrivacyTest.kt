package com.example.data.network

import com.example.model.AudioQuality
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class InnerTubeClientPrivacyTest {
    @Test
    fun primaryCandidatesPreventMirrorResolution() = runBlocking {
        var mirrorCalls = 0
        val result = resolveMirrorFallbackIfNeeded(listOf("primary"), allowMirrors = true) {
            mirrorCalls++
            listOf("mirror")
        }

        assertThat(result.getOrThrow()).containsExactly("primary")
        assertThat(mirrorCalls).isEqualTo(0)
    }

    @Test
    fun emptyPrimaryCandidatesResolveMirrorsExactlyOnceWhenEnabled() = runBlocking {
        var mirrorCalls = 0
        val result = resolveMirrorFallbackIfNeeded(emptyList<String>(), allowMirrors = true) {
            mirrorCalls++
            listOf("mirror")
        }

        assertThat(result.getOrThrow()).containsExactly("mirror")
        assertThat(mirrorCalls).isEqualTo(1)
    }

    @Test
    fun emptyPrimaryCandidatesFailWithoutResolvingMirrorsWhenDisabled() = runBlocking {
        var mirrorCalls = 0
        val result = resolveMirrorFallbackIfNeeded(emptyList<String>(), allowMirrors = false) {
            mirrorCalls++
            listOf("mirror")
        }

        assertThat(result.isFailure).isTrue()
        assertThat(mirrorCalls).isEqualTo(0)
    }

    @Test
    fun mirrorHostsAreContactedOnlyWhenOptedIn() = runBlocking {
        val contactedHosts = mutableListOf<String>()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                synchronized(contactedHosts) { contactedHosts += request.url.host }
                val primary = request.url.host == "music.youtube.com"
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (primary) 200 else 404)
                    .message(if (primary) "OK" else "Not Found")
                    .body(
                        "{\"playabilityStatus\":{\"status\":\"UNPLAYABLE\"}}"
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()
        val client = InnerTubeClient(http)

        client.resolveStreamCandidates("test_video", AudioQuality.HIGH, allowMirrorFallbacks = false)
        assertThat(synchronized(contactedHosts) { contactedHosts.toList() }.all { it == "music.youtube.com" })
            .isTrue()

        synchronized(contactedHosts) { contactedHosts.clear() }
        client.resolveStreamCandidates("test_video", AudioQuality.HIGH, allowMirrorFallbacks = true)
        assertThat(synchronized(contactedHosts) { contactedHosts.any { it != "music.youtube.com" } }).isTrue()
    }
}
