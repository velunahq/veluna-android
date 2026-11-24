package com.example.data.cloud

import android.content.Context
import com.example.R
import com.example.data.local.SyncTombstoneEntity
import com.example.data.local.VelunaUserPrefs
import com.example.model.LOCAL_SONG_PREFIX
import com.example.model.SongItem
import com.example.model.TasteProfile
import com.example.model.TasteSeed
import com.example.util.SecurityValidators
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Collections
import java.util.Date
import java.util.UUID

class StalePlaylistRevisionException(
    val remoteRevision: Long,
    val attemptedRevision: Long
) : IllegalStateException(
    "Remote playlist revision $remoteRevision supersedes local revision $attemptedRevision"
)

class IncompletePlaylistSnapshotException(
    val playlistKey: String,
    val snapshotId: String,
    reason: String
) : IllegalStateException(
    "Playlist '$playlistKey' snapshot '$snapshotId' is incomplete: $reason"
)

data class SyncWatermarks(
    val likedMs: Long = 0L,
    val playlistsMs: Long = 0L,
    val seedsMs: Long = 0L,
    val hiddenMs: Long = 0L
)

class CloudSyncRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    internal var onBeforeCommitPlaylistSnapshot: (suspend (snapshotId: String, chunkIndex: Int) -> Unit)? = null
    internal var onAfterSnapshotCommittedBeforePublish: (suspend (playlistKey: String, snapshotId: String) -> Unit)? = null
    internal var onBeforePublishTransaction: (suspend (playlistKey: String, snapshotId: String) -> Unit)? = null
    internal var onAfterPublishTransactionSuccess: (suspend (playlistKey: String, snapshotId: String) -> Unit)? = null
    internal var onBeforeReadSnapshotTracks: (suspend (playlistKey: String, snapshotId: String) -> Unit)? = null
    internal var onBeforeGcClaim: (suspend (playlistKey: String, snapshotId: String) -> Unit)? = null
    internal var onAfterGcClaimBeforeTrackDelete: (suspend (playlistKey: String, snapshotId: String) -> Unit)? = null
    internal var onBeforeGcDeleteSnapshot: (suspend (playlistKey: String, snapshotId: String) -> Unit)? = null
    internal var onBeforeGcDeleteTrackBatch: (suspend (playlistKey: String, snapshotId: String, batchIndex: Int) -> Unit)? = null
    internal var onBeforeFetchAccountSnapshot: (suspend () -> Unit)? = null
    internal var collectionQueryPageSize: Long = DEFAULT_COLLECTION_QUERY_PAGE_SIZE

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        Firebase.auth
    )

    // Cache document paths confirmed to exist on server to avoid pre-read doc.get() round trips
    private val knownExistingPaths: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun clearSessionCache() {
        knownExistingPaths.clear()
    }

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeUserProfile(userId: String): Flow<CloudUserProfile?> = flow {
        val docRef = db.collection("users").document(userId)
        emitAll(
            docRef.snapshots()
                .map { it.toObject(CloudUserProfile::class.java) }
                .catch { error ->
                    val firestoreError = unwrapFirestoreError(error)
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.GET, docRef.path)
                    }
                    throw error
                }
        )
    }

    fun observeLikedSongs(userId: String): Flow<List<CloudLikedSong>> = flow {
        val colRef = db.collection("users").document(userId).collection("liked_songs")
        emitAll(
            colRef.snapshots()
                .map { snap -> snap.toObjects(CloudLikedSong::class.java) }
                .catch { error ->
                    val firestoreError = unwrapFirestoreError(error)
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path)
                    }
                    throw error
                }
        )
    }

    fun observePlaylists(userId: String): Flow<List<CloudPlaylist>> = flow {
        val colRef = db.collection("users").document(userId).collection("playlists")
        emitAll(
            colRef.snapshots()
                .map { snap -> snap.toObjects(CloudPlaylist::class.java) }
                .catch { error ->
                    val firestoreError = unwrapFirestoreError(error)
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path)
                    }
                    throw error
                }
        )
    }

    fun observeTasteSeeds(userId: String): Flow<List<CloudTasteSeed>> = flow {
        val colRef = db.collection("users").document(userId).collection("taste_seeds")
        emitAll(
            colRef.snapshots()
                .map { snap -> snap.toObjects(CloudTasteSeed::class.java) }
                .catch { error ->
                    val firestoreError = unwrapFirestoreError(error)
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path)
                    }
                    throw error
                }
        )
    }

    fun observeHiddenArtists(userId: String): Flow<List<CloudHiddenArtist>> = flow {
        val colRef = db.collection("users").document(userId).collection("hidden_artists")
        emitAll(
            colRef.snapshots()
                .map { snap -> snap.toObjects(CloudHiddenArtist::class.java) }
                .catch { error ->
                    val firestoreError = unwrapFirestoreError(error)
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path)
                    }
                    throw error
                }
        )
    }

    suspend fun getUserProfile(): CloudUserProfile? {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        return try {
            val snap = docRef.get().await()
            if (snap.exists()) knownExistingPaths.add(docRef.path)
            snap.toObject(CloudUserProfile::class.java)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            throw e
        }
    }

    suspend fun fetchAccountSnapshot(watermarks: SyncWatermarks = SyncWatermarks()): CloudAccountSnapshot {
        val uid = requireUserId()
        val userDoc = db.collection("users").document(uid)
        return try {
            onBeforeFetchAccountSnapshot?.invoke()
            val profileSnap = userDoc.get().await()
            if (profileSnap.exists()) knownExistingPaths.add(userDoc.path)
            val profile = profileSnap.toObject(CloudUserProfile::class.java)

            val seedsDocs = queryCollectionSince(userDoc.collection("taste_seeds"), watermarks.seedsMs)
            seedsDocs.forEach { knownExistingPaths.add(it.reference.path) }
            val seeds = seedsDocs.mapNotNull { it.toObject(CloudTasteSeed::class.java) }

            val likedDocs = queryCollectionSince(userDoc.collection("liked_songs"), watermarks.likedMs)
            likedDocs.forEach { knownExistingPaths.add(it.reference.path) }
            val liked = likedDocs.mapNotNull { it.toObject(CloudLikedSong::class.java) }

            val playlistsDocs = queryCollectionSince(userDoc.collection("playlists"), watermarks.playlistsMs)
            playlistsDocs.forEach { knownExistingPaths.add(it.reference.path) }
            val playlistDocs = playlistsDocs.mapNotNull { it.toObject(CloudPlaylist::class.java) }

            val playlistsWithTracks = playlistDocs.map { pl ->
                val (resolvedPl, subTracks) = if (!pl.deleted) {
                    fetchPlaylistTracksSafely(userDoc, pl)
                } else {
                    pl to emptyList()
                }
                CloudPlaylistWithTracks(
                    playlist = resolvedPl,
                    subcollectionTracks = subTracks,
                    hasLegacyArrayTracks = resolvedPl.tracks.isNotEmpty()
                )
            }

            val hiddenDocs = queryCollectionSince(userDoc.collection("hidden_artists"), watermarks.hiddenMs)
            hiddenDocs.forEach { knownExistingPaths.add(it.reference.path) }
            val hidden = hiddenDocs.mapNotNull { it.toObject(CloudHiddenArtist::class.java) }

            CloudAccountSnapshot(
                profile = profile,
                tasteSeeds = seeds,
                likedSongs = liked,
                playlists = playlistsWithTracks,
                hiddenArtists = hidden,
                maxLikedUpdatedAtMs = maxTimestampMillis(liked.map { it.updatedAt }, watermarks.likedMs),
                maxSeedsUpdatedAtMs = maxTimestampMillis(seeds.map { it.updatedAt }, watermarks.seedsMs),
                maxPlaylistsUpdatedAtMs = maxTimestampMillis(
                    playlistsWithTracks.map { it.playlist.updatedAt },
                    watermarks.playlistsMs
                ),
                maxHiddenUpdatedAtMs = maxTimestampMillis(hidden.map { it.updatedAt }, watermarks.hiddenMs)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, userDoc.path)
            throw e
        }
    }

    private suspend fun fetchPlaylistTracksSafely(
        userDoc: DocumentReference,
        initialPl: CloudPlaylist
    ): Pair<CloudPlaylist, List<CloudPlaylistTrack>> {
        val plRef = userDoc.collection("playlists").document(initialPl.playlistKey)
        var currentPl = initialPl
        repeat(MAX_SNAPSHOT_READ_ATTEMPTS) { attempt ->
            if (currentPl.deleted) {
                return currentPl to emptyList()
            }
            val activeSnapId = currentPl.activeSnapshotId?.trim()?.takeIf { it.isNotEmpty() }
            if (activeSnapId == null) {
                // True legacy playlist without snapshot pointer: read legacy subcollection only.
                val legacySnap = plRef.collection("tracks").get().await()
                legacySnap.documents.forEach { knownExistingPaths.add(it.reference.path) }
                return currentPl to legacySnap.toObjects(CloudPlaylistTrack::class.java)
            }

            onBeforeReadSnapshotTracks?.invoke(currentPl.playlistKey, activeSnapId)

            val snapRef = plRef.collection("snapshots").document(activeSnapId)
            val snapMetaDoc = snapRef.get().await()
            val trSnap = snapRef.collection("tracks").get().await()

            val snapValid = snapMetaDoc.exists() && snapMetaDoc.getString("state") == "COMMITTED"
            val expectedCount = snapMetaDoc.getLong("trackCount") ?: currentPl.trackCount
            val loadedTracks = trSnap.toObjects(CloudPlaylistTrack::class.java)

            if (snapValid && (expectedCount < 0L || loadedTracks.size.toLong() == expectedCount)) {
                trSnap.documents.forEach { knownExistingPaths.add(it.reference.path) }
                return currentPl to loadedTracks
            }

            // Re-check parent playlist in case concurrent publisher rotated activeSnapshotId mid-read.
            val refreshed = plRef.get().await().toObject(CloudPlaylist::class.java)
            if (refreshed != null && (refreshed.deleted || refreshed.activeSnapshotId != activeSnapId)) {
                currentPl = refreshed
                return@repeat
            }
            if (attempt == MAX_SNAPSHOT_READ_ATTEMPTS - 1) {
                val reason = if (!snapMetaDoc.exists()) {
                    "snapshot document missing"
                } else if (snapMetaDoc.getString("state") != "COMMITTED") {
                    "snapshot state=${snapMetaDoc.getString("state")}"
                } else {
                    "expected $expectedCount tracks but loaded ${loadedTracks.size}"
                }
                throw IncompletePlaylistSnapshotException(currentPl.playlistKey, activeSnapId, reason)
            }
        }
        throw IncompletePlaylistSnapshotException(
            currentPl.playlistKey,
            currentPl.activeSnapshotId.orEmpty(),
            "exhausted snapshot read retries"
        )
    }

    private suspend fun queryCollectionSince(
        colRef: CollectionReference,
        sinceMs: Long
    ): List<DocumentSnapshot> {
        val pageSize = collectionQueryPageSize.coerceAtLeast(1L)
        val baseQuery: Query = if (sinceMs > 0L) {
            val overlapSinceMs = (sinceMs - WATERMARK_OVERLAP_MS).coerceAtLeast(0L)
            colRef
                .whereGreaterThanOrEqualTo("updatedAt", Timestamp(Date(overlapSinceMs)))
                .orderBy("updatedAt", Query.Direction.ASCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
        } else {
            colRef.orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
        }

        val collected = mutableListOf<DocumentSnapshot>()
        var lastDoc: DocumentSnapshot? = null
        while (true) {
            val pageQuery = if (lastDoc == null) {
                baseQuery.limit(pageSize)
            } else {
                baseQuery.startAfter(lastDoc).limit(pageSize)
            }
            val snap = pageQuery.get().await()
            if (snap.isEmpty) break
            collected.addAll(snap.documents)
            if (snap.size().toLong() < pageSize) break
            lastDoc = snap.documents.last()
        }
        return collected
    }

    private fun maxTimestampMillis(timestamps: List<Timestamp?>, baseline: Long): Long {
        var maxMs = baseline
        timestamps.forEach { ts ->
            // Truncate to millisecond floor to stay consistent with Timestamp(Date(overlapSinceMs)).
            val ms = ts?.let { it.seconds * 1000L + (it.nanoseconds / 1_000_000L) } ?: 0L
            if (ms > maxMs) maxMs = ms
        }
        return maxMs
    }

    /**
     * Merges fields without performing a pre-read `doc.get()`. Uses [knownExistingPaths] to decide
     * whether `createdAt` should be included on first write; if a concurrent device already created
     * the doc, falls back once to writing without `createdAt`.
     */
    private suspend fun setMergedWithoutPreRead(
        docRef: DocumentReference,
        mutableFields: Map<String, Any?>,
        extraCreateFields: Map<String, Any?> = emptyMap()
    ) {
        val cleanMutable = mutableFields.filterValues { it != null }
        val knownExists = docRef.path in knownExistingPaths
        if (knownExists) {
            docRef.set(cleanMutable, SetOptions.merge()).await()
            return
        }

        val createPayload = (cleanMutable + extraCreateFields + ("createdAt" to FieldValue.serverTimestamp()))
            .filterValues { it != null }
        try {
            docRef.set(createPayload, SetOptions.merge()).await()
            knownExistingPaths.add(docRef.path)
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                // Likely already exists on server with an earlier createdAt; merge mutable fields only
                docRef.set(cleanMutable, SetOptions.merge()).await()
                knownExistingPaths.add(docRef.path)
            } else {
                throw e
            }
        }
    }

    suspend fun upsertUserProfile(
        prefs: VelunaUserPrefs,
        tasteProfile: TasteProfile,
        photoUrl: String?,
        overrideDisplayName: String? = null,
        prefsUpdatedAtMs: Long = System.currentTimeMillis()
    ) {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val safeName = (overrideDisplayName ?: prefs.userDisplayName)
            .trim()
            .ifBlank { "Listener" }
            .take(80)
        val safePhoto = SecurityValidators.sanitizeHttpsUrl(photoUrl, 500)
        val safeGenres = tasteProfile.selectedGenres
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(25)
        val safeAccent = prefs.customAccentHex?.trim()?.takeIf { SecurityValidators.isValidHexColor(it) }

        val mutableFields = mapOf<String, Any?>(
            "displayName" to safeName,
            "email" to FieldValue.delete(),
            "photoUrl" to safePhoto,
            "themePreset" to prefs.themePreset.id.take(32),
            "themeMode" to prefs.themeMode.name,
            "customAccentHex" to safeAccent,
            "adaptToArtwork" to prefs.adaptToArtwork,
            "cornerRadiusDp" to prefs.cornerRadiusDp.coerceIn(4, 24).toLong(),
            "audioQuality" to prefs.audioQuality.name,
            "autoplay" to prefs.autoplay,
            "loudnessNormalization" to prefs.loudnessNormalization,
            "hideMusicVideos" to prefs.hideMusicVideos,
            "selectedGenres" to safeGenres,
            "onboardingCompleted" to tasteProfile.onboardingCompleted,
            "prefsUpdatedAt" to prefsUpdatedAtMs.coerceAtLeast(0L),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        try {
            setMergedWithoutPreRead(
                docRef = docRef,
                mutableFields = mutableFields,
                extraCreateFields = mapOf("userId" to uid)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    /**
     * Upserts active seeds using stable docIds and preserves each seed's real client timestamp
     * ([TasteSeed.updatedAtMs]) rather than fabricating `now`. Writes explicit tombstone documents
     * for deleted/cleared seeds so empty lists propagate across devices.
     */
    suspend fun upsertTasteSeeds(
        artists: List<TasteSeed>,
        songs: List<TasteSeed>,
        tombstones: List<SyncTombstoneEntity> = emptyList(),
        nowMs: Long = System.currentTimeMillis()
    ) {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("taste_seeds")
        val targetMap = linkedMapOf<String, Map<String, Any?>>()

        artists.take(30).forEach { seed ->
            val docId = resolveSeedDocId("art", seed)
            val seedTs = (seed.updatedAtMs.takeIf { it > 0L } ?: nowMs).coerceAtLeast(0L)
            targetMap[docId] = mapOf(
                "kind" to "ARTIST",
                "name" to seed.name.trim().ifBlank { "Artist" }.take(160),
                "subtitle" to seed.subtitle?.take(160),
                "thumbnail" to SecurityValidators.sanitizeHttpsUrl(seed.thumbnail, 500),
                "deleted" to false,
                "clientUpdatedAt" to seedTs,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        }
        songs.take(30).forEach { seed ->
            val docId = resolveSeedDocId("sng", seed)
            val seedTs = (seed.updatedAtMs.takeIf { it > 0L } ?: nowMs).coerceAtLeast(0L)
            targetMap[docId] = mapOf(
                "kind" to "SONG",
                "name" to seed.name.trim().ifBlank { "Track" }.take(160),
                "subtitle" to seed.subtitle?.take(160),
                "thumbnail" to SecurityValidators.sanitizeHttpsUrl(seed.thumbnail, 500),
                "deleted" to false,
                "clientUpdatedAt" to seedTs,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        }

        try {
            for ((docId, fields) in targetMap) {
                setMergedWithoutPreRead(colRef.document(docId), fields)
            }
            for (tomb in tombstones) {
                if (tomb.entityKey !in targetMap && isValidFirestoreId(tomb.entityKey)) {
                    val tombFields = mapOf(
                        "deleted" to true,
                        "clientUpdatedAt" to tomb.clientUpdatedAt.coerceAtLeast(0L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    setMergedWithoutPreRead(colRef.document(tomb.entityKey), tombFields)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, colRef.path)
            throw e
        }
    }

    suspend fun writeTasteSeedTombstone(seedDocId: String, clientUpdatedAt: Long) {
        val uid = requireUserId()
        val safeId = sanitizeDocId(seedDocId, prefix = "art")
        val docRef = db.collection("users").document(uid).collection("taste_seeds").document(safeId)
        try {
            val fields = mapOf(
                "deleted" to true,
                "clientUpdatedAt" to clientUpdatedAt.coerceAtLeast(0L),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            setMergedWithoutPreRead(docRef, fields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    suspend fun syncLikedSong(
        song: SongItem,
        liked: Boolean,
        likedAtMs: Long = System.currentTimeMillis(),
        clientUpdatedAtMs: Long = likedAtMs
    ) {
        if (song.isLocal || song.videoId.startsWith(LOCAL_SONG_PREFIX)) return
        val uid = requireUserId()
        val safeId = sanitizeDocId(song.videoId, prefix = "trk")
        val docRef = db.collection("users").document(uid).collection("liked_songs").document(safeId)
        try {
            if (!liked) {
                val tombFields = mapOf(
                    "deleted" to true,
                    "clientUpdatedAt" to clientUpdatedAtMs.coerceAtLeast(0L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                setMergedWithoutPreRead(docRef, tombFields)
                return
            }
            val mutableFields = mapOf<String, Any?>(
                "title" to song.title.trim().ifBlank { "Track" }.take(200),
                "artists" to song.artists.trim().take(200),
                "album" to song.album?.take(200),
                "albumId" to song.albumId?.take(128),
                "artistId" to song.artistId?.take(128),
                "duration" to song.duration?.take(24),
                "durationSecs" to song.durationSeconds.coerceAtLeast(0L),
                "thumbnail" to SecurityValidators.sanitizeHttpsUrl(song.thumbnail, 500),
                "explicit" to song.explicit,
                "likedAt" to likedAtMs.coerceAtLeast(0L),
                "deleted" to false,
                "clientUpdatedAt" to clientUpdatedAtMs.coerceAtLeast(0L),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            setMergedWithoutPreRead(docRef, mutableFields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, if (liked) OperationType.WRITE else OperationType.DELETE, docRef.path)
            throw e
        }
    }

    /**
     * Removes any erroneously synced `LOCAL:` doc from `liked_songs` (hard delete migration).
     */
    suspend fun purgeLocalLikedDocsFromCloud(videoIds: List<String>) {
        if (videoIds.isEmpty()) return
        val uid = requireUserId()
        val col = db.collection("users").document(uid).collection("liked_songs")
        for (id in videoIds) {
            try {
                val safeId = sanitizeDocId(id, prefix = "trk")
                col.document(safeId).delete().await()
                knownExistingPaths.remove(col.document(safeId).path)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort cleanup of legacy local docs
            }
        }
    }

    /**
     * Atomically publishes a versioned playlist snapshot:
     * 1. Creates staging snapshot doc (`state = STAGING`, server `createdAt`)
     * 2. Writes track documents in batches <= 400 under `snapshots/{snapshotId}/tracks`
     * 3. Marks snapshot `COMMITTED`
     * 4. Runs a Firestore transaction reading BOTH `snapRef` (requiring `state == COMMITTED`) and
     *    `plDocRef` (verifying monotonic `syncRevision` / deterministic causal `SyncMutationStamp`)
     *    before switching `activeSnapshotId`.
     *
     * Never deletes `snapRef` immediately on ambiguous transaction outcomes (cancellation or
     * post-send network/transport error during the publish tx); confirmed pre-commit aborts clean
     * up safely via `claimAndDeleteUnreferencedSnapshot` which verifies `activeSnapshotId` inside a tx.
     */
    suspend fun upsertPlaylist(
        playlistKey: String,
        title: String,
        description: String,
        coverUri: String?,
        tracks: List<SongItem>,
        clientUpdatedAt: Long,
        syncRevision: Long = 1L,
        removeLegacyTracksField: Boolean = false,
        coverRemoved: Boolean = false,
        playlistMutationStamp: SyncMutationStamp? = null,
        fieldStamps: Map<String, SyncMutationStamp> = emptyMap(),
        trackAddStamps: Map<String, SyncMutationStamp> = emptyMap(),
        trackTombstones: Map<String, SyncMutationStamp> = emptyMap()
    ) {
        val uid = requireUserId()
        val safeKey = sanitizeDocId(playlistKey, prefix = "pl")
        val plDocRef = db.collection("users").document(uid).collection("playlists").document(safeKey)

        val targetRev = syncRevision.coerceAtLeast(1L)
        val safeClientTs = clientUpdatedAt.coerceAtLeast(0L)
        val safeCover = if (coverRemoved) null else SecurityValidators.sanitizeHttpsUrl(coverUri, 500)
        val remoteTracks = tracks.filter { !it.isLocal && !it.videoId.startsWith(LOCAL_SONG_PREFIX) }

        val snapshotId = "snap_${targetRev}_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val snapRef = plDocRef.collection("snapshots").document(snapshotId)

        var publishTxStarted = false
        var confirmedPreCommitAbort = false
        var committedParent = false

        val cleanFieldStamps = fieldStamps
            .filterKeys { it in VALID_FIELD_STAMP_KEYS }
            .mapValues { (_, s) -> s.toFirestoreMap() }
        val cleanTrackTombstones = trackTombstones.entries
            .sortedByDescending { it.value }
            .take(500)
            .associate { (vid, stamp) -> vid.take(128) to stamp.toFirestoreMap() }

        try {
            snapRef.set(
                mapOf(
                    "state" to "STAGING",
                    "syncRevision" to targetRev,
                    "trackCount" to remoteTracks.size.toLong(),
                    "clientUpdatedAt" to safeClientTs,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            val snapTracksCol = snapRef.collection("tracks")
            remoteTracks.withIndex().chunked(SNAPSHOT_BATCH_LIMIT).forEachIndexed { chunkIdx, chunk ->
                onBeforeCommitPlaylistSnapshot?.invoke(snapshotId, chunkIdx)
                val batch = db.batch()
                for ((idx, s) in chunk) {
                    val trkId = snapshotTrackDocId(idx, s.videoId)
                    val stamp = trackAddStamps[s.videoId]
                    val trkFields = linkedMapOf<String, Any?>(
                        "videoId" to s.videoId.take(128),
                        "title" to s.title.trim().ifBlank { "Track" }.take(200),
                        "artists" to s.artists.trim().take(200),
                        "album" to s.album?.take(200),
                        "albumId" to s.albumId?.take(128),
                        "artistId" to s.artistId?.take(128),
                        "duration" to s.duration?.take(24),
                        "durationSecs" to s.durationSeconds.coerceAtLeast(0L),
                        "thumbnail" to SecurityValidators.sanitizeHttpsUrl(s.thumbnail, 500),
                        "explicit" to s.explicit,
                        "position" to idx,
                        "addedAt" to (s.addedAt ?: safeClientTs).coerceAtLeast(0L),
                        "deleted" to false,
                        "clientUpdatedAt" to safeClientTs,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (stamp != null && stamp.isPresent) {
                        trkFields["opTs"] = stamp.timestampMs.coerceAtLeast(0L)
                        trkFields["opSeq"] = stamp.seq.coerceAtLeast(0L)
                        trkFields["deviceId"] = stamp.deviceId.take(128)
                        trkFields["opId"] = stamp.opId.take(128)
                    }
                    batch.set(snapTracksCol.document(trkId), trkFields.filterValues { it != null })
                }
                batch.commit().await()
            }

            snapRef.update(
                mapOf(
                    "state" to "COMMITTED",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            onAfterSnapshotCommittedBeforePublish?.invoke(safeKey, snapshotId)
            onBeforePublishTransaction?.invoke(safeKey, snapshotId)

            val candidateStamp = playlistMutationStamp ?: SyncMutationStamp(
                timestampMs = safeClientTs,
                seq = 0L,
                deviceId = "",
                opId = ""
            )

            publishTxStarted = true
            db.runTransaction { tx ->
                val snapDoc = tx.get(snapRef)
                if (!snapDoc.exists() ||
                    snapDoc.getString("state") != "COMMITTED" ||
                    (snapDoc.getLong("syncRevision") ?: 0L) != targetRev ||
                    (snapDoc.getLong("trackCount") ?: -1L) != remoteTracks.size.toLong()
                ) {
                    confirmedPreCommitAbort = true
                    throw IncompletePlaylistSnapshotException(
                        safeKey,
                        snapshotId,
                        "Snapshot state=${snapDoc.getString("state")} missing or not COMMITTED before publish"
                    )
                }

                val existing = tx.get(plDocRef)
                if (existing.exists()) {
                    val existingDeleted = existing.getBoolean("deleted") == true
                    val existingRev = existing.getLong("syncRevision") ?: 0L
                    val existingStamp = SyncMutationStamp(
                        timestampMs = existing.getLong("clientUpdatedAt") ?: 0L,
                        seq = existing.getLong("lastOpSeq") ?: 0L,
                        deviceId = existing.getString("lastDeviceId").orEmpty(),
                        opId = existing.getString("lastOpId").orEmpty()
                    )
                    val stale = if (existingDeleted) {
                        targetRev <= existingRev
                    } else {
                        existingRev > targetRev || (existingRev == targetRev && existingStamp > candidateStamp)
                    }
                    if (stale) {
                        confirmedPreCommitAbort = true
                        throw StalePlaylistRevisionException(existingRev, targetRev)
                    }
                }

                val payload = linkedMapOf<String, Any?>(
                    "title" to title.trim().ifBlank { "Playlist" }.take(120),
                    "description" to description.take(500),
                    "deleted" to false,
                    "syncRevision" to targetRev,
                    "activeSnapshotId" to snapshotId,
                    "trackCount" to remoteTracks.size.toLong(),
                    "clientUpdatedAt" to safeClientTs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (playlistMutationStamp != null && playlistMutationStamp.isPresent) {
                    payload["lastDeviceId"] = playlistMutationStamp.deviceId.take(128)
                    payload["lastOpSeq"] = playlistMutationStamp.seq.coerceAtLeast(0L)
                    payload["lastOpId"] = playlistMutationStamp.opId.take(128)
                }
                if (cleanFieldStamps.isNotEmpty()) {
                    payload["fieldStamps"] = cleanFieldStamps
                }
                if (cleanTrackTombstones.isNotEmpty() || (existing.exists() && existing.contains("trackTombstones"))) {
                    payload["trackTombstones"] = cleanTrackTombstones
                }

                if (coverRemoved) {
                    payload["coverRemoved"] = true
                    if (existing.exists() && existing.contains("coverUri")) {
                        payload["coverUri"] = FieldValue.delete()
                    }
                } else if (safeCover != null) {
                    payload["coverUri"] = safeCover
                    payload["coverRemoved"] = false
                } else {
                    // Preserve any existing remote HTTPS cover when this device has only a local cover or unchanged cover.
                    payload["coverRemoved"] = false
                }
                if (!existing.exists()) {
                    payload["createdAt"] = FieldValue.serverTimestamp()
                    tx.set(plDocRef, payload.filterValues { it != null })
                } else {
                    if (removeLegacyTracksField || existing.contains("tracks")) {
                        payload["tracks"] = FieldValue.delete()
                    }
                    tx.update(plDocRef, payload)
                }
                null
            }.await()
            committedParent = true
            knownExistingPaths.add(plDocRef.path)
            onAfterPublishTransactionSuccess?.invoke(safeKey, snapshotId)
        } catch (e: CancellationException) {
            if (!committedParent && (!publishTxStarted || confirmedPreCommitAbort)) {
                withContext(NonCancellable) {
                    claimAndDeleteUnreferencedSnapshot(plDocRef, snapRef)
                }
            }
            throw e
        } catch (e: StalePlaylistRevisionException) {
            withContext(NonCancellable) {
                claimAndDeleteUnreferencedSnapshot(plDocRef, snapRef)
            }
            throw e
        } catch (e: IncompletePlaylistSnapshotException) {
            withContext(NonCancellable) {
                claimAndDeleteUnreferencedSnapshot(plDocRef, snapRef)
            }
            throw e
        } catch (e: Exception) {
            val permDenied = unwrapFirestoreError(e)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ||
                unwrapFirestoreError(e)?.code == FirebaseFirestoreException.Code.INVALID_ARGUMENT
            if (!committedParent && (!publishTxStarted || confirmedPreCommitAbort || permDenied)) {
                withContext(NonCancellable) {
                    claimAndDeleteUnreferencedSnapshot(plDocRef, snapRef)
                }
            }
            handleFirestoreError(e, OperationType.WRITE, plDocRef.path)
            throw e
        }

        if (removeLegacyTracksField) {
            // Safe once the parent doc points to the new COMMITTED snapshot: clean up obsolete legacy tracks.
            try {
                deleteCollectionInBatches(plDocRef.collection("tracks"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort legacy cleanup
            }
        }
    }

    suspend fun deletePlaylist(
        playlistKey: String,
        clientUpdatedAt: Long = System.currentTimeMillis(),
        syncRevision: Long = 0L,
        clearLegacyTracksArray: Boolean = true,
        playlistMutationStamp: SyncMutationStamp? = null
    ) {
        val uid = requireUserId()
        val safeKey = sanitizeDocId(playlistKey, prefix = "pl")
        val docRef = db.collection("users").document(uid).collection("playlists").document(safeKey)
        val safeTs = clientUpdatedAt.coerceAtLeast(0L)
        try {
            db.runTransaction { tx ->
                val existing = tx.get(docRef)
                val existingRev = existing.getLong("syncRevision") ?: 0L
                val existingDeleted = existing.getBoolean("deleted") == true
                val existingStamp = SyncMutationStamp(
                    timestampMs = existing.getLong("clientUpdatedAt") ?: 0L,
                    seq = existing.getLong("lastOpSeq") ?: 0L,
                    deviceId = existing.getString("lastDeviceId").orEmpty(),
                    opId = existing.getString("lastOpId").orEmpty()
                )
                val targetRev = if (syncRevision > 0L) syncRevision else maxOf(existingRev + 1L, 1L)
                val candidateStamp = playlistMutationStamp ?: SyncMutationStamp(timestampMs = safeTs)

                if (existing.exists() && syncRevision > 0L) {
                    val stale = existingRev > targetRev ||
                        (existingRev == targetRev && !existingDeleted && existingStamp > candidateStamp)
                    if (stale) {
                        throw StalePlaylistRevisionException(existingRev, targetRev)
                    }
                }

                if (existing.exists()) {
                    val updates = linkedMapOf<String, Any>(
                        "deleted" to true,
                        "clientUpdatedAt" to safeTs,
                        "syncRevision" to targetRev,
                        "trackCount" to 0L,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (playlistMutationStamp != null && playlistMutationStamp.isPresent) {
                        updates["lastDeviceId"] = playlistMutationStamp.deviceId.take(128)
                        updates["lastOpSeq"] = playlistMutationStamp.seq.coerceAtLeast(0L)
                        updates["lastOpId"] = playlistMutationStamp.opId.take(128)
                    }
                    if (existing.contains("activeSnapshotId")) {
                        updates["activeSnapshotId"] = FieldValue.delete()
                    }
                    if (clearLegacyTracksArray && existing.contains("tracks")) {
                        updates["tracks"] = FieldValue.delete()
                    }
                    tx.update(docRef, updates)
                } else {
                    val createMap = linkedMapOf<String, Any>(
                        "deleted" to true,
                        "clientUpdatedAt" to safeTs,
                        "syncRevision" to targetRev,
                        "trackCount" to 0L,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (playlistMutationStamp != null && playlistMutationStamp.isPresent) {
                        createMap["lastDeviceId"] = playlistMutationStamp.deviceId.take(128)
                        createMap["lastOpSeq"] = playlistMutationStamp.seq.coerceAtLeast(0L)
                        createMap["lastOpId"] = playlistMutationStamp.opId.take(128)
                    }
                    tx.set(docRef, createMap)
                }
                null
            }.await()
            knownExistingPaths.add(docRef.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StalePlaylistRevisionException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    internal var serverTimeProviderOverride: (suspend (uid: String) -> Long?)? = null

    /**
     * Resolves authoritative Firestore server wall-clock epoch millis by committing a
     * `FieldValue.serverTimestamp()` write to owner-only `users/{uid}/sync_meta/clock`
     * and reading it back from `Source.SERVER`. Returns null if offline/unavailable so
     * snapshot GC fails safely (retains snapshots) rather than trusting skewed device clocks.
     */
    suspend fun fetchAuthoritativeServerTimeMs(): Long? {
        val uid = requireUserId()
        serverTimeProviderOverride?.let { override ->
            return override(uid)
        }
        val clockRef = db.collection("users").document(uid).collection("sync_meta").document("clock")
        return try {
            clockRef.set(mapOf("serverTime" to FieldValue.serverTimestamp())).await()
            val serverSnap = clockRef.get(Source.SERVER).await()
            val ts = serverSnap.getTimestamp("serverTime")
            ts?.let { it.seconds * 1000L + (it.nanoseconds / 1_000_000L) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Concurrency-safe garbage collector for old playlist snapshots and legacy tracks:
     * 1. Uses strictly the Firestore server `createdAt` timestamp compared against authoritative
     *    Firestore server time when [nowMs] is omitted (never trusting skewed device wall clocks,
     *    and never deleting non-DELETING snapshots if server time is unavailable).
     * 2. Uses separate retention policies: [stagingRetentionMs] (1h) for abandoned `STAGING` snapshots
     *    and [retentionWindowMs] (7d) for superseded `COMMITTED` snapshots outside [retainCommittedCount].
     * 3. Atomically claims eligible snapshots via a Firestore transaction (`STAGING`/`COMMITTED` -> `DELETING`)
     *    that also reads the parent playlist doc and aborts if `activeSnapshotId == snap.id`.
     * 4. Deletes `tracks` subcollection batches outside the tx, verifies `tracks` is empty, and only
     *    then deletes the manifest document (resuming cleanly on `DELETING` snapshots after failures).
     */
    suspend fun garbageCollectPlaylistSnapshots(
        playlistKey: String,
        retentionWindowMs: Long = DEFAULT_SNAPSHOT_RETENTION_MS,
        retainCommittedCount: Int = DEFAULT_RETAINED_COMMITTED_SNAPSHOTS,
        nowMs: Long? = null,
        stagingRetentionMs: Long = DEFAULT_STAGING_SNAPSHOT_RETENTION_MS,
        throwOnError: Boolean = false
    ): Int {
        val uid = requireUserId()
        val safeKey = sanitizeDocId(playlistKey, prefix = "pl")
        val plDocRef = db.collection("users").document(uid).collection("playlists").document(safeKey)
        var deletedCount = 0
        try {
            val effectiveNowMs: Long? = nowMs ?: fetchAuthoritativeServerTimeMs()
            val parentSnap = plDocRef.get().await()
            val activeId = parentSnap.getString("activeSnapshotId")
            val snaps = plDocRef.collection("snapshots").get().await()
            val protectedCommitted = snaps.documents
                .filter { it.getString("state") == "COMMITTED" }
                .sortedWith(
                    compareByDescending<DocumentSnapshot> {
                        it.getLong("syncRevision") ?: 0L
                    }.thenByDescending {
                        it.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                    }.thenByDescending {
                        it.getLong("clientUpdatedAt") ?: 0L
                    }
                )
                .take(retainCommittedCount.coerceAtLeast(1))
                .map { it.id }
                .toSet()

            for (doc in snaps.documents) {
                if (doc.id == activeId) continue
                val state = doc.getString("state")
                if (state == "COMMITTED" && doc.id in protectedCommitted) continue

                if (state != "DELETING") {
                    val serverNowMs = effectiveNowMs ?: continue
                    val serverCreatedAtMs = doc.getTimestamp("createdAt")?.toDate()?.time ?: continue
                    val requiredWindow = when (state) {
                        "STAGING" -> stagingRetentionMs.coerceAtLeast(0L)
                        "COMMITTED" -> retentionWindowMs.coerceAtLeast(0L)
                        else -> continue
                    }
                    if (serverNowMs - serverCreatedAtMs < requiredWindow) continue

                    onBeforeGcClaim?.invoke(safeKey, doc.id)

                    val claimed = db.runTransaction { tx ->
                        val freshParent = tx.get(plDocRef)
                        if (freshParent.exists() && freshParent.getString("activeSnapshotId") == doc.id) {
                            return@runTransaction false
                        }
                        val freshSnap = tx.get(doc.reference)
                        if (!freshSnap.exists()) return@runTransaction false
                        val freshState = freshSnap.getString("state")
                        if (freshState == "DELETING") return@runTransaction true
                        if (freshState == "COMMITTED" && doc.id in protectedCommitted) {
                            return@runTransaction false
                        }
                        val freshCreatedAtMs = freshSnap.getTimestamp("createdAt")?.toDate()?.time
                            ?: return@runTransaction false
                        val freshWindow = when (freshState) {
                            "STAGING" -> stagingRetentionMs.coerceAtLeast(0L)
                            "COMMITTED" -> retentionWindowMs.coerceAtLeast(0L)
                            else -> return@runTransaction false
                        }
                        if (serverNowMs - freshCreatedAtMs < freshWindow) return@runTransaction false

                        tx.update(
                            doc.reference,
                            mapOf(
                                "state" to "DELETING",
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                        true
                    }.await()
                    if (!claimed) continue
                    onAfterGcClaimBeforeTrackDelete?.invoke(safeKey, doc.id)
                }

                onBeforeGcDeleteSnapshot?.invoke(safeKey, doc.id)
                val deletedAllTracks = deleteSnapshotTracksSafely(safeKey, doc.reference)
                if (!deletedAllTracks) {
                    if (throwOnError) {
                        throw IllegalStateException("Remaining snapshot tracks prevented manifest deletion for ${doc.id}")
                    }
                    continue
                }
                doc.reference.delete().await()
                deletedCount++
            }
            if (activeId != null) {
                deleteCollectionInBatches(plDocRef.collection("tracks"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (throwOnError) throw e
            // Best-effort deferred garbage collection
        }
        return deletedCount
    }

    /**
     * Claims and deletes an unreferenced snapshot whose creation or pre-commit publication failed,
     * after verifying in a transaction with `plDocRef` that `activeSnapshotId != snapRef.id`.
     */
    private suspend fun claimAndDeleteUnreferencedSnapshot(
        plDocRef: DocumentReference,
        snapRef: DocumentReference
    ) {
        try {
            val claimed = db.runTransaction { tx ->
                val parent = tx.get(plDocRef)
                if (parent.exists() && parent.getString("activeSnapshotId") == snapRef.id) {
                    return@runTransaction false
                }
                val snapDoc = tx.get(snapRef)
                if (!snapDoc.exists()) return@runTransaction false
                val state = snapDoc.getString("state")
                if (state == "DELETING") return@runTransaction true
                if (state == "STAGING" || state == "COMMITTED") {
                    tx.update(
                        snapRef,
                        mapOf(
                            "state" to "DELETING",
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    return@runTransaction true
                }
                false
            }.await()
            if (!claimed) return

            if (deleteSnapshotTracksSafely(plDocRef.id, snapRef)) {
                snapRef.delete().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort cleanup; if failed, snapshot stays safely isolated or handled by deferred GC
        }
    }

    private suspend fun deleteSnapshotTracksSafely(
        playlistKey: String,
        snapRef: DocumentReference
    ): Boolean {
        val tracksCol = snapRef.collection("tracks")
        var batchIdx = 0
        while (true) {
            val page = tracksCol.limit(SNAPSHOT_BATCH_LIMIT.toLong()).get().await()
            if (page.isEmpty) break
            onBeforeGcDeleteTrackBatch?.invoke(playlistKey, snapRef.id, batchIdx)
            val batch = db.batch()
            for (trk in page.documents) {
                batch.delete(trk.reference)
            }
            batch.commit().await()
            batchIdx++
            if (page.size() < SNAPSHOT_BATCH_LIMIT) break
        }
        return tracksCol.limit(1).get().await().isEmpty
    }

    suspend fun syncHiddenArtist(
        name: String,
        channelId: String?,
        blocked: Boolean,
        blockedAtMs: Long = System.currentTimeMillis(),
        clientUpdatedAtMs: Long = blockedAtMs
    ) {
        val uid = requireUserId()
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        val key = if (!blocked && cleanName.startsWith("blk_") && isValidFirestoreId(cleanName)) {
            cleanName
        } else {
            stableDocId("blk", cleanName.lowercase())
        }
        val docRef = db.collection("users").document(uid).collection("hidden_artists").document(key)
        try {
            if (!blocked) {
                val tombFields = mapOf(
                    "deleted" to true,
                    "clientUpdatedAt" to clientUpdatedAtMs.coerceAtLeast(0L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                setMergedWithoutPreRead(docRef, tombFields)
                return
            }
            val mutableFields = mapOf<String, Any?>(
                "name" to cleanName.take(160),
                "channelId" to channelId?.take(128),
                "blockedAt" to blockedAtMs.coerceAtLeast(0L),
                "deleted" to false,
                "clientUpdatedAt" to clientUpdatedAtMs.coerceAtLeast(0L),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            setMergedWithoutPreRead(docRef, mutableFields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, if (blocked) OperationType.WRITE else OperationType.DELETE, docRef.path)
            throw e
        }
    }

    /**
     * Unlinks any `activeSnapshotId` pointer on [plDoc], transitions every child snapshot manifest
     * to terminal `DELETING`, deletes snapshot `tracks`, deletes snapshot manifests, and deletes legacy `tracks`.
     */
    private suspend fun deletePlaylistSubtreeSafely(plDoc: DocumentSnapshot) {
        if (!plDoc.getString("activeSnapshotId").isNullOrBlank()) {
            plDoc.reference.update(
                mapOf(
                    "activeSnapshotId" to null,
                    "trackCount" to 0L,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
        val snaps = plDoc.reference.collection("snapshots").get().await()
        for (snapDoc in snaps.documents) {
            if (snapDoc.getString("state") != "DELETING") {
                snapDoc.reference.update(
                    mapOf(
                        "state" to "DELETING",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            deleteCollectionInBatches(snapDoc.reference.collection("tracks"))
            snapDoc.reference.delete().await()
        }
        deleteCollectionInBatches(plDoc.reference.collection("tracks"))
    }

    /**
     * Deletes all Firestore documents owned by [userId] in batches of 400 (idempotent).
     * Never silently swallows errors; callers handle PERMISSION_DENIED vs transient network retries.
     */
    suspend fun deleteAllUserData(userId: String) {
        val uid = requireUserId()
        require(uid == userId) { "Authenticated UID does not match deletion target." }
        val userDoc = db.collection("users").document(uid)

        try {
            // 1. Nested snapshot/tracks subcollections inside each playlist (strict lifecycle order)
            val playlists = userDoc.collection("playlists").get().await()
            for (plDoc in playlists.documents) {
                deletePlaylistSubtreeSafely(plDoc)
            }

            // 2. Direct subcollections under /users/{uid}
            for (sub in listOf("playlists", "liked_songs", "taste_seeds", "hidden_artists", "sync_meta")) {
                deleteCollectionInBatches(userDoc.collection(sub))
            }

            // 3. Root profile document
            userDoc.delete().await()
            clearSessionCache()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, userDoc.path)
            throw e
        }
    }

    private suspend fun deleteCollectionInBatches(colRef: CollectionReference) {
        while (true) {
            val snap = colRef.limit(400).get().await()
            if (snap.isEmpty) break
            val batch = db.batch()
            for (doc in snap.documents) {
                batch.delete(doc.reference)
            }
            batch.commit().await()
            if (snap.size() < 400) break
        }
    }

    /**
     * Client-side maintenance sweep removing soft-tombstone documents older than the retention cutoff
     * and recording the compaction high-watermark on `users/{userId}` when a profile document exists.
     */
    suspend fun purgeExpiredCloudTombstones(userId: String, olderThanEpochMs: Long) {
        val userDoc = db.collection("users").document(userId)
        val safeCutoff = olderThanEpochMs.coerceAtLeast(0L)
        if (userDoc.get().await().exists()) {
            db.runTransaction { tx ->
                val snap = tx.get(userDoc)
                if (snap.exists()) {
                    val prevCompacted = snap.getLong("tombstoneCompactedBeforeMs") ?: 0L
                    val nextCompacted = maxOf(prevCompacted, safeCutoff)
                    val prevGen = snap.getLong("syncGeneration") ?: 0L
                    tx.update(
                        userDoc,
                        mapOf(
                            "tombstoneCompactedBeforeMs" to nextCompacted,
                            "syncGeneration" to (prevGen + 1L),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                null
            }.await()
        }
        for (sub in listOf("liked_songs", "playlists", "taste_seeds", "hidden_artists")) {
            while (true) {
                val expired = userDoc.collection(sub)
                    .whereEqualTo("deleted", true)
                    .whereLessThan("clientUpdatedAt", safeCutoff)
                    .limit(200)
                    .get()
                    .await()
                if (expired.isEmpty) break

                if (sub == "playlists") {
                    // Firestore does not cascade deletes; safely unlink & transition snapshots -> DELETING first.
                    for (doc in expired.documents) {
                        deletePlaylistSubtreeSafely(doc)
                    }
                }

                expired.documents.chunked(400).forEach { docs ->
                    val batch = db.batch()
                    docs.forEach { doc -> batch.delete(doc.reference) }
                    batch.commit().await()
                }
            }
        }
    }

    private fun unwrapFirestoreError(error: Throwable): FirebaseFirestoreException? =
        generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()

    companion object {
        const val SNAPSHOT_BATCH_LIMIT = 400
        const val MAX_SNAPSHOT_READ_ATTEMPTS = 3
        const val WATERMARK_OVERLAP_MS = 10_000L
        const val DEFAULT_COLLECTION_QUERY_PAGE_SIZE = 250L
        const val DEFAULT_SNAPSHOT_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
        const val DEFAULT_STAGING_SNAPSHOT_RETENTION_MS = 60L * 60L * 1000L
        const val DEFAULT_RETAINED_COMMITTED_SNAPSHOTS = 3
        private val VALID_FIELD_STAMP_KEYS = setOf("title", "description", "cover", "order")

        fun snapshotTrackDocId(index: Int, videoId: String): String {
            val raw = "trk_${index.toString().padStart(5, '0')}_${videoId.trim()}"
            val cleaned = raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            return if (cleaned.length in 1..128 && !cleaned.startsWith("LOCAL")) {
                cleaned
            } else {
                stableDocId("trk_${index}", videoId)
            }
        }

        fun stableDocId(prefix: String, raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(24)
            return "${prefix}_$digest".take(128)
        }

        fun resolveSeedDocId(prefix: String, seed: TasteSeed): String {
            val candidate = seed.id.trim()
            if (candidate.isNotEmpty() && isValidFirestoreId(candidate) &&
                (candidate.startsWith("art_") || candidate.startsWith("sng_") || candidate.startsWith("UC") || candidate.startsWith("seed_"))
            ) {
                return candidate
            }
            val normKey = candidate.ifBlank { seed.name.lowercase().trim() + "::" + (seed.subtitle?.lowercase()?.trim().orEmpty()) }
            return stableDocId(prefix, normKey)
        }

        fun isValidFirestoreId(id: String): Boolean =
            id.length in 1..128 && id.matches(Regex("^[a-zA-Z0-9_\\-]+$"))

        fun sanitizeDocId(raw: String, prefix: String): String {
            val clean = raw.trim()
            return if (clean.isNotEmpty() && clean.length <= 128 && clean.matches(Regex("^[a-zA-Z0-9_\\-]+$")) && !clean.startsWith("LOCAL")) {
                clean
            } else {
                stableDocId(prefix, raw)
            }
        }

        fun songFromCloudMap(map: Map<String, Any?>): SongItem? {
            val videoId = (map["videoId"] as? String)?.trim()
                ?.takeIf { it.isNotBlank() && !it.startsWith(LOCAL_SONG_PREFIX) }
                ?: return null
            val title = (map["title"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return SongItem(
                videoId = videoId,
                title = title,
                artists = (map["artists"] as? String).orEmpty(),
                album = map["album"] as? String,
                albumId = map["albumId"] as? String,
                artistId = map["artistId"] as? String,
                duration = map["duration"] as? String,
                thumbnail = SecurityValidators.sanitizeHttpsUrl(map["thumbnail"] as? String, 2048),
                explicit = (map["explicit"] as? Boolean) ?: false
            )
        }
    }
}
