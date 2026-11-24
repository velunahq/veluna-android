package com.example.data.cloud

import com.example.model.SongItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BaselineTrackOp(
    val deleted: Boolean = false,
    val stamp: SyncMutationStamp = SyncMutationStamp.EMPTY
)

@Serializable
data class PlaylistBaselineSnapshot(
    val title: String,
    val description: String,
    val remoteCoverUri: String? = null,
    val coverRemoved: Boolean = false,
    val trackIds: List<String> = emptyList(),
    val fieldStamps: Map<String, SyncMutationStamp> = emptyMap(),
    val trackOps: Map<String, BaselineTrackOp> = emptyMap()
)

data class MergedPlaylistResult(
    val title: String,
    val description: String,
    val remoteCoverUri: String?,
    val coverRemoved: Boolean,
    val tracks: List<SongItem>,
    val needsLocalUpdate: Boolean,
    val needsCloudPush: Boolean,
    val mergedFieldStamps: Map<String, SyncMutationStamp> = emptyMap(),
    val mergedTrackAddStamps: Map<String, SyncMutationStamp> = emptyMap(),
    val mergedTrackTombstones: Map<String, SyncMutationStamp> = emptyMap(),
    val winningPlaylistStamp: SyncMutationStamp = SyncMutationStamp.EMPTY
)

internal object PlaylistThreeWayMerger {

    const val FIELD_TITLE = "title"
    const val FIELD_DESCRIPTION = "description"
    const val FIELD_COVER = "cover"
    const val FIELD_ORDER = "order"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeBaselineSnapshot(snapshot: PlaylistBaselineSnapshot): String =
        json.encodeToString(snapshot)

    fun decodeBaselineSnapshot(raw: String?): PlaylistBaselineSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<PlaylistBaselineSnapshot>(raw) }.getOrNull()
    }

    /**
     * Performs deterministic, perspective-independent 3-way merge of playlist metadata,
     * causal track additions/removals/re-additions, and ordering.
     *
     * Ordering rules:
     * - Every field and track operation carries a causal `SyncMutationStamp(timestampMs, seq, deviceId, opId)`
     *   which establishes a total order independent of which device runs the merge.
     * - Same-timestamp edits from different devices break ties by `(seq, deviceId, opId)`, and
     *   exact stamp ties (e.g. legacy zero-stamp snapshots) break ties by canonical content value
     *   — never by "prefer local" or "prefer remote".
     * - Offline add-then-remove produces a persistent local track tombstone stamp which competes
     *   causally against concurrent remote additions of the same track.
     */
    fun merge(
        baseline: PlaylistBaselineSnapshot?,
        localTitle: String,
        localDescription: String,
        localRemoteCoverUri: String?,
        localCoverRemoved: Boolean,
        localTracks: List<SongItem>,
        localUpdatedAt: Long,
        remoteTitle: String,
        remoteDescription: String,
        remoteCoverUri: String?,
        remoteCoverRemoved: Boolean,
        remoteTracks: List<SongItem>,
        remoteUpdatedAt: Long,
        localDeviceId: String = "dev_local",
        localFieldStamps: Map<String, SyncMutationStamp> = emptyMap(),
        localTrackOps: Map<String, BaselineTrackOp> = emptyMap(),
        remoteDeviceId: String = "dev_remote",
        remoteFieldStamps: Map<String, SyncMutationStamp> = emptyMap(),
        remoteTrackAddStamps: Map<String, SyncMutationStamp> = emptyMap(),
        remoteTrackTombstones: Map<String, SyncMutationStamp> = emptyMap()
    ): MergedPlaylistResult {
        val localPlaylistFallback = SyncMutationStamp(
            timestampMs = localUpdatedAt.coerceAtLeast(0L),
            seq = 0L,
            deviceId = localDeviceId
        )
        val remotePlaylistFallback = SyncMutationStamp(
            timestampMs = remoteUpdatedAt.coerceAtLeast(0L),
            seq = 0L,
            deviceId = remoteDeviceId
        )

        val localTitleStamp = localFieldStamps[FIELD_TITLE]?.takeIf { it.isPresent } ?: localPlaylistFallback
        val remoteTitleStamp = remoteFieldStamps[FIELD_TITLE]?.takeIf { it.isPresent } ?: remotePlaylistFallback
        val (mergedTitle, wonTitleStamp) = resolveScalarField(
            baseVal = baseline?.title,
            baseStamp = baseline?.fieldStamps?.get(FIELD_TITLE),
            localVal = localTitle,
            localStamp = localTitleStamp,
            remoteVal = remoteTitle,
            remoteStamp = remoteTitleStamp
        ) { a, b -> a.compareTo(b) >= 0 }

        val localDescStamp = localFieldStamps[FIELD_DESCRIPTION]?.takeIf { it.isPresent } ?: localPlaylistFallback
        val remoteDescStamp = remoteFieldStamps[FIELD_DESCRIPTION]?.takeIf { it.isPresent } ?: remotePlaylistFallback
        val (mergedDescription, wonDescStamp) = resolveScalarField(
            baseVal = baseline?.description,
            baseStamp = baseline?.fieldStamps?.get(FIELD_DESCRIPTION),
            localVal = localDescription,
            localStamp = localDescStamp,
            remoteVal = remoteDescription,
            remoteStamp = remoteDescStamp
        ) { a, b -> a.compareTo(b) >= 0 }

        val localCoverPair = (if (localCoverRemoved) null else localRemoteCoverUri) to localCoverRemoved
        val remoteCoverPair = (if (remoteCoverRemoved) null else remoteCoverUri) to remoteCoverRemoved
        val baseCoverPair = baseline?.let {
            (if (it.coverRemoved) null else it.remoteCoverUri) to it.coverRemoved
        }
        val localCoverStamp = localFieldStamps[FIELD_COVER]?.takeIf { it.isPresent } ?: localPlaylistFallback
        val remoteCoverStamp = remoteFieldStamps[FIELD_COVER]?.takeIf { it.isPresent } ?: remotePlaylistFallback
        val (mergedCoverPair, wonCoverStamp) = resolveCoverField(
            basePair = baseCoverPair,
            baseStamp = baseline?.fieldStamps?.get(FIELD_COVER),
            localPair = localCoverPair,
            localStamp = localCoverStamp,
            remotePair = remoteCoverPair,
            remoteStamp = remoteCoverStamp
        )
        val mergedRemoteCover = if (mergedCoverPair.second) null else mergedCoverPair.first
        val mergedCoverRemoved = mergedCoverPair.second

        val localIds = localTracks.map { it.videoId }.distinct()
        val remoteIds = remoteTracks.map { it.videoId }.distinct()
        val localSongById = localTracks.associateBy { it.videoId }
        val remoteSongById = remoteTracks.associateBy { it.videoId }
        val baseIds = baseline?.trackIds?.distinct().orEmpty()
        val baseIdSet = baseIds.toSet()

        val allCandidateTrackIds = LinkedHashSet<String>().apply {
            addAll(baseIds)
            baseline?.trackOps?.keys?.let(::addAll)
            addAll(localIds)
            addAll(localTrackOps.keys)
            addAll(remoteIds)
            addAll(remoteTrackAddStamps.keys)
            addAll(remoteTrackTombstones.keys)
        }

        val survivingOrAddedSet = LinkedHashSet<String>()
        val mergedAddStamps = linkedMapOf<String, SyncMutationStamp>()
        val mergedTombstones = linkedMapOf<String, SyncMutationStamp>()
        val chosenSongById = linkedMapOf<String, SongItem>()

        for (vid in allCandidateTrackIds) {
            val baseOp = baseline?.trackOps?.get(vid)
                ?: if (vid in baseIdSet) BaselineTrackOp(deleted = false, stamp = SyncMutationStamp.EMPTY) else null

            val localSong = localSongById[vid]
            val localExplicitOp = localTrackOps[vid]
            val localOp: BaselineTrackOp? = when {
                localSong != null && localExplicitOp != null && !localExplicitOp.deleted -> localExplicitOp
                localSong != null -> BaselineTrackOp(
                    deleted = false,
                    stamp = SyncMutationStamp(
                        timestampMs = (localSong.addedAt ?: localUpdatedAt).coerceAtLeast(0L),
                        seq = 0L,
                        deviceId = localDeviceId
                    )
                )
                localExplicitOp != null && localExplicitOp.deleted -> localExplicitOp
                baseline != null && vid in baseIdSet -> BaselineTrackOp(
                    deleted = true,
                    stamp = localPlaylistFallback
                )
                else -> null
            }

            val remoteSong = remoteSongById[vid]
            val remoteAddStamp = remoteTrackAddStamps[vid]
            val remoteDelStamp = remoteTrackTombstones[vid]
            val remoteActiveStamp: SyncMutationStamp? = when {
                remoteSong != null -> remoteAddStamp?.takeIf { it.isPresent }
                    ?: SyncMutationStamp(
                        timestampMs = (remoteSong.addedAt ?: remoteUpdatedAt).coerceAtLeast(0L),
                        seq = 0L,
                        deviceId = remoteDeviceId
                    )
                else -> null
            }
            val remoteOp: BaselineTrackOp? = when {
                remoteActiveStamp != null && remoteDelStamp != null && remoteDelStamp.isPresent -> {
                    if (remoteDelStamp > remoteActiveStamp) {
                        BaselineTrackOp(deleted = true, stamp = remoteDelStamp)
                    } else {
                        BaselineTrackOp(deleted = false, stamp = remoteActiveStamp)
                    }
                }
                remoteActiveStamp != null -> BaselineTrackOp(deleted = false, stamp = remoteActiveStamp)
                remoteDelStamp != null && remoteDelStamp.isPresent -> BaselineTrackOp(deleted = true, stamp = remoteDelStamp)
                baseline != null && vid in baseIdSet -> BaselineTrackOp(
                    deleted = true,
                    stamp = remotePlaylistFallback
                )
                else -> null
            }

            val winner: Pair<BaselineTrackOp, Boolean> = when {
                localOp == null && remoteOp == null -> continue
                localOp != null && remoteOp == null -> localOp to false
                localOp == null && remoteOp != null -> remoteOp to true
                else -> {
                    val l = localOp!!
                    val r = remoteOp!!
                    val lSameAsBase = baseOp != null && l.deleted == baseOp.deleted && l.stamp == baseOp.stamp
                    val rSameAsBase = baseOp != null && r.deleted == baseOp.deleted && r.stamp == baseOp.stamp
                    when {
                        lSameAsBase && !rSameAsBase -> r to true
                        rSameAsBase && !lSameAsBase -> l to false
                        else -> {
                            val cmp = l.stamp.compareTo(r.stamp)
                            when {
                                cmp > 0 -> l to false
                                cmp < 0 -> r to true
                                // Exact unbreakable causal tie: prefer active track over removal deterministically
                                !l.deleted && r.deleted -> l to false
                                l.deleted && !r.deleted -> r to true
                                else -> l to false
                            }
                        }
                    }
                }
            }

            val (winningOp, fromRemote) = winner
            if (winningOp.deleted) {
                mergedTombstones[vid] = winningOp.stamp
            } else {
                val song = if (fromRemote) (remoteSong ?: localSong) else (localSong ?: remoteSong)
                if (song != null) {
                    survivingOrAddedSet.add(vid)
                    mergedAddStamps[vid] = winningOp.stamp
                    chosenSongById[vid] = song
                }
            }
        }

        val localOrderStamp = localFieldStamps[FIELD_ORDER]?.takeIf { it.isPresent } ?: localPlaylistFallback
        val remoteOrderStamp = remoteFieldStamps[FIELD_ORDER]?.takeIf { it.isPresent } ?: remotePlaylistFallback

        val localFilteredForOrder = localIds.filter { it in survivingOrAddedSet }
        val remoteFilteredForOrder = remoteIds.filter { it in survivingOrAddedSet }

        val (preferRemoteOrder, wonOrderStamp) = if (baseline != null) {
            val survivingBoth = baseIdSet.intersect(survivingOrAddedSet)
            val baseOrderSig = baseIds.filter { it in survivingBoth }
            val localReordered = localIds.filter { it in survivingBoth } != baseOrderSig
            val remoteReordered = remoteIds.filter { it in survivingBoth } != baseOrderSig
            when {
                remoteReordered && !localReordered -> true to remoteOrderStamp
                localReordered && !remoteReordered -> false to localOrderStamp
                !localReordered && !remoteReordered -> {
                    val orderCmp = remoteOrderStamp.compareTo(localOrderStamp)
                    when {
                        orderCmp > 0 -> true to remoteOrderStamp
                        orderCmp < 0 -> false to localOrderStamp
                        else -> {
                            val useRemote = remoteFilteredForOrder.joinToString(",") >= localFilteredForOrder.joinToString(",")
                            useRemote to if (useRemote) remoteOrderStamp else localOrderStamp
                        }
                    }
                }
                else -> {
                    val orderCmp = remoteOrderStamp.compareTo(localOrderStamp)
                    when {
                        orderCmp > 0 -> true to remoteOrderStamp
                        orderCmp < 0 -> false to localOrderStamp
                        else -> {
                            val useRemote = remoteFilteredForOrder.joinToString(",") >= localFilteredForOrder.joinToString(",")
                            useRemote to if (useRemote) remoteOrderStamp else localOrderStamp
                        }
                    }
                }
            }
        } else {
            val orderCmp = remoteOrderStamp.compareTo(localOrderStamp)
            when {
                orderCmp > 0 -> true to remoteOrderStamp
                orderCmp < 0 -> false to localOrderStamp
                else -> {
                    val useRemote = remoteFilteredForOrder.joinToString(",") >= localFilteredForOrder.joinToString(",")
                    useRemote to if (useRemote) remoteOrderStamp else localOrderStamp
                }
            }
        }

        val orderedIds = LinkedHashSet<String>(survivingOrAddedSet.size)
        if (preferRemoteOrder) {
            remoteIds.filterTo(orderedIds) { it in survivingOrAddedSet }
            localIds.filterTo(orderedIds) { it in survivingOrAddedSet }
        } else {
            localIds.filterTo(orderedIds) { it in survivingOrAddedSet }
            remoteIds.filterTo(orderedIds) { it in survivingOrAddedSet }
        }
        survivingOrAddedSet.forEach { orderedIds.add(it) }

        val mergedIds = orderedIds.toList()
        val mergedTracks = mergedIds.mapNotNull { chosenSongById[it] }

        val mergedFieldStamps = linkedMapOf(
            FIELD_TITLE to wonTitleStamp,
            FIELD_DESCRIPTION to wonDescStamp,
            FIELD_COVER to wonCoverStamp,
            FIELD_ORDER to wonOrderStamp
        ).filterValues { it.isPresent }

        val winningPlaylistStamp = (
            mergedFieldStamps.values +
                mergedAddStamps.values +
                mergedTombstones.values
            ).maxOrNull() ?: maxOf(localPlaylistFallback, remotePlaylistFallback)

        val localMatchesMerged =
            localTitle == mergedTitle &&
                localDescription == mergedDescription &&
                localRemoteCoverUri == mergedRemoteCover &&
                localCoverRemoved == mergedCoverRemoved &&
                localIds == mergedIds

        val remoteMatchesMerged =
            remoteTitle == mergedTitle &&
                remoteDescription == mergedDescription &&
                remoteCoverUri == mergedRemoteCover &&
                remoteCoverRemoved == mergedCoverRemoved &&
                remoteIds == mergedIds &&
                remoteFieldStamps == mergedFieldStamps &&
                remoteTrackTombstones == mergedTombstones

        return MergedPlaylistResult(
            title = mergedTitle,
            description = mergedDescription,
            remoteCoverUri = mergedRemoteCover,
            coverRemoved = mergedCoverRemoved,
            tracks = mergedTracks,
            needsLocalUpdate = !localMatchesMerged,
            needsCloudPush = !remoteMatchesMerged,
            mergedFieldStamps = mergedFieldStamps,
            mergedTrackAddStamps = mergedAddStamps,
            mergedTrackTombstones = mergedTombstones,
            winningPlaylistStamp = winningPlaylistStamp
        )
    }

    private fun <T> resolveScalarField(
        baseVal: T?,
        baseStamp: SyncMutationStamp?,
        localVal: T,
        localStamp: SyncMutationStamp,
        remoteVal: T,
        remoteStamp: SyncMutationStamp,
        preferAOnTie: (T, T) -> Boolean
    ): Pair<T, SyncMutationStamp> {
        if (localVal == remoteVal) {
            return localVal to maxOf(localStamp, remoteStamp)
        }
        if (baseVal != null) {
            val localChanged = localVal != baseVal || (baseStamp != null && localStamp > baseStamp)
            val remoteChanged = remoteVal != baseVal || (baseStamp != null && remoteStamp > baseStamp)
            if (localChanged && !remoteChanged) return localVal to localStamp
            if (!localChanged && remoteChanged) return remoteVal to remoteStamp
        }
        val cmp = remoteStamp.compareTo(localStamp)
        return when {
            cmp > 0 -> remoteVal to remoteStamp
            cmp < 0 -> localVal to localStamp
            else -> if (preferAOnTie(remoteVal, localVal)) remoteVal to remoteStamp else localVal to localStamp
        }
    }

    private fun resolveCoverField(
        basePair: Pair<String?, Boolean>?,
        baseStamp: SyncMutationStamp?,
        localPair: Pair<String?, Boolean>,
        localStamp: SyncMutationStamp,
        remotePair: Pair<String?, Boolean>,
        remoteStamp: SyncMutationStamp
    ): Pair<Pair<String?, Boolean>, SyncMutationStamp> {
        // Without a baseline, do not let an empty default local cover erase an active remote HTTPS cover.
        if (basePair == null) {
            if (!localPair.second && localPair.first == null && !remotePair.second && remotePair.first != null) {
                return remotePair to remoteStamp
            }
            if (!remotePair.second && remotePair.first == null && !localPair.second && localPair.first != null) {
                return localPair to localStamp
            }
        }
        return resolveScalarField(
            baseVal = basePair,
            baseStamp = baseStamp,
            localVal = localPair,
            localStamp = localStamp,
            remoteVal = remotePair,
            remoteStamp = remoteStamp
        ) { a, b ->
            val keyA = "${a.second}:${a.first.orEmpty()}"
            val keyB = "${b.second}:${b.first.orEmpty()}"
            keyA.compareTo(keyB) >= 0
        }
    }
}
