package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.BlockedArtistEntity
import com.example.data.local.ListenEntity
import com.example.data.local.ListenSongEntity
import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaDatabase
import com.example.data.network.InnerTubeClient
import com.example.data.network.LyricsRepository
import com.example.data.network.ScrobbleClient
import com.example.data.repository.VelunaRepository
import com.example.data.security.InMemoryAesGcmVaultCipher
import com.example.data.security.SecretVault
import com.example.model.LOCAL_PLAYLIST_PREFIX
import com.example.model.SongItem
import com.example.playback.PlayerController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
class ViewModelsRegressionTest {

    private lateinit var tempDir: File
    private lateinit var db: VelunaDatabase
    private lateinit var prefsRepo: UserPrefsRepository
    private lateinit var repo: VelunaRepository
    private lateinit var innerTube: InnerTubeClient
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "vm_test_${UUID.randomUUID()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, VelunaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val ds = PreferenceDataStoreFactory.create(
            produceFile = { File(tempDir, "prefs.preferences_pb") }
        )
        val vault = SecretVault(File(tempDir, "vault.bin"), InMemoryAesGcmVaultCipher())
        prefsRepo = UserPrefsRepository(context, vault, ds)

        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        innerTube = InnerTubeClient(http)
        repo = VelunaRepository(
            context = context,
            dao = db.velunaDao(),
            prefsRepo = prefsRepo,
            innerTube = innerTube,
            database = db
        )
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test
    fun detailViewModel_loadPlaylist_resetsStalePageWhenSwitchingPlaylist() = runBlocking {
        val dao = db.velunaDao()
        val playlistAId = repo.createPlaylist("Playlist One", "First")
        repo.addTrackToPlaylist(
            playlistAId,
            SongItem(videoId = "v1", title = "Song A", artists = "Artist A", duration = "3:00")
        )

        val vm = DetailViewModel(innerTube, repo)
        vm.loadPlaylist("$LOCAL_PLAYLIST_PREFIX$playlistAId")

        val stateA = vm.playlistState.first { !it.isLoading }
        assertThat(stateA.page?.title).isEqualTo("Playlist One")
        assertThat(stateA.page?.items).hasSize(1)

        vm.setPlaylistFilter("Song")
        assertThat(vm.playlistState.value.filterQuery).isEqualTo("Song")

        // Switch to a second local playlist ID that does not exist (returns failure)
        vm.loadPlaylist("${LOCAL_PLAYLIST_PREFIX}999999", force = true)
        val stateB = vm.playlistState.first { !it.isLoading }

        assertThat(stateB.page).isNull()
        assertThat(stateB.filterQuery).isEmpty()
        assertThat(stateB.error).isNotNull()

        // Ensure cached observer flows return the same StateFlow instance for the same ID
        assertThat(vm.observeIsAlbumSaved("alb1")).isSameInstanceAs(vm.observeIsAlbumSaved("alb1"))
        assertThat(vm.observeIsArtistFollowed("art1")).isSameInstanceAs(vm.observeIsArtistFollowed("art1"))
        assertThat(dao).isNotNull()
    }

    @Test
    fun homeSearchViewModel_recentJumpBack_filtersCollaborationsContainingBlockedArtist() = runBlocking {
        val dao = db.velunaDao()
        val now = System.currentTimeMillis()
        val blockedCollab = SongItem(
            videoId = "collab1",
            title = "Collab Track",
            artists = "Allowed Artist, Blocked Artist",
            duration = "3:20"
        )
        val cleanTrack = SongItem(
            videoId = "clean1",
            title = "Clean Track",
            artists = "Allowed Artist, Featured Friend",
            duration = "2:50"
        )
        dao.upsertListenSong(com.example.data.local.ListenSongEntity(blockedCollab.videoId, Json.encodeToString(blockedCollab)))
        dao.upsertListenSong(ListenSongEntity(cleanTrack.videoId, Json.encodeToString(cleanTrack)))
        dao.insertListen(ListenEntity(videoId = blockedCollab.videoId, playedAt = now - 1000L, secs = 120L))
        dao.insertListen(ListenEntity(videoId = cleanTrack.videoId, playedAt = now, secs = 120L))
        dao.insertBlockedArtist(BlockedArtistEntity(name = "Blocked Artist", blockedAt = now))

        val vm = HomeSearchViewModel(innerTube, repo, prefsRepo)
        val collector = launch { vm.recentJumpBack.collect() }

        val items = vm.recentJumpBack.first { it.isNotEmpty() }
        assertThat(items.map { it.id }).containsExactly("clean1")

        // Clearing search resets isSearching/query cleanly
        vm.submitSearch("ambient")
        vm.clearSearchResults()
        assertThat(vm.searchState.value.query).isEmpty()
        assertThat(vm.searchState.value.isSearching).isFalse()

        collector.cancel()
    }

    @Test
    fun nowPlayingViewModel_setEqBand_preservesRapidEditsAcrossMultipleBands() = runBlocking {
        val scrobbleClient = ScrobbleClient(OkHttpClient(), db.velunaDao(), prefsRepo)
        val controller = PlayerController(
            context = context,
            dao = db.velunaDao(),
            repository = repo,
            prefsRepo = prefsRepo,
            innerTube = innerTube,
            scrobbleClient = scrobbleClient
        )
        val lyricsRepo = LyricsRepository(db.velunaDao(), innerTube)
        val vm = NowPlayingViewModel(controller, lyricsRepo, innerTube, prefsRepo)

        vm.setEqBand(0, 6f)
        vm.setEqBand(4, -3f)
        vm.setEqBand(9, 4f)

        val savedPrefs = prefsRepo.prefsFlow.first { it.eqBandsDb[9] == 4f && it.eqBandsDb[0] == 6f }
        assertThat(savedPrefs.eqEnabled).isTrue()
        assertThat(savedPrefs.eqPresetName).isEqualTo("Custom")
        assertThat(savedPrefs.eqBandsDb[0]).isEqualTo(6f)
        assertThat(savedPrefs.eqBandsDb[4]).isEqualTo(-3f)
        assertThat(savedPrefs.eqBandsDb[9]).isEqualTo(4f)
    }

    @Test
    fun detailViewModel_removeTrackFromLocalPlaylist_updatesLoadedTracks(): Unit = runBlocking {
        val plId = repo.createPlaylist("Workout", "")
        repo.addTrackToPlaylist(plId, SongItem(videoId = "trk1", title = "One", artists = "Artist"))
        repo.addTrackToPlaylist(plId, SongItem(videoId = "trk2", title = "Two", artists = "Artist"))

        val vm = DetailViewModel(innerTube, repo)
        vm.loadPlaylist("$LOCAL_PLAYLIST_PREFIX$plId", force = true)
        val initial = vm.playlistState.first { !it.isLoading && it.page != null }
        assertThat(initial.page!!.items.map { it.videoId }).containsExactly("trk1", "trk2").inOrder()

        vm.removeTrackFromLocalPlaylist(plId, "trk1")
        val updated = vm.playlistState.first { !it.isLoading && it.page?.items?.size == 1 }
        assertThat(updated.page!!.items.map { it.videoId }).containsExactly("trk2")
    }

    @Test
    fun homeSearchViewModel_failedSearchSetsErrorAndEditingQueryClearsIt() = runBlocking {
        val vm = HomeSearchViewModel(innerTube, repo, prefsRepo)
        vm.submitSearch("nonexistent query")

        val failedState = vm.searchState.first { !it.isSearching && it.error != null }
        assertThat(failedState.results).isNull()
        assertThat(failedState.query).isEqualTo("nonexistent query")

        vm.onQueryChange("new query")
        val editedState = vm.searchState.value
        assertThat(editedState.error).isNull()
        assertThat(editedState.results).isNull()
        assertThat(editedState.query).isEqualTo("new query")
    }
}
