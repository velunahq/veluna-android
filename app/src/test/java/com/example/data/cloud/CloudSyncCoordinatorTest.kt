package com.example.data.cloud

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.local.DownloadedTrackEntity
import com.example.data.local.FollowedArtistEntity
import com.example.data.local.ListenEntity
import com.example.data.local.ListenSongEntity
import com.example.data.local.LocalDataProvenance
import com.example.data.local.LocalTrackEntity
import com.example.data.local.LyricsCacheEntity
import com.example.data.local.PendingScrobbleEntity
import com.example.data.local.SavedAlbumEntity
import com.example.data.local.ScrobbleServices
import com.example.data.local.SearchHistoryEntity
import com.example.data.local.ShortcutEntity
import com.example.data.local.SyncEntityTypes
import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaDatabase
import com.example.data.network.InnerTubeClient
import com.example.data.repository.VelunaRepository
import com.example.data.security.InMemoryAesGcmVaultCipher
import com.example.data.security.SecretVault
import com.example.model.AudioQuality
import com.example.model.BrowseKind
import com.example.model.SongItem
import com.example.model.TasteProfile
import com.example.model.TasteSeed
import com.example.model.ThemeMode
import com.example.model.VelunaThemePreset
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CloudSyncCoordinatorTest : FirestoreEmulatorTestBase() {

    private val tempDirs = mutableListOf<File>()
    private val openDbs = mutableListOf<VelunaDatabase>()
    private val coordinators = mutableListOf<CloudSyncCoordinator>()

    private inner class TestDevice(
        val name: String,
        freshInstall: Boolean = true
    ) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dir: File = File(context.cacheDir, "coord_${name}_${UUID.randomUUID()}").also {
            it.mkdirs()
            tempDirs.add(it)
        }
        val db: VelunaDatabase = Room.databaseBuilder(
            context,
            VelunaDatabase::class.java,
            File(dir, "veluna.db").absolutePath
        ).allowMainThreadQueries().build().also { openDbs.add(it) }
        val dao = db.velunaDao()
        val vault = SecretVault(File(dir, "vault.bin"), InMemoryAesGcmVaultCipher())
        val ds = PreferenceDataStoreFactory.create(produceFile = { File(dir, "prefs.preferences_pb") })
        val prefsRepo = UserPrefsRepository(context, vault, ds)
        val repo = VelunaRepository(
            context = context,
            dao = dao,
            prefsRepo = prefsRepo,
            innerTube = InnerTubeClient(OkHttpClient()),
            database = db,
            journalDir = File(dir, "journal")
        )

        init {
            if (freshInstall) {
                runBlocking {
                    prefsRepo.initializeInstallProvenanceIfClean(dao)
                }
            }
        }

        fun createCoordinator(
            customCloudRepo: CloudSyncRepository? = null,
            autoStartSync: Boolean = true,
            awaitStartupProvenance: (suspend () -> Unit)? = null
        ): CloudSyncCoordinator {
            val cloudRepo = customCloudRepo ?: CloudSyncRepository(firestore, auth)
            return CloudSyncCoordinator(
                context = context,
                dao = dao,
                prefsRepo = prefsRepo,
                repository = repo,
                cloudRepo = cloudRepo,
                auth = auth,
                ioDispatcher = Dispatchers.IO,
                stateDir = File(dir, "sync_state"),
                autoStartSync = autoStartSync,
                awaitStartupProvenance = awaitStartupProvenance
            ).also { coordinators.add(it) }
        }
    }

    @After
    override fun tearDownFirebase() {
        coordinators.forEach { runCatching { it.shutdown() } }
        coordinators.clear()
        openDbs.forEach { runCatching { it.close() } }
        openDbs.clear()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
        super.tearDownFirebase()
    }

    @Test
    fun sameTitledPlaylistsAcrossDevices_coexistViaDistinctCloudKeys() : Unit = runBlocking {
        val email = "dup_title_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val devA = TestDevice("A")
        val devB = TestDevice("B")

        val plAId = devA.repo.createPlaylist("Workout", "Morning lift")
        devA.repo.addTrackToPlaylist(
            plAId,
            SongItem(videoId = "trk_a1", title = "Energy", artists = "Artist A")
        )
        val coordA = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) {
            assertThat(coordA.syncNowAndAwait()).isTrue()
        }
        coordA.shutdown()

        // Device B creates its own local playlist also named "Workout" before its first sync
        val plBId = devB.repo.createPlaylist("Workout", "Evening run")
        devB.repo.addTrackToPlaylist(
            plBId,
            SongItem(videoId = "trk_b1", title = "Sprint", artists = "Artist B")
        )
        val coordB = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) {
            assertThat(coordB.syncNowAndAwait()).isTrue()
        }
        coordB.shutdown()

        // Device A syncs again and must receive Device B's distinct playlist alongside its own
        val coordA2 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) {
            assertThat(coordA2.syncNowAndAwait()).isTrue()
        }
        coordA2.shutdown()

        val listA = devA.dao.getPlaylistsOnce()
        val listB = devB.dao.getPlaylistsOnce()
        assertThat(listA).hasSize(2)
        assertThat(listB).hasSize(2)
        assertThat(listA.map { it.cloudKey }.toSet()).isEqualTo(listB.map { it.cloudKey }.toSet())
        assertThat(listA.map { it.description }).containsExactly("Morning lift", "Evening run")
    }

    @Test
    fun playlistTrackMutationsAndReorders_incrementRevisionAndPropagateAcrossDevices() : Unit = runBlocking {
        val email = "pl_rev_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val devA = TestDevice("A")
        val devB = TestDevice("B")

        val plId = devA.repo.createPlaylist("Mix", "Initial")
        val s1 = SongItem(videoId = "trk_1", title = "One", artists = "Artist 1")
        val s2 = SongItem(videoId = "trk_2", title = "Two", artists = "Artist 2")
        val s3 = SongItem(videoId = "trk_3", title = "Three", artists = "Artist 3")
        devA.repo.addTrackToPlaylist(plId, s1)
        devA.repo.addTrackToPlaylist(plId, s2)
        val revBeforeSync = devA.dao.getPlaylistById(plId)!!.syncRevision
        assertThat(revBeforeSync).isEqualTo(3L)

        val coordA = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA.syncNowAndAwait()).isTrue() }

        // Mutate playlist: add s3, reorder index 2 -> 0, remove s2, rename
        devA.repo.addTrackToPlaylist(plId, s3)
        devA.repo.reorderPlaylistTracksByIndex(plId, 2, 0)
        devA.repo.removeTrackFromPlaylist(plId, "trk_2")
        devA.repo.updatePlaylist(plId, "Mix Reordered", "Updated", null)

        val revAfterMutations = devA.dao.getPlaylistById(plId)!!.syncRevision
        assertThat(revAfterMutations).isEqualTo(7L)

        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA.syncNowAndAwait()).isTrue() }
        coordA.shutdown()

        val coordB = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB.syncNowAndAwait()).isTrue() }
        coordB.shutdown()

        val syncedOnB = devB.dao.getPlaylistsOnce().single()
        assertThat(syncedOnB.title).isEqualTo("Mix Reordered")
        assertThat(syncedOnB.syncRevision).isEqualTo(7L)
        assertThat(devB.dao.getPlaylistTracks(syncedOnB.id).map { it.videoId })
            .containsExactly("trk_3", "trk_1")
            .inOrder()
    }

    @Test
    fun offlinePlaylistConflict_newerClientUpdatedAtWinsAndBumpsRevisionMonotonically() : Unit = runBlocking {
        val email = "conflict_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val devA = TestDevice("A")
        val devB = TestDevice("B")

        val plIdA = devA.repo.createPlaylist("Shared", "v1")
        devA.repo.addTrackToPlaylist(plIdA, SongItem(videoId = "t1", title = "T1", artists = "A"))
        val coordA = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA.syncNowAndAwait()).isTrue() }
        coordA.shutdown()

        val coordB = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB.syncNowAndAwait()).isTrue() }
        coordB.shutdown()

        val cloudKey = devA.dao.getPlaylistsOnce().single().cloudKey
        val plIdB = devB.dao.getPlaylistsOnce().single().id

        val baseTs = maxOf(
            devA.dao.getPlaylistById(plIdA)!!.updatedAt,
            devB.dao.getPlaylistById(plIdB)!!.updatedAt
        )

        // Device B edits earlier while offline (and syncs once on reconnect)
        val earlier = baseTs + 1_000L
        devB.dao.updatePlaylist(plIdB, "Device B Earlier", "B won rev", null, earlier)
        val coordB2 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB2.syncNowAndAwait()).isTrue() }
        coordB2.shutdown()

        // Device A edits with a strictly newer timestamp while still at equal or lower revision
        val newer = baseTs + 5_000L
        devA.dao.updatePlaylist(plIdA, "Device A Newer Winner", "Newer timestamp", null, newer)
        val coordA2 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA2.syncNowAndAwait()).isTrue() }
        coordA2.shutdown()

        // Device B converges cleanly to Device A's newer edit
        val coordB3 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB3.syncNowAndAwait()).isTrue() }
        coordB3.shutdown()

        val finalA = devA.dao.getPlaylistsOnce().single { it.cloudKey == cloudKey }
        val finalB = devB.dao.getPlaylistsOnce().single { it.cloudKey == cloudKey }
        assertThat(finalA.title).isEqualTo("Device A Newer Winner")
        assertThat(finalB.title).isEqualTo("Device A Newer Winner")
        assertThat(finalB.syncRevision).isAtLeast(finalA.syncRevision)
    }

    @Test
    fun tasteSeedTimestampPreservedAndDeletionTombstonesPropagateWithoutResurrection() : Unit = runBlocking {
        val email = "seeds_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val devA = TestDevice("A")
        val devB = TestDevice("B")

        val realPastTs = System.currentTimeMillis() - 60_000L
        val artistSeed = TasteSeed(id = "art_daft", name = "Daft Punk", updatedAtMs = realPastTs)
        val legacyZeroSeed = TasteSeed(id = "art_zero", name = "Justice", updatedAtMs = 0L)

        devA.prefsRepo.saveTasteProfile(
            TasteProfile(
                onboardingCompleted = true,
                selectedGenres = listOf("Electronic"),
                seedArtists = listOf(artistSeed, legacyZeroSeed)
            ),
            touchProfileTimestamp = true
        )

        val coordA = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA.syncNowAndAwait()).isTrue() }
        coordA.shutdown()

        val localAfterStamp = devA.prefsRepo.tasteProfileFlow.first()
        val daftLocal = localAfterStamp.seedArtists.first { it.name == "Daft Punk" }
        val justiceLocal = localAfterStamp.seedArtists.first { it.name == "Justice" }
        assertThat(daftLocal.updatedAtMs).isEqualTo(realPastTs)
        assertThat(justiceLocal.updatedAtMs).isGreaterThan(0L)

        // Device B pulls seeds cleanly
        val coordB = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB.syncNowAndAwait()).isTrue() }
        coordB.shutdown()
        assertThat(devB.prefsRepo.tasteProfileFlow.first().seedArtists.map { it.name })
            .containsExactly("Daft Punk", "Justice")

        // Delete "Justice" locally on Device B via baseline diff (offline removal) and sync
        devB.prefsRepo.updateTasteProfile(touchProfileTimestamp = true) { profile ->
            profile.copy(seedArtists = profile.seedArtists.filterNot { it.name == "Justice" })
        }
        val coordB2 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB2.syncNowAndAwait()).isTrue() }
        coordB2.shutdown()

        // Device A syncs and must drop "Justice" without resurrecting it
        val coordA2 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA2.syncNowAndAwait()).isTrue() }
        coordA2.shutdown()

        assertThat(devA.prefsRepo.tasteProfileFlow.first().seedArtists.map { it.name })
            .containsExactly("Daft Punk")
    }

    @Test
    fun profilePrefsBidirectionalLww_syncsCrossDevicePrefsWhileLeavingDeviceOnlyPrefsUntouched() : Unit = runBlocking {
        val email = "prefs_lww_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val devA = TestDevice("A")
        val devB = TestDevice("B")

        devA.prefsRepo.saveTasteProfile(TasteProfile(onboardingCompleted = true, selectedGenres = listOf("Jazz")), true)
        devA.prefsRepo.setThemeMode(ThemeMode.LIGHT)
        devA.prefsRepo.setAudioQuality(AudioQuality.LOW)
        devA.prefsRepo.setPlaybackSpeed(1.5f) // device-only

        val coordA = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA.syncNowAndAwait()).isTrue() }
        coordA.shutdown()

        // Device B starts with onboardingCompleted = false and localPrefsTs = 0 so it adopts initial cloud profile
        devB.prefsRepo.setPlaybackSpeed(0.75f) // device-only
        devB.prefsRepo.setClassicPlayerLayout(true) // device-only
        val coordB = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB.syncNowAndAwait()).isTrue() }
        coordB.shutdown()

        val bInitial = devB.prefsRepo.prefsFlow.first()
        assertThat(bInitial.themeMode).isEqualTo(ThemeMode.LIGHT)
        assertThat(bInitial.audioQuality).isEqualTo(AudioQuality.LOW)
        assertThat(bInitial.playbackSpeed).isEqualTo(0.75f)
        assertThat(bInitial.classicPlayerLayout).isTrue()

        // Device B is now established; update synced prefs on B with newer timestamp
        devB.prefsRepo.setThemePreset(VelunaThemePreset.TOKYO_NIGHT)
        devB.prefsRepo.setThemeMode(ThemeMode.DARK)
        devB.prefsRepo.setAudioQuality(AudioQuality.HIGH)
        devB.prefsRepo.setAutoplay(false)
        val coordB2 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB2.syncNowAndAwait()).isTrue() }
        coordB2.shutdown()

        // Device A syncs without local changes -> adopts B's newer prefs while preserving A's playbackSpeed 1.5f
        val coordA2 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA2.syncNowAndAwait()).isTrue() }
        coordA2.shutdown()

        val aFinal = devA.prefsRepo.prefsFlow.first()
        assertThat(aFinal.themePreset).isEqualTo(VelunaThemePreset.TOKYO_NIGHT)
        assertThat(aFinal.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(aFinal.audioQuality).isEqualTo(AudioQuality.HIGH)
        assertThat(aFinal.autoplay).isFalse()
        assertThat(aFinal.playbackSpeed).isEqualTo(1.5f)
        assertThat(aFinal.classicPlayerLayout).isFalse()
    }

    @Test
    fun offlineMutationsAndDeletionsSurviveRestartAndTrackUnsyncedCountAccurately() : Unit = runBlocking {
        val email = "restart_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)

        val dev = TestDevice("Restart")
        val plKeepId = dev.repo.createPlaylist("Keep", "")
        val plDeleteId = dev.repo.createPlaylist("Remove Offline", "")
        val songLike = SongItem(videoId = "trk_like", title = "Liked", artists = "Artist")
        dev.repo.toggleLike(songLike)
        dev.repo.blockArtist("Noisy Act")

        // Initial sync commits watermarks and baselines
        val coord1 = dev.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coord1.syncNowAndAwait()).isTrue() }
        assertThat(coord1.countUnsyncedChangesFor(uid)).isEqualTo(0)
        coord1.shutdown()

        // Simulate app offline mutations before restart while watermarks > 0
        dev.repo.addTrackToPlaylist(plKeepId, SongItem(videoId = "trk_added", title = "Added", artists = "Artist"))
        dev.repo.deletePlaylist(plDeleteId)
        dev.repo.toggleLike(songLike) // unlike
        dev.repo.unblockArtist("Noisy Act")

        // Recreate coordinator against existing DB & DataStore (simulated cold restart)
        val coord2 = dev.createCoordinator()
        val pendingBeforeSync = coord2.countUnsyncedChangesFor(uid)
        assertThat(pendingBeforeSync).isGreaterThan(0)

        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coord2.syncNowAndAwait()).isTrue() }
        assertThat(coord2.countUnsyncedChangesFor(uid)).isEqualTo(0)
        coord2.shutdown()

        // Verify cloud actually reflects offline deletions and updates
        val cloudSnap = CloudSyncRepository(firestore, auth).fetchAccountSnapshot()
        assertThat(cloudSnap.likedSongs.first { it.videoId == "trk_like" }.deleted).isTrue()
        assertThat(cloudSnap.hiddenArtists.none { !it.deleted }).isTrue()
        val deletedCloudPl = cloudSnap.playlists.firstOrNull { it.playlist.title == "Remove Offline" }
        assertThat(deletedCloudPl == null || deletedCloudPl.playlist.deleted).isTrue()
        val keptCloudPl = cloudSnap.playlists.single { !it.playlist.deleted }
        assertThat(keptCloudPl.subcollectionTracks.map { it.videoId }).containsExactly("trk_added")
    }

    @Test
    fun signOutVsAccountDeletion_preservesDeviceDataOnSignOutAndWipesCredentialsOnDeletion(): Unit = runBlocking {
        val email = "wipe_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)

        val dev = TestDevice("WipeCheck")
        // Seed synced tables
        val plId = dev.repo.createPlaylist("Synced List", "Should clear")
        dev.repo.addTrackToPlaylist(plId, SongItem(videoId = "trk_pl", title = "T", artists = "A"))
        dev.repo.toggleLike(SongItem(videoId = "trk_s", title = "Liked", artists = "A"))
        dev.repo.blockArtist("Blocked Band")

        // Seed device-only tables & SecretVault scrobble credentials
        dev.dao.insertListen(ListenEntity(videoId = "trk_l", playedAt = System.currentTimeMillis(), secs = 200))
        dev.dao.upsertListenSong(ListenSongEntity("trk_l", """{"videoId":"trk_l","title":"Listen","artists":"A"}"""))
        dev.dao.insertSavedAlbum(SavedAlbumEntity("alb_1", "Saved Album", "Sub", null))
        dev.dao.insertFollowedArtist(FollowedArtistEntity("art_1", "Followed Artist", null, "1M"))
        dev.dao.upsertShortcut(ShortcutEntity("sc_1", BrowseKind.ALBUM.name, "Shortcut", null, null, 0))
        dev.dao.upsertSearchHistory(SearchHistoryEntity("daft punk"))
        dev.dao.upsertDownload(
            DownloadedTrackEntity("dl_1", "/tmp/dl_1.opus", "opus", 128, 1024L, """{"videoId":"dl_1","title":"DL","artists":"A"}""")
        )
        dev.dao.upsertLocalTracks(listOf(LocalTrackEntity("content://local/1", "Local", "Artist", "Album", "alb")))
        dev.dao.upsertLyricsCache(LyricsCacheEntity("trk_l", """{"plain":"hello"}"""))
        dev.dao.insertPendingScrobble(PendingScrobbleEntity(service = ScrobbleServices.LASTFM, payloadJson = "{}", startedAtSecs = 100L))
        dev.prefsRepo.setScrobblingConfig(
            enabled = true,
            lastFmUser = "alice",
            lastFmKey = "api_key_keep",
            lastFmSecret = "api_secret_keep",
            lastFmSession = "session_keep",
            listenBrainzToken = "lb_token_keep"
        )

        val coord = dev.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coord.syncNowAndAwait()).isTrue() }

        // Mutate locally so countUnsyncedChangesFor(uid) > 0, then signOut() flushes cleanly and wipes synced tables
        dev.repo.createPlaylist("Unsynced Draft", "")
        assertThat(coord.countUnsyncedChangesFor(uid)).isGreaterThan(0)
        coord.signOut()
        val deadline = System.currentTimeMillis() + 12_000L
        while ((coord.authState.value.isSignedIn || coord.authState.value.isAuthBusy) && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50L)
        }
        assertThat(coord.authState.value.isSignedIn).isFalse()
        assertThat(coord.authState.value.isAuthBusy).isFalse()
        coord.shutdown()

        // Re-sign in on another device and confirm signOut() flushed "Unsynced Draft" before wiping local state
        signInTestUser(email)
        val cloudAfterSignOut = CloudSyncRepository(firestore, auth).fetchAccountSnapshot()
        assertThat(cloudAfterSignOut.playlists.map { it.playlist.title }).contains("Unsynced Draft")

        // Ordinary sign-out clears ONLY synced tables + cloud watermarks, preserving device history & scrobble secrets
        assertThat(dev.dao.getPlaylistsOnce()).isEmpty()
        assertThat(dev.dao.getLikedSongsOnce()).isEmpty()
        assertThat(dev.dao.getBlockedArtistsOnce()).isEmpty()
        assertThat(dev.dao.getAllTombstones()).isEmpty()
        assertThat(dev.dao.getAllSyncBaselinesForUser(uid)).isEmpty()

        assertThat(dev.dao.getListensSince(0L)).hasSize(1)
        assertThat(dev.dao.getSavedAlbumsOnce()).hasSize(1)
        assertThat(dev.dao.getFollowedArtistsOnce()).hasSize(1)
        assertThat(dev.dao.getShortcutsOnce()).hasSize(1)
        assertThat(dev.dao.getSearchHistoryOnce(10)).hasSize(1)
        assertThat(dev.dao.getDownload("dl_1")).isNotNull()
        assertThat(dev.dao.getLocalTracksOnce()).hasSize(1)
        assertThat(dev.dao.countPendingScrobbles()).isEqualTo(1)
        val afterSignOutPrefs = dev.prefsRepo.prefsFlow.first()
        assertThat(afterSignOutPrefs.scrobblingEnabled).isTrue()
        assertThat(afterSignOutPrefs.lastFmSessionKey).isEqualTo("session_keep")
        assertThat(afterSignOutPrefs.listenBrainzToken).isEqualTo("lb_token_keep")

        // Permanent account deletion clears all account tables & SecretVault while keeping downloads/local/lyrics_cache
        dev.dao.clearAccountData()
        dev.prefsRepo.resetUserKeys()

        assertThat(dev.dao.getListensSince(0L)).isEmpty()
        assertThat(dev.dao.getSavedAlbumsOnce()).isEmpty()
        assertThat(dev.dao.getFollowedArtistsOnce()).isEmpty()
        assertThat(dev.dao.getShortcutsOnce()).isEmpty()
        assertThat(dev.dao.getSearchHistoryOnce(10)).isEmpty()
        assertThat(dev.dao.countPendingScrobbles()).isEqualTo(0)
        val afterDeletePrefs = dev.prefsRepo.prefsFlow.first()
        assertThat(afterDeletePrefs.scrobblingEnabled).isFalse()
        assertThat(afterDeletePrefs.lastFmSessionKey).isEmpty()
        assertThat(afterDeletePrefs.listenBrainzToken).isEmpty()

        assertThat(dev.dao.getDownload("dl_1")).isNotNull()
        assertThat(dev.dao.getLocalTracksOnce()).hasSize(1)
        assertThat(dev.dao.getLyricsCache("trk_l")).isNotNull()
    }

    @Test
    fun threeWayPlaylistMerge_preservesConcurrentOfflineTrackAdditionsAndRemovalsIdempotently(): Unit = runBlocking {
        val email = "threeway_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val devA = TestDevice("3WayA")
        val devB = TestDevice("3WayB")

        val plIdA = devA.repo.createPlaylist("Collab Mix", "Base")
        val t1 = SongItem(videoId = "trk_1", title = "Track 1", artists = "Artist")
        val t2 = SongItem(videoId = "trk_2", title = "Track 2", artists = "Artist")
        val tA = SongItem(videoId = "trk_a_add", title = "Added by A", artists = "Artist")
        val tB = SongItem(videoId = "trk_b_add", title = "Added by B", artists = "Artist")
        devA.repo.addTrackToPlaylist(plIdA, t1)
        devA.repo.addTrackToPlaylist(plIdA, t2)

        val coordA1 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA1.syncNowAndAwait()).isTrue() }
        coordA1.shutdown()

        val coordB1 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB1.syncNowAndAwait()).isTrue() }
        coordB1.shutdown()
        val plIdB = devB.dao.getPlaylistsOnce().single().id

        // Both devices edit offline independently:
        // Device A adds tA; Device B removes t2 and adds tB
        devA.repo.addTrackToPlaylist(plIdA, tA)
        devB.repo.removeTrackFromPlaylist(plIdB, "trk_2")
        devB.repo.addTrackToPlaylist(plIdB, tB)

        // Device B syncs first -> cloud has [trk_1, trk_b_add]
        val coordB2 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB2.syncNowAndAwait()).isTrue() }
        coordB2.shutdown()

        // Device A syncs while dirty locally and dirty remotely -> 3-way merge retains trk_1, removes trk_2, keeps BOTH trk_b_add and trk_a_add!
        val coordA2 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA2.syncNowAndAwait()).isTrue() }
        coordA2.shutdown()

        // Device B syncs again -> both converge idempotently with zero duplicates
        val coordB3 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB3.syncNowAndAwait()).isTrue() }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB3.syncNowAndAwait()).isTrue() }
        coordB3.shutdown()

        val idsA = devA.dao.getPlaylistTracks(plIdA).map { it.videoId }
        val idsB = devB.dao.getPlaylistTracks(plIdB).map { it.videoId }
        assertThat(idsA).containsExactly("trk_1", "trk_b_add", "trk_a_add").inOrder()
        assertThat(idsB).isEqualTo(idsA)
    }

    @Test
    fun playlistCoverLifecycle_preservesRemoteHttpsCoverWithLocalFileCover_andPropagatesRemoval(): Unit = runBlocking {
        val email = "cover_life_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)

        val devA = TestDevice("CovA")
        val devB = TestDevice("CovB")
        val coversDir = File(devB.context.filesDir, "playlist_covers").apply { mkdirs() }
        val bmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        val baos = java.io.ByteArrayOutputStream().also {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            bmp.recycle()
        }
        val localCoverFile = File(coversDir, "devb_cover_${UUID.randomUUID()}.png").apply {
            writeBytes(baos.toByteArray())
        }
        val localFileUri = android.net.Uri.fromFile(localCoverFile).toString()

        val plIdA = devA.repo.createPlaylist("Cover Sync", "With art")
        devA.repo.updatePlaylist(plIdA, "Cover Sync", "With art", "https://cdn.example.com/shared.jpg")
        val coordA1 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA1.syncNowAndAwait()).isTrue() }
        coordA1.shutdown()

        val coordB1 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB1.syncNowAndAwait()).isTrue() }
        val plB = devB.dao.getPlaylistsOnce().single()
        assertThat(plB.remoteCoverUri).isEqualTo("https://cdn.example.com/shared.jpg")

        // Attach local file cover on Device B and sync -> remoteCoverUri in cloud stays intact and unsynced count reaches 0
        devB.repo.updatePlaylist(plB.id, plB.title, plB.description, localFileUri)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB1.syncNowAndAwait()).isTrue() }
        assertThat(coordB1.countUnsyncedChangesFor(uid)).isEqualTo(0)
        val plBAfterLocalCover = devB.dao.getPlaylistById(plB.id)!!
        assertThat(plBAfterLocalCover.coverUri).isEqualTo(localFileUri)
        assertThat(plBAfterLocalCover.remoteCoverUri).isEqualTo("https://cdn.example.com/shared.jpg")

        val cloudAfterLocalCover = CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists.single().playlist
        assertThat(cloudAfterLocalCover.coverUri).isEqualTo("https://cdn.example.com/shared.jpg")
        coordB1.shutdown()

        // Device A clears cover -> coverRemoved propagates to Device B and removes its coverUri
        devA.repo.updatePlaylist(plIdA, "Cover Sync", "Removed art", null)
        val coordA2 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA2.syncNowAndAwait()).isTrue() }
        coordA2.shutdown()

        val coordB2 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB2.syncNowAndAwait()).isTrue() }
        coordB2.shutdown()

        val plBAfterRemove = devB.dao.getPlaylistById(plB.id)!!
        assertThat(plBAfterRemove.coverUri).isNull()
        assertThat(plBAfterRemove.remoteCoverUri).isNull()
        assertThat(plBAfterRemove.coverRemoved).isTrue()
        localCoverFile.delete()
    }

    @Test
    fun accountTransition_wipesPreviousAccountSyncedDataBeforeSyncingNewAccount(): Unit = runBlocking {
        val emailA = "acc_a_${UUID.randomUUID()}@test.com"
        val emailB = "acc_b_${UUID.randomUUID()}@test.com"
        signInTestUser(emailA)

        val dev = TestDevice("SwitchAcc")
        val plId = dev.repo.createPlaylist("Alice Private List", "Only Alice")
        dev.repo.addTrackToPlaylist(plId, SongItem(videoId = "alice_trk", title = "Alice Song", artists = "Alice"))
        dev.repo.toggleLike(SongItem(videoId = "alice_like", title = "Alice Like", artists = "Alice"))
        dev.dao.insertSavedAlbum(SavedAlbumEntity("device_alb", "Device Album", "Keep", null))

        val coordA = dev.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA.syncNowAndAwait()).isTrue() }
        coordA.shutdown()

        // Sign directly into Account B on the same local database without running explicit signOut() first
        val uidB = signInTestUser(emailB)
        val coordB = dev.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordB.syncNowAndAwait()).isTrue() }
        coordB.shutdown()

        assertThat(dev.prefsRepo.getActiveCloudUid()).isEqualTo(uidB)
        assertThat(dev.dao.getPlaylistsOnce()).isEmpty()
        assertThat(dev.dao.getLikedSongsOnce()).isEmpty()
        assertThat(dev.dao.getSavedAlbumsOnce().map { it.albumId }).containsExactly("device_alb")

        // Account B's cloud must NEVER contain Account A's playlist or liked songs
        val snapB = CloudSyncRepository(firestore, auth).fetchAccountSnapshot()
        assertThat(snapB.playlists).isEmpty()
        assertThat(snapB.likedSongs).isEmpty()

        // Account A's cloud data remains completely intact
        signInTestUser(emailA)
        val snapA = CloudSyncRepository(firestore, auth).fetchAccountSnapshot()
        assertThat(snapA.playlists.map { it.playlist.title }).containsExactly("Alice Private List")
        assertThat(snapA.likedSongs.map { it.videoId }).containsExactly("alice_like")
    }

    @Test
    fun staleOfflineClient_forcesFullFetchAfterCloudCompaction_andPrunesUnchangedDeletedItems(): Unit = runBlocking {
        val email = "stale_compact_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)

        val devA = TestDevice("StaleA")
        val plCompactedId = devA.repo.createPlaylist("Compacted Away", "Should prune")
        val plEditedId = devA.repo.createPlaylist("Edited Offline", "Should keep")
        val sCompact = SongItem(videoId = "trk_compact", title = "Compact Like", artists = "A")
        val sKeep = SongItem(videoId = "trk_keep", title = "Keep Like", artists = "B")
        devA.repo.toggleLike(sCompact)
        devA.repo.toggleLike(sKeep)
        devA.repo.blockArtist("Compacted Band")
        devA.prefsRepo.saveTasteProfile(
            TasteProfile(
                onboardingCompleted = true,
                selectedGenres = listOf("Rock"),
                seedArtists = listOf(
                    TasteSeed(id = "art_keep", name = "Kept Seed", updatedAtMs = System.currentTimeMillis() - 10_000L),
                    TasteSeed(id = "art_gone", name = "Gone Seed", updatedAtMs = System.currentTimeMillis() - 10_000L)
                )
            ),
            touchProfileTimestamp = true
        )

        val coordA1 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA1.syncNowAndAwait()).isTrue() }
        coordA1.shutdown()

        val compactedPlKey = devA.dao.getPlaylistById(plCompactedId)!!.cloudKey
        val lastSuccessA = devA.prefsRepo.getLastSuccessfulSyncAtMs(uid)
        assertThat(lastSuccessA).isGreaterThan(0L)

        // Cloud side deletes & compacts tombstones older than cutoff (advance compaction boundary past lastSuccessA within skew)
        val cloudRepo = CloudSyncRepository(firestore, auth)
        val oldTombTs = lastSuccessA - 35L * 24L * 3600L * 1000L
        cloudRepo.deletePlaylist(compactedPlKey, clientUpdatedAt = oldTombTs, syncRevision = 2L)
        cloudRepo.syncLikedSong(sCompact, liked = false, likedAtMs = oldTombTs, clientUpdatedAtMs = oldTombTs)
        cloudRepo.syncHiddenArtist("Compacted Band", null, blocked = false, blockedAtMs = oldTombTs, clientUpdatedAtMs = oldTombTs)
        cloudRepo.writeTasteSeedTombstone("art_gone", clientUpdatedAt = oldTombTs)
        val compactWatermark = lastSuccessA + 60_000L
        cloudRepo.purgeExpiredCloudTombstones(uid, compactWatermark)

        // Meanwhile offline Device A modifies "Edited Offline" legitimately before reconnecting
        devA.repo.updatePlaylist(plEditedId, "Edited While Offline", "Updated", null)

        val coordA2 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordA2.syncNowAndAwait()).isTrue() }
        coordA2.shutdown()

        // Compaction-pruned items are removed locally and NOT resurrected in Firestore
        assertThat(devA.dao.getPlaylistsOnce().map { it.title }).containsExactly("Edited While Offline")
        assertThat(devA.dao.getLikedSongsOnce().map { it.videoId }).containsExactly("trk_keep")
        assertThat(devA.dao.getBlockedArtistsOnce()).isEmpty()
        assertThat(devA.prefsRepo.tasteProfileFlow.first().seedArtists.map { it.name }).containsExactly("Kept Seed")

        val finalCloud = cloudRepo.fetchAccountSnapshot()
        assertThat(finalCloud.playlists.filter { !it.playlist.deleted }.map { it.playlist.title })
            .containsExactly("Edited While Offline")
        assertThat(finalCloud.likedSongs.filter { !it.deleted }.map { it.videoId }).containsExactly("trk_keep")
    }

    @Test
    fun signOutTimeout_surfacesConfirmCount_supportsDismissAndConfirmDiscard(): Unit = runBlocking {
        val email = "signout_to_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val dev = TestDevice("SignOutTimeout")
        dev.repo.createPlaylist("Initial Synced", "")
        val coord = dev.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coord.syncNowAndAwait()).isTrue() }

        // Create an offline change that would be lost, then force immediate flush timeout
        dev.repo.createPlaylist("Unsynced Pending", "")
        coord.signOutFlushTimeoutMs = 0L
        coord.signOut()

        val deadline1 = System.currentTimeMillis() + 5_000L
        while (coord.authState.value.pendingSignOutConfirmCount == null && System.currentTimeMillis() < deadline1) {
            kotlinx.coroutines.delay(25L)
        }
        assertThat(coord.authState.value.pendingSignOutConfirmCount).isAtLeast(1)
        assertThat(coord.authState.value.isSignedIn).isTrue()
        assertThat(dev.dao.getPlaylistsOnce().map { it.title }).contains("Unsynced Pending")

        // Dismiss preserves session, local unsynced playlist, and clears pending confirmation prompt
        coord.dismissSignOutConfirm()
        assertThat(coord.authState.value.pendingSignOutConfirmCount).isNull()
        assertThat(coord.authState.value.isSignedIn).isTrue()
        assertThat(dev.dao.getPlaylistsOnce().map { it.title }).contains("Unsynced Pending")

        // Confirm discard completes sign-out and wipes synced tables cleanly
        coord.confirmSignOutDiscard()
        val deadline2 = System.currentTimeMillis() + 8_000L
        while ((coord.authState.value.isSignedIn || coord.authState.value.isAuthBusy) && System.currentTimeMillis() < deadline2) {
            kotlinx.coroutines.delay(25L)
        }
        assertThat(coord.authState.value.isSignedIn).isFalse()
        assertThat(dev.dao.getPlaylistsOnce()).isEmpty()
        coord.shutdown()
    }

    @Test
    fun playlistMerge_offlineAddThenRemoveVsConcurrentAddition_preservesIntentionalRemovalDeterministically(): Unit = runBlocking {
        val email = "add_rem_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val devA = TestDevice("AddRemA")
        val devB = TestDevice("AddRemB")

        val plIdA = devA.repo.createPlaylist("Causal Mix", "Base")
        val t1 = SongItem(videoId = "trk_base", title = "Base Song", artists = "Artist")
        val tX = SongItem(videoId = "trk_x", title = "Disputed Song", artists = "Artist")
        devA.repo.addTrackToPlaylist(plIdA, t1)

        val cA1 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA1.syncNowAndAwait()).isTrue() }
        cA1.shutdown()

        val cB1 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB1.syncNowAndAwait()).isTrue() }
        cB1.shutdown()
        val plIdB = devB.dao.getPlaylistsOnce().single().id

        // Device B adds tX offline FIRST
        devB.repo.addTrackToPlaylist(plIdB, tX)
        kotlinx.coroutines.delay(10L)

        // Device A independently adds tX and then intentionally REMOVES tX offline AFTER B's addition
        devA.repo.addTrackToPlaylist(plIdA, tX)
        kotlinx.coroutines.delay(10L)
        devA.repo.removeTrackFromPlaylist(plIdA, "trk_x")

        // B syncs first -> cloud has [trk_base, trk_x]
        val cB2 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB2.syncNowAndAwait()).isTrue() }
        cB2.shutdown()

        // A reconnects and syncs -> A's newer causal removal tombstone of trk_x must win, NOT be resurrected!
        val cA2 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA2.syncNowAndAwait()).isTrue() }
        cA2.shutdown()

        // B syncs again -> both converge without trk_x
        val cB3 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB3.syncNowAndAwait()).isTrue() }
        cB3.shutdown()

        assertThat(devA.dao.getPlaylistTracks(plIdA).map { it.videoId }).containsExactly("trk_base")
        assertThat(devB.dao.getPlaylistTracks(plIdB).map { it.videoId }).containsExactly("trk_base")
    }

    @Test
    fun playlistMerge_removeThenIntentionalReAdd_andConcurrentReordersConvergeAcrossDevices(): Unit = runBlocking {
        val email = "readd_reorder_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val devA = TestDevice("ReaddA")
        val devB = TestDevice("ReaddB")

        val plIdA = devA.repo.createPlaylist("Order & Readd", "Base")
        val s1 = SongItem("trk_1", "One", "A")
        val s2 = SongItem("trk_2", "Two", "A")
        val s3 = SongItem("trk_3", "Three", "A")
        val s4 = SongItem("trk_4", "Four", "A")
        devA.repo.addTrackToPlaylist(plIdA, s1)
        devA.repo.addTrackToPlaylist(plIdA, s2)
        devA.repo.addTrackToPlaylist(plIdA, s3)

        val cA1 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA1.syncNowAndAwait()).isTrue() }
        cA1.shutdown()

        val cB1 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB1.syncNowAndAwait()).isTrue() }
        cB1.shutdown()
        val plIdB = devB.dao.getPlaylistsOnce().single().id

        // Device A removes s2 and reorders remaining tracks [s3, s1] -> syncs
        devA.repo.removeTrackFromPlaylist(plIdA, "trk_2")
        devA.repo.reorderPlaylistTracksByIndex(plIdA, 1, 0) // [s3, s1]
        val cA2 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA2.syncNowAndAwait()).isTrue() }
        cA2.shutdown()

        // Device B picks up the removal and reorder, then intentionally re-adds s2 & adds s4 later
        val cB2 = devB.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB2.syncNowAndAwait()).isTrue() }
        assertThat(devB.dao.getPlaylistTracks(plIdB).map { it.videoId }).containsExactly("trk_3", "trk_1").inOrder()
        kotlinx.coroutines.delay(10L)
        devB.repo.addTrackToPlaylist(plIdB, s2)
        devB.repo.addTrackToPlaylist(plIdB, s4)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB2.syncNowAndAwait()).isTrue() }
        cB2.shutdown()

        // Device A syncs repeatedly -> converges idempotently without duplicates
        val cA3 = devA.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) {
            assertThat(cA3.syncNowAndAwait()).isTrue()
            assertThat(cA3.syncNowAndAwait()).isTrue()
        }
        cA3.shutdown()

        val finalA = devA.dao.getPlaylistTracks(plIdA).map { it.videoId }
        val finalB = devB.dao.getPlaylistTracks(plIdB).map { it.videoId }
        assertThat(finalA).hasSize(4)
        assertThat(finalA.toSet()).containsExactly("trk_1", "trk_2", "trk_3", "trk_4")
        assertThat(finalB).isEqualTo(finalA)
    }

    @Test
    fun playlistThreeWayMerger_sameTimestampDifferentDeviceIdAndOrderConvergence_arePerspectiveIndependent() {
        val t1 = SongItem("trk_1", "One", "A")
        val t2 = SongItem("trk_2", "Two", "A")
        val t3 = SongItem("trk_3", "Three", "A")
        val base = PlaylistBaselineSnapshot(
            title = "Shared",
            description = "Orig",
            remoteCoverUri = null,
            coverRemoved = false,
            trackIds = listOf("trk_1"),
            trackOps = mapOf("trk_1" to BaselineTrackOp(false, SyncMutationStamp(1_000L, 1L, "dev_a", "op_0")))
        )
        val tsSame = 5_000L
        val titleA = SyncMutationStamp(tsSame, 2L, "dev_alpha", "op_a")
        val titleB = SyncMutationStamp(tsSame, 2L, "dev_beta", "op_b")
        val addA = SyncMutationStamp(tsSame, 3L, "dev_alpha", "op_add_a")
        val addB = SyncMutationStamp(tsSame, 3L, "dev_beta", "op_add_b")

        val fromPerspectiveAlpha = PlaylistThreeWayMerger.merge(
            baseline = base,
            localTitle = "Title From Alpha",
            localDescription = "Orig",
            localRemoteCoverUri = null,
            localCoverRemoved = false,
            localTracks = listOf(t1, t2),
            localUpdatedAt = tsSame,
            remoteTitle = "Title From Beta",
            remoteDescription = "Orig",
            remoteCoverUri = "https://cdn.example.com/beta.jpg",
            remoteCoverRemoved = false,
            remoteTracks = listOf(t1, t3),
            remoteUpdatedAt = tsSame,
            localDeviceId = "dev_alpha",
            localFieldStamps = mapOf(PlaylistThreeWayMerger.FIELD_TITLE to titleA),
            localTrackOps = mapOf("trk_2" to BaselineTrackOp(false, addA)),
            remoteDeviceId = "dev_beta",
            remoteFieldStamps = mapOf(
                PlaylistThreeWayMerger.FIELD_TITLE to titleB,
                PlaylistThreeWayMerger.FIELD_COVER to titleB
            ),
            remoteTrackAddStamps = mapOf("trk_3" to addB)
        )

        val fromPerspectiveBeta = PlaylistThreeWayMerger.merge(
            baseline = base,
            localTitle = "Title From Beta",
            localDescription = "Orig",
            localRemoteCoverUri = "https://cdn.example.com/beta.jpg",
            localCoverRemoved = false,
            localTracks = listOf(t1, t3),
            localUpdatedAt = tsSame,
            remoteTitle = "Title From Alpha",
            remoteDescription = "Orig",
            remoteCoverUri = null,
            remoteCoverRemoved = false,
            remoteTracks = listOf(t1, t2),
            remoteUpdatedAt = tsSame,
            localDeviceId = "dev_beta",
            localFieldStamps = mapOf(
                PlaylistThreeWayMerger.FIELD_TITLE to titleB,
                PlaylistThreeWayMerger.FIELD_COVER to titleB
            ),
            localTrackOps = mapOf("trk_3" to BaselineTrackOp(false, addB)),
            remoteDeviceId = "dev_alpha",
            remoteFieldStamps = mapOf(PlaylistThreeWayMerger.FIELD_TITLE to titleA),
            remoteTrackAddStamps = mapOf("trk_2" to addA)
        )

        assertThat(fromPerspectiveAlpha.title).isEqualTo(fromPerspectiveBeta.title)
        assertThat(fromPerspectiveAlpha.title).isEqualTo("Title From Beta")
        assertThat(fromPerspectiveAlpha.remoteCoverUri).isEqualTo(fromPerspectiveBeta.remoteCoverUri)
        assertThat(fromPerspectiveAlpha.tracks.map { it.videoId })
            .isEqualTo(fromPerspectiveBeta.tracks.map { it.videoId })
        assertThat(fromPerspectiveAlpha.winningPlaylistStamp).isEqualTo(fromPerspectiveBeta.winningPlaylistStamp)

        // Duplicate identical op delivery never creates duplicate tracks
        val dupOpCheck = PlaylistThreeWayMerger.merge(
            baseline = base,
            localTitle = "Title From Beta",
            localDescription = "Orig",
            localRemoteCoverUri = null,
            localCoverRemoved = false,
            localTracks = listOf(t1, t2),
            localUpdatedAt = tsSame,
            remoteTitle = "Title From Beta",
            remoteDescription = "Orig",
            remoteCoverUri = null,
            remoteCoverRemoved = false,
            remoteTracks = listOf(t1, t2),
            remoteUpdatedAt = tsSame,
            localDeviceId = "dev_beta",
            localTrackOps = mapOf("trk_2" to BaselineTrackOp(false, addB)),
            remoteDeviceId = "dev_beta",
            remoteTrackAddStamps = mapOf("trk_2" to addB)
        )
        assertThat(dupOpCheck.tracks.map { it.videoId }).containsExactly("trk_1", "trk_2")
        assertThat(dupOpCheck.needsLocalUpdate).isFalse()
    }

    @Test
    fun playlistMerge_legacyV4BaselineWithoutSnapshotJson_migratesAndMergesSafely(): Unit = runBlocking {
        val email = "leg_base_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)

        val dev = TestDevice("LegacyBase")
        val plId = dev.dao.createPlaylistWithTracks(
            playlist = com.example.data.local.LocalPlaylistEntity(
                cloudKey = "pl_local_42",
                title = "Legacy Album Mix",
                description = "From v4",
                createdAt = 1700000000000L,
                updatedAt = 1700000001000L,
                syncRevision = 1L
            ),
            tracks = listOf(
                com.example.data.local.LocalPlaylistTrackEntity(
                    playlistId = 0,
                    videoId = "trk_leg_1",
                    songJson = dev.repo.encodeSong(SongItem("trk_leg_1", "Leg 1", "Artist")),
                    position = 0,
                    addedAt = 1700000000000L
                )
            )
        )
        // Legacy baseline row has empty snapshotJson
        dev.dao.upsertSyncBaseline(
            com.example.data.local.SyncBaselineEntity(
                userId = uid,
                entityType = SyncEntityTypes.PLAYLIST,
                entityKey = "pl_local_42",
                revision = 1L,
                signature = "old_sig",
                clientUpdatedAt = 1700000001000L,
                deleted = false,
                updatedAt = 1700000001000L,
                snapshotJson = ""
            )
        )
        dev.prefsRepo.setActiveCloudUid(uid)

        val coord = dev.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coord.syncNowAndAwait()).isTrue() }
        coord.shutdown()

        val upgradedBase = dev.dao.getAllSyncBaselinesForUser(uid)
            .first { it.entityType == SyncEntityTypes.PLAYLIST && it.entityKey == "pl_local_42" }
        assertThat(upgradedBase.snapshotJson).isNotEmpty()
        assertThat(dev.dao.getPlaylistById(plId)?.cloudKey).isEqualTo("pl_local_42")
        assertThat(dev.dao.getPlaylistTracks(plId).map { it.videoId }).containsExactly("trk_leg_1")
    }

    @Test
    fun unknownOwnerUpgrade_failsClosedIntoQuarantine_andSupportsExplicitMergeOrDiscard(): Unit = runBlocking {
        val emailB = "unowned_b_${UUID.randomUUID()}@test.com"
        // Seed Account B cloud first with B's own cloud playlist
        signInTestUser(emailB)
        val cloudRepo = CloudSyncRepository(firestore, auth)
        cloudRepo.upsertPlaylist(
            playlistKey = "pl_bob_cloud",
            title = "Bob Cloud List",
            description = "From cloud",
            coverUri = null,
            tracks = listOf(SongItem("bob_trk", "Bob Song", "Bob")),
            clientUpdatedAt = System.currentTimeMillis(),
            syncRevision = 1L
        )

        // Device 1: simulate legacy pre-ACTIVE_CLOUD_UID upgrade with pl_local_* + like + blocked + tombstone + device-only SavedAlbum
        val devMerge = TestDevice("UpgradeMerge", freshInstall = false)
        devMerge.dao.createPlaylistWithTracks(
            playlist = com.example.data.local.LocalPlaylistEntity(
                cloudKey = "pl_local_1",
                title = "Unowned Local List",
                description = "Pre-upgrade",
                createdAt = 1700000000000L,
                updatedAt = 1700000005000L,
                syncRevision = 1L
            ),
            tracks = listOf(
                com.example.data.local.LocalPlaylistTrackEntity(
                    playlistId = 0,
                    videoId = "unowned_trk",
                    songJson = devMerge.repo.encodeSong(SongItem("unowned_trk", "Unowned Song", "A")),
                    position = 0,
                    addedAt = 1700000005000L
                )
            )
        )
        devMerge.repo.toggleLike(SongItem("unowned_like", "Unowned Like", "A"))
        devMerge.repo.blockArtist("Unowned Blocked")
        devMerge.dao.insertSavedAlbum(SavedAlbumEntity("keep_alb", "Keep Album", "Device only", null))
        assertThat(devMerge.prefsRepo.getActiveCloudUid()).isNull()

        val coordMerge = devMerge.createCoordinator()
        // Fail closed! Must NOT sync into Bob's cloud automatically
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordMerge.syncNowAndAwait()).isFalse() }
        assertThat(coordMerge.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(coordMerge.authState.value.unownedDataItemCount).isAtLeast(3)
        assertThat(coordMerge.readQuarantineFile()).isNotNull()
        assertThat(devMerge.dao.getPlaylistsOnce()).isEmpty()
        assertThat(devMerge.dao.getSavedAlbumsOnce().map { it.albumId }).containsExactly("keep_alb")

        // Verify Bob's cloud still contains ONLY Bob's original playlist!
        val cloudWhileQuarantined = cloudRepo.fetchAccountSnapshot()
        assertThat(cloudWhileQuarantined.playlists.map { it.playlist.title }).containsExactly("Bob Cloud List")
        assertThat(cloudWhileQuarantined.likedSongs).isEmpty()

        // Explicit Merge option restores quarantine, adopts ownership, and merges with Bob's cloud
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordMerge.resolveUnownedDataMergeAndAwait()).isTrue() }
        assertThat(coordMerge.authState.value.hasUnownedDataConflict).isFalse()
        assertThat(coordMerge.readQuarantineFile()).isNull()
        assertThat(devMerge.dao.getPlaylistsOnce().map { it.title })
            .containsExactly("Bob Cloud List", "Unowned Local List")
        assertThat(devMerge.dao.getSavedAlbumsOnce().map { it.albumId }).containsExactly("keep_alb")
        coordMerge.shutdown()

        // Device 2: same unowned upgrade state, user chooses Discard & Sync Account
        val devDiscard = TestDevice("UpgradeDiscard", freshInstall = false)
        devDiscard.dao.createPlaylistWithTracks(
            playlist = com.example.data.local.LocalPlaylistEntity(
                cloudKey = "pl_local_99",
                title = "Should Discard Only Synced",
                description = "",
                createdAt = 1700000000000L,
                updatedAt = 1700000005000L,
                syncRevision = 1L
            ),
            tracks = emptyList()
        )
        devDiscard.dao.insertSavedAlbum(SavedAlbumEntity("keep_alb_2", "Keep Album 2", "Device only", null))
        val coordDiscard = devDiscard.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordDiscard.syncNowAndAwait()).isFalse() }
        assertThat(coordDiscard.authState.value.hasUnownedDataConflict).isTrue()

        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordDiscard.resolveUnownedDataDiscardAndAwait()).isTrue() }
        assertThat(coordDiscard.authState.value.hasUnownedDataConflict).isFalse()
        assertThat(coordDiscard.readQuarantineFile()).isNull()
        assertThat(devDiscard.dao.getPlaylistsOnce().map { it.title }).contains("Bob Cloud List")
        assertThat(devDiscard.dao.getPlaylistsOnce().map { it.title }).doesNotContain("Should Discard Only Synced")
        assertThat(devDiscard.dao.getSavedAlbumsOnce().map { it.albumId }).containsExactly("keep_alb_2")
        coordDiscard.shutdown()
    }

    @Test
    fun foreignBaselinesWithoutActiveUid_failsClosed_andProcessDeathDuringAccountSwitchRecoversSafely(): Unit = runBlocking {
        val emailA = "sw_a_${UUID.randomUUID()}@test.com"
        val emailB = "sw_b_${UUID.randomUUID()}@test.com"
        val uidA = signInTestUser(emailA)

        // Part 1: Device has baselines for uidA without ACTIVE_CLOUD_UID (corrupted/legacy prefs), signs in as B -> fails closed!
        val devForeign = TestDevice("ForeignBase", freshInstall = false)
        devForeign.repo.createPlaylist("Alice UUID Pl", "")
        devForeign.dao.upsertSyncBaseline(
            com.example.data.local.SyncBaselineEntity(
                userId = uidA,
                entityType = SyncEntityTypes.PLAYLIST,
                entityKey = devForeign.dao.getPlaylistsOnce().single().cloudKey,
                revision = 1L,
                signature = "sig",
                clientUpdatedAt = 1000L,
                deleted = false,
                updatedAt = 1000L
            )
        )
        val uidB = signInTestUser(emailB)
        val coordForeign = devForeign.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coordForeign.syncNowAndAwait()).isFalse() }
        assertThat(coordForeign.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists).isEmpty()
        coordForeign.shutdown()

        // Part 2: Account A -> B transition process death mid-wipe, followed by B -> A round-trip
        signInTestUser(emailA)
        val dev = TestDevice("CrashSwitch")
        val plIdA = dev.repo.createPlaylist("Alice Real Data", "")
        dev.repo.addTrackToPlaylist(plIdA, SongItem("a_trk", "Alice Song", "Alice"))
        dev.dao.insertSavedAlbum(SavedAlbumEntity("dev_alb_switch", "Device Album", "Keep", null))
        val cA1 = dev.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA1.syncNowAndAwait()).isTrue() }
        cA1.shutdown()

        // Start switch to B and simulate crash right after PENDING_ACCOUNT_TRANSITION_UID is persisted
        signInTestUser(emailB)
        val cCrash = dev.createCoordinator(autoStartSync = false)
        cCrash.onAfterAccountTransitionWipeBeforeFinalize = { _, _ ->
            throw IllegalStateException("Simulated crash mid-account transition")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cCrash.syncNowAndAwait()).isFalse() }
        assertThat(dev.prefsRepo.getPendingAccountTransitionUid()).isEqualTo(uidB)
        cCrash.shutdown()

        // Process restarts on Account B -> resumes pending transition wipe before touching cloud B!
        val cBResume = dev.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cBResume.syncNowAndAwait()).isTrue() }
        cBResume.shutdown()

        assertThat(dev.prefsRepo.getPendingAccountTransitionUid()).isNull()
        assertThat(dev.prefsRepo.getActiveCloudUid()).isEqualTo(uidB)
        assertThat(dev.dao.getPlaylistsOnce()).isEmpty()
        assertThat(dev.dao.getSavedAlbumsOnce().map { it.albumId }).containsExactly("dev_alb_switch")
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists).isEmpty()

        // Now switch B -> A: A's cloud data restores cleanly while device-only album stays
        signInTestUser(emailA)
        val cBackToA = dev.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cBackToA.syncNowAndAwait()).isTrue() }
        cBackToA.shutdown()
        assertThat(dev.prefsRepo.getActiveCloudUid()).isEqualTo(uidA)
        assertThat(dev.dao.getPlaylistsOnce().map { it.title }).containsExactly("Alice Real Data")
        assertThat(dev.dao.getSavedAlbumsOnce().map { it.albumId }).containsExactly("dev_alb_switch")
    }

    @Test
    fun partialCollectionSyncFailure_keepsSyncNowFalseAndDoesNotAdvanceFailingWatermark(): Unit = runBlocking {
        val email = "partial_fail_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val dev = TestDevice("PartialFail")
        dev.repo.toggleLike(SongItem("like_ok", "Liked Ok", "A"))
        dev.repo.createPlaylist("Failing Upload Pl", "Will fail tx")
        dev.repo.blockArtist("Blocked Ok")

        val faultRepo = CloudSyncRepository(firestore, auth).apply {
            onBeforePublishTransaction = { _, _ ->
                throw java.io.IOException("Simulated playlist publish network failure")
            }
        }
        val coord = dev.createCoordinator(customCloudRepo = faultRepo, autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coord.syncNowAndAwait()).isFalse() }

        // Honest error reporting: errorMessage non-null, pending > 0, playlists watermark stayed at 0L
        assertThat(coord.authState.value.errorMessage).isNotNull()
        assertThat(coord.authState.value.pendingUnsyncedCount).isAtLeast(1)
        assertThat(dev.prefsRepo.getSyncWatermark(UserPrefsRepository.WATERMARK_PLAYLISTS)).isEqualTo(0L)

        // Likes and hidden artists were NOT rolled back or lost
        val snapDuringFailure = CloudSyncRepository(firestore, auth).fetchAccountSnapshot()
        assertThat(snapDuringFailure.likedSongs.map { it.videoId }).containsExactly("like_ok")
        assertThat(snapDuringFailure.hiddenArtists.map { it.name }).containsExactly("Blocked Ok")

        // Clear fault and retry -> full sync succeeds cleanly
        faultRepo.onBeforePublishTransaction = null
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(coord.syncNowAndAwait()).isTrue() }
        assertThat(coord.authState.value.errorMessage).isNull()
        assertThat(coord.authState.value.pendingUnsyncedCount).isEqualTo(0)
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists.map { it.playlist.title })
            .containsExactly("Failing Upload Pl")
        coord.shutdown()
    }

    @Test
    fun staleOfflineClient_zeroLastSuccessAtWithBaselines_triggersCompactionFullFetch(): Unit = runBlocking {
        val email = "stale_zero_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)

        val dev = TestDevice("StaleZero")
        val compactedId = dev.repo.createPlaylist("Old Deleted Remotely", "")
        val keepId = dev.repo.createPlaylist("Keep Stale", "")
        val c1 = dev.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c1.syncNowAndAwait()).isTrue() }
        c1.shutdown()

        val oldKey = dev.dao.getPlaylistById(compactedId)!!.cloudKey
        val realLastSuccess = dev.prefsRepo.getLastSuccessfulSyncAtMs(uid)
        val cloudRepo = CloudSyncRepository(firestore, auth)
        val oldTombTs = realLastSuccess - 40L * 24L * 3600L * 1000L
        cloudRepo.deletePlaylist(oldKey, clientUpdatedAt = oldTombTs, syncRevision = 2L)
        cloudRepo.purgeExpiredCloudTombstones(uid, realLastSuccess + 60_000L)

        // Simulate reset/zeroed lastSuccessfulSyncAtMs while durable baselines remain
        dev.prefsRepo.setLastSuccessfulSyncAtMs(uid, 0L)
        dev.repo.updatePlaylist(keepId, "Keep Updated", "", null)

        val c2 = dev.createCoordinator()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c2.syncNowAndAwait()).isTrue() }
        c2.shutdown()

        assertThat(dev.dao.getPlaylistsOnce().map { it.title }).containsExactly("Keep Updated")
        assertThat(cloudRepo.fetchAccountSnapshot().playlists.filter { !it.playlist.deleted }.map { it.playlist.title })
            .containsExactly("Keep Updated")
    }

    @Test
    fun resolveUnownedDataMerge_crashBeforePrefsCommit_keepsQuarantineAndRecoversIdempotentlyOnRestart(): Unit = runBlocking {
        val emailA = "q_merge_crash_prefs_${UUID.randomUUID()}@test.com"
        val uidA = signInTestUser(emailA)
        val cloudRepo = CloudSyncRepository(firestore, auth)
        cloudRepo.upsertPlaylist(
            playlistKey = "pl_cloud_a",
            title = "Cloud Existing",
            description = "Already in cloud",
            coverUri = null,
            tracks = listOf(SongItem("cloud_t1", "Cloud Song", "Cloud Artist")),
            clientUpdatedAt = System.currentTimeMillis(),
            syncRevision = 1L
        )

        val dev = TestDevice("QMergeCrashPrefs", freshInstall = false)
        val pl1 = dev.repo.createPlaylist("Offline Mix 1", "Desc 1")
        dev.repo.addTrackToPlaylist(pl1, SongItem("off_1", "Off One", "Artist 1"))
        val pl2 = dev.repo.createPlaylist("Offline Mix 2", "Desc 2")
        dev.repo.addTrackToPlaylist(pl2, SongItem("off_2", "Off Two", "Artist 2"))
        dev.repo.toggleLike(SongItem("off_like", "Off Like", "Artist L"))
        dev.repo.blockArtist("Off Blocked")
        dev.prefsRepo.setDisplayName("Offline Listener")
        dev.prefsRepo.saveTasteProfile(
            TasteProfile(
                onboardingCompleted = true,
                seedArtists = listOf(TasteSeed("art_1", "Seed Artist", subtitle = "Artist")),
                seedSongs = listOf(TasteSeed("sng_1", "Seed Song", subtitle = "Seed Artist"))
            ),
            touchProfileTimestamp = true
        )
        dev.dao.insertSavedAlbum(SavedAlbumEntity("keep_dev_alb", "Keep Album", "Local", null))
        assertThat(dev.prefsRepo.getActiveCloudUid()).isNull()

        val c1 = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c1.syncNowAndAwait()).isFalse() }
        assertThat(c1.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(c1.readQuarantineFile()).isNotNull()

        // Inject crash AFTER Room tx commits (ROOM_APPLIED) and BEFORE DataStore prefs update!
        c1.onAfterQuarantineRoomApplyBeforePrefs = { _, _ ->
            throw IllegalStateException("Simulated process crash between Room and Prefs")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c1.resolveUnownedDataMergeAndAwait()).isFalse() }
        assertThat(c1.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(dev.prefsRepo.getActiveCloudUid()).isNull()
        assertThat(c1.readQuarantineFile()).isNotNull()
        assertThat(c1.readQuarantineResolutionJournal()?.state).isEqualTo(QuarantineResolutionState.ROOM_APPLIED)
        // Cloud must NOT have been mutated when resolution aborted
        assertThat(cloudRepo.fetchAccountSnapshot().playlists.map { it.playlist.title }).containsExactly("Cloud Existing")
        c1.shutdown()

        // Simulate app restart -> new coordinator resumes journal for uidA idempotently without duplicate cloudKey error
        val cRestart = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cRestart.syncNowAndAwait()).isTrue() }

        assertThat(cRestart.authState.value.hasUnownedDataConflict).isFalse()
        assertThat(cRestart.readQuarantineFile()).isNull()
        assertThat(cRestart.readQuarantineResolutionJournal()).isNull()
        assertThat(cRestart.listRetainedPayloadFiles()).isEmpty()
        assertThat(dev.prefsRepo.getActiveCloudUid()).isEqualTo(uidA)
        assertThat(dev.prefsRepo.prefsFlow.first().userDisplayName).isEqualTo("Offline Listener")
        assertThat(dev.dao.getPlaylistsOnce().map { it.title })
            .containsExactly("Cloud Existing", "Offline Mix 1", "Offline Mix 2")
        assertThat(dev.dao.getLikedSongsOnce().map { it.videoId }).containsExactly("off_like")
        assertThat(dev.dao.getBlockedArtistsOnce().map { it.name }).containsExactly("Off Blocked")
        assertThat(dev.dao.getSavedAlbumsOnce().map { it.albumId }).containsExactly("keep_dev_alb")
        assertThat(cloudRepo.fetchAccountSnapshot().playlists.map { it.playlist.title })
            .containsExactly("Cloud Existing", "Offline Mix 1", "Offline Mix 2")
        cRestart.shutdown()
    }

    @Test
    fun resolveUnownedDataMerge_crashAfterPrefsBeforeDeleteFile_recoversCleanlyOnRestart(): Unit = runBlocking {
        val email = "q_merge_crash_del_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)

        val dev = TestDevice("QMergeCrashDel", freshInstall = false)
        val plId = dev.repo.createPlaylist("Offline Preserved", "")
        dev.repo.addTrackToPlaylist(plId, SongItem("trk_del_1", "Track One", "Artist"))

        val c1 = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c1.syncNowAndAwait()).isFalse() }
        assertThat(c1.authState.value.hasUnownedDataConflict).isTrue()

        // Crash AFTER DataStore prefs set ACTIVE_CLOUD_UID, BEFORE deleting quarantine file
        c1.onAfterQuarantinePrefsBeforeDeleteFile = { _, _ ->
            throw IllegalStateException("Simulated crash before deleting quarantine file")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c1.resolveUnownedDataMergeAndAwait()).isFalse() }
        assertThat(dev.prefsRepo.getActiveCloudUid()).isEqualTo(uid)
        assertThat(c1.readQuarantineFile()).isNotNull()
        assertThat(c1.readQuarantineResolutionJournal()?.state).isEqualTo(QuarantineResolutionState.PREFS_APPLIED)
        c1.shutdown()

        // Restart finishes quarantine deletion & journal cleanup even though ACTIVE_CLOUD_UID was already set
        val cRestart = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cRestart.syncNowAndAwait()).isTrue() }
        assertThat(cRestart.readQuarantineFile()).isNull()
        assertThat(cRestart.readQuarantineResolutionJournal()).isNull()
        assertThat(cRestart.listRetainedPayloadFiles()).isEmpty()
        assertThat(cRestart.authState.value.hasUnownedDataConflict).isFalse()
        assertThat(dev.dao.getPlaylistsOnce().map { it.title }).containsExactly("Offline Preserved")
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists.map { it.playlist.title })
            .containsExactly("Offline Preserved")
        cRestart.shutdown()
    }

    @Test
    fun resolveUnownedDataMerge_crashThenDifferentAccountSignsIn_rollsBackPartialStateAndPreservesQuarantine(): Unit = runBlocking {
        val emailA = "q_foreign_a_${UUID.randomUUID()}@test.com"
        val emailB = "q_foreign_b_${UUID.randomUUID()}@test.com"
        val uidA = signInTestUser(emailA)

        val dev = TestDevice("QForeignRollback", freshInstall = false)
        val plId = dev.repo.createPlaylist("Quarantined Mix", "Should not leak to B")
        dev.repo.addTrackToPlaylist(plId, SongItem("q_trk", "Q Song", "Artist"))
        dev.repo.toggleLike(SongItem("q_like", "Q Like", "Artist"))
        dev.dao.insertSavedAlbum(SavedAlbumEntity("keep_foreign_alb", "Keep Alb", "Device", null))

        // Account A quarantines data and crashes mid-merge at ROOM_APPLIED
        val cA = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.syncNowAndAwait()).isFalse() }
        cA.onAfterQuarantineRoomApplyBeforePrefs = { _, _ ->
            throw IllegalStateException("Crash mid-merge for A")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.resolveUnownedDataMergeAndAwait()).isFalse() }
        assertThat(cA.readQuarantineResolutionJournal()?.targetUid).isEqualTo(uidA)
        assertThat(cA.readQuarantineResolutionJournal()?.state).isEqualTo(QuarantineResolutionState.ROOM_APPLIED)
        cA.shutdown()

        // Different account B signs in -> must roll back A's partial Room rows, preserve quarantineFile,
        // NOT sync A's data into B's cloud, and prompt B explicitly!
        signInTestUser(emailB)
        val cB = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB.syncNowAndAwait()).isFalse() }

        assertThat(cB.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(cB.readQuarantineResolutionJournal()).isNull()
        assertThat(cB.readQuarantineFile()).isNotNull()
        assertThat(cB.listRetainedPayloadFiles()).isEmpty()
        assertThat(dev.dao.getPlaylistsOnce()).isEmpty()
        assertThat(dev.dao.getLikedSongsOnce()).isEmpty()
        assertThat(dev.dao.getSavedAlbumsOnce().map { it.albumId }).containsExactly("keep_foreign_alb")
        val cloudSnapB = CloudSyncRepository(firestore, auth).fetchAccountSnapshot()
        assertThat(cloudSnapB.playlists).isEmpty()
        assertThat(cloudSnapB.likedSongs).isEmpty()
        cB.shutdown()
    }

    @Test
    fun resolveUnownedDataDiscard_crashMidResolution_recoversCleanlyForSameUserAndProtectsOtherUser(): Unit = runBlocking {
        val emailA = "q_disc_a_${UUID.randomUUID()}@test.com"
        val emailB = "q_disc_b_${UUID.randomUUID()}@test.com"
        signInTestUser(emailA)
        val cloudRepoA = CloudSyncRepository(firestore, auth)
        cloudRepoA.upsertPlaylist(
            playlistKey = "pl_a_cloud_only",
            title = "A Cloud Only",
            description = "",
            coverUri = null,
            tracks = listOf(SongItem("a_trk", "A Track", "A")),
            clientUpdatedAt = System.currentTimeMillis(),
            syncRevision = 1L
        )

        val dev = TestDevice("QDiscardCrash", freshInstall = false)
        dev.repo.createPlaylist("Local To Discard", "")
        val c1 = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c1.syncNowAndAwait()).isFalse() }

        // Crash during DISCARD before prefs update -> foreign sign-in must still see quarantineFile
        c1.onAfterQuarantineRoomApplyBeforePrefs = { _, _ ->
            throw IllegalStateException("Crash mid-discard before prefs")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c1.resolveUnownedDataDiscardAndAwait()).isFalse() }
        assertThat(c1.readQuarantineResolutionJournal()?.action).isEqualTo(QuarantineResolutionAction.DISCARD)
        assertThat(c1.readQuarantineFile()).isNotNull()
        c1.shutdown()

        // Different account B signs in -> stale DISCARD journal rolled back, quarantine preserved, fails closed
        signInTestUser(emailB)
        val cB = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB.syncNowAndAwait()).isFalse() }
        assertThat(cB.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(cB.readQuarantineFile()).isNotNull()
        cB.shutdown()

        // Original account A signs back in, picks Discard cleanly without crash hook -> succeeds
        signInTestUser(emailA)
        val cA2 = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA2.syncNowAndAwait()).isFalse() }
        assertThat(cA2.authState.value.hasUnownedDataConflict).isTrue()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA2.resolveUnownedDataDiscardAndAwait()).isTrue() }
        assertThat(cA2.readQuarantineFile()).isNull()
        assertThat(cA2.readQuarantineResolutionJournal()).isNull()
        assertThat(cA2.listRetainedPayloadFiles()).isEmpty()
        assertThat(dev.dao.getPlaylistsOnce().map { it.title }).containsExactly("A Cloud Only")
        cA2.shutdown()
    }

    @Test
    fun resolveUnownedDataMerge_multiplePlaylistsAndOps_retryAfterFailureAppliesAtomically(): Unit = runBlocking {
        val email = "q_multi_pl_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val dev = TestDevice("QMultiPl", freshInstall = false)
        val p1 = dev.repo.createPlaylist("Playlist Alpha", "One")
        dev.repo.addTrackToPlaylist(p1, SongItem("t_a1", "Alpha 1", "Artist"))
        dev.repo.addTrackToPlaylist(p1, SongItem("t_a2", "Alpha 2", "Artist"))
        val p2 = dev.repo.createPlaylist("Playlist Beta", "Two")
        dev.repo.addTrackToPlaylist(p2, SongItem("t_b1", "Beta 1", "Artist"))

        val c = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c.syncNowAndAwait()).isFalse() }

        // Fail before delete, then invoke resolveUnownedDataMergeAndAwait() again (same coordinator retry)
        var failOnce = true
        c.onAfterQuarantineDeleteBeforeJournalComplete = { _, _ ->
            if (failOnce) {
                failOnce = false
                throw IllegalStateException("Transient failure after quarantine delete")
            }
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c.resolveUnownedDataMergeAndAwait()).isFalse() }
        assertThat(c.readQuarantineResolutionJournal()?.state).isEqualTo(QuarantineResolutionState.QUARANTINE_CLEARED)

        // Retry completes cleanly and leaves both playlists with all tracks & ops intact
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c.resolveUnownedDataMergeAndAwait()).isTrue() }
        assertThat(c.readQuarantineResolutionJournal()).isNull()
        assertThat(c.listRetainedPayloadFiles()).isEmpty()
        val playlists = dev.dao.getPlaylistsOnce().sortedBy { it.title }
        assertThat(playlists.map { it.title }).containsExactly("Playlist Alpha", "Playlist Beta").inOrder()
        assertThat(dev.dao.getPlaylistTracks(playlists[0].id).map { it.videoId }).containsExactly("t_a1", "t_a2").inOrder()
        assertThat(dev.dao.getPlaylistTracks(playlists[1].id).map { it.videoId }).containsExactly("t_b1")
        assertThat(dev.dao.getPlaylistTrackOps(playlists[0].id)).isNotEmpty()
        assertThat(dev.dao.getPlaylistFieldOps(playlists[0].id)).isNotEmpty()
        c.shutdown()
    }

    @Test
    fun resolveUnownedDataMerge_crashAfterDeleteBeforeClearedState_thenForeignUidSignsIn_restoresQuarantineWithoutLoss(): Unit = runBlocking {
        val emailA = "q_pre_clear_a_${UUID.randomUUID()}@test.com"
        val emailB = "q_pre_clear_b_${UUID.randomUUID()}@test.com"
        val uidA = signInTestUser(emailA)

        val dev = TestDevice("QPreClearForeign", freshInstall = false)
        val plId = dev.repo.createPlaylist("Crucial Unuploaded Mix", "Never lose this")
        dev.repo.addTrackToPlaylist(plId, SongItem("crucial_1", "Crucial Song", "Artist C"))
        dev.repo.toggleLike(SongItem("crucial_like", "Crucial Like", "Artist C"))
        dev.repo.blockArtist("Crucial Blocked")
        dev.dao.insertSavedAlbum(SavedAlbumEntity("dev_alb_c", "Device Album", "Keep", null))

        val cA = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.syncNowAndAwait()).isFalse() }
        assertThat(cA.authState.value.hasUnownedDataConflict).isTrue()

        // Crash AFTER unowned_account_quarantine.json was deleted in PREFS_APPLIED, BEFORE writing QUARANTINE_CLEARED!
        cA.onAfterQuarantineDeleteBeforeClearedState = { _, _ ->
            throw IllegalStateException("Simulated crash right after deleting quarantine file")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.resolveUnownedDataMergeAndAwait()).isFalse() }
        assertThat(cA.readQuarantineFile()).isNull()
        assertThat(cA.listRetainedPayloadFiles()).hasSize(1)
        assertThat(cA.readQuarantineResolutionJournal()?.state).isEqualTo(QuarantineResolutionState.PREFS_APPLIED)
        assertThat(cA.readQuarantineResolutionJournal()?.targetUid).isEqualTo(uidA)
        cA.shutdown()

        // Different account B signs in -> rollback must restore unowned_account_quarantine.json from retained archive BEFORE clearing Room!
        signInTestUser(emailB)
        val cB = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB.syncNowAndAwait()).isFalse() }

        assertThat(cB.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(cB.readQuarantineResolutionJournal()).isNull()
        assertThat(cB.listRetainedPayloadFiles()).isEmpty()
        val restoredQuarantine = cB.readQuarantineFile()
        assertThat(restoredQuarantine).isNotNull()
        assertThat(restoredQuarantine!!.playlists.map { it.title }).containsExactly("Crucial Unuploaded Mix")
        assertThat(restoredQuarantine.likedSongs.map { it.videoId }).containsExactly("crucial_like")
        assertThat(restoredQuarantine.blockedArtists.map { it.name }).containsExactly("Crucial Blocked")
        assertThat(dev.dao.getPlaylistsOnce()).isEmpty()
        assertThat(dev.dao.getLikedSongsOnce()).isEmpty()
        assertThat(dev.dao.getBlockedArtistsOnce()).isEmpty()
        assertThat(dev.dao.getSavedAlbumsOnce().map { it.albumId }).containsExactly("dev_alb_c")
        val cloudSnapB = CloudSyncRepository(firestore, auth).fetchAccountSnapshot()
        assertThat(cloudSnapB.playlists).isEmpty()
        assertThat(cloudSnapB.likedSongs).isEmpty()
        cB.shutdown()

        // Account A signs back in -> can now explicitly Merge the restored quarantine without any lost data!
        signInTestUser(emailA)
        val cA2 = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA2.syncNowAndAwait()).isFalse() }
        assertThat(cA2.authState.value.hasUnownedDataConflict).isTrue()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA2.resolveUnownedDataMergeAndAwait()).isTrue() }
        assertThat(dev.dao.getPlaylistsOnce().map { it.title }).containsExactly("Crucial Unuploaded Mix")
        assertThat(dev.dao.getLikedSongsOnce().map { it.videoId }).containsExactly("crucial_like")
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists.map { it.playlist.title })
            .containsExactly("Crucial Unuploaded Mix")
        cA2.shutdown()
    }

    @Test
    fun resolveUnownedDataDiscard_crashAfterDeleteBeforeClearedState_thenForeignUidSignsIn_restoresQuarantine(): Unit = runBlocking {
        val emailA = "q_disc_del_a_${UUID.randomUUID()}@test.com"
        val emailB = "q_disc_del_b_${UUID.randomUUID()}@test.com"
        signInTestUser(emailA)

        val dev = TestDevice("QDiscDelForeign", freshInstall = false)
        dev.repo.createPlaylist("Pending Discard List", "")
        val cA = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.syncNowAndAwait()).isFalse() }

        cA.onAfterQuarantineDeleteBeforeClearedState = { _, _ ->
            throw IllegalStateException("Crash after deleting quarantine during DISCARD")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.resolveUnownedDataDiscardAndAwait()).isFalse() }
        assertThat(cA.readQuarantineFile()).isNull()
        assertThat(cA.listRetainedPayloadFiles()).hasSize(1)
        assertThat(cA.readQuarantineResolutionJournal()?.action).isEqualTo(QuarantineResolutionAction.DISCARD)
        cA.shutdown()

        signInTestUser(emailB)
        val cB = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB.syncNowAndAwait()).isFalse() }
        assertThat(cB.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(cB.readQuarantineFile()?.playlists?.map { it.title }).containsExactly("Pending Discard List")
        assertThat(cB.listRetainedPayloadFiles()).isEmpty()
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists).isEmpty()
        cB.shutdown()
    }

    @Test
    fun resolveUnownedDataMerge_crashAtPayloadArchivedAndCompletedCleanup_recoversCleanly(): Unit = runBlocking {
        val emailA = "q_stage_a_${UUID.randomUUID()}@test.com"
        val emailB = "q_stage_b_${UUID.randomUUID()}@test.com"
        signInTestUser(emailA)

        // Part 1: Crash right after PAYLOAD_ARCHIVED -> foreign UID switches cleanly; same UID merges cleanly
        val dev1 = TestDevice("QArchivedStage", freshInstall = false)
        dev1.repo.createPlaylist("Archived Stage Pl", "")
        val c1 = dev1.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c1.syncNowAndAwait()).isFalse() }
        c1.onAfterQuarantinePayloadArchived = { _, _ ->
            throw IllegalStateException("Crash at PAYLOAD_ARCHIVED")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c1.resolveUnownedDataMergeAndAwait()).isFalse() }
        assertThat(c1.readQuarantineResolutionJournal()?.state).isEqualTo(QuarantineResolutionState.PAYLOAD_ARCHIVED)
        assertThat(c1.listRetainedPayloadFiles()).hasSize(1)
        c1.shutdown()

        signInTestUser(emailB)
        val c1Foreign = dev1.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c1Foreign.syncNowAndAwait()).isFalse() }
        assertThat(c1Foreign.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(c1Foreign.listRetainedPayloadFiles()).isEmpty()
        assertThat(c1Foreign.readQuarantineResolutionJournal()).isNull()
        assertThat(c1Foreign.readQuarantineFile()?.playlists?.map { it.title }).containsExactly("Archived Stage Pl")
        c1Foreign.shutdown()

        // Part 2: Crash after COMPLETED persisted BEFORE cleanup, then after payload delete before journal delete
        signInTestUser(emailA)
        val dev2 = TestDevice("QCompletedCleanup", freshInstall = false)
        dev2.repo.createPlaylist("Completed Stage Pl", "")
        val c2 = dev2.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c2.syncNowAndAwait()).isFalse() }
        c2.onAfterQuarantineJournalCompletedBeforeCleanup = { _, _ ->
            throw IllegalStateException("Crash after COMPLETED before retained payload cleanup")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c2.resolveUnownedDataMergeAndAwait()).isFalse() }
        assertThat(c2.readQuarantineResolutionJournal()?.state).isEqualTo(QuarantineResolutionState.COMPLETED)
        assertThat(c2.listRetainedPayloadFiles()).hasSize(1)
        c2.shutdown()

        val c2Restart = dev2.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c2Restart.syncNowAndAwait()).isTrue() }
        assertThat(c2Restart.authState.value.hasUnownedDataConflict).isFalse()
        assertThat(c2Restart.readQuarantineResolutionJournal()).isNull()
        assertThat(c2Restart.listRetainedPayloadFiles()).isEmpty()
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists.map { it.playlist.title })
            .containsExactly("Completed Stage Pl")
        c2Restart.shutdown()

        // Part 3: Crash after retained payload deleted BEFORE journal deleted (use distinct fresh user C)
        val emailC = "q_stage_c_${UUID.randomUUID()}@test.com"
        signInTestUser(emailC)
        val dev3 = TestDevice("QPostPayloadDel", freshInstall = false)
        dev3.repo.createPlaylist("Post Cleanup Pl", "")
        val c3 = dev3.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c3.syncNowAndAwait()).isFalse() }
        c3.onAfterQuarantinePayloadCleanupBeforeJournalDelete = { _, _ ->
            throw IllegalStateException("Crash before journal file delete")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c3.resolveUnownedDataMergeAndAwait()).isFalse() }
        assertThat(c3.listRetainedPayloadFiles()).isEmpty()
        assertThat(c3.readQuarantineResolutionJournal()?.state).isEqualTo(QuarantineResolutionState.COMPLETED)
        c3.shutdown()

        val c3Restart = dev3.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c3Restart.syncNowAndAwait()).isTrue() }
        assertThat(c3Restart.readQuarantineResolutionJournal()).isNull()
        assertThat(c3Restart.authState.value.hasUnownedDataConflict).isFalse()
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists.map { it.playlist.title })
            .containsExactly("Post Cleanup Pl")
        c3Restart.shutdown()
    }

    @Test
    fun resolveUnownedData_missingRecoveryPayloadsOrCorruptJournal_failsClosedWithoutWipingRoom(): Unit = runBlocking {
        val emailA = "q_missing_a_${UUID.randomUUID()}@test.com"
        val emailB = "q_missing_b_${UUID.randomUUID()}@test.com"
        signInTestUser(emailA)

        // Case 1: Incomplete journal after quarantineFile deletion AND retained file missing/corrupted -> fail closed!
        val devMissing = TestDevice("QMissingPayloads", freshInstall = false)
        devMissing.repo.createPlaylist("Must Not Wipe Blindly", "")
        val cA = devMissing.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.syncNowAndAwait()).isFalse() }
        cA.onAfterQuarantineDeleteBeforeClearedState = { _, _ ->
            throw IllegalStateException("Crash after quarantine delete")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.resolveUnownedDataMergeAndAwait()).isFalse() }
        // Corrupt/remove retained archive to simulate unrecoverable disk loss
        cA.listRetainedPayloadFiles().forEach { it.delete() }
        cA.shutdown()

        signInTestUser(emailB)
        val cB = devMissing.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB.syncNowAndAwait()).isFalse() }
        assertThat(cB.authState.value.syncStatus).isEqualTo("Quarantine recovery error")
        // Room row applied for A MUST NOT be wiped cleanly without a recovery payload, and MUST NOT sync to B!
        assertThat(devMissing.dao.getPlaylistsOnce().map { it.title }).containsExactly("Must Not Wipe Blindly")
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists).isEmpty()
        cB.shutdown()

        // Case 2: Corrupt journal file -> fails closed cleanly without wiping or syncing
        val devCorrupt = TestDevice("QCorruptJournal", freshInstall = false)
        devCorrupt.repo.createPlaylist("Unowned Protected", "")
        val syncStateDir = File(devCorrupt.dir, "sync_state").apply { mkdirs() }
        File(syncStateDir, "unowned_quarantine_resolution_journal.json").writeText("{corrupt json")
        val cCorrupt = devCorrupt.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cCorrupt.syncNowAndAwait()).isFalse() }
        assertThat(cCorrupt.authState.value.syncStatus).isEqualTo("Quarantine recovery error")
        assertThat(devCorrupt.dao.getPlaylistsOnce().map { it.title }).containsExactly("Unowned Protected")
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists).isEmpty()
        cCorrupt.shutdown()
    }

    @Test
    fun unknownOwnerUpgrade_uuidPlaylistsLikesBlockedAndSeedsWithoutBaselinesOrMarkers_allFailClosedIntoQuarantine(): Unit = runBlocking {
        val email = "prov_upgrade_${UUID.randomUUID()}@test.com"
        signInTestUser(email)
        val cloudRepo = CloudSyncRepository(firestore, auth)

        // 1. Upgraded device with UUID playlists ONLY (no pl_local_*, no baselines, no tombstones, no legacy markers)
        val devUuidPl = TestDevice("UpUuidOnly", freshInstall = false)
        devUuidPl.repo.createPlaylist("Pre-Provenance UUID Playlist", "Desc")
        // Simulate real app startup calling initializeInstallProvenanceIfClean on pre-populated DB
        devUuidPl.prefsRepo.initializeInstallProvenanceIfClean(devUuidPl.dao)
        assertThat(devUuidPl.prefsRepo.getLocalDataProvenance()).isEqualTo(LocalDataProvenance.UNKNOWN_OR_LEGACY)
        val cPl = devUuidPl.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cPl.syncNowAndAwait()).isFalse() }
        assertThat(cPl.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(cPl.readQuarantineFile()?.playlists?.map { it.title }).containsExactly("Pre-Provenance UUID Playlist")
        cPl.shutdown()

        // 2. Liked songs ONLY (no playlists, no baselines, no tombstones, no legacy markers)
        val devLikesOnly = TestDevice("UpLikesOnly", freshInstall = false)
        devLikesOnly.repo.toggleLike(SongItem("up_like_1", "Legacy Liked", "Artist"))
        val cLikes = devLikesOnly.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cLikes.syncNowAndAwait()).isFalse() }
        assertThat(cLikes.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(cLikes.readQuarantineFile()?.likedSongs?.map { it.videoId }).containsExactly("up_like_1")
        cLikes.shutdown()

        // 3. Blocked artists ONLY
        val devBlockedOnly = TestDevice("UpBlockedOnly", freshInstall = false)
        devBlockedOnly.repo.blockArtist("Legacy Blocked Artist")
        val cBlocked = devBlockedOnly.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cBlocked.syncNowAndAwait()).isFalse() }
        assertThat(cBlocked.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(cBlocked.readQuarantineFile()?.blockedArtists?.map { it.name }).containsExactly("Legacy Blocked Artist")
        cBlocked.shutdown()

        // 4. Taste seeds ONLY
        val devSeedsOnly = TestDevice("UpSeedsOnly", freshInstall = false)
        devSeedsOnly.prefsRepo.saveTasteProfile(
            TasteProfile(
                onboardingCompleted = true,
                seedArtists = listOf(TasteSeed("art_up", "Upgraded Seed Artist"))
            )
        )
        val cSeeds = devSeedsOnly.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cSeeds.syncNowAndAwait()).isFalse() }
        assertThat(cSeeds.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(cSeeds.readQuarantineFile()?.tasteProfile?.seedArtists?.map { it.name })
            .containsExactly("Upgraded Seed Artist")
        cSeeds.shutdown()

        // Cloud remains completely clean across all 4 unowned upgrades
        val cloudSnap = cloudRepo.fetchAccountSnapshot()
        assertThat(cloudSnap.playlists).isEmpty()
        assertThat(cloudSnap.likedSongs).isEmpty()
        assertThat(cloudSnap.hiddenArtists).isEmpty()
        assertThat(cloudSnap.tasteSeeds).isEmpty()
    }

    @Test
    fun accountOwnedProvenanceWithMissingActiveUid_failsClosed_whileFreshInstallAdoptsWithoutQuarantine(): Unit = runBlocking {
        val emailA = "prov_own_a_${UUID.randomUUID()}@test.com"
        val emailB = "prov_own_b_${UUID.randomUUID()}@test.com"
        val uidA = signInTestUser(emailA)

        // Part 1: Genuine fresh install -> offline mutations before first sign-in adopt cleanly without quarantine
        val devFresh = TestDevice("FreshInstall", freshInstall = true)
        assertThat(devFresh.prefsRepo.getLocalDataProvenance()).isEqualTo(LocalDataProvenance.FRESH_LOCAL)
        val plId = devFresh.repo.createPlaylist("Offline First Mix", "Created offline on fresh install")
        devFresh.repo.addTrackToPlaylist(plId, SongItem("fr_trk", "Fresh Track", "Fresh Artist"))
        devFresh.repo.toggleLike(SongItem("fr_like", "Fresh Like", "Fresh Artist"))
        devFresh.repo.blockArtist("Fresh Blocked")
        devFresh.prefsRepo.saveTasteProfile(
            TasteProfile(
                onboardingCompleted = true,
                seedArtists = listOf(TasteSeed("art_fr", "Fresh Seed"))
            ),
            touchProfileTimestamp = true
        )

        val cA = devFresh.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.syncNowAndAwait()).isTrue() }
        assertThat(cA.authState.value.hasUnownedDataConflict).isFalse()
        assertThat(devFresh.prefsRepo.getActiveCloudUid()).isEqualTo(uidA)
        assertThat(devFresh.prefsRepo.getLocalDataProvenance()).isEqualTo(LocalDataProvenance.ACCOUNT_OWNED)
        val cloudSnapA = CloudSyncRepository(firestore, auth).fetchAccountSnapshot()
        assertThat(cloudSnapA.playlists.map { it.playlist.title }).containsExactly("Offline First Mix")
        assertThat(cloudSnapA.likedSongs.map { it.videoId }).containsExactly("fr_like")

        // Part 2: Ordinary sign-out resets provenance to FRESH_LOCAL and allows subsequent offline edits before next sign-in
        cA.signOut()
        val deadline = System.currentTimeMillis() + 10_000L
        while ((cA.authState.value.isSignedIn || cA.authState.value.isAuthBusy) && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(25L)
        }
        assertThat(cA.authState.value.isSignedIn).isFalse()
        assertThat(devFresh.prefsRepo.getLocalDataProvenance()).isEqualTo(LocalDataProvenance.FRESH_LOCAL)
        cA.shutdown()

        devFresh.repo.createPlaylist("Post-SignOut Offline Mix", "")
        signInTestUser(emailA)
        val cA2 = devFresh.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA2.syncNowAndAwait()).isTrue() }
        assertThat(cA2.authState.value.hasUnownedDataConflict).isFalse()
        assertThat(devFresh.prefsRepo.getLocalDataProvenance()).isEqualTo(LocalDataProvenance.ACCOUNT_OWNED)
        cA2.shutdown()

        // Part 3: ACCOUNT_OWNED provenance where ACTIVE_CLOUD_UID & baselines were unexpectedly cleared while Room rows remain -> fails closed for B!
        devFresh.prefsRepo.setActiveCloudUid(null)
        devFresh.dao.clearAllSyncBaselines()
        signInTestUser(emailB)
        val cB = devFresh.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB.syncNowAndAwait()).isFalse() }
        assertThat(cB.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists).isEmpty()
        cB.shutdown()
    }

    // =========================================================================
    // P1: Quarantine Completion Requires Verified Remote Cloud Synchronization
    // =========================================================================

    @Test
    fun resolveUnownedDataMerge_crashAtCloudSyncPendingAndDifferentAccountSignsIn_preservesADataAndBlocksB(): Unit = runBlocking {
        val emailA = "p1_pend_a_${UUID.randomUUID()}@test.com"
        val emailB = "p1_pend_b_${UUID.randomUUID()}@test.com"
        val uidA = signInTestUser(emailA)

        val dev = TestDevice("P1CloudSyncPending", freshInstall = false)
        val plId = dev.repo.createPlaylist("Alice Migrated Mix", "Must reach A only")
        dev.repo.addTrackToPlaylist(plId, SongItem("mig_1", "Migrated One", "Artist M"))
        dev.repo.toggleLike(SongItem("mig_like", "Migrated Like", "Artist M"))
        dev.repo.blockArtist("Migrated Blocked")
        dev.prefsRepo.setDisplayName("Alice Upgraded")
        dev.dao.insertSavedAlbum(SavedAlbumEntity("dev_only_p1", "Keep Device Album", "Local", null))
        dev.prefsRepo.setPlaybackSpeed(1.5f)
        dev.prefsRepo.setClassicPlayerLayout(true)

        val cA = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.syncNowAndAwait()).isFalse() }
        assertThat(cA.authState.value.hasUnownedDataConflict).isTrue()

        // Crash right after local restoration finishes (CLOUD_SYNC_PENDING written) BEFORE first cloud sync!
        cA.onAfterQuarantineCloudSyncPendingBeforeSync = { _, _ ->
            throw IllegalStateException("Process died right after local restore before first cloud sync")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.resolveUnownedDataMergeAndAwait()).isFalse() }

        // Even though ACTIVE_CLOUD_UID was written and Room restored locally, retained payload & journal MUST remain!
        assertThat(dev.prefsRepo.getActiveCloudUid()).isEqualTo(uidA)
        assertThat(cA.readQuarantineFile()).isNull()
        assertThat(cA.readQuarantineResolutionJournal()?.state).isEqualTo(QuarantineResolutionState.CLOUD_SYNC_PENDING)
        assertThat(cA.readQuarantineResolutionJournal()?.targetUid).isEqualTo(uidA)
        assertThat(cA.listRetainedPayloadFiles()).hasSize(1)
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists).isEmpty()
        cA.shutdown()

        // Account B signs in directly -> must NOT inherit A's merge, must NOT delete A's payload, must restore quarantine & block B!
        signInTestUser(emailB)
        val cB = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB.syncNowAndAwait()).isFalse() }

        assertThat(cB.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(cB.readQuarantineResolutionJournal()).isNull()
        assertThat(cB.listRetainedPayloadFiles()).isEmpty()
        val restoredQuarantine = cB.readQuarantineFile()
        assertThat(restoredQuarantine).isNotNull()
        assertThat(restoredQuarantine!!.playlists.map { it.title }).containsExactly("Alice Migrated Mix")
        assertThat(restoredQuarantine.likedSongs.map { it.videoId }).containsExactly("mig_like")
        assertThat(restoredQuarantine.blockedArtists.map { it.name }).containsExactly("Migrated Blocked")
        assertThat(restoredQuarantine.displayName).isEqualTo("Alice Upgraded")
        assertThat(dev.dao.getPlaylistsOnce()).isEmpty()
        assertThat(dev.dao.getLikedSongsOnce()).isEmpty()
        assertThat(dev.prefsRepo.getActiveCloudUid()).isNull()
        // Device-only data remained untouched
        assertThat(dev.dao.getSavedAlbumsOnce().map { it.albumId }).containsExactly("dev_only_p1")
        assertThat(dev.prefsRepo.prefsFlow.first().playbackSpeed).isEqualTo(1.5f)
        assertThat(dev.prefsRepo.prefsFlow.first().classicPlayerLayout).isTrue()
        // Account B's cloud must remain completely untouched
        val cloudSnapB = CloudSyncRepository(firestore, auth).fetchAccountSnapshot()
        assertThat(cloudSnapB.playlists).isEmpty()
        assertThat(cloudSnapB.likedSongs).isEmpty()
        cB.shutdown()

        // Account A signs back in and accepts Merge cleanly -> A's cloud now receives the full migrated library
        signInTestUser(emailA)
        val cA2 = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA2.syncNowAndAwait()).isFalse() }
        assertThat(cA2.authState.value.hasUnownedDataConflict).isTrue()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA2.resolveUnownedDataMergeAndAwait()).isTrue() }
        assertThat(cA2.readQuarantineFile()).isNull()
        assertThat(cA2.readQuarantineResolutionJournal()).isNull()
        assertThat(cA2.listRetainedPayloadFiles()).isEmpty()
        val cloudSnapA = CloudSyncRepository(firestore, auth).fetchAccountSnapshot()
        assertThat(cloudSnapA.playlists.map { it.playlist.title }).containsExactly("Alice Migrated Mix")
        assertThat(cloudSnapA.likedSongs.map { it.videoId }).containsExactly("mig_like")
        assertThat(cloudSnapA.hiddenArtists.map { it.name }).containsExactly("Migrated Blocked")
        assertThat(cloudSnapA.profile?.displayName).isEqualTo("Alice Upgraded")
        cA2.shutdown()
    }

    @Test
    fun resolveUnownedDataMerge_spoofedLocalCompletedJournalWithoutRemoteCommit_doesNotFinalizeForForeignUid(): Unit = runBlocking {
        val emailA = "spoof_a_${UUID.randomUUID()}@test.com"
        val emailB = "spoof_b_${UUID.randomUUID()}@test.com"
        val uidA = signInTestUser(emailA)

        val dev = TestDevice("SpoofCompleted", freshInstall = false)
        dev.repo.createPlaylist("Must Not Drop On Spoofed Completed", "")
        val cA = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.syncNowAndAwait()).isFalse() }

        // Pause at CLOUD_SYNC_PENDING, then overwrite state to COMPLETED without remote sync baselines/lastSuccess
        cA.onAfterQuarantineCloudSyncPendingBeforeSync = { _, _ ->
            throw IllegalStateException("Stop at CLOUD_SYNC_PENDING")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.resolveUnownedDataMergeAndAwait()).isFalse() }
        val journalFile = File(dev.dir, "sync_state/unowned_quarantine_resolution_journal.json")
        val updated = journalFile.readText().replace("CLOUD_SYNC_PENDING", "COMPLETED")
        journalFile.writeText(updated)
        cA.shutdown()

        // Account B signs in -> must NOT treat premature COMPLETED journal as globally finalized!
        signInTestUser(emailB)
        val cB = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB.syncNowAndAwait()).isFalse() }
        assertThat(cB.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(cB.readQuarantineFile()?.playlists?.map { it.title })
            .containsExactly("Must Not Drop On Spoofed Completed")
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists).isEmpty()
        cB.shutdown()
    }

    @Test
    fun resolveUnownedDataMerge_firstCloudFetchFailsAndPartialLikesSucceedPlaylistFails_retainsPayloadAndConvergesIdempotently(): Unit = runBlocking {
        val emailA = "p1_partial_a_${UUID.randomUUID()}@test.com"
        val emailB = "p1_partial_b_${UUID.randomUUID()}@test.com"
        val uidA = signInTestUser(emailA)

        val dev = TestDevice("P1PartialSync", freshInstall = false)
        val plId = dev.repo.createPlaylist("Partial Retry Mix", "Keep ops intact")
        dev.repo.addTrackToPlaylist(plId, SongItem("pr_1", "Song 1", "Art"))
        dev.repo.addTrackToPlaylist(plId, SongItem("pr_2", "Song 2", "Art"))
        dev.repo.toggleLike(SongItem("pr_like", "Partial Liked", "Art"))
        dev.dao.insertSavedAlbum(SavedAlbumEntity("dev_partial_keep", "Keep Alb", "Local", null))

        // Step 1: First cloud call fails outright
        val failFetchRepo = CloudSyncRepository(firestore, auth).apply {
            onBeforeFetchAccountSnapshot = { throw IOException("Initial network offline") }
        }
        val cFirstFail = dev.createCoordinator(customCloudRepo = failFetchRepo, autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cFirstFail.syncNowAndAwait()).isFalse() }
        assertThat(cFirstFail.authState.value.hasUnownedDataConflict).isTrue()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cFirstFail.resolveUnownedDataMergeAndAwait()).isFalse() }

        // Local restored, journal = CLOUD_SYNC_PENDING, retained payload kept!
        assertThat(dev.dao.getPlaylistsOnce().map { it.title }).containsExactly("Partial Retry Mix")
        assertThat(cFirstFail.readQuarantineResolutionJournal()?.state).isEqualTo(QuarantineResolutionState.CLOUD_SYNC_PENDING)
        assertThat(cFirstFail.listRetainedPayloadFiles()).hasSize(1)
        assertThat(cFirstFail.authState.value.errorMessage).isNotNull()
        cFirstFail.shutdown()

        // Step 2: Process restarts under A, Likes succeed in Firestore while Playlists fail!
        val partialFailRepo = CloudSyncRepository(firestore, auth).apply {
            onBeforePublishTransaction = { _, _ ->
                throw IOException("Playlist publish dropped mid-sync")
            }
        }
        val cPartialFail = dev.createCoordinator(customCloudRepo = partialFailRepo, autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cPartialFail.syncNowAndAwait()).isFalse() }

        // Likes reached A's cloud, playlists did NOT -> must NOT finalize!
        val midSnapA = CloudSyncRepository(firestore, auth).fetchAccountSnapshot()
        assertThat(midSnapA.likedSongs.map { it.videoId }).containsExactly("pr_like")
        assertThat(midSnapA.playlists).isEmpty()
        assertThat(cPartialFail.readQuarantineResolutionJournal()?.state).isEqualTo(QuarantineResolutionState.CLOUD_SYNC_PENDING)
        assertThat(cPartialFail.listRetainedPayloadFiles()).hasSize(1)
        cPartialFail.shutdown()

        // Step 3: While still CLOUD_SYNC_PENDING after partial upload, if B signs in -> B stays blocked and quarantine is restored
        signInTestUser(emailB)
        val cB = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB.syncNowAndAwait()).isFalse() }
        assertThat(cB.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(cB.readQuarantineFile()?.playlists?.map { it.title }).containsExactly("Partial Retry Mix")
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists).isEmpty()
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().likedSongs).isEmpty()
        cB.shutdown()

        // Step 4: Original account A signs back in and retries twice -> converges cleanly without duplicate playlists/tracks/ops!
        signInTestUser(emailA)
        val cA2 = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA2.syncNowAndAwait()).isFalse() }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA2.resolveUnownedDataMergeAndAwait()).isTrue() }
        // Second duplicate call is also completely idempotent
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA2.syncNowAndAwait()).isTrue() }

        assertThat(cA2.readQuarantineFile()).isNull()
        assertThat(cA2.readQuarantineResolutionJournal()).isNull()
        assertThat(cA2.listRetainedPayloadFiles()).isEmpty()
        val pls = dev.dao.getPlaylistsOnce()
        assertThat(pls.map { it.title }).containsExactly("Partial Retry Mix")
        assertThat(dev.dao.getPlaylistTracks(pls.single().id).map { it.videoId })
            .containsExactly("pr_1", "pr_2").inOrder()
        val finalSnapA = CloudSyncRepository(firestore, auth).fetchAccountSnapshot()
        assertThat(finalSnapA.playlists.map { it.playlist.title }).containsExactly("Partial Retry Mix")
        assertThat(finalSnapA.playlists.single().subcollectionTracks.filter { !it.deleted }.map { it.videoId })
            .containsExactly("pr_1", "pr_2").inOrder()
        assertThat(finalSnapA.likedSongs.map { it.videoId }).containsExactly("pr_like")
        assertThat(dev.dao.getSavedAlbumsOnce().map { it.albumId }).containsExactly("dev_partial_keep")
        cA2.shutdown()
    }

    @Test
    fun resolveUnownedDataMerge_differentAccountSignsInAfterRemoteFinalization_switchesCleanlyWithoutLeakingA(): Unit = runBlocking {
        val emailA = "p1_fin_a_${UUID.randomUUID()}@test.com"
        val emailB = "p1_fin_b_${UUID.randomUUID()}@test.com"
        val uidA = signInTestUser(emailA)

        // Seed Account B with its own playlist first
        val uidB = signInTestUser(emailB)
        CloudSyncRepository(firestore, auth).upsertPlaylist(
            playlistKey = "pl_b_owned",
            title = "Bob Final Cloud",
            description = "",
            coverUri = null,
            tracks = listOf(SongItem("b1", "Bob Song", "Bob")),
            clientUpdatedAt = System.currentTimeMillis(),
            syncRevision = 1L
        )

        signInTestUser(emailA)
        val dev = TestDevice("P1PostCommitB", freshInstall = false)
        dev.repo.createPlaylist("Alice Finalized Mix", "")
        dev.dao.insertSavedAlbum(SavedAlbumEntity("dev_post_fin", "Keep Device", "Local", null))
        val cA = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.syncNowAndAwait()).isFalse() }

        // Simulate crash AFTER CLOUD_SYNC_COMMITTED (and COMPLETED) written, BEFORE journal/retained cleanup!
        cA.onAfterQuarantineJournalCompletedBeforeCleanup = { _, _ ->
            throw IllegalStateException("Crash after remote commit & COMPLETED before disk cleanup")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.resolveUnownedDataMergeAndAwait()).isFalse() }
        assertThat(cA.readQuarantineResolutionJournal()?.state).isEqualTo(QuarantineResolutionState.COMPLETED)
        assertThat(cA.listRetainedPayloadFiles()).hasSize(1)
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists.map { it.playlist.title })
            .containsExactly("Alice Finalized Mix")
        cA.shutdown()

        // Different account B signs in directly -> cleans up finalized A journal cleanly, wipes A's Room rows, syncs B!
        signInTestUser(emailB)
        val cB = dev.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB.syncNowAndAwait()).isTrue() }
        assertThat(cB.readQuarantineResolutionJournal()).isNull()
        assertThat(cB.readQuarantineFile()).isNull()
        assertThat(cB.listRetainedPayloadFiles()).isEmpty()
        assertThat(dev.prefsRepo.getActiveCloudUid()).isEqualTo(uidB)
        assertThat(dev.dao.getPlaylistsOnce().map { it.title }).containsExactly("Bob Final Cloud")
        assertThat(dev.dao.getSavedAlbumsOnce().map { it.albumId }).containsExactly("dev_post_fin")
        val snapB = CloudSyncRepository(firestore, auth).fetchAccountSnapshot()
        assertThat(snapB.playlists.map { it.playlist.title }).containsExactly("Bob Final Cloud")

        // Confirm A's cloud still has Alice's finalized mix untouched
        signInTestUser(emailA)
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists.map { it.playlist.title })
            .containsExactly("Alice Finalized Mix")
        assertThat(uidA).isNotEqualTo(uidB)
        cB.shutdown()
    }

    @Test
    fun resolveUnownedDataMerge_duplicateRecoveryAttemptsAndCancellationDuringRetry_remainIdempotentAndRecover(): Unit = runBlocking {
        val email = "p1_cancel_dup_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        val dev = TestDevice("P1CancelDup", freshInstall = false)
        val plId = dev.repo.createPlaylist("Cancellable Retry Mix", "")
        dev.repo.addTrackToPlaylist(plId, SongItem("cr_1", "Song 1", "Art"))

        val enteredTx = CompletableDeferred<Unit>()
        val holdTx = CompletableDeferred<Unit>()
        val slowRepo = CloudSyncRepository(firestore, auth).apply {
            onBeforePublishTransaction = { _, _ ->
                enteredTx.complete(Unit)
                holdTx.await()
            }
        }
        val c = dev.createCoordinator(customCloudRepo = slowRepo, autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c.syncNowAndAwait()).isFalse() }

        // Launch merge & cancel while cloud playlist publish is mid-flight
        val job = launch { c.resolveUnownedDataMergeAndAwait() }
        withTimeout(SYNC_TIMEOUT_MS) { enteredTx.await() }
        job.cancelAndJoin()

        assertThat(c.authState.value.isSyncing).isFalse()
        assertThat(c.readQuarantineResolutionJournal()?.state).isEqualTo(QuarantineResolutionState.CLOUD_SYNC_PENDING)
        assertThat(c.listRetainedPayloadFiles()).hasSize(1)

        // Duplicate recovery attempts after clearing fault converge idempotently without duplicating playlists/tracks/ops
        slowRepo.onBeforePublishTransaction = null
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c.resolveUnownedDataMergeAndAwait()).isTrue() }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(c.resolveUnownedDataMergeAndAwait()).isTrue() }
        assertThat(c.readQuarantineResolutionJournal()).isNull()
        assertThat(c.listRetainedPayloadFiles()).isEmpty()
        val pls = dev.dao.getPlaylistsOnce()
        assertThat(pls).hasSize(1)
        assertThat(dev.dao.getPlaylistTracks(pls.single().id).map { it.videoId }).containsExactly("cr_1")
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists.map { it.playlist.title })
            .containsExactly("Cancellable Retry Mix")
        c.shutdown()
    }

    @Test
    fun resolveUnownedDataMerge_corruptOrMissingRetainedPayloadAtCloudPending_failsClosedWithoutWipingOrUploading(): Unit = runBlocking {
        val emailA = "p1_corrupt_ret_a_${UUID.randomUUID()}@test.com"
        val emailB = "p1_corrupt_ret_b_${UUID.randomUUID()}@test.com"
        signInTestUser(emailA)

        // Subcase 1: Retained payload deleted while CLOUD_SYNC_PENDING under SAME account A -> fails closed!
        val devMissingSame = TestDevice("P1MissingRetA", freshInstall = false)
        devMissingSame.repo.createPlaylist("Keep Protected Local", "")
        val cA = devMissingSame.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.syncNowAndAwait()).isFalse() }
        cA.onAfterQuarantineCloudSyncPendingBeforeSync = { _, _ ->
            throw IllegalStateException("Crash at CLOUD_SYNC_PENDING")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA.resolveUnownedDataMergeAndAwait()).isFalse() }
        cA.listRetainedPayloadFiles().forEach { it.delete() }
        cA.shutdown()

        val cAResume = devMissingSame.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cAResume.syncNowAndAwait()).isFalse() }
        assertThat(cAResume.authState.value.syncStatus).isEqualTo("Quarantine recovery error")
        assertThat(cAResume.readQuarantineResolutionJournal()?.state).isEqualTo(QuarantineResolutionState.CLOUD_SYNC_PENDING)
        assertThat(devMissingSame.dao.getPlaylistsOnce().map { it.title }).containsExactly("Keep Protected Local")
        cAResume.shutdown()

        // Subcase 2: Retained payload corrupted while CLOUD_SYNC_PENDING and foreign B signs in -> fails closed!
        val devCorruptRet = TestDevice("P1CorruptRetB", freshInstall = false)
        devCorruptRet.repo.createPlaylist("Corrupt Archive Protected", "")
        val cA2 = devCorruptRet.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA2.syncNowAndAwait()).isFalse() }
        cA2.onAfterQuarantineCloudSyncPendingBeforeSync = { _, _ ->
            throw IllegalStateException("Crash at CLOUD_SYNC_PENDING")
        }
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cA2.resolveUnownedDataMergeAndAwait()).isFalse() }
        cA2.listRetainedPayloadFiles().forEach { it.writeText("{corrupt retained payload") }
        cA2.shutdown()

        signInTestUser(emailB)
        val cB = devCorruptRet.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cB.syncNowAndAwait()).isFalse() }
        assertThat(cB.authState.value.syncStatus).isEqualTo("Quarantine recovery error")
        assertThat(devCorruptRet.dao.getPlaylistsOnce().map { it.title }).containsExactly("Corrupt Archive Protected")
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists).isEmpty()
        cB.shutdown()
    }

    // =========================================================================
    // P1: Single Ordered Startup Pipeline (Restore Recovery -> Provenance -> Auth -> Sync)
    // =========================================================================

    @Test
    fun startupInitializationCoordinator_recoversInterruptedRestoreBeforeProvenanceAndBlocksOnFailure(): Unit = runBlocking {
        val email = "p1_startup_order_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        // Subcase 1: Interrupted backup restore leaves transient playlists in Room alongside a PREPARING/ROOM_APPLIED restore journal.
        // Shared StartupInitializationCoordinator MUST recover restore & stabilize DB BEFORE provenance classification,
        // and concurrent callers (app startup + coordinator) must observe ONLY the recovered CLEAN FRESH_LOCAL state!
        val dev = TestDevice("P1StartupOrder", freshInstall = false)
        val backupPl = dev.repo.createPlaylist("Transient Restored Mix", "From failed backup import")
        dev.repo.addTrackToPlaylist(backupPl, SongItem("bk_1", "Bk Song", "Artist"))
        val backupJson = dev.repo.exportBackupJson(dev.prefsRepo.prefsFlow.first())
        dev.dao.clearSyncedAccountData() // clean before import so original snapshot is empty!
        assertThat(dev.dao.getPlaylistsOnce()).isEmpty()
        assertThat(dev.prefsRepo.getLocalDataProvenance()).isNull()

        // Inject process crash mid-restore right after Room applied transient backup playlist
        // and prevent the import catch block from completing its normal rollback.
        dev.repo.onBeforeDataStoreRestore = {
            throw IllegalStateException("Crash mid backup import after Room apply")
        }
        dev.repo.onBeforeRollbackAttempt = {
            throw IllegalStateException("Simulated process interruption before rollback completes")
        }
        val importResult = dev.repo.importBackupJson(backupJson)
        assertThat(importResult.isFailure).isTrue()
        dev.repo.onBeforeDataStoreRestore = null
        dev.repo.onBeforeRollbackAttempt = null
        // Transient playlist is currently sitting in Room with an uncommitted restore journal!
        assertThat(dev.repo.hasUncommittedRestoreJournal()).isTrue()
        assertThat(dev.dao.getPlaylistsOnce().map { it.title }).containsExactly("Transient Restored Mix")

        val sharedStartup = StartupInitializationCoordinator(dev.repo, dev.prefsRepo, dev.dao)
        dev.repo.runWithSyncSuppressed = null // simulate early startup container or real suppression wired

        val provenanceSawCleanRoom = AtomicBoolean(false)
        val concurrentStartCount = AtomicInteger(0)
        sharedStartup.onAfterRestoreRecoveryBeforeVerification = {
            concurrentStartCount.incrementAndGet()
            // Restore recovery has already rolled back the transient playlist BEFORE verification/provenance!
            assertThat(dev.dao.getPlaylistsOnce()).isEmpty()
            assertThat(dev.repo.hasUncommittedRestoreJournal()).isFalse()
        }
        sharedStartup.onBeforeProvenanceInitialization = {
            provenanceSawCleanRoom.set(dev.dao.getPlaylistsOnce().isEmpty() && !dev.repo.hasUncommittedRestoreJournal())
        }

        val c = dev.createCoordinator(
            autoStartSync = false,
            awaitStartupProvenance = { sharedStartup.ensureInitialized() }
        )
        // Simulate VelunaApplication startupJob + CloudSyncCoordinator.syncNowAndAwait() racing at launch
        val appInitJob = async(Dispatchers.IO) { sharedStartup.ensureInitialized() }
        val syncJob = async(Dispatchers.IO) { c.syncNowAndAwait() }
        withTimeout(SYNC_TIMEOUT_MS) {
            appInitJob.await()
            assertThat(syncJob.await()).isTrue()
        }

        assertThat(concurrentStartCount.get()).isEqualTo(1)
        assertThat(provenanceSawCleanRoom.get()).isTrue()
        assertThat(sharedStartup.isInitialized()).isTrue()
        // Because restore was recovered BEFORE provenance scan, device was rightly classified FRESH_LOCAL, not false UNKNOWN_OR_LEGACY!
        assertThat(dev.prefsRepo.getLocalDataProvenance()).isEqualTo(LocalDataProvenance.ACCOUNT_OWNED)
        assertThat(c.authState.value.hasUnownedDataConflict).isFalse()
        assertThat(dev.dao.getPlaylistsOnce()).isEmpty()
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists).isEmpty()
        c.shutdown()

        // Subcase 2: Failing/corrupt startup restore blocks ownership check, Firestore sync, and background watchers!
        val devFailStartup = TestDevice("P1StartupFail", freshInstall = false)
        File(devFailStartup.dir, "journal").apply { mkdirs() }
            .resolve("restore_journal.json")
            .writeText("{corrupt restore journal")
        val failStartup = StartupInitializationCoordinator(devFailStartup.repo, devFailStartup.prefsRepo, devFailStartup.dao)
        var firestoreTouched = false
        val probeRepo = CloudSyncRepository(firestore, auth).apply {
            onBeforeFetchAccountSnapshot = { firestoreTouched = true }
        }
        val cFail = devFailStartup.createCoordinator(
            customCloudRepo = probeRepo,
            autoStartSync = true,
            awaitStartupProvenance = { failStartup.ensureInitialized() }
        )
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cFail.syncNowAndAwait()).isFalse() }
        assertThat(failStartup.isInitialized()).isFalse()
        assertThat(devFailStartup.prefsRepo.getActiveCloudUid()).isNull()
        assertThat(firestoreTouched).isFalse()
        assertThat(cFail.authState.value.errorMessage).isNotNull()

        // Verify watchers were NOT launched even if local Room mutates afterward!
        devFailStartup.repo.createPlaylist("Should Not Auto Watch Push", "")
        delay(800L)
        assertThat(firestoreTouched).isFalse()
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().playlists).isEmpty()
        cFail.shutdown()
    }

    // =========================================================================
    // P2: Profile-Only Provenance Detection, Quarantine, Merge/Discard & Device-Only Separation
    // =========================================================================

    @Test
    fun profileOnlyUnknownOwnerStates_allTriggerQuarantineAndRestoreOrClearSyncableProfileSafely(): Unit = runBlocking {
        val email = "p2_profile_q_${UUID.randomUUID()}@test.com"
        signInTestUser(email)

        // Helper verifying unowned profile-only state triggers quarantine & clears cloud-syncable keys while keeping device-only keys
        suspend fun verifyProfileOnlyQuarantine(
            label: String,
            mutate: suspend (TestDevice) -> Unit,
            assertQuarantine: (UnownedAccountDataQuarantine) -> Unit,
            testMerge: Boolean = false
        ) {
            val dev = TestDevice(label, freshInstall = false)
            // Populate device-only settings + SecretVault to verify preservation
            dev.prefsRepo.setPlaybackSpeed(1.75f)
            dev.prefsRepo.setEqualizer(true, "Bass", listOf(4f, 3f, 2f, 1f, 0f, 0f, 0f, 1f, 2f, 3f))
            dev.prefsRepo.setClassicPlayerLayout(true)
            dev.prefsRepo.setAllowMirrorFallbacks(true)
            dev.prefsRepo.setLyricsProvidersOrder("lrclib,-boidu")
            dev.prefsRepo.setRomanizeLyrics(true)
            dev.prefsRepo.setShowMoodChips(false)
            dev.prefsRepo.setShowHeroArtworkBackdrop(false)
            dev.prefsRepo.setScrobblingConfig(true, "fm_user", "k1", "s1", "sess1", "lb1")

            mutate(dev)
            dev.prefsRepo.initializeInstallProvenanceIfClean(dev.dao)
            assertThat(dev.prefsRepo.getLocalDataProvenance()).isEqualTo(LocalDataProvenance.UNKNOWN_OR_LEGACY)

            val c = dev.createCoordinator(autoStartSync = false)
            withTimeout(SYNC_TIMEOUT_MS) { assertThat(c.syncNowAndAwait()).isFalse() }
            assertThat(c.authState.value.hasUnownedDataConflict).isTrue()
            assertThat(c.authState.value.unownedDataItemCount).isAtLeast(1)
            val q = c.readQuarantineFile()!!
            assertQuarantine(q)

            // Syncable profile prefs were cleared while device-only prefs & SecretVault remained intact!
            val postQuarantinePrefs = dev.prefsRepo.prefsFlow.first()
            assertThat(postQuarantinePrefs.userDisplayName).isEqualTo("Listener")
            assertThat(postQuarantinePrefs.themePreset).isEqualTo(VelunaThemePreset.DEFAULT)
            assertThat(postQuarantinePrefs.themeMode).isEqualTo(ThemeMode.DARK)
            assertThat(postQuarantinePrefs.audioQuality).isEqualTo(AudioQuality.HIGH)
            assertThat(postQuarantinePrefs.playbackSpeed).isEqualTo(1.75f)
            assertThat(postQuarantinePrefs.eqEnabled).isTrue()
            assertThat(postQuarantinePrefs.eqPresetName).isEqualTo("Bass")
            assertThat(postQuarantinePrefs.classicPlayerLayout).isTrue()
            assertThat(postQuarantinePrefs.allowMirrorFallbacks).isTrue()
            assertThat(postQuarantinePrefs.lyricsProviderOrder).contains("lrclib")
            assertThat(postQuarantinePrefs.romanizeLyrics).isTrue()
            assertThat(postQuarantinePrefs.showMoodChips).isFalse()
            assertThat(postQuarantinePrefs.showHeroArtworkBackdrop).isFalse()
            assertThat(postQuarantinePrefs.lastFmSessionKey).isEqualTo("sess1")
            assertThat(postQuarantinePrefs.listenBrainzToken).isEqualTo("lb1")

            if (testMerge) {
                withTimeout(SYNC_TIMEOUT_MS) { assertThat(c.resolveUnownedDataMergeAndAwait()).isTrue() }
            } else {
                withTimeout(SYNC_TIMEOUT_MS) { assertThat(c.resolveUnownedDataDiscardAndAwait()).isTrue() }
            }
            // Device-only prefs survived Merge/Discard untouched too!
            val finalPrefs = dev.prefsRepo.prefsFlow.first()
            assertThat(finalPrefs.playbackSpeed).isEqualTo(1.75f)
            assertThat(finalPrefs.eqEnabled).isTrue()
            assertThat(finalPrefs.lastFmSessionKey).isEqualTo("sess1")
            assertThat(finalPrefs.listenBrainzToken).isEqualTo("lb1")
            c.shutdown()
        }

        // 1. PREFS_UPDATED_AT only
        verifyProfileOnlyQuarantine(
            label = "P2PrefsTsOnly",
            mutate = { it.prefsRepo.setPrefsUpdatedAt(1_750_000_000_000L) },
            assertQuarantine = { assertThat(it.prefsUpdatedAtMs).isEqualTo(1_750_000_000_000L) }
        )

        // 2. onboardingCompleted only
        verifyProfileOnlyQuarantine(
            label = "P2OnboardOnly",
            mutate = { it.prefsRepo.saveTasteProfile(TasteProfile(onboardingCompleted = true), touchProfileTimestamp = false) },
            assertQuarantine = { assertThat(it.tasteProfile?.onboardingCompleted).isTrue() }
        )

        // 3. displayName only
        verifyProfileOnlyQuarantine(
            label = "P2DisplayNameOnly",
            mutate = { it.prefsRepo.setDisplayName("LegacyProfileUser") },
            assertQuarantine = { assertThat(it.displayName).isEqualTo("LegacyProfileUser") },
            testMerge = true
        )

        // Verify Merged displayName synced to cloud!
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().profile?.displayName)
            .isEqualTo("LegacyProfileUser")

        // 4. selectedGenres only (fresh email so previous displayName cloud doesn't interfere)
        signInTestUser("p2_genres_${UUID.randomUUID()}@test.com")
        verifyProfileOnlyQuarantine(
            label = "P2GenresOnly",
            mutate = {
                it.prefsRepo.saveTasteProfile(TasteProfile(selectedGenres = listOf("Synthwave", "Indie")), touchProfileTimestamp = true)
            },
            assertQuarantine = { assertThat(it.tasteProfile?.selectedGenres).containsExactly("Synthwave", "Indie") },
            testMerge = true
        )
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().profile?.selectedGenres)
            .containsExactly("Synthwave", "Indie")

        // 5. Non-default cloud-syncable theme & audio preferences only -> Merge restores them & Discard clears them!
        signInTestUser("p2_theme_audio_${UUID.randomUUID()}@test.com")
        verifyProfileOnlyQuarantine(
            label = "P2ThemeAudioMerge",
            mutate = {
                it.prefsRepo.setThemePreset(VelunaThemePreset.TOKYO_NIGHT)
                it.prefsRepo.setThemeMode(ThemeMode.LIGHT)
                it.prefsRepo.setCustomAccent("#FF3366")
                it.prefsRepo.setAdaptToArtwork(false)
                it.prefsRepo.setCornerRadius(20)
                it.prefsRepo.setAudioQuality(AudioQuality.LOW)
                it.prefsRepo.setAutoplay(false)
                it.prefsRepo.setLoudnessNormalization(false)
                it.prefsRepo.setHideMusicVideos(true)
            },
            assertQuarantine = {
                assertThat(it.profilePrefs?.themeModeName).isEqualTo(ThemeMode.LIGHT.name)
                assertThat(it.profilePrefs?.customAccentHex).isEqualTo("#FF3366")
                assertThat(it.profilePrefs?.audioQualityName).isEqualTo(AudioQuality.LOW.name)
                assertThat(it.profilePrefs?.hideMusicVideos).isTrue()
            },
            testMerge = true
        )
        val cloudThemeProfile = CloudSyncRepository(firestore, auth).fetchAccountSnapshot().profile!!
        assertThat(cloudThemeProfile.themePreset).isEqualTo(VelunaThemePreset.TOKYO_NIGHT.id)
        assertThat(cloudThemeProfile.themeMode).isEqualTo(ThemeMode.LIGHT.name)
        assertThat(cloudThemeProfile.customAccentHex).isEqualTo("#FF3366")
        assertThat(cloudThemeProfile.adaptToArtwork).isFalse()
        assertThat(cloudThemeProfile.cornerRadiusDp).isEqualTo(20L)
        assertThat(cloudThemeProfile.audioQuality).isEqualTo(AudioQuality.LOW.name)
        assertThat(cloudThemeProfile.autoplay).isFalse()
        assertThat(cloudThemeProfile.loudnessNormalization).isFalse()
        assertThat(cloudThemeProfile.hideMusicVideos).isTrue()

        signInTestUser("p2_theme_discard_${UUID.randomUUID()}@test.com")
        val devThemeDiscard = TestDevice("P2ThemeAudioDiscard", freshInstall = false)
        devThemeDiscard.prefsRepo.setThemeMode(ThemeMode.LIGHT)
        devThemeDiscard.prefsRepo.setCustomAccent("#11AA22")
        val cDiscardTheme = devThemeDiscard.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cDiscardTheme.syncNowAndAwait()).isFalse() }
        assertThat(cDiscardTheme.authState.value.hasUnownedDataConflict).isTrue()
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cDiscardTheme.resolveUnownedDataDiscardAndAwait()).isTrue() }
        assertThat(devThemeDiscard.prefsRepo.prefsFlow.first().themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(devThemeDiscard.prefsRepo.prefsFlow.first().customAccentHex).isNull()
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().profile?.themeMode)
            .isEqualTo(ThemeMode.DARK.name)
        cDiscardTheme.shutdown()
    }

    @Test
    fun freshInstall_deviceOnlySettingsVsOfflineProfileEdits_signOutAndMissingUidAccountOwnedProfileBehavior(): Unit = runBlocking {
        val emailA = "p2_fresh_a_${UUID.randomUUID()}@test.com"
        val emailB = "p2_fresh_b_${UUID.randomUUID()}@test.com"
        val uidA = signInTestUser(emailA)

        // 1. Fresh install with ONLY device-only settings + SecretVault -> stays FRESH_LOCAL & no false quarantine!
        val devDeviceOnly = TestDevice("P2DeviceOnly", freshInstall = false)
        devDeviceOnly.prefsRepo.setPlaybackSpeed(1.25f)
        devDeviceOnly.prefsRepo.setEqualizer(true, "Rock", listOf(3f, 2f, 1f, 0f, -1f, 0f, 1f, 2f, 3f, 4f))
        devDeviceOnly.prefsRepo.setClassicPlayerLayout(true)
        devDeviceOnly.prefsRepo.setAllowMirrorFallbacks(true)
        devDeviceOnly.prefsRepo.setLyricsProvidersOrder("youtube,-lrclib")
        devDeviceOnly.prefsRepo.setRomanizeLyrics(true)
        devDeviceOnly.prefsRepo.setShowMoodChips(false)
        devDeviceOnly.prefsRepo.setShowHeroArtworkBackdrop(false)
        devDeviceOnly.prefsRepo.setScrobblingConfig(true, "dev_only_fm", "dk", "ds", "dsess", "dlb")
        devDeviceOnly.prefsRepo.initializeInstallProvenanceIfClean(devDeviceOnly.dao)
        assertThat(devDeviceOnly.prefsRepo.getLocalDataProvenance()).isEqualTo(LocalDataProvenance.FRESH_LOCAL)

        val cDevOnly = devDeviceOnly.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cDevOnly.syncNowAndAwait()).isTrue() }
        assertThat(cDevOnly.authState.value.hasUnownedDataConflict).isFalse()
        assertThat(devDeviceOnly.prefsRepo.prefsFlow.first().playbackSpeed).isEqualTo(1.25f)
        assertThat(devDeviceOnly.prefsRepo.prefsFlow.first().lastFmSessionKey).isEqualTo("dsess")
        cDevOnly.shutdown()

        // 2. Genuine fresh install -> offline profile edits before first sign-in adopt cleanly without quarantine
        val emailFresh = "p2_fresh_prof_${UUID.randomUUID()}@test.com"
        signInTestUser(emailFresh)
        val devFresh = TestDevice("P2FreshOfflineProfile", freshInstall = true)
        assertThat(devFresh.prefsRepo.getLocalDataProvenance()).isEqualTo(LocalDataProvenance.FRESH_LOCAL)
        devFresh.prefsRepo.setPlaybackSpeed(1.4f)
        devFresh.prefsRepo.setScrobblingConfig(true, "fresh_fm", "fk", "fs", "fsess", "flb")
        devFresh.prefsRepo.setDisplayName("Fresh Offline Profile")
        devFresh.prefsRepo.setThemeMode(ThemeMode.LIGHT)
        devFresh.prefsRepo.saveTasteProfile(
            TasteProfile(onboardingCompleted = true, selectedGenres = listOf("Jazz", "Lo-Fi")),
            touchProfileTimestamp = true
        )

        val cFresh = devFresh.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cFresh.syncNowAndAwait()).isTrue() }
        assertThat(cFresh.authState.value.hasUnownedDataConflict).isFalse()
        assertThat(devFresh.prefsRepo.getLocalDataProvenance()).isEqualTo(LocalDataProvenance.ACCOUNT_OWNED)
        val cloudFreshProfile = CloudSyncRepository(firestore, auth).fetchAccountSnapshot().profile!!
        assertThat(cloudFreshProfile.displayName).isEqualTo("Fresh Offline Profile")
        assertThat(cloudFreshProfile.themeMode).isEqualTo(ThemeMode.LIGHT.name)
        assertThat(cloudFreshProfile.selectedGenres).containsExactly("Jazz", "Lo-Fi")

        // 3. Ordinary sign-out resets ALL cloud-syncable profile/theme/audio keys and provenance while keeping device-only & SecretVault
        cFresh.signOut()
        val signOutDeadline = System.currentTimeMillis() + 10_000L
        while ((cFresh.authState.value.isSignedIn || cFresh.authState.value.isAuthBusy) && System.currentTimeMillis() < signOutDeadline) {
            delay(25L)
        }
        cFresh.shutdown()
        assertThat(devFresh.prefsRepo.getLocalDataProvenance()).isEqualTo(LocalDataProvenance.FRESH_LOCAL)
        assertThat(devFresh.prefsRepo.getActiveCloudUid()).isNull()
        val afterSignOutPrefs = devFresh.prefsRepo.prefsFlow.first()
        assertThat(afterSignOutPrefs.userDisplayName).isEqualTo("Listener")
        assertThat(afterSignOutPrefs.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(afterSignOutPrefs.audioQuality).isEqualTo(AudioQuality.HIGH)
        assertThat(devFresh.prefsRepo.tasteProfileFlow.first().onboardingCompleted).isFalse()
        assertThat(devFresh.prefsRepo.tasteProfileFlow.first().selectedGenres).isEmpty()
        assertThat(devFresh.prefsRepo.hasSyncableProfileState()).isFalse()
        // Device-only & SecretVault preserved
        assertThat(afterSignOutPrefs.playbackSpeed).isEqualTo(1.4f)
        assertThat(afterSignOutPrefs.lastFmSessionKey).isEqualTo("fsess")
        assertThat(afterSignOutPrefs.listenBrainzToken).isEqualTo("flb")

        // 4. ACCOUNT_OWNED profile-only state with missing ACTIVE_CLOUD_UID & missing baselines fails closed for Account B
        val devAccOwnedProf = TestDevice("P2AccOwnedMissingUid", freshInstall = true)
        devAccOwnedProf.prefsRepo.setDisplayName("Alice Profile Only")
        signInTestUser(emailA)
        val cAccA = devAccOwnedProf.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cAccA.syncNowAndAwait()).isTrue() }
        cAccA.shutdown()
        assertThat(devAccOwnedProf.prefsRepo.getLocalDataProvenance()).isEqualTo(LocalDataProvenance.ACCOUNT_OWNED)
        assertThat(devAccOwnedProf.prefsRepo.getActiveCloudUid()).isEqualTo(uidA)

        // Simulate missing ACTIVE_CLOUD_UID & cleared baselines while ACCOUNT_OWNED profile-only prefs remain
        devAccOwnedProf.prefsRepo.setActiveCloudUid(null)
        devAccOwnedProf.dao.clearAllSyncBaselines()
        signInTestUser(emailB)
        val cAccB = devAccOwnedProf.createCoordinator(autoStartSync = false)
        withTimeout(SYNC_TIMEOUT_MS) { assertThat(cAccB.syncNowAndAwait()).isFalse() }
        assertThat(cAccB.authState.value.hasUnownedDataConflict).isTrue()
        assertThat(cAccB.readQuarantineFile()?.displayName).isEqualTo("Alice Profile Only")
        // Account B's cloud must NOT inherit Alice's profile
        assertThat(CloudSyncRepository(firestore, auth).fetchAccountSnapshot().profile).isNull()
        cAccB.shutdown()
    }

    private companion object {
        const val SYNC_TIMEOUT_MS = 15_000L
    }
}

