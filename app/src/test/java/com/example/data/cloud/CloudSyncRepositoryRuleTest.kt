package com.example.data.cloud

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.local.SyncEntityTypes
import com.example.data.local.SyncTombstoneEntity
import com.example.data.local.VelunaUserPrefs
import com.example.model.LOCAL_SONG_PREFIX
import com.example.model.SongItem
import com.example.model.TasteProfile
import com.example.model.TasteSeed
import com.google.common.truth.Truth.assertThat
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class CloudSyncRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun authenticatedOwner_canSyncProfileSeedsLikesPlaylistsAndTombstones() : Unit = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = CloudSyncRepository(firestore, auth)

        val tasteProfile = TasteProfile(
            onboardingCompleted = true,
            selectedGenres = listOf("Electronic", "Indie"),
            seedArtists = listOf(TasteSeed(id = "UC_daft", name = "Daft Punk")),
            seedSongs = listOf(TasteSeed(id = "trk_abc", name = "Instant Crush"))
        )
        val songId = "trk_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val plKey = "pl_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val sampleSong = SongItem(
            videoId = songId,
            title = "Instant Crush",
            artists = "Daft Punk",
            duration = "5:37"
        )

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertUserProfile(
                prefs = VelunaUserPrefs(userDisplayName = "Alice"),
                tasteProfile = tasteProfile,
                photoUrl = "https://example.com/alice.png"
            )
            repo.upsertTasteSeeds(tasteProfile.seedArtists, tasteProfile.seedSongs)
            repo.syncLikedSong(sampleSong, liked = true)
            repo.upsertPlaylist(
                playlistKey = plKey,
                title = "Focus Mix",
                description = "Chill electronics",
                coverUri = null,
                tracks = listOf(sampleSong),
                clientUpdatedAt = System.currentTimeMillis()
            )
        }

        val snapshot = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        assertThat(snapshot.profile?.userId).isEqualTo(uid)
        assertThat(snapshot.profile?.displayName).isEqualTo("Alice")
        assertThat(snapshot.likedSongs.map { it.videoId }).contains(songId)
        val syncedPlaylist = snapshot.playlists.first { it.playlist.playlistKey == plKey }
        assertThat(syncedPlaylist.playlist.tracks).isEmpty()
        assertThat(syncedPlaylist.subcollectionTracks.map { it.videoId }).containsExactly(songId)

        // Tombstone propagation on unlike + playlist delete + empty seed tombstones
        val tombNow = System.currentTimeMillis()
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.syncLikedSong(sampleSong, liked = false, likedAtMs = tombNow, clientUpdatedAtMs = tombNow)
            repo.deletePlaylist(plKey, clientUpdatedAt = tombNow)
            repo.upsertTasteSeeds(
                artists = emptyList(),
                songs = emptyList(),
                tombstones = listOf(
                    SyncTombstoneEntity(SyncEntityTypes.SEED, "UC_daft", tombNow, tombNow),
                    SyncTombstoneEntity(SyncEntityTypes.SEED, CloudSyncRepository.resolveSeedDocId("sng", tasteProfile.seedSongs.first()), tombNow, tombNow)
                ),
                nowMs = tombNow
            )
        }

        val postTombstone = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        assertThat(postTombstone.likedSongs.first { it.videoId == songId }.deleted).isTrue()
        assertThat(postTombstone.playlists.first { it.playlist.playlistKey == plKey }.playlist.deleted).isTrue()
        assertThat(postTombstone.tasteSeeds.filter { !it.deleted }).isEmpty()
    }

    @Test
    fun localTracks_areNeverUploadedToLikedSongs() : Unit = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = CloudSyncRepository(firestore, auth)
        val localSong = SongItem(
            videoId = "${LOCAL_SONG_PREFIX}content://media/external/audio/1",
            title = "Local Riff",
            artists = "Me",
            isLocal = true
        )
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.syncLikedSong(localSong, liked = true)
        }
        val snapshot = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        assertThat(snapshot.likedSongs.none { it.videoId.startsWith(LOCAL_SONG_PREFIX) }).isTrue()
    }

    @Test
    fun deleteAllUserData_wipesProfileAndSubcollections() : Unit = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = CloudSyncRepository(firestore, auth)
        val songId = "trk_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val plKey = "pl_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val song = SongItem(videoId = songId, title = "Delete Me", artists = "Artist")

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertUserProfile(VelunaUserPrefs(userDisplayName = "Alice"), TasteProfile(), ALICE_EMAIL, null)
            repo.syncLikedSong(song, liked = true)
            repo.upsertPlaylist(plKey, "Temp", "", null, listOf(song), System.currentTimeMillis())
            repo.deleteAllUserData(uid)
        }

        val afterDelete = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        assertThat(afterDelete.profile).isNull()
        assertThat(afterDelete.likedSongs).isEmpty()
        assertThat(afterDelete.playlists).isEmpty()
    }

    @Test
    fun purgeExpiredCloudTombstones_deletesAllTracksBeforePlaylistParent() : Unit = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = CloudSyncRepository(firestore, auth)
        val now = System.currentTimeMillis()
        val expiredAt = now - 40L * 24L * 60L * 60L * 1000L
        val playlist = firestore.collection("users").document(uid)
            .collection("playlists").document("pl_expired")
        val tracks = playlist.collection("tracks")

        withTimeout(30_000L) {
            playlist.set(
                mapOf(
                    "deleted" to true,
                    "clientUpdatedAt" to expiredAt,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            repeat(2) { batchIndex ->
                val batch = firestore.batch()
                val start = batchIndex * 400
                val end = minOf(start + 400, 450)
                for (index in start until end) {
                    val trackId = "trk_$index"
                    batch.set(
                        tracks.document(trackId),
                        mapOf(
                            "videoId" to trackId,
                            "title" to "Track $index",
                            "artists" to "Artist",
                            "durationSecs" to 180,
                            "explicit" to false,
                            "position" to index,
                            "addedAt" to expiredAt,
                            "deleted" to false,
                            "clientUpdatedAt" to expiredAt,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.commit().await()
            }

            repo.purgeExpiredCloudTombstones(uid, now - 30L * 24L * 60L * 60L * 1000L)
        }

        assertThat(tracks.get().await().documents).isEmpty()
        assertThat(playlist.get().await().exists()).isFalse()
    }

    @Test
    fun unauthenticatedCaller_rejectedBySecurityRules() : Unit = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        auth.signOut()
        val repo = CloudSyncRepository(firestore, auth)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeLikedSongs(aliceUid).first()
            }
            fail("Expected PERMISSION_DENIED for unauthenticated listener")
        } catch (e: Exception) {
            val fsError = checkNotNull(e.unwrapFirestoreException()) {
                "Expected nested FirebaseFirestoreException, got $e"
            }
            assertThat(fsError.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
        }
    }

    @Test
    fun crossUserAccess_rejectedBySecurityRules() : Unit = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = CloudSyncRepository(firestore, auth)
        val songId = "trk_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.syncLikedSong(
                SongItem(videoId = songId, title = "Secret Track", artists = "Artist"),
                liked = true
            )
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = CloudSyncRepository(firestore, auth)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeLikedSongs(aliceUid).first()
            }
            fail("Expected PERMISSION_DENIED for cross-user read")
        } catch (e: Exception) {
            val fsError = checkNotNull(e.unwrapFirestoreException()) {
                "Expected nested FirebaseFirestoreException, got $e"
            }
            assertThat(fsError.code).isEqualTo(FirebaseFirestoreException.Code.PERMISSION_DENIED)
        }
    }

    @Test
    fun upsertPlaylist_chunksOver450TracksAtomicallyAndRestoresOrder() : Unit = runBlocking {
        val email = "chunk_${UUID.randomUUID()}@test.com"
        signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val plKey = "pl_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val tracks = (0 until 460).map { i ->
            SongItem(
                videoId = "trk_%04d".format(i),
                title = "Track $i",
                artists = "Artist ${i % 10}",
                duration = "3:10",
                addedAt = 1700000000000L + i
            )
        }

        withTimeout(60_000L) {
            repo.upsertPlaylist(
                playlistKey = plKey,
                title = "Mega Mix",
                description = "460 tracks across batches",
                coverUri = "https://example.com/mega.jpg",
                tracks = tracks,
                clientUpdatedAt = System.currentTimeMillis(),
                syncRevision = 1L
            )
        }

        val snapshot = withTimeout(30_000L) { repo.fetchAccountSnapshot() }
        val loaded = snapshot.playlists.first { it.playlist.playlistKey == plKey }
        assertThat(loaded.playlist.syncRevision).isEqualTo(1L)
        assertThat(loaded.playlist.trackCount).isEqualTo(460L)
        assertThat(loaded.playlist.activeSnapshotId).isNotNull()
        assertThat(loaded.subcollectionTracks).hasSize(460)
        val orderedIds = loaded.subcollectionTracks.sortedBy { it.position }.map { it.videoId }
        assertThat(orderedIds.first()).isEqualTo("trk_0000")
        assertThat(orderedIds.last()).isEqualTo("trk_0459")
    }

    @Test
    fun upsertPlaylist_partialChunkFailureKeepsPreviousActiveSnapshotIntact() : Unit = runBlocking {
        val email = "atomic_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val plKey = "pl_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        val v1Tracks = (0 until 5).map { i ->
            SongItem(videoId = "v1_$i", title = "V1 $i", artists = "Artist")
        }
        withTimeout(20_000L) {
            repo.upsertPlaylist(
                playlistKey = plKey,
                title = "V1 Playlist",
                description = "Stable",
                coverUri = null,
                tracks = v1Tracks,
                clientUpdatedAt = System.currentTimeMillis() - 1_000L,
                syncRevision = 1L
            )
        }
        val beforeSnap = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        val v1SnapshotId = beforeSnap.playlists.first { it.playlist.playlistKey == plKey }.playlist.activeSnapshotId
        assertThat(v1SnapshotId).isNotNull()

        val v2Tracks = (0 until 420).map { i ->
            SongItem(videoId = "v2_$i", title = "V2 $i", artists = "Artist")
        }
        repo.onBeforeCommitPlaylistSnapshot = { _, chunkIdx ->
            if (chunkIdx == 1) {
                throw IllegalStateException("Simulated network drop before chunk 1")
            }
        }

        var failed = false
        try {
            withTimeout(20_000L) {
                repo.upsertPlaylist(
                    playlistKey = plKey,
                    title = "V2 Interrupted",
                    description = "Should not publish",
                    coverUri = null,
                    tracks = v2Tracks,
                    clientUpdatedAt = System.currentTimeMillis(),
                    syncRevision = 2L
                )
            }
        } catch (_: IllegalStateException) {
            failed = true
        } finally {
            repo.onBeforeCommitPlaylistSnapshot = null
        }

        assertThat(failed).isTrue()
        val afterSnap = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        val intact = afterSnap.playlists.first { it.playlist.playlistKey == plKey }
        assertThat(intact.playlist.syncRevision).isEqualTo(1L)
        assertThat(intact.playlist.title).isEqualTo("V1 Playlist")
        assertThat(intact.playlist.activeSnapshotId).isEqualTo(v1SnapshotId)
        assertThat(intact.subcollectionTracks.map { it.videoId })
            .containsExactly("v1_0", "v1_1", "v1_2", "v1_3", "v1_4")

        val snapDocs = firestore.collection("users").document(uid)
            .collection("playlists").document(plKey)
            .collection("snapshots").get().await().documents
        assertThat(snapDocs.map { it.id }).containsExactly(v1SnapshotId)
    }

    @Test
    fun upsertPlaylist_staleRevisionRejectedAndStagingSnapshotCleanedUp() : Unit = runBlocking {
        val email = "stale_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val plKey = "pl_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertPlaylist(
                playlistKey = plKey,
                title = "Rev 3 Title",
                description = "Newest",
                coverUri = null,
                tracks = listOf(SongItem(videoId = "trk_new", title = "New", artists = "Artist")),
                clientUpdatedAt = System.currentTimeMillis(),
                syncRevision = 3L
            )
        }

        var threwStale = false
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                repo.upsertPlaylist(
                    playlistKey = plKey,
                    title = "Stale Rev 2",
                    description = "Old",
                    coverUri = null,
                    tracks = listOf(SongItem(videoId = "trk_old", title = "Old", artists = "Artist")),
                    clientUpdatedAt = System.currentTimeMillis() - 10_000L,
                    syncRevision = 2L
                )
            }
        } catch (_: StalePlaylistRevisionException) {
            threwStale = true
        }

        assertThat(threwStale).isTrue()
        val snap = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        val playlist = snap.playlists.first { it.playlist.playlistKey == plKey }
        assertThat(playlist.playlist.syncRevision).isEqualTo(3L)
        assertThat(playlist.playlist.title).isEqualTo("Rev 3 Title")
        assertThat(playlist.subcollectionTracks.map { it.videoId }).containsExactly("trk_new")

        val snapDocs = firestore.collection("users").document(uid)
            .collection("playlists").document(plKey)
            .collection("snapshots").get().await().documents
        assertThat(snapDocs).hasSize(1)
    }

    @Test
    fun legacyPlaylistTracks_readFallbackAndMigratedOnSnapshotPublish() : Unit = runBlocking {
        val email = "legacy_pl_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val plKey = "pl_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val now = System.currentTimeMillis()
        val plRef = firestore.collection("users").document(uid).collection("playlists").document(plKey)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            plRef.set(
                mapOf(
                    "title" to "Legacy Playlist",
                    "description" to "Pre-snapshot",
                    "deleted" to false,
                    "clientUpdatedAt" to now,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            plRef.collection("tracks").document("trk_leg").set(
                mapOf(
                    "videoId" to "trk_leg",
                    "title" to "Legacy Track",
                    "artists" to "Old Artist",
                    "durationSecs" to 195L,
                    "explicit" to false,
                    "position" to 0,
                    "addedAt" to now,
                    "deleted" to false,
                    "clientUpdatedAt" to now,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }

        val initial = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        val legacyLoaded = initial.playlists.first { it.playlist.playlistKey == plKey }
        assertThat(legacyLoaded.playlist.activeSnapshotId).isNull()
        assertThat(legacyLoaded.subcollectionTracks.map { it.videoId }).containsExactly("trk_leg")

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertPlaylist(
                playlistKey = plKey,
                title = "Migrated Playlist",
                description = "Now snapshotted",
                coverUri = null,
                tracks = listOf(SongItem(videoId = "trk_leg", title = "Legacy Track", artists = "Old Artist")),
                clientUpdatedAt = now + 1_000L,
                syncRevision = 1L,
                removeLegacyTracksField = true
            )
        }

        assertThat(plRef.collection("tracks").get().await().documents).isEmpty()
        val migrated = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        val migratedPl = migrated.playlists.first { it.playlist.playlistKey == plKey }
        assertThat(migratedPl.playlist.activeSnapshotId).isNotNull()
        assertThat(migratedPl.subcollectionTracks.map { it.videoId }).containsExactly("trk_leg")
    }

    @Test
    fun upsertUserProfile_persistsPrefsUpdatedAtAndPurgesExpiredSnapshotTracks() : Unit = runBlocking {
        val email = "prefs_ts_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val prefsTs = System.currentTimeMillis() - 5_000L

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertUserProfile(
                prefs = VelunaUserPrefs(userDisplayName = "Casey", cornerRadiusDp = 20),
                tasteProfile = TasteProfile(onboardingCompleted = true, selectedGenres = listOf("Soul")),
                photoUrl = null,
                prefsUpdatedAtMs = prefsTs
            )
        }
        val profile = withTimeout(DEFAULT_TIMEOUT_MS) { repo.getUserProfile() }
        assertThat(profile?.prefsUpdatedAt).isEqualTo(prefsTs)
        assertThat(profile?.cornerRadiusDp).isEqualTo(20)

        // Seed an expired deleted playlist with a snapshot/tracks subtree and verify purge removes everything
        val plRef = firestore.collection("users").document(uid).collection("playlists").document("pl_exp_snap")
        val snapRef = plRef.collection("snapshots").document("snap_1_abc")
        val expiredAt = System.currentTimeMillis() - 40L * 24L * 60L * 60L * 1000L
        withTimeout(DEFAULT_TIMEOUT_MS) {
            plRef.set(
                mapOf(
                    "deleted" to true,
                    "clientUpdatedAt" to expiredAt,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            snapRef.set(
                mapOf(
                    "state" to "STAGING",
                    "syncRevision" to 1L,
                    "trackCount" to 1L,
                    "clientUpdatedAt" to expiredAt,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            snapRef.collection("tracks").document("0000_trk_1").set(
                mapOf(
                    "videoId" to "trk_1",
                    "title" to "Old",
                    "artists" to "Artist",
                    "durationSecs" to 100L,
                    "explicit" to false,
                    "position" to 0,
                    "addedAt" to expiredAt,
                    "deleted" to false,
                    "clientUpdatedAt" to expiredAt,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            snapRef.update(
                mapOf(
                    "state" to "COMMITTED",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            repo.purgeExpiredCloudTombstones(uid, System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L)
        }

        assertThat(snapRef.collection("tracks").get().await().documents).isEmpty()
        assertThat(plRef.collection("snapshots").get().await().documents).isEmpty()
        assertThat(plRef.get().await().exists()).isFalse()
    }

    @Test
    fun fetchAccountSnapshot_throwsIncompleteSnapshotException_onMismatchedTracks_andRulesBlockActiveSnapshotDelete(): Unit = runBlocking {
        val email = "snap_corrupt_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val now = System.currentTimeMillis()
        val plRef = firestore.collection("users").document(uid).collection("playlists").document("pl_broken")
        val snapRef = plRef.collection("snapshots").document("snap_mismatch")

        // Stage 1 track while claiming trackCount = 2, transition STAGING -> COMMITTED, and publish
        withTimeout(DEFAULT_TIMEOUT_MS) {
            snapRef.set(
                mapOf(
                    "state" to "STAGING",
                    "syncRevision" to 3L,
                    "trackCount" to 2L,
                    "clientUpdatedAt" to now,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            snapRef.collection("tracks").document("trk_00000_one").set(
                mapOf(
                    "videoId" to "one",
                    "title" to "One",
                    "artists" to "A",
                    "durationSecs" to 120L,
                    "explicit" to false,
                    "position" to 0,
                    "addedAt" to now,
                    "deleted" to false,
                    "clientUpdatedAt" to now,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            snapRef.update(
                mapOf(
                    "state" to "COMMITTED",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            plRef.set(
                mapOf(
                    "title" to "Broken Snap",
                    "description" to "",
                    "deleted" to false,
                    "syncRevision" to 3L,
                    "activeSnapshotId" to "snap_mismatch",
                    "trackCount" to 2L,
                    "clientUpdatedAt" to now,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }

        var caughtMismatch: IncompletePlaylistSnapshotException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        } catch (e: IncompletePlaylistSnapshotException) {
            caughtMismatch = e
        }
        assertThat(caughtMismatch).isNotNull()
        assertThat(caughtMismatch!!.message).contains("expected 2 tracks")

        // Hardened Firestore rules must refuse transitioning or deleting an actively referenced snapshot/track
        var deniedTrackDelete = false
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                snapRef.collection("tracks").document("trk_00000_one").delete().await()
            }
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) deniedTrackDelete = true
        }
        assertThat(deniedTrackDelete).isTrue()

        var deniedDeletingTransition = false
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                snapRef.update(mapOf("state" to "DELETING", "updatedAt" to FieldValue.serverTimestamp())).await()
            }
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) deniedDeletingTransition = true
        }
        assertThat(deniedDeletingTransition).isTrue()

        var deniedManifestDelete = false
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) { snapRef.delete().await() }
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) deniedManifestDelete = true
        }
        assertThat(deniedManifestDelete).isTrue()
    }

    @Test
    fun garbageCollectPlaylistSnapshots_keepsActiveAndRecentSnapshots_andDeletesExpiredOldOnes(): Unit = runBlocking {
        val email = "gc_snap_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val plKey = "pl_gc_test"
        val now = System.currentTimeMillis()
        val oldTs = now - 14L * 24L * 3600L * 1000L

        for (rev in 1L..5L) {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                repo.upsertPlaylist(
                    playlistKey = plKey,
                    title = "Mix v$rev",
                    description = "Rev $rev",
                    coverUri = null,
                    tracks = listOf(SongItem(videoId = "trk_$rev", title = "T$rev", artists = "Artist")),
                    clientUpdatedAt = if (rev <= 3L) oldTs + rev else now,
                    syncRevision = rev
                )
            }
        }

        val plRef = firestore.collection("users").document(uid).collection("playlists").document(plKey)
        val snapsBefore = plRef.collection("snapshots").get().await().documents
        assertThat(snapsBefore).hasSize(5)

        // Old clientUpdatedAt alone must NEVER cause newly uploaded snapshots to be deleted
        val deletedTooSoon = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.garbageCollectPlaylistSnapshots(
                playlistKey = plKey,
                retentionWindowMs = 7L * 24L * 3600L * 1000L,
                retainCommittedCount = 2,
                nowMs = System.currentTimeMillis()
            )
        }
        assertThat(deletedTooSoon).isEqualTo(0)

        // Once genuine server createdAt exceeds the 7-day window, top-2 committed (rev 4 & 5) stay and rev 1..3 are deleted
        val maxServerCreatedMs = snapsBefore.maxOf { it.getTimestamp("createdAt")?.toDate()?.time ?: now }
        val deleted = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.garbageCollectPlaylistSnapshots(
                playlistKey = plKey,
                retentionWindowMs = 7L * 24L * 3600L * 1000L,
                retainCommittedCount = 2,
                nowMs = maxServerCreatedMs + 8L * 24L * 3600L * 1000L
            )
        }
        assertThat(deleted).isEqualTo(3)

        val snapsAfter = plRef.collection("snapshots").get().await().documents
        assertThat(snapsAfter.mapNotNull { it.getLong("syncRevision") }).containsExactly(4L, 5L)
        val activeSnapId = plRef.get().await().getString("activeSnapshotId")
        assertThat(snapsAfter.map { it.id }).contains(activeSnapId)
    }

    @Test
    fun upsertPlaylist_afterDeleteTombstone_blocksStaleRevisionAndAllowsHigherRevision(): Unit = runBlocking {
        val email = "tomb_rev_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val plKey = "pl_tomb_rev"
        val t0 = System.currentTimeMillis() - 10_000L

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertPlaylist(
                playlistKey = plKey,
                title = "Initial",
                description = "v2",
                coverUri = null,
                tracks = listOf(SongItem(videoId = "trk_1", title = "T1", artists = "A")),
                clientUpdatedAt = t0,
                syncRevision = 2L
            )
            repo.deletePlaylist(plKey, clientUpdatedAt = t0 + 1_000L, syncRevision = 2L)
        }

        var blocked = false
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                repo.upsertPlaylist(
                    playlistKey = plKey,
                    title = "Stale Resurrection",
                    description = "",
                    coverUri = null,
                    tracks = listOf(SongItem(videoId = "trk_old", title = "Old", artists = "A")),
                    clientUpdatedAt = t0 + 2_000L,
                    syncRevision = 2L
                )
            }
        } catch (_: StalePlaylistRevisionException) {
            blocked = true
        }
        assertThat(blocked).isTrue()

        val plRef = firestore.collection("users").document(uid).collection("playlists").document(plKey)
        assertThat(plRef.get().await().getBoolean("deleted")).isTrue()

        // Valid recreation with strictly higher syncRevision succeeds
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertPlaylist(
                playlistKey = plKey,
                title = "Recreated",
                description = "v3",
                coverUri = null,
                tracks = listOf(SongItem(videoId = "trk_new", title = "New", artists = "A")),
                clientUpdatedAt = t0 + 3_000L,
                syncRevision = 3L
            )
        }
        val fetched = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.fetchAccountSnapshot().playlists.first { it.playlist.playlistKey == plKey }
        }
        assertThat(fetched.playlist.deleted).isFalse()
        assertThat(fetched.playlist.title).isEqualTo("Recreated")
        assertThat(fetched.subcollectionTracks.map { it.videoId }).containsExactly("trk_new")
    }

    @Test
    fun concurrentUpsertPlaylist_losersThrowStaleAndDoNotCorruptWinnerSnapshot(): Unit = runBlocking {
        val email = "race_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val writerSlow = CloudSyncRepository(firestore, auth)
        val writerFast = CloudSyncRepository(firestore, auth)
        val plKey = "pl_race"
        val t0 = System.currentTimeMillis() - 5_000L

        val slowReachedPublish = CompletableDeferred<Unit>()
        val allowSlowToPublish = CompletableDeferred<Unit>()
        writerSlow.onBeforePublishTransaction = { _, _ ->
            slowReachedPublish.complete(Unit)
            allowSlowToPublish.await()
        }

        val slowJob = async {
            runCatching {
                writerSlow.upsertPlaylist(
                    playlistKey = plKey,
                    title = "Writer A Older",
                    description = "Older TS",
                    coverUri = null,
                    tracks = listOf(SongItem(videoId = "trk_a", title = "A", artists = "Artist")),
                    clientUpdatedAt = t0,
                    syncRevision = 2L
                )
            }
        }

        withTimeout(DEFAULT_TIMEOUT_MS) { slowReachedPublish.await() }
        // Fast writer completes rev 2 with newer timestamp before slow writer's publish transaction
        withTimeout(DEFAULT_TIMEOUT_MS) {
            writerFast.upsertPlaylist(
                playlistKey = plKey,
                title = "Writer B Winner",
                description = "Newer TS",
                coverUri = null,
                tracks = listOf(SongItem(videoId = "trk_b", title = "B", artists = "Artist")),
                clientUpdatedAt = t0 + 2_000L,
                syncRevision = 2L
            )
        }
        allowSlowToPublish.complete(Unit)

        val slowResult = withTimeout(DEFAULT_TIMEOUT_MS) { slowJob.await() }
        assertThat(slowResult.exceptionOrNull()).isInstanceOf(StalePlaylistRevisionException::class.java)

        val loaded = withTimeout(DEFAULT_TIMEOUT_MS) {
            writerFast.fetchAccountSnapshot().playlists.first { it.playlist.playlistKey == plKey }
        }
        assertThat(loaded.playlist.title).isEqualTo("Writer B Winner")
        assertThat(loaded.subcollectionTracks.map { it.videoId }).containsExactly("trk_b")

        // Stale writer's staging snapshot was cleaned up cleanly
        val snapshots = firestore.collection("users").document(uid)
            .collection("playlists").document(plKey).collection("snapshots").get().await().documents
        assertThat(snapshots).hasSize(1)
    }

    @Test
    fun garbageCollectPlaylistSnapshots_protectsRecentStagingAndCollectsAbandonedStagingAfterWindow(): Unit = runBlocking {
        val email = "gc_staging_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val publisher = CloudSyncRepository(firestore, auth)
        val gcRunner = CloudSyncRepository(firestore, auth)
        val plKey = "pl_gc_stage"

        val inStaging = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()
        publisher.onBeforeCommitPlaylistSnapshot = { _, _ ->
            inStaging.complete(Unit)
            allowFinish.await()
        }

        val publishJob = async {
            publisher.upsertPlaylist(
                playlistKey = plKey,
                title = "Staging List",
                description = "",
                coverUri = null,
                tracks = listOf(SongItem("trk_s1", "S1", "A")),
                clientUpdatedAt = System.currentTimeMillis(),
                syncRevision = 1L
            )
        }

        withTimeout(DEFAULT_TIMEOUT_MS) { inStaging.await() }

        // While STAGING is active and recent, GC (even with aggressive retention) must not delete it
        val deletedWhileStaging = withTimeout(DEFAULT_TIMEOUT_MS) {
            gcRunner.garbageCollectPlaylistSnapshots(
                playlistKey = plKey,
                retentionWindowMs = 0L,
                retainCommittedCount = 1,
                nowMs = System.currentTimeMillis(),
                stagingRetentionMs = 60_000L
            )
        }
        assertThat(deletedWhileStaging).isEqualTo(0)

        allowFinish.complete(Unit)
        withTimeout(DEFAULT_TIMEOUT_MS) { publishJob.await() }

        val plRef = firestore.collection("users").document(uid).collection("playlists").document(plKey)
        // Create an abandoned STAGING snapshot doc manually
        val abandonedRef = plRef.collection("snapshots").document("snap_abandoned_1")
        abandonedRef.set(
            mapOf(
                "state" to "STAGING",
                "syncRevision" to 9L,
                "trackCount" to 1L,
                "clientUpdatedAt" to 1_000L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        abandonedRef.collection("tracks").document("trk_00000_x").set(
            mapOf(
                "videoId" to "x",
                "title" to "Orphan",
                "artists" to "A",
                "durationSecs" to 10L,
                "explicit" to false,
                "position" to 0,
                "addedAt" to 1_000L,
                "deleted" to false,
                "clientUpdatedAt" to 1_000L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()

        val abandonedCreatedMs = abandonedRef.get().await().getTimestamp("createdAt")?.toDate()?.time
            ?: System.currentTimeMillis()
        // Within 1h staging retention window -> preserved
        assertThat(
            gcRunner.garbageCollectPlaylistSnapshots(
                playlistKey = plKey,
                nowMs = abandonedCreatedMs + 30L * 60L * 1000L,
                stagingRetentionMs = 60L * 60L * 1000L
            )
        ).isEqualTo(0)
        assertThat(abandonedRef.get().await().exists()).isTrue()

        // After 1h staging retention window -> claimed DELETING & purged safely
        assertThat(
            gcRunner.garbageCollectPlaylistSnapshots(
                playlistKey = plKey,
                nowMs = abandonedCreatedMs + 2L * 60L * 60L * 1000L,
                stagingRetentionMs = 60L * 60L * 1000L
            )
        ).isEqualTo(1)
        assertThat(abandonedRef.get().await().exists()).isFalse()
        assertThat(abandonedRef.collection("tracks").get().await().documents).isEmpty()
    }

    @Test
    fun gcAndPublishRace_publicationWinsClaimRace_gcReReadsActiveSnapshotAndRefusesDeletion(): Unit = runBlocking {
        val email = "race_pub_wins_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val publisher = CloudSyncRepository(firestore, auth)
        val gcRunner = CloudSyncRepository(firestore, auth)
        val plKey = "pl_race_pub_wins"

        // Pre-seed a STAGING snapshot for rev 1 before GC runs, then let GC pause right before claim tx
        // while publisher completes staging upload and publishes rev 1 on the same snapshot path?
        // Simulate exact tx race: publisher commits rev 1 to COMMITTED & pauses before publish tx;
        // simultaneously stagingRetentionMs = 0L lets GC see COMMITTED snapshot only if not top-N, OR:
        // create staging snapshot doc via publisher hook paused before COMMITTED mark, then complete publish.
        val beforeBatchUpload = CompletableDeferred<Unit>()
        val proceedUploadAndPublish = CompletableDeferred<Unit>()
        val gcAtClaim = CompletableDeferred<Unit>()
        val releaseGcClaim = CompletableDeferred<Unit>()
        var targetSnapId: String? = null

        publisher.onBeforeCommitPlaylistSnapshot = { snapId, _ ->
            targetSnapId = snapId
            beforeBatchUpload.complete(Unit)
            proceedUploadAndPublish.await()
        }
        gcRunner.onBeforeGcClaim = { _, snapId ->
            if (snapId == targetSnapId) {
                gcAtClaim.complete(Unit)
                releaseGcClaim.await()
            }
        }

        val pubJob = async {
            publisher.upsertPlaylist(
                playlistKey = plKey,
                title = "Winner Active",
                description = "Active",
                coverUri = null,
                tracks = listOf(SongItem("trk_win", "Win", "Artist")),
                clientUpdatedAt = System.currentTimeMillis(),
                syncRevision = 1L
            )
        }

        withTimeout(DEFAULT_TIMEOUT_MS) { beforeBatchUpload.await() }
        val snapCreatedMs = firestore.collection("users").document(uid)
            .collection("playlists").document(plKey)
            .collection("snapshots").document(targetSnapId!!)
            .get().await().getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis()

        val gcJob = async {
            gcRunner.garbageCollectPlaylistSnapshots(
                playlistKey = plKey,
                retentionWindowMs = 0L,
                retainCommittedCount = 1,
                nowMs = snapCreatedMs + 10_000L,
                stagingRetentionMs = 0L
            )
        }

        withTimeout(DEFAULT_TIMEOUT_MS) { gcAtClaim.await() }
        // Publication wins first!
        proceedUploadAndPublish.complete(Unit)
        withTimeout(DEFAULT_TIMEOUT_MS) { pubJob.await() }

        // Now GC executes its claim tx -> must see freshParent.activeSnapshotId == targetSnapId and abort!
        releaseGcClaim.complete(Unit)
        val deleted = withTimeout(DEFAULT_TIMEOUT_MS) { gcJob.await() }
        assertThat(deleted).isEqualTo(0)

        val fetched = publisher.fetchAccountSnapshot().playlists.single { it.playlist.playlistKey == plKey }
        assertThat(fetched.playlist.activeSnapshotId).isEqualTo(targetSnapId)
        assertThat(fetched.subcollectionTracks.map { it.videoId }).containsExactly("trk_win")
    }

    @Test
    fun gcAndPublishRace_gcClaimsDeletingFirst_publisherAbortsCleanlyAndPreservesWinner(): Unit = runBlocking {
        val email = "race_gc_wins_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repoFast = CloudSyncRepository(firestore, auth)
        val repoSlow = CloudSyncRepository(firestore, auth)
        val gcRunner = CloudSyncRepository(firestore, auth)
        val plKey = "pl_race_gc_wins"

        // Publish rev 2 first so rev 2 is active and in top-1 committed
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repoFast.upsertPlaylist(
                playlistKey = plKey,
                title = "Rev 2 Winner",
                description = "",
                coverUri = null,
                tracks = listOf(SongItem("trk_r2", "R2", "A")),
                clientUpdatedAt = System.currentTimeMillis(),
                syncRevision = 2L
            )
        }

        // Slow writer uploads rev 1 snapshot to COMMITTED and pauses before its publish tx
        val slowReadyToPublish = CompletableDeferred<Unit>()
        val allowSlowPublish = CompletableDeferred<Unit>()
        repoSlow.onBeforePublishTransaction = { _, _ ->
            slowReadyToPublish.complete(Unit)
            allowSlowPublish.await()
        }

        val slowJob = async {
            runCatching {
                repoSlow.upsertPlaylist(
                    playlistKey = plKey,
                    title = "Stale Rev 1",
                    description = "",
                    coverUri = null,
                    tracks = listOf(SongItem("trk_r1", "R1", "A")),
                    clientUpdatedAt = System.currentTimeMillis() - 5_000L,
                    syncRevision = 1L
                )
            }
        }
        withTimeout(DEFAULT_TIMEOUT_MS) { slowReadyToPublish.await() }

        // GC claims & deletes superseded rev 1 (retainCommittedCount = 1 retains rev 2 only)
        val snapsNow = firestore.collection("users").document(uid)
            .collection("playlists").document(plKey).collection("snapshots").get().await().documents
        val maxCreated = snapsNow.maxOf { it.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis() }
        val gcDeleted = withTimeout(DEFAULT_TIMEOUT_MS) {
            gcRunner.garbageCollectPlaylistSnapshots(
                playlistKey = plKey,
                retentionWindowMs = 0L,
                retainCommittedCount = 1,
                nowMs = maxCreated + 10_000L
            )
        }
        assertThat(gcDeleted).isEqualTo(1)

        // Slow writer attempts publish tx after GC claimed/deleted its snapshot -> fails safely
        allowSlowPublish.complete(Unit)
        val slowRes = withTimeout(DEFAULT_TIMEOUT_MS) { slowJob.await() }
        assertThat(slowRes.exceptionOrNull()).isInstanceOf(IncompletePlaylistSnapshotException::class.java)

        val intact = repoFast.fetchAccountSnapshot().playlists.single { it.playlist.playlistKey == plKey }
        assertThat(intact.playlist.syncRevision).isEqualTo(2L)
        assertThat(intact.subcollectionTracks.map { it.videoId }).containsExactly("trk_r2")
    }

    @Test
    fun ambiguousPublishOutcome_doesNotDeleteCommittedActiveSnapshot_andDeferredGcKeepsIt(): Unit = runBlocking {
        val email = "ambig_tx_${UUID.randomUUID()}@test.com"
        signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val plKey = "pl_ambig"

        // Parent commit succeeds on server, then client experiences network drop / exception after tx commit
        repo.onAfterPublishTransactionSuccess = { _, _ ->
            throw RuntimeException("Simulated transport drop right after server commit")
        }

        var threw = false
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                repo.upsertPlaylist(
                    playlistKey = plKey,
                    title = "Ambiguous Commit",
                    description = "Must remain readable",
                    coverUri = null,
                    tracks = listOf(SongItem("trk_ambig", "Ambig", "Artist")),
                    clientUpdatedAt = System.currentTimeMillis(),
                    syncRevision = 1L
                )
            }
        } catch (_: RuntimeException) {
            threw = true
        } finally {
            repo.onAfterPublishTransactionSuccess = null
        }
        assertThat(threw).isTrue()

        // Even aggressive deferred GC must see activeSnapshotId and preserve the snapshot & tracks
        val deleted = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.garbageCollectPlaylistSnapshots(
                playlistKey = plKey,
                retentionWindowMs = 0L,
                retainCommittedCount = 1,
                nowMs = System.currentTimeMillis() + 365L * 24L * 3600L * 1000L,
                stagingRetentionMs = 0L
            )
        }
        assertThat(deleted).isEqualTo(0)

        val snap = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        val loaded = snap.playlists.single { it.playlist.playlistKey == plKey }
        assertThat(loaded.playlist.activeSnapshotId).isNotNull()
        assertThat(loaded.subcollectionTracks.map { it.videoId }).containsExactly("trk_ambig")
    }

    @Test
    fun readerDuringConcurrentPublish_readsCompletePreviousCommittedSnapshotWithoutMixedTracks(): Unit = runBlocking {
        val email = "concurrent_read_${UUID.randomUUID()}@test.com"
        signInTestUser(email)
        val writer = CloudSyncRepository(firestore, auth)
        val reader = CloudSyncRepository(firestore, auth)
        val plKey = "pl_reader_iso"

        withTimeout(DEFAULT_TIMEOUT_MS) {
            writer.upsertPlaylist(
                playlistKey = plKey,
                title = "V1",
                description = "",
                coverUri = null,
                tracks = (0 until 5).map { i -> SongItem("v1_$i", "V1 $i", "A") },
                clientUpdatedAt = System.currentTimeMillis() - 2_000L,
                syncRevision = 1L
            )
        }

        val readerPaused = CompletableDeferred<Unit>()
        val allowReaderTracks = CompletableDeferred<Unit>()
        reader.onBeforeReadSnapshotTracks = { _, _ ->
            readerPaused.complete(Unit)
            allowReaderTracks.await()
        }

        val readJob = async { reader.fetchAccountSnapshot() }
        withTimeout(DEFAULT_TIMEOUT_MS) { readerPaused.await() }

        // Meanwhile writer stages and publishes v2 with completely different tracks
        withTimeout(DEFAULT_TIMEOUT_MS) {
            writer.upsertPlaylist(
                playlistKey = plKey,
                title = "V2",
                description = "",
                coverUri = null,
                tracks = (0 until 3).map { i -> SongItem("v2_$i", "V2 $i", "A") },
                clientUpdatedAt = System.currentTimeMillis(),
                syncRevision = 2L
            )
        }

        allowReaderTracks.complete(Unit)
        val v1Read = withTimeout(DEFAULT_TIMEOUT_MS) { readJob.await() }
        val plRead = v1Read.playlists.single { it.playlist.playlistKey == plKey }
        assertThat(plRead.playlist.syncRevision).isEqualTo(1L)
        assertThat(plRead.subcollectionTracks.map { it.videoId })
            .containsExactly("v1_0", "v1_1", "v1_2", "v1_3", "v1_4").inOrder()
    }

    @Test
    fun gcInterruptedMidTrackDeletion_keepsManifestUntilAllTracksRemoved_andResumesCleanly(): Unit = runBlocking {
        val email = "gc_resume_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val plKey = "pl_gc_resume"

        // Rev 1 has 450 tracks (2 delete batches of 400 + 50), superseded by Rev 2
        val bigTracks = (0 until 450).map { i -> SongItem("big_$i", "Big $i", "Artist") }
        withTimeout(60_000L) {
            repo.upsertPlaylist(plKey, "Rev 1 Big", "", null, bigTracks, System.currentTimeMillis() - 2_000L, syncRevision = 1L)
            repo.upsertPlaylist(plKey, "Rev 2 Small", "", null, listOf(SongItem("small_0", "Small", "A")), System.currentTimeMillis(), syncRevision = 2L)
        }

        val plRef = firestore.collection("users").document(uid).collection("playlists").document(plKey)
        val rev1SnapId = plRef.collection("snapshots").get().await().documents
            .first { it.getLong("syncRevision") == 1L }.id
        val rev1MaxCreated = plRef.collection("snapshots").document(rev1SnapId).get().await()
            .getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis()

        // Inject failure on 2nd track batch delete -> batch 0 deletes 400 tracks, fails before batch 1
        repo.onBeforeGcDeleteTrackBatch = { _, _, batchIndex ->
            if (batchIndex == 1) throw IllegalStateException("Interrupted mid-track GC")
        }
        var failed = false
        try {
            withTimeout(30_000L) {
                repo.garbageCollectPlaylistSnapshots(
                    playlistKey = plKey,
                    retentionWindowMs = 0L,
                    retainCommittedCount = 1,
                    nowMs = rev1MaxCreated + 10_000L,
                    throwOnError = true
                )
            }
        } catch (_: IllegalStateException) {
            failed = true
        } finally {
            repo.onBeforeGcDeleteTrackBatch = null
        }
        assertThat(failed).isTrue()

        // Manifest MUST still exist in state DELETING with 50 tracks remaining
        val rev1AfterFail = plRef.collection("snapshots").document(rev1SnapId).get().await()
        assertThat(rev1AfterFail.exists()).isTrue()
        assertThat(rev1AfterFail.getString("state")).isEqualTo("DELETING")
        assertThat(plRef.collection("snapshots").document(rev1SnapId).collection("tracks").get().await().size()).isEqualTo(50)

        // Retry GC without fault -> resumes from DELETING, wipes remaining 50 tracks, then deletes manifest
        val resumedDeleted = withTimeout(30_000L) {
            repo.garbageCollectPlaylistSnapshots(
                playlistKey = plKey,
                retentionWindowMs = 0L,
                retainCommittedCount = 1,
                nowMs = rev1MaxCreated + 10_000L,
                throwOnError = true
            )
        }
        assertThat(resumedDeleted).isEqualTo(1)
        assertThat(plRef.collection("snapshots").document(rev1SnapId).get().await().exists()).isFalse()
        assertThat(plRef.collection("snapshots").document(rev1SnapId).collection("tracks").get().await().isEmpty).isTrue()
    }

    @Test
    fun upsertPlaylist_emptyTracklessPlaylist_publishesAndReadsEmpty(): Unit = runBlocking {
        val email = "empty_snap_${UUID.randomUUID()}@test.com"
        signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val plKey = "pl_empty_tracks"

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertPlaylist(
                playlistKey = plKey,
                title = "Empty Mix",
                description = "No tracks yet",
                coverUri = null,
                tracks = emptyList(),
                clientUpdatedAt = System.currentTimeMillis(),
                syncRevision = 1L
            )
        }
        val fetched = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.fetchAccountSnapshot().playlists.single { it.playlist.playlistKey == plKey }
        }
        assertThat(fetched.playlist.trackCount).isEqualTo(0L)
        assertThat(fetched.playlist.activeSnapshotId).isNotNull()
        assertThat(fetched.subcollectionTracks).isEmpty()
    }

    @Test
    fun fetchAccountSnapshot_watermarkOverlapHandlesSameTimestampDocsWithoutSkipping(): Unit = runBlocking {
        val email = "overlap_${UUID.randomUUID()}@test.com"
        signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val baseTs = System.currentTimeMillis() - 1_000L

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.syncLikedSong(SongItem("trk_1", "Song 1", "A"), liked = true, likedAtMs = baseTs)
        }
        val snap1 = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        val wm1 = snap1.maxLikedUpdatedAtMs
        assertThat(wm1).isGreaterThan(0L)

        // Second doc created within the 10-second overlap window is still returned when syncing from wm1
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.syncLikedSong(SongItem("trk_2", "Song 2", "B"), liked = true, likedAtMs = baseTs + 1L)
        }
        val incremental = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.fetchAccountSnapshot(SyncWatermarks(likedMs = wm1))
        }
        assertThat(incremental.likedSongs.map { it.videoId }).containsAtLeast("trk_1", "trk_2")
    }

    @Test
    fun fetchAccountSnapshot_paginatesAcrossPagesWithIdenticalServerTimestamps(): Unit = runBlocking {
        val email = "paginate_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth).apply {
            collectionQueryPageSize = 3L
        }

        // Write 8 documents in a single Firestore batch so every doc receives the exact same server updatedAt
        val col = firestore.collection("users").document(uid).collection("liked_songs")
        val batch = firestore.batch()
        for (i in 0 until 8) {
            batch.set(
                col.document("trk_page_%02d".format(i)),
                mapOf(
                    "title" to "Paged Song $i",
                    "artists" to "Artist",
                    "durationSecs" to 180L,
                    "explicit" to false,
                    "likedAt" to 1700000000000L,
                    "deleted" to false,
                    "clientUpdatedAt" to 1700000000000L,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
        batch.commit().await()

        val full = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        assertThat(full.likedSongs.map { it.videoId }).containsExactlyElementsIn(
            (0 until 8).map { "trk_page_%02d".format(it) }
        )

        val incremental = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.fetchAccountSnapshot(SyncWatermarks(likedMs = full.maxLikedUpdatedAtMs))
        }
        assertThat(incremental.likedSongs.map { it.videoId }).containsExactlyElementsIn(
            (0 until 8).map { "trk_page_%02d".format(it) }
        )
    }

    @Test
    fun purgeExpiredCloudTombstones_persistsCompactedWatermarkAndGenerationOnProfile(): Unit = runBlocking {
        val email = "compact_meta_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val now = System.currentTimeMillis()
        val oldDeletedAt = now - 45L * 24L * 3600L * 1000L
        val cutoff = now - 30L * 24L * 3600L * 1000L

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertUserProfile(VelunaUserPrefs(userDisplayName = "Compactor"), TasteProfile(), email, null)
            repo.syncLikedSong(SongItem("trk_old_del", "Gone", "A"), liked = false, likedAtMs = oldDeletedAt, clientUpdatedAtMs = oldDeletedAt)
            repo.syncLikedSong(SongItem("trk_active", "Stay", "A"), liked = true, likedAtMs = now, clientUpdatedAtMs = now)
            repo.purgeExpiredCloudTombstones(uid, cutoff)
        }

        val after = withTimeout(DEFAULT_TIMEOUT_MS) { repo.fetchAccountSnapshot() }
        assertThat(after.profile?.tombstoneCompactedBeforeMs).isEqualTo(cutoff)
        assertThat(after.profile?.syncGeneration).isAtLeast(1L)
        assertThat(after.likedSongs.map { it.videoId }).containsExactly("trk_active")
    }

    @Test
    fun garbageCollectPlaylistSnapshots_usesAuthoritativeServerTime_notSkewedDeviceClock(): Unit = runBlocking {
        val email = "gc_clock_skew_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)
        val plKey = "pl_clock_skew"
        val now = System.currentTimeMillis()

        // Create 4 revisions so rev 1 is outside retainCommittedCount=1 and rev 2..4 would be eligible if expired
        for (rev in 1L..2L) {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                repo.upsertPlaylist(
                    playlistKey = plKey,
                    title = "Skew Pl $rev",
                    description = "",
                    coverUri = null,
                    tracks = listOf(SongItem("trk_$rev", "T$rev", "Artist")),
                    clientUpdatedAt = now - 5_000L,
                    syncRevision = rev
                )
            }
        }
        val plRef = firestore.collection("users").document(uid).collection("playlists").document(plKey)
        val stagingRef = plRef.collection("snapshots").document("snap_abandoned_fresh")
        withTimeout(DEFAULT_TIMEOUT_MS) {
            stagingRef.set(
                mapOf(
                    "state" to "STAGING",
                    "syncRevision" to 3L,
                    "trackCount" to 0L,
                    "clientUpdatedAt" to now,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }

        // 1. Default GC path uses genuine Firestore server time (sync_meta/clock), NOT device wall clock:
        //    neither fresh STAGING nor fresh superseded COMMITTED snapshot is deleted.
        val deletedWithRealServerTime = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.garbageCollectPlaylistSnapshots(
                playlistKey = plKey,
                retentionWindowMs = 7L * 24L * 3600L * 1000L,
                retainCommittedCount = 1,
                stagingRetentionMs = 60L * 60L * 1000L,
                throwOnError = true
            )
        }
        assertThat(deletedWithRealServerTime).isEqualTo(0)
        assertThat(stagingRef.get().await().exists()).isTrue()

        // 2. When authoritative server time cannot be fetched (offline), GC fails safely (retains non-DELETING snapshots)
        repo.serverTimeProviderOverride = { null }
        val deletedWhenServerTimeMissing = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.garbageCollectPlaylistSnapshots(
                playlistKey = plKey,
                retentionWindowMs = 0L,
                retainCommittedCount = 1,
                stagingRetentionMs = 0L,
                throwOnError = true
            )
        }
        assertThat(deletedWhenServerTimeMissing).isEqualTo(0)
        assertThat(plRef.collection("snapshots").get().await().size()).isEqualTo(3)

        // 3. When authoritative server time advances beyond retention windows, stale STAGING and superseded COMMITTED
        //    snapshots are cleanly reclaimed while active rev 2 stays intact.
        val newestCreatedMs = plRef.collection("snapshots").get().await().documents
            .maxOf { it.getTimestamp("createdAt")?.toDate()?.time ?: now }
        repo.serverTimeProviderOverride = { newestCreatedMs + 10L * 24L * 3600L * 1000L }
        val deletedAfterServerExpiry = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.garbageCollectPlaylistSnapshots(
                playlistKey = plKey,
                retentionWindowMs = 7L * 24L * 3600L * 1000L,
                retainCommittedCount = 1,
                stagingRetentionMs = 60L * 60L * 1000L,
                throwOnError = true
            )
        }
        repo.serverTimeProviderOverride = null
        assertThat(deletedAfterServerExpiry).isEqualTo(2)
        val remaining = plRef.collection("snapshots").get().await().documents
        assertThat(remaining.mapNotNull { it.getLong("syncRevision") }).containsExactly(2L)
    }

    @Test
    fun deleteAllUserData_underStrictSnapshotRules_deletesActiveSnapshotsAndSyncMetaCleanly(): Unit = runBlocking {
        val email = "del_strict_${UUID.randomUUID()}@test.com"
        val uid = signInTestUser(email)
        val repo = CloudSyncRepository(firestore, auth)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertUserProfile(VelunaUserPrefs(userDisplayName = "Del Strict"), TasteProfile(), email, null)
            repo.upsertPlaylist(
                playlistKey = "pl_strict_del",
                title = "Rev 1",
                description = "",
                coverUri = null,
                tracks = listOf(SongItem("trk_d1", "D1", "Artist")),
                clientUpdatedAt = System.currentTimeMillis() - 1000L,
                syncRevision = 1L
            )
            repo.upsertPlaylist(
                playlistKey = "pl_strict_del",
                title = "Rev 2",
                description = "",
                coverUri = null,
                tracks = listOf(SongItem("trk_d2", "D2", "Artist")),
                clientUpdatedAt = System.currentTimeMillis(),
                syncRevision = 2L
            )
            // Also materialize sync_meta/clock via GC
            repo.garbageCollectPlaylistSnapshots("pl_strict_del", throwOnError = true)
            repo.deleteAllUserData(uid)
        }

        val userDoc = firestore.collection("users").document(uid)
        assertThat(userDoc.get().await().exists()).isFalse()
        assertThat(userDoc.collection("playlists").get().await().documents).isEmpty()
        assertThat(userDoc.collection("sync_meta").get().await().documents).isEmpty()
    }

    private companion object {
        const val ALICE_EMAIL = "alice_veluna@test.com"
        const val BOB_EMAIL = "bob_veluna@test.com"
        const val DEFAULT_TIMEOUT_MS = 10000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}

