package com.example.data.network

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.PendingScrobbleEntity
import com.example.data.local.ScrobbleServices
import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaDatabase
import com.example.data.security.InMemoryAesGcmVaultCipher
import com.example.data.security.SecretVault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
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
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
class ScrobbleClientBatchTest {

    private lateinit var tempDir: File
    private lateinit var db: VelunaDatabase
    private lateinit var prefsRepo: UserPrefsRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "scrobble_batch_${UUID.randomUUID()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, VelunaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val ds = PreferenceDataStoreFactory.create(
            produceFile = { File(tempDir, "prefs.preferences_pb") }
        )
        val vault = SecretVault(File(tempDir, "vault.bin"), InMemoryAesGcmVaultCipher())
        prefsRepo = UserPrefsRepository(context, vault, ds)
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun flushPendingScrobbles_preservesFailureWhenAnotherServiceSucceedsInSameBatch(): Unit = runBlocking {
        prefsRepo.setScrobblingConfig(
            enabled = true,
            lastFmUser = "alice",
            lastFmKey = "k",
            lastFmSecret = "s",
            lastFmSession = "sk",
            listenBrainzToken = "lb_token"
        )
        val dao = db.velunaDao()
        val nowMs = System.currentTimeMillis()
        val nowSecs = nowMs / 1000L
        val payload = """{"title":"Track","artists":"Artist","startedAtSecs":$nowSecs}"""

        // ListenBrainz processed first -> fails (401); Last.fm processed second -> succeeds (200)
        dao.insertPendingScrobble(PendingScrobbleEntity(service = ScrobbleServices.LISTENBRAINZ, payloadJson = payload, startedAtSecs = nowSecs))
        dao.insertPendingScrobble(PendingScrobbleEntity(service = ScrobbleServices.LASTFM, payloadJson = payload, startedAtSecs = nowSecs))

        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if ("listenbrainz" in url) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(401)
                        .message("Unauthorized")
                        .body("""{"error":"Invalid user token"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"scrobbles":{}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val client = ScrobbleClient(http, dao, prefsRepo)
        client.flushPendingScrobbles(nowMs)

        val errAfterPartialFailure = prefsRepo.lastScrobbleErrorFlow.first()
        assertThat(errAfterPartialFailure).contains("Invalid user token")

        // Next batch where Last.fm succeeds cleanly should clear lastScrobbleError
        dao.insertPendingScrobble(PendingScrobbleEntity(service = ScrobbleServices.LASTFM, payloadJson = payload, startedAtSecs = nowSecs))
        client.flushPendingScrobbles(nowMs + 1000L)

        val errAfterCleanBatch = prefsRepo.lastScrobbleErrorFlow.first()
        assertThat(errAfterCleanBatch).isNull()
    }
}
