package com.example.data.network

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.VelunaDatabase
import com.example.model.SongItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
class LyricsRepositoryTest {

    private lateinit var db: VelunaDatabase
    private lateinit var repo: LyricsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VelunaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val body = when (host) {
                    "lrclib.net" -> """
                        {
                          "syncedLyrics": "[00:01.00] Line one\n[00:05.00] Line two"
                        }
                    """.trimIndent()
                    "api-lyrics.simpmusic.org" -> """
                        {
                          "data": [
                            {
                              "syncedLyrics": "[00:01.00] Pinned line one\n[00:05.00] Pinned line two"
                            }
                          ]
                        }
                    """.trimIndent()
                    else -> "{}"
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        repo = LyricsRepository(db.velunaDao(), InnerTubeClient(http))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun clearPinnedSource_deletesPinnedCacheRowAndFallsBackToAutoProvider() = runBlocking {
        val song = SongItem(
            videoId = "dQw4w9WgXcQ",
            title = "Test Track",
            artists = "Test Artist",
            duration = "3:00"
        )
        val order = "lrclib:1,simpmusic:1"

        val pinned = repo.getLyrics(song, order, forceProviderId = "simpmusic")
        assertThat(pinned).isNotNull()
        assertThat(pinned!!.providerId).isEqualTo("simpmusic")
        assertThat(db.velunaDao().getLyricsCache(song.videoId)?.pinned).isTrue()

        val reset = repo.clearPinnedSource(song, order)
        assertThat(reset).isNotNull()
        assertThat(reset!!.providerId).isEqualTo("lrclib")
        assertThat(reset.pinned).isFalse()
        assertThat(db.velunaDao().getLyricsCache(song.videoId)?.pinned).isFalse()
    }
}
