package com.example.data.cloud

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.BuildConfig
import com.example.data.cloud.GoogleAuthHelper.findActivity
import com.example.data.local.BlockedArtistEntity
import com.example.data.local.LikedSongEntity
import com.example.data.local.LocalDataProvenance
import com.example.data.local.LocalPlaylistEntity
import com.example.data.local.LocalPlaylistTrackEntity
import com.example.data.local.PlaylistFieldNames
import com.example.data.local.PlaylistFieldOpEntity
import com.example.data.local.PlaylistTrackOpEntity
import com.example.data.local.QuarantinePlaylistBundle
import com.example.data.local.SyncBaselineEntity
import com.example.data.local.SyncEntityTypes
import com.example.data.local.SyncTombstoneEntity
import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaDao
import com.example.data.local.VelunaUserPrefs
import com.example.data.repository.SerializableBlockedArtist
import com.example.data.repository.SerializableLikedSong
import com.example.data.repository.SerializableLocalPlaylist
import com.example.data.repository.SerializableLocalPlaylistTrack
import com.example.data.repository.SerializablePlaylistFieldOp
import com.example.data.repository.SerializablePlaylistTrackOp
import com.example.data.repository.VelunaRepository
import com.example.model.AudioQuality
import com.example.model.LOCAL_PLAYLIST_PREFIX
import com.example.model.LOCAL_SONG_PREFIX
import com.example.model.SongItem
import com.example.model.TasteProfile
import com.example.model.TasteSeed
import com.example.model.ThemeMode
import com.example.model.VelunaThemePreset
import com.example.util.SecurityValidators
import com.example.util.suspendRunCatching
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Immutable
data class CloudAuthUiState(
    val isSignedIn: Boolean = false,
    val uid: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val isAuthBusy: Boolean = false,
    val isSyncing: Boolean = false,
    val syncStatus: String? = null,
    val lastSyncedAtMs: Long? = null,
    val errorMessage: String? = null,
    val pendingUnsyncedCount: Int = 0,
    val pendingSignOutConfirmCount: Int? = null,
    val isDeletingAccount: Boolean = false,
    val deleteAccountError: String? = null,
    val needsRetryAuthDelete: Boolean = false,
    val hasUnownedDataConflict: Boolean = false,
    val unownedDataItemCount: Int = 0
)

@Serializable
internal data class SerializableSyncTombstone(
    val entityType: String,
    val entityKey: String,
    val clientUpdatedAt: Long,
    val deletedAt: Long
)

@Serializable
internal enum class QuarantineResolutionAction {
    MERGE,
    DISCARD
}

@Serializable
internal enum class QuarantineResolutionState {
    PREPARING,
    PAYLOAD_ARCHIVED,
    ROOM_APPLIED,
    PREFS_APPLIED,
    QUARANTINE_CLEARED,
    CLOUD_SYNC_PENDING,
    CLOUD_SYNC_COMMITTED,
    COMPLETED
}

@Serializable
internal data class UnownedQuarantineResolutionJournal(
    val operationId: String,
    val action: QuarantineResolutionAction,
    val targetUid: String,
    val startedAtMs: Long,
    val state: QuarantineResolutionState,
    val retainedPayloadFilename: String = ""
)

@Serializable
internal data class QuarantinedSyncableProfilePrefs(
    val themePresetId: String = VelunaThemePreset.DEFAULT.id,
    val themeModeName: String = ThemeMode.DARK.name,
    val customAccentHex: String? = null,
    val adaptToArtwork: Boolean = true,
    val cornerRadiusDp: Int = 12,
    val audioQualityName: String = AudioQuality.HIGH.name,
    val autoplay: Boolean = true,
    val loudnessNormalization: Boolean = true,
    val hideMusicVideos: Boolean = false
) {
    fun hasNonDefaultValues(): Boolean =
        themePresetId != VelunaThemePreset.DEFAULT.id ||
            themeModeName != ThemeMode.DARK.name ||
            !customAccentHex.isNullOrBlank() ||
            !adaptToArtwork ||
            cornerRadiusDp != 12 ||
            audioQualityName != AudioQuality.HIGH.name ||
            !autoplay ||
            !loudnessNormalization ||
            hideMusicVideos
}

@Serializable
internal data class UnownedAccountDataQuarantine(
    val createdAtMs: Long,
    val likedSongs: List<SerializableLikedSong> = emptyList(),
    val playlists: List<SerializableLocalPlaylist> = emptyList(),
    val playlistTracks: List<SerializableLocalPlaylistTrack> = emptyList(),
    val playlistTrackOps: List<SerializablePlaylistTrackOp> = emptyList(),
    val playlistFieldOps: List<SerializablePlaylistFieldOp> = emptyList(),
    val blockedArtists: List<SerializableBlockedArtist> = emptyList(),
    val tombstones: List<SerializableSyncTombstone> = emptyList(),
    val tasteProfile: TasteProfile? = null,
    val displayName: String = "Listener",
    val prefsUpdatedAtMs: Long = 0L,
    val profilePrefs: QuarantinedSyncableProfilePrefs? = null
) {
    fun hasSyncableProfileData(): Boolean {
        val cleanName = displayName.trim()
        val hasTaste = tasteProfile != null && (
            tasteProfile.onboardingCompleted ||
                tasteProfile.selectedGenres.isNotEmpty() ||
                tasteProfile.seedArtists.isNotEmpty() ||
                tasteProfile.seedSongs.isNotEmpty()
            )
        return prefsUpdatedAtMs > 0L ||
            (cleanName.isNotBlank() && cleanName != "Listener") ||
            hasTaste ||
            (profilePrefs?.hasNonDefaultValues() == true)
    }

    val itemCount: Int
        get() {
            val raw = likedSongs.size + playlists.size + blockedArtists.size +
                (tasteProfile?.let { it.seedArtists.size + it.seedSongs.size + it.selectedGenres.size } ?: 0)
            return if (raw > 0) raw else if (hasSyncableProfileData()) 1 else 0
        }
}

class CloudSyncCoordinator(
    private val context: Context,
    private val dao: VelunaDao,
    private val prefsRepo: UserPrefsRepository,
    private val repository: VelunaRepository,
    private val cloudRepo: CloudSyncRepository,
    private val auth: FirebaseAuth = Firebase.auth,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val stateDir: File? = null,
    private val autoStartSync: Boolean = true,
    private val awaitStartupProvenance: (suspend () -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val syncMutex = Mutex()
    private val transitionMutex = Mutex()
    private val suppressLocalPushCount = AtomicInteger(0)
    private val isSigningOut = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }
    private val defaultStartupCoordinator by lazy {
        StartupInitializationCoordinator(repository, prefsRepo, dao)
    }

    private val _authState = MutableStateFlow(buildUiState(auth.currentUser))
    val authState: StateFlow<CloudAuthUiState> = _authState.asStateFlow()

    @Volatile
    private var activeUserId: String? = null
    private var autoSignInAttempted = false
    private var autoSignInJob: Job? = null
    private val syncLifecycleLock = Any()
    private val watcherJobs = mutableListOf<Job>()
    private var signOutJob: Job? = null
    private var retryJob: Job? = null
    private var retryAttempt = 0
    internal var signOutFlushTimeoutMs: Long = 10_000L

    // Test hooks for process-death / cancellation injection around account transitions & quarantine resolution
    internal var onBeforeAccountTransitionWipe: (suspend (fromUid: String, toUid: String) -> Unit)? = null
    internal var onAfterAccountTransitionWipeBeforeFinalize: (suspend (fromUid: String, toUid: String) -> Unit)? = null
    internal var onBeforeQuarantineRoomApply: (suspend (action: QuarantineResolutionAction, uid: String) -> Unit)? = null
    internal var onAfterQuarantinePayloadArchived: (suspend (action: QuarantineResolutionAction, uid: String) -> Unit)? = null
    internal var onAfterQuarantineRoomApplyBeforePrefs: (suspend (action: QuarantineResolutionAction, uid: String) -> Unit)? = null
    internal var onAfterQuarantinePrefsBeforeDeleteFile: (suspend (action: QuarantineResolutionAction, uid: String) -> Unit)? = null
    internal var onAfterQuarantineDeleteBeforeClearedState: (suspend (action: QuarantineResolutionAction, uid: String) -> Unit)? = null
    internal var onAfterQuarantineDeleteBeforeJournalComplete: (suspend (action: QuarantineResolutionAction, uid: String) -> Unit)? = null
    internal var onAfterQuarantineCloudSyncPendingBeforeSync: (suspend (action: QuarantineResolutionAction, uid: String) -> Unit)? = null
    internal var onAfterQuarantineCloudSyncCommitted: (suspend (action: QuarantineResolutionAction, uid: String) -> Unit)? = null
    internal var onAfterQuarantineJournalCompletedBeforeCleanup: (suspend (action: QuarantineResolutionAction, uid: String) -> Unit)? = null
    internal var onAfterQuarantinePayloadCleanupBeforeJournalDelete: (suspend (action: QuarantineResolutionAction, uid: String) -> Unit)? = null

    // Track failed operations so honest sync status never masks partial failures
    private val failedCollections = ConcurrentHashMap<String, String>()

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        if (user == null && !isSigningOut.get()) {
            _authState.update { CloudAuthUiState(isSignedIn = false) }
        } else if (user != null) {
            _authState.update { cur ->
                cur.copy(
                    isSignedIn = true,
                    uid = user.uid,
                    displayName = user.displayName ?: cur.displayName,
                    photoUrl = SecurityValidators.sanitizeHttpsUrl(user.photoUrl?.toString(), 500) ?: cur.photoUrl,
                    isAuthBusy = false
                )
            }
        }

        synchronized(syncLifecycleLock) {
            if (user == null) {
                stopSyncForSignedOutLocked(clearFailureState = !isSigningOut.get())
            } else if (autoStartSync && !isSigningOut.get() && activeUserId != user.uid) {
                startSyncForUserLocked(user)
            }
        }
    }

    init {
        auth.addAuthStateListener(authStateListener)
        if (autoStartSync) {
            auth.currentUser?.let { existing ->
                synchronized(syncLifecycleLock) {
                    if (activeUserId != existing.uid) {
                        startSyncForUserLocked(existing)
                    }
                }
            }
        }
    }

    private fun buildUiState(user: FirebaseUser?): CloudAuthUiState {
        return if (user == null) {
            CloudAuthUiState(isSignedIn = false)
        } else {
            CloudAuthUiState(
                isSignedIn = true,
                uid = user.uid,
                displayName = user.displayName,
                photoUrl = SecurityValidators.sanitizeHttpsUrl(user.photoUrl?.toString(), 500)
            )
        }
    }

    fun attemptAutoSignInOnLaunch(activityContext: Context) {
        val activityRef = WeakReference(activityContext.findActivity())
        synchronized(syncLifecycleLock) {
            if (autoSignInAttempted || auth.currentUser != null || _authState.value.isAuthBusy) return
            autoSignInAttempted = true
            autoSignInJob?.cancel()
            autoSignInJob = scope.launch {
                val activity = activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                    ?: return@launch
                GoogleAuthHelper.attemptAutoSignIn(activity)
            }
        }
    }

    fun signIn(activityContext: Context) {
        val activityRef = WeakReference(activityContext.findActivity())
        synchronized(syncLifecycleLock) {
            if (_authState.value.isAuthBusy || isSigningOut.get()) return
            autoSignInJob?.cancel()
            autoSignInJob = null
            _authState.update { it.copy(isAuthBusy = true, errorMessage = null) }
        }
        scope.launch {
            try {
                val activity = activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                if (activity == null) {
                    _authState.update {
                        it.copy(
                            isAuthBusy = false,
                            errorMessage = "Unable to open Google Sign-In from this screen."
                        )
                    }
                    return@launch
                }
                when (val outcome = GoogleAuthHelper.signInWithGoogle(activity)) {
                    is GoogleSignInOutcome.Success -> {
                        _authState.update {
                            it.copy(
                                isSignedIn = true,
                                uid = outcome.user.uid,
                                displayName = outcome.user.displayName,
                                photoUrl = SecurityValidators.sanitizeHttpsUrl(outcome.user.photoUrl?.toString(), 500),
                                isAuthBusy = false,
                                errorMessage = null
                            )
                        }
                        synchronized(syncLifecycleLock) {
                            if (activeUserId != outcome.user.uid) {
                                startSyncForUserLocked(outcome.user)
                            }
                        }
                    }
                    GoogleSignInOutcome.Cancelled -> {
                        _authState.update { it.copy(isAuthBusy = false) }
                    }
                    is GoogleSignInOutcome.Error -> {
                        _authState.update {
                            it.copy(
                                isAuthBusy = false,
                                errorMessage = outcome.message
                            )
                        }
                    }
                }
            } finally {
                _authState.update { it.copy(isAuthBusy = false) }
            }
        }
    }

    /**
     * Cancels background debounce watchers, flushes pending sync for up to 10s,
     * and counts durable unsynced changes from `sync_baselines` + Room before signing out.
     * If changes remain unsynced, asks the user to confirm via [pendingSignOutConfirmCount].
     */
    fun signOut() {
        if (_authState.value.isAuthBusy || _authState.value.isDeletingAccount) return
        if (!isSigningOut.compareAndSet(false, true)) return
        _authState.update { it.copy(isAuthBusy = true, errorMessage = null, pendingSignOutConfirmCount = null) }
        val launchedJob = scope.launch {
            val user = auth.currentUser
            var timedOut = false
            if (user != null) {
                val merged = withTimeoutOrNull(signOutFlushTimeoutMs) {
                    runBidirectionalMerge(user, allowDuringSignOut = true)
                }
                if (merged == null) {
                    timedOut = true
                    failedCollections["signout_timeout"] = "Sync flush timed out before sign-out."
                }
            }
            if (!isSigningOut.get()) return@launch
            val remaining = if (user != null) {
                val unsynced = countUnsyncedChangesFor(user.uid)
                if (timedOut) unsynced.coerceAtLeast(1) else unsynced
            } else {
                0
            }
            if (remaining > 0) {
                _authState.update {
                    it.copy(
                        isAuthBusy = false,
                        pendingUnsyncedCount = remaining,
                        pendingSignOutConfirmCount = remaining,
                        errorMessage = if (timedOut) "Sync timed out before sign-out." else it.errorMessage
                    )
                }
            } else {
                performLocalWipeAndSignOut(signOutAuth = true, clearAllAccountData = false)
            }
        }
        synchronized(syncLifecycleLock) {
            cancelWorkersLocked()
            signOutJob?.cancel()
            signOutJob = launchedJob
        }
    }

    fun dismissSignOutConfirm() {
        val user = auth.currentUser
        var needStartSync = false
        synchronized(syncLifecycleLock) {
            signOutJob?.cancel()
            signOutJob = null
            isSigningOut.set(false)
            failedCollections.remove("signout_timeout")
            _authState.update { it.copy(pendingSignOutConfirmCount = null, isAuthBusy = false) }
            if (user != null && activeUserId != user.uid) {
                startSyncForUserLocked(user)
            } else if (user != null && activeUserId == user.uid && watcherJobs.isEmpty()) {
                needStartSync = true
            }
        }
        if (needStartSync && user != null) {
            scope.launch { maybeLaunchWatchers(user) }
        }
    }

    fun confirmSignOutDiscard() {
        val discardedForUid = auth.currentUser?.uid
        synchronized(syncLifecycleLock) {
            signOutJob?.cancel()
            signOutJob = null
            isSigningOut.set(true)
            cancelWorkersLocked()
        }
        _authState.update { it.copy(pendingSignOutConfirmCount = null, isAuthBusy = true) }
        scope.launch {
            performLocalWipeAndSignOut(
                signOutAuth = true,
                clearAllAccountData = false,
                discardedPendingUid = discardedForUid
            )
        }
    }

    /**
     * Deletes all Firestore user data and the Firebase Auth account in strict safe order:
     * Re-auth (if stale) -> Firestore batch wipe -> Auth user delete -> full local account wipe.
     */
    fun deleteAccount(activityContext: Context) {
        if (_authState.value.isDeletingAccount) return
        val user = auth.currentUser ?: return
        val activityRef = WeakReference(activityContext.findActivity())
        isSigningOut.set(true)
        _authState.update { it.copy(isDeletingAccount = true, deleteAccountError = null) }

        scope.launch {
            try {
                if (!_authState.value.needsRetryAuthDelete && GoogleAuthHelper.needsRecentSignIn(user)) {
                    val reauthActivity = activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                    if (reauthActivity == null) {
                        isSigningOut.set(false)
                        _authState.update {
                            it.copy(
                                isDeletingAccount = false,
                                deleteAccountError = "Re-authentication requires an active screen."
                            )
                        }
                        return@launch
                    }
                    when (val reauth = GoogleAuthHelper.reauthenticateWithGoogle(reauthActivity, user)) {
                        is GoogleSignInOutcome.Success -> Unit
                        GoogleSignInOutcome.Cancelled -> {
                            isSigningOut.set(false)
                            _authState.update { it.copy(isDeletingAccount = false) }
                            return@launch
                        }
                        is GoogleSignInOutcome.Error -> {
                            isSigningOut.set(false)
                            _authState.update {
                                it.copy(isDeletingAccount = false, deleteAccountError = reauth.message)
                            }
                            return@launch
                        }
                    }
                }

                if (!_authState.value.needsRetryAuthDelete) {
                    stopSyncForSignedOut()
                    try {
                        syncMutex.withLock {
                            cloudRepo.deleteAllUserData(user.uid)
                        }
                    } catch (e: FirebaseFirestoreException) {
                        val msg = if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            "Permission denied deleting cloud data. Please sign in again and retry."
                        } else {
                            SecurityValidators.safeUserErrorMessage(e, "Failed to erase cloud data.")
                        }
                        isSigningOut.set(false)
                        _authState.update { it.copy(isDeletingAccount = false, deleteAccountError = msg) }
                        return@launch
                    } catch (e: Exception) {
                        isSigningOut.set(false)
                        _authState.update {
                            it.copy(
                                isDeletingAccount = false,
                                deleteAccountError = SecurityValidators.safeUserErrorMessage(
                                    e,
                                    "Network error deleting cloud data. Retry to finish."
                                )
                            )
                        }
                        return@launch
                    }
                }

                try {
                    GoogleAuthHelper.deleteAuthUser(user)
                } catch (_: FirebaseAuthRecentLoginRequiredException) {
                    val reauthActivity = activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                    if (reauthActivity == null) {
                        isSigningOut.set(false)
                        _authState.update {
                            it.copy(
                                isDeletingAccount = false,
                                needsRetryAuthDelete = true,
                                deleteAccountError = "Cloud data erased. Re-open Account settings to confirm deletion."
                            )
                        }
                        return@launch
                    }
                    when (val reauth = GoogleAuthHelper.reauthenticateWithGoogle(reauthActivity, user)) {
                        is GoogleSignInOutcome.Success -> {
                            suspendRunCatching { GoogleAuthHelper.deleteAuthUser(user) }.onFailure { err ->
                                isSigningOut.set(false)
                                _authState.update {
                                    it.copy(
                                        isDeletingAccount = false,
                                        needsRetryAuthDelete = true,
                                        deleteAccountError = SecurityValidators.safeUserErrorMessage(
                                            err,
                                            "Cloud data erased, but failed to delete Auth account. Tap Retry."
                                        )
                                    )
                                }
                                return@launch
                            }
                        }
                        GoogleSignInOutcome.Cancelled -> {
                            isSigningOut.set(false)
                            _authState.update {
                                it.copy(
                                    isDeletingAccount = false,
                                    needsRetryAuthDelete = true,
                                    deleteAccountError = "Cloud data erased. Confirm Google re-authentication to finish deleting your account."
                                )
                            }
                            return@launch
                        }
                        is GoogleSignInOutcome.Error -> {
                            isSigningOut.set(false)
                            _authState.update {
                                it.copy(
                                    isDeletingAccount = false,
                                    needsRetryAuthDelete = true,
                                    deleteAccountError = reauth.message
                                )
                            }
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    isSigningOut.set(false)
                    _authState.update {
                        it.copy(
                            isDeletingAccount = false,
                            needsRetryAuthDelete = true,
                            deleteAccountError = SecurityValidators.safeUserErrorMessage(
                                e,
                                "Cloud data erased, but Auth deletion failed. Retry."
                            )
                        )
                    }
                    return@launch
                }

                performLocalWipeAndSignOut(signOutAuth = true, clearAllAccountData = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                isSigningOut.set(false)
                _authState.update {
                    it.copy(
                        isDeletingAccount = false,
                        deleteAccountError = SecurityValidators.safeUserErrorMessage(
                            e,
                            "Account deletion failed."
                        )
                    )
                }
            }
        }
    }

    fun clearDeleteAccountError() {
        _authState.update { it.copy(deleteAccountError = null) }
    }

    private suspend fun performLocalWipeAndSignOut(
        signOutAuth: Boolean,
        clearAllAccountData: Boolean,
        discardedPendingUid: String? = null
    ) {
        stopSyncForSignedOut(clearFailureState = true)
        syncMutex.withLock {
            cloudRepo.clearSessionCache()
            if (clearAllAccountData) {
                dao.clearAccountData()
                prefsRepo.resetUserKeys()
                deleteQuarantineFileIfExists()
                readQuarantineResolutionJournal()?.let { deleteRetainedPayloadIfExists(it) }
                deleteAllRetainedPayloadFiles()
                deleteQuarantineResolutionJournalStrict()
            } else {
                dao.clearSyncedAccountData()
                prefsRepo.resetCloudSyncStateForSignOut()
                if (!discardedPendingUid.isNullOrBlank()) {
                    val journal = readQuarantineResolutionJournal()
                    if (journal != null &&
                        journal.targetUid == discardedPendingUid &&
                        journal.action == QuarantineResolutionAction.MERGE &&
                        journal.state == QuarantineResolutionState.CLOUD_SYNC_PENDING
                    ) {
                        deleteRetainedPayloadIfExists(journal)
                        deleteQuarantineResolutionJournalStrict()
                    }
                }
            }
            if (signOutAuth) {
                GoogleAuthHelper.signOut(context.applicationContext, auth)
            }
        }
        isSigningOut.set(false)
        _authState.value = CloudAuthUiState(isSignedIn = false)
    }

    fun syncNow() {
        val user = auth.currentUser ?: return
        if (isSigningOut.get()) return
        scope.launch {
            runBidirectionalMerge(user)
        }
    }

    internal suspend fun syncNowAndAwait(): Boolean {
        val user = auth.currentUser ?: return false
        return runBidirectionalMerge(user)
    }

    fun resolveUnownedDataMergeIntoCurrentAccount() {
        val user = auth.currentUser ?: return
        if (isSigningOut.get()) return
        scope.launch {
            resolveUnownedDataMergeAndAwait()
        }
    }

    internal suspend fun resolveUnownedDataMergeAndAwait(): Boolean {
        val user = auth.currentUser ?: return false
        return resolveQuarantineWithJournalAndMerge(user, QuarantineResolutionAction.MERGE, "Migrating library…")
    }

    fun resolveUnownedDataDiscardAndSyncAccount() {
        val user = auth.currentUser ?: return
        if (isSigningOut.get()) return
        scope.launch {
            resolveUnownedDataDiscardAndAwait()
        }
    }

    internal suspend fun resolveUnownedDataDiscardAndAwait(): Boolean {
        val user = auth.currentUser ?: return false
        return resolveQuarantineWithJournalAndMerge(user, QuarantineResolutionAction.DISCARD, "Syncing cloud account…")
    }

    private suspend fun awaitAuthoritativeStartupPipeline() {
        val customBarrier = awaitStartupProvenance
        if (customBarrier != null) {
            customBarrier()
        } else {
            defaultStartupCoordinator.ensureInitialized()
        }
    }

    private suspend fun resolveQuarantineWithJournalAndMerge(
        user: FirebaseUser,
        action: QuarantineResolutionAction,
        busyStatus: String
    ): Boolean {
        _authState.update { it.copy(isSyncing = true, errorMessage = null, syncStatus = busyStatus) }
        try {
            awaitAuthoritativeStartupPipeline()
            failedCollections.remove("startup_init")
        } catch (e: CancellationException) {
            _authState.update { it.copy(isSyncing = false) }
            throw e
        } catch (e: Exception) {
            val safeMsg = SecurityValidators.safeUserErrorMessage(e, "Startup recovery failed.")
            failedCollections["startup_init"] = safeMsg
            _authState.update {
                it.copy(isSyncing = false, syncStatus = "Sync failed: $safeMsg", errorMessage = safeMsg)
            }
            return false
        }

        val resolved = syncMutex.withLock {
            suppressLocalPushCount.incrementAndGet()
            try {
                if (repository.hasUncommittedRestoreJournal() || prefsRepo.getLocalDataProvenance() == null) {
                    failedCollections["startup_init"] = "Pending backup recovery or local provenance incomplete."
                    _authState.update {
                        it.copy(
                            isSyncing = false,
                            syncStatus = "Quarantine recovery error",
                            errorMessage = "Pending backup recovery or local provenance incomplete."
                        )
                    }
                    return@withLock false
                }

                val journalFile = quarantineResolutionJournalFile()
                val existingJournal = readQuarantineResolutionJournal()
                if (journalFile.exists() && existingJournal == null) {
                    throw IllegalStateException("Pending library resolution journal could not be read.")
                }

                if (existingJournal != null) {
                    if (isJournalCommittedGloballyLocked(existingJournal)) {
                        finalizeCommittedQuarantineCleanupLocked(existingJournal.targetUid, existingJournal)
                    } else if (existingJournal.targetUid != user.uid) {
                        if (!rollbackStaleQuarantineResolutionForForeignUidLocked(existingJournal)) {
                            throw IllegalStateException("Pending library resolution payload could not be restored.")
                        }
                    } else if (existingJournal.action != action) {
                        val hasRecoveryPayload = !retainedFilePresentAndCorrupt(existingJournal) &&
                            resolveReadableRecoveryPayload(existingJournal) != null
                        if (hasRecoveryPayload && existingJournal.state != QuarantineResolutionState.QUARANTINE_CLEARED) {
                            if (!rollbackStaleQuarantineResolutionForForeignUidLocked(existingJournal)) {
                                throw IllegalStateException("Pending library resolution payload could not be restored.")
                            }
                        } else if (existingJournal.state == QuarantineResolutionState.QUARANTINE_CLEARED &&
                            existingJournal.action == QuarantineResolutionAction.DISCARD
                        ) {
                            executeQuarantineResolutionWithJournalLocked(user.uid, existingJournal)
                            return@withLock true
                        } else {
                            throw IllegalStateException("Pending library resolution payload could not be restored.")
                        }
                    } else {
                        executeQuarantineResolutionWithJournalLocked(user.uid, existingJournal)
                        return@withLock true
                    }
                }

                val qFile = quarantineFile()
                if (qFile.exists() && readQuarantineFile() == null) {
                    throw IllegalStateException("Quarantined local library file could not be read.")
                }

                val hasQuarantine = qFile.exists()
                if (!hasQuarantine) {
                    if (action == QuarantineResolutionAction.DISCARD) {
                        dao.clearSyncedAccountData()
                        prefsRepo.commitQuarantineDiscardPrefs(user.uid)
                        cloudRepo.clearSessionCache()
                        failedCollections.clear()
                    } else {
                        prefsRepo.setPendingAccountTransitionUid(null)
                        prefsRepo.setActiveCloudUid(user.uid)
                    }
                    return@withLock true
                }

                val opId = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
                val freshJournal = UnownedQuarantineResolutionJournal(
                    operationId = opId,
                    action = action,
                    targetUid = user.uid,
                    startedAtMs = System.currentTimeMillis(),
                    state = QuarantineResolutionState.PREPARING,
                    retainedPayloadFilename = "${UNOWNED_RETAINED_PREFIX}${opId}.json"
                )
                writeQuarantineResolutionJournalAtomically(freshJournal)
                executeQuarantineResolutionWithJournalLocked(user.uid, freshJournal)
                true
            } catch (e: CancellationException) {
                val conflictCount = readQuarantineFile()?.itemCount ?: 0
                val stillConflicted = quarantineFile().exists()
                _authState.update {
                    it.copy(
                        isSyncing = false,
                        hasUnownedDataConflict = stillConflicted,
                        unownedDataItemCount = if (stillConflicted) conflictCount else 0
                    )
                }
                throw e
            } catch (e: Exception) {
                val safeMsg = SecurityValidators.safeUserErrorMessage(e, "Failed to resolve local library conflict.")
                val conflictCount = readQuarantineFile()?.itemCount ?: 0
                val stillConflicted = quarantineFile().exists()
                _authState.update {
                    it.copy(
                        isSyncing = false,
                        hasUnownedDataConflict = stillConflicted,
                        unownedDataItemCount = if (stillConflicted) conflictCount else 0,
                        syncStatus = "Quarantine recovery error",
                        errorMessage = safeMsg
                    )
                }
                false
            } finally {
                suppressLocalPushCount.decrementAndGet()
            }
        }
        if (!resolved) return false
        _authState.update { it.copy(hasUnownedDataConflict = false, unownedDataItemCount = 0) }
        val ok = runBidirectionalMerge(user)
        if (ok) {
            maybeLaunchWatchers(user)
        }
        return ok
    }

    suspend fun <T> runWithLocalSyncSuppressed(block: suspend () -> T): T = syncMutex.withLock {
        suppressLocalPushCount.incrementAndGet()
        try {
            block()
        } finally {
            suppressLocalPushCount.decrementAndGet()
        }
    }

    fun clearError() {
        _authState.update { it.copy(errorMessage = null) }
    }

    fun shutdown() {
        auth.removeAuthStateListener(authStateListener)
        synchronized(syncLifecycleLock) {
            signOutJob?.cancel()
            signOutJob = null
            stopSyncForSignedOutLocked(clearFailureState = true)
        }
    }

    private fun stopSyncForSignedOut(clearFailureState: Boolean = true) {
        synchronized(syncLifecycleLock) {
            stopSyncForSignedOutLocked(clearFailureState)
        }
    }

    private fun cancelWorkersLocked(): List<Job> {
        retryJob?.cancel()
        val cancelled = mutableListOf<Job>()
        retryJob?.let(cancelled::add)
        retryJob = null
        retryAttempt = 0
        watcherJobs.forEach {
            it.cancel()
            cancelled.add(it)
        }
        watcherJobs.clear()
        return cancelled
    }

    private fun stopSyncForSignedOutLocked(clearFailureState: Boolean = true) {
        activeUserId = null
        cancelWorkersLocked()
        if (clearFailureState) {
            failedCollections.clear()
        }
    }

    private fun startSyncForUserLocked(user: FirebaseUser) {
        if (isSigningOut.get()) return
        if (activeUserId == user.uid && watcherJobs.isNotEmpty()) return
        val priorJobs = cancelWorkersLocked() + listOfNotNull(signOutJob)
        activeUserId = user.uid

        val mainJob = scope.launch {
            transitionMutex.withLock {
                val me = currentCoroutineContext()[Job]
                for (prev in priorJobs) {
                    if (prev != me) {
                        runCatching { prev.cancelAndJoin() }
                    }
                }
            }
            if (activeUserId != user.uid || auth.currentUser?.uid != user.uid || isSigningOut.get()) {
                return@launch
            }
            runBidirectionalMerge(user)
            maybeLaunchWatchers(user)
        }
        watcherJobs.add(mainJob)
    }

    private suspend fun maybeLaunchWatchers(user: FirebaseUser) {
        val readyToWatch = auth.currentUser?.uid == user.uid &&
            !isSigningOut.get() &&
            !failedCollections.containsKey("startup_init") &&
            !hasPendingQuarantineOrJournal() &&
            !repository.hasUncommittedRestoreJournal() &&
            prefsRepo.getLocalDataProvenance() != null &&
            prefsRepo.getActiveCloudUid() == user.uid
        if (!readyToWatch) return
        synchronized(syncLifecycleLock) {
            if (activeUserId == user.uid &&
                auth.currentUser?.uid == user.uid &&
                !isSigningOut.get() &&
                watcherJobs.isEmpty() &&
                !failedCollections.containsKey("startup_init") &&
                !hasPendingQuarantineOrJournal()
            ) {
                launchWatchersLocked(user)
            }
        }
    }

    internal suspend fun countUnsyncedChangesFor(userId: String): Int {
        val baselinesByType = dao.getAllSyncBaselinesForUser(userId).groupBy { it.entityType }
        val tombstones = dao.getAllTombstones().groupBy { it.entityType }
        var count = 0

        // 1. Liked songs
        val likedBaselines = baselinesByType[SyncEntityTypes.LIKED].orEmpty().associateBy { it.entityKey }
        val localLikes = dao.getLikedSongsOnce().filter { !it.videoId.startsWith(LOCAL_SONG_PREFIX) }
        val activeLikeKeys = HashSet<String>(localLikes.size)
        for (entity in localLikes) {
            activeLikeKeys.add(entity.videoId)
            val song = repository.decodeSong(entity.songJson) ?: continue
            val sig = SyncSignatureHelper.likedSongSignature(song)
            val base = likedBaselines[entity.videoId]
            if (base == null || base.deleted || base.signature != sig) {
                count++
            }
        }
        val likedTombs = tombstones[SyncEntityTypes.LIKED].orEmpty().associateBy { it.entityKey }
        for (tomb in likedTombs.values) {
            val base = likedBaselines[tomb.entityKey]
            if (base == null || !base.deleted || tomb.clientUpdatedAt > base.clientUpdatedAt) count++
        }
        for (base in likedBaselines.values) {
            if (!base.deleted && base.entityKey !in activeLikeKeys && base.entityKey !in likedTombs) count++
        }

        // 2. Playlists
        val plBaselines = baselinesByType[SyncEntityTypes.PLAYLIST].orEmpty().associateBy { it.entityKey }
        val localPlaylists = dao.getPlaylistsOnce()
        val activePlKeys = HashSet<String>(localPlaylists.size)
        for (pl in localPlaylists) {
            activePlKeys.add(pl.cloudKey)
            val tracks = dao.getPlaylistTracks(pl.id)
                .mapNotNull { repository.decodeSong(it.songJson) }
                .filter { !it.isLocal }
            val sig = SyncSignatureHelper.playlistSignature(
                cloudKey = pl.cloudKey,
                title = pl.title,
                description = pl.description,
                coverUri = pl.remoteCoverUri ?: SecurityValidators.sanitizeHttpsUrl(pl.coverUri, 500),
                tracks = tracks,
                coverRemoved = pl.coverRemoved
            )
            val base = plBaselines[pl.cloudKey]
            if (base == null || base.deleted || pl.syncRevision > base.revision || base.signature != sig) {
                count++
            }
        }
        val plTombs = tombstones[SyncEntityTypes.PLAYLIST].orEmpty().associateBy { it.entityKey }
        for (tomb in plTombs.values) {
            val base = plBaselines[tomb.entityKey]
            if (base == null || !base.deleted || tomb.clientUpdatedAt > base.clientUpdatedAt) count++
        }
        for (base in plBaselines.values) {
            if (!base.deleted && base.entityKey !in activePlKeys && base.entityKey !in plTombs) count++
        }

        // 3. Hidden artists
        val hiddenBaselines = baselinesByType[SyncEntityTypes.HIDDEN_ARTIST].orEmpty().associateBy { it.entityKey }
        val localBlocked = dao.getBlockedArtistsOnce()
        val activeHiddenKeys = HashSet<String>(localBlocked.size)
        for (b in localBlocked) {
            val norm = b.name.lowercase().trim()
            if (norm.isBlank()) continue
            val key = CloudSyncRepository.stableDocId("blk", norm)
            activeHiddenKeys.add(key)
            val sig = SyncSignatureHelper.hiddenArtistSignature(b.name, b.channelId)
            val base = hiddenBaselines[key]
            if (base == null || base.deleted || base.signature != sig) count++
        }
        val hiddenTombs = tombstones[SyncEntityTypes.HIDDEN_ARTIST].orEmpty().associateBy { it.entityKey }
        for (tomb in hiddenTombs.values) {
            val base = hiddenBaselines[tomb.entityKey]
            if (base == null || !base.deleted || tomb.clientUpdatedAt > base.clientUpdatedAt) count++
        }
        for (base in hiddenBaselines.values) {
            if (!base.deleted && base.entityKey !in activeHiddenKeys && base.entityKey !in hiddenTombs) count++
        }

        // 4. Taste seeds
        val seedBaselines = baselinesByType[SyncEntityTypes.SEED].orEmpty().associateBy { it.entityKey }
        val taste = prefsRepo.tasteProfileFlow.first()
        val activeSeedKeys = HashSet<String>()
        taste.seedArtists.forEach { seed ->
            val id = CloudSyncRepository.resolveSeedDocId("art", seed)
            activeSeedKeys.add(id)
            val sig = SyncSignatureHelper.tasteSeedSignature("ARTIST", seed)
            val base = seedBaselines[id]
            if (base == null || base.deleted || base.signature != sig) count++
        }
        taste.seedSongs.forEach { seed ->
            val id = CloudSyncRepository.resolveSeedDocId("sng", seed)
            activeSeedKeys.add(id)
            val sig = SyncSignatureHelper.tasteSeedSignature("SONG", seed)
            val base = seedBaselines[id]
            if (base == null || base.deleted || base.signature != sig) count++
        }
        val seedTombs = tombstones[SyncEntityTypes.SEED].orEmpty().associateBy { it.entityKey }
        for (tomb in seedTombs.values) {
            val base = seedBaselines[tomb.entityKey]
            if (base == null || !base.deleted || tomb.clientUpdatedAt > base.clientUpdatedAt) count++
        }
        for (base in seedBaselines.values) {
            if (!base.deleted && base.entityKey !in activeSeedKeys && base.entityKey !in seedTombs) count++
        }

        // 5. Profile & preferences
        val prefs = prefsRepo.prefsFlow.first()
        val profBase = baselinesByType[SyncEntityTypes.PROFILE_PREFS].orEmpty().firstOrNull()
        val profSig = SyncSignatureHelper.syncableProfilePrefsSignature(prefs, taste, prefs.userDisplayName)
        if (profBase == null || profBase.signature != profSig) {
            count++
        }

        return count.coerceAtLeast(if (failedCollections.isNotEmpty()) failedCollections.size else 0)
    }

    private suspend fun scrubLocalLikesOnceIfNeeded() {
        if (prefsRepo.isLocalLikesScrubbed()) return
        val localIds = dao.getLocalLikedVideoIds()
        if (localIds.isNotEmpty()) {
            dao.deleteLocalLikedSongs()
            suspendRunCatching { cloudRepo.purgeLocalLikedDocsFromCloud(localIds) }
        }
        prefsRepo.markLocalLikesScrubbed()
    }

    private fun stateRootDir(): File = stateDir ?: context.noBackupFilesDir

    private fun quarantineFile(): File =
        File(stateRootDir(), UNOWNED_QUARANTINE_FILENAME)

    private fun quarantineResolutionJournalFile(): File =
        File(stateRootDir(), UNOWNED_RESOLUTION_JOURNAL_FILENAME)

    private fun sanitizeSafeChildFilename(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains("..")) {
            return null
        }
        return trimmed
    }

    private fun retainedPayloadFile(journal: UnownedQuarantineResolutionJournal): File? {
        val safeName = sanitizeSafeChildFilename(journal.retainedPayloadFilename) ?: return null
        val root = stateRootDir().canonicalFile
        val candidate = File(root, safeName).canonicalFile
        return if (candidate.parentFile == root) candidate else null
    }

    internal fun listRetainedPayloadFiles(): List<File> {
        val dir = stateRootDir()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter {
            it.isFile && it.name.startsWith(UNOWNED_RETAINED_PREFIX) && it.name.endsWith(".json")
        }.orEmpty()
    }

    private fun readQuarantineFromFile(file: File): UnownedAccountDataQuarantine? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<UnownedAccountDataQuarantine>(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    internal fun readQuarantineFile(): UnownedAccountDataQuarantine? =
        readQuarantineFromFile(quarantineFile())

    internal fun readQuarantineResolutionJournal(): UnownedQuarantineResolutionJournal? {
        val file = quarantineResolutionJournalFile()
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<UnownedQuarantineResolutionJournal>(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun retainedFilePresentAndCorrupt(journal: UnownedQuarantineResolutionJournal): Boolean {
        val retained = retainedPayloadFile(journal) ?: return false
        return retained.exists() && readQuarantineFromFile(retained) == null
    }

    private fun resolveReadableRecoveryPayload(journal: UnownedQuarantineResolutionJournal): UnownedAccountDataQuarantine? {
        if (retainedFilePresentAndCorrupt(journal)) return null
        return retainedPayloadFile(journal)?.let(::readQuarantineFromFile) ?: readQuarantineFile()
    }

    private suspend fun isJournalCommittedGloballyLocked(journal: UnownedQuarantineResolutionJournal): Boolean {
        if (journal.state == QuarantineResolutionState.CLOUD_SYNC_COMMITTED) return true
        if (journal.state != QuarantineResolutionState.COMPLETED) return false
        if (journal.action == QuarantineResolutionAction.DISCARD) return true
        val activeUid = prefsRepo.getActiveCloudUid()
        if (activeUid != journal.targetUid) return false
        val lastSuccess = prefsRepo.getLastSuccessfulSyncAtMs(journal.targetUid)
        val hasBaselines = dao.getAllSyncBaselinesForUser(journal.targetUid).isNotEmpty()
        return lastSuccess >= journal.startedAtMs || hasBaselines
    }

    private fun hasPendingQuarantineOrJournal(): Boolean {
        if (_authState.value.hasUnownedDataConflict) return true
        if (quarantineFile().exists()) return true
        return quarantineResolutionJournalFile().exists()
    }

    private fun writeQuarantineAtomically(quarantine: UnownedAccountDataQuarantine) {
        writeTextAtomically(quarantineFile(), UNOWNED_QUARANTINE_FILENAME, json.encodeToString(quarantine))
    }

    private fun writeRetainedPayloadAtomically(filename: String, quarantine: UnownedAccountDataQuarantine) {
        val safeName = sanitizeSafeChildFilename(filename)
            ?: throw IllegalArgumentException("Invalid retained quarantine filename: $filename")
        val target = File(stateRootDir(), safeName)
        writeTextAtomically(target, safeName, json.encodeToString(quarantine))
    }

    private fun writeQuarantineResolutionJournalAtomically(journal: UnownedQuarantineResolutionJournal) {
        writeTextAtomically(quarantineResolutionJournalFile(), UNOWNED_RESOLUTION_JOURNAL_FILENAME, json.encodeToString(journal))
    }

    private fun writeTextAtomically(target: File, baseName: String, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$baseName.tmp")
        val bytes = content.toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                FileOutputStream(target).use { dst ->
                    dst.write(bytes)
                    dst.flush()
                    dst.fd.sync()
                }
                tmp.delete()
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun deleteQuarantineFileIfExists() {
        val file = quarantineFile()
        if (file.exists() && !file.delete()) {
            throw java.io.IOException("Failed to remove resolved quarantine file")
        }
        File(file.parentFile, "$UNOWNED_QUARANTINE_FILENAME.tmp").takeIf { it.exists() }?.delete()
    }

    private fun deleteRetainedPayloadIfExists(journal: UnownedQuarantineResolutionJournal) {
        val file = retainedPayloadFile(journal) ?: return
        if (file.exists() && !file.delete()) {
            throw java.io.IOException("Failed to remove retained quarantine payload file")
        }
        File(file.parentFile, "${file.name}.tmp").takeIf { it.exists() }?.delete()
    }

    private fun deleteAllRetainedPayloadFiles() {
        val dir = stateRootDir()
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(UNOWNED_RETAINED_PREFIX)) {
                f.delete()
            }
        }
    }

    private fun deleteQuarantineResolutionJournalStrict() {
        val file = quarantineResolutionJournalFile()
        if (file.exists() && !file.delete()) {
            throw java.io.IOException("Failed to remove resolved quarantine journal file")
        }
        File(file.parentFile, "$UNOWNED_RESOLUTION_JOURNAL_FILENAME.tmp").takeIf { it.exists() }?.delete()
    }

    private suspend fun rollbackStaleQuarantineResolutionForForeignUidLocked(
        staleJournal: UnownedQuarantineResolutionJournal
    ): Boolean {
        return withContext(NonCancellable) {
            val qFile = quarantineFile()
            val preservedQuarantine = readQuarantineFile()
            if (qFile.exists() && preservedQuarantine == null) {
                return@withContext false
            }
            if (retainedFilePresentAndCorrupt(staleJournal)) {
                return@withContext false
            }
            val retainedQuarantine = retainedPayloadFile(staleJournal)?.let(::readQuarantineFromFile)
            val recoveryPayload = retainedQuarantine ?: preservedQuarantine

            // Fail closed WITHOUT destroying Room or deleting the journal if no readable recovery payload exists.
            if (recoveryPayload == null) {
                return@withContext false
            }

            if (preservedQuarantine == null) {
                writeQuarantineAtomically(recoveryPayload)
            }
            if (readQuarantineFile() == null) {
                return@withContext false
            }

            dao.clearSyncedAccountData()
            prefsRepo.resetCloudSyncStateForSignOut()
            prefsRepo.setPendingAccountTransitionUid(null)
            cloudRepo.clearSessionCache()
            failedCollections.clear()

            deleteRetainedPayloadIfExists(staleJournal)
            deleteQuarantineResolutionJournalStrict()
            true
        }
    }

    private suspend fun finalizeCommittedQuarantineCleanupLocked(
        userUid: String,
        journal: UnownedQuarantineResolutionJournal
    ) {
        var cur = journal
        if (cur.state == QuarantineResolutionState.CLOUD_SYNC_COMMITTED) {
            cur = cur.copy(state = QuarantineResolutionState.COMPLETED)
            writeQuarantineResolutionJournalAtomically(cur)
            onAfterQuarantineJournalCompletedBeforeCleanup?.invoke(cur.action, userUid)
        }
        if (cur.state == QuarantineResolutionState.COMPLETED) {
            deleteRetainedPayloadIfExists(cur)
            onAfterQuarantinePayloadCleanupBeforeJournalDelete?.invoke(cur.action, userUid)
            deleteQuarantineResolutionJournalStrict()
        }
    }

    private suspend fun executeQuarantineResolutionWithJournalLocked(
        userUid: String,
        initialJournal: UnownedQuarantineResolutionJournal
    ) {
        var cur = initialJournal
        if (cur.state == QuarantineResolutionState.CLOUD_SYNC_COMMITTED ||
            cur.state == QuarantineResolutionState.COMPLETED
        ) {
            finalizeCommittedQuarantineCleanupLocked(userUid, cur)
            return
        }

        if (cur.state == QuarantineResolutionState.PREPARING) {
            val payload = resolveReadableRecoveryPayload(cur)
                ?: throw IllegalStateException("Quarantine payload unreadable or missing before archival.")
            val retainedName = cur.retainedPayloadFilename.takeIf { sanitizeSafeChildFilename(it) != null }
                ?: "${UNOWNED_RETAINED_PREFIX}${cur.operationId}.json"
            val existingRetained = File(stateRootDir(), retainedName)
            if (existingRetained.exists() && readQuarantineFromFile(existingRetained) == null) {
                throw IllegalStateException("Existing retained quarantine archive is corrupt.")
            }
            if (!existingRetained.exists()) {
                writeRetainedPayloadAtomically(retainedName, payload)
            }
            cur = cur.copy(
                state = QuarantineResolutionState.PAYLOAD_ARCHIVED,
                retainedPayloadFilename = retainedName
            )
            writeQuarantineResolutionJournalAtomically(cur)
            onAfterQuarantinePayloadArchived?.invoke(cur.action, userUid)
        }

        if (cur.action == QuarantineResolutionAction.MERGE) {
            val quarantine = if (
                cur.state == QuarantineResolutionState.PAYLOAD_ARCHIVED ||
                cur.state == QuarantineResolutionState.ROOM_APPLIED
            ) {
                resolveReadableRecoveryPayload(cur)
                    ?: throw IllegalStateException("Quarantine payload unreadable or missing during merge resolution.")
            } else {
                null
            }

            if (cur.state == QuarantineResolutionState.PAYLOAD_ARCHIVED && quarantine != null) {
                onBeforeQuarantineRoomApply?.invoke(QuarantineResolutionAction.MERGE, userUid)
                val liked = quarantine.likedSongs.map { LikedSongEntity(it.videoId, it.songJson, it.likedAt) }
                val blocked = quarantine.blockedArtists.map { BlockedArtistEntity(it.name, it.channelId, it.blockedAt) }
                val tombs = quarantine.tombstones.map { SyncTombstoneEntity(it.entityType, it.entityKey, it.clientUpdatedAt, it.deletedAt) }
                val bundles = quarantine.playlists.map { pl ->
                    val tracks = quarantine.playlistTracks
                        .filter { it.playlistId == pl.id }
                        .sortedBy { it.position }
                        .map {
                            LocalPlaylistTrackEntity(
                                playlistId = 0,
                                videoId = it.videoId,
                                songJson = it.songJson,
                                position = it.position,
                                addedAt = it.addedAt
                            )
                        }
                    val trkOps = quarantine.playlistTrackOps
                        .filter { it.playlistId == pl.id }
                        .map { PlaylistTrackOpEntity(0, it.videoId, it.deleted, it.timestampMs, it.opSeq, it.deviceId, it.opId) }
                    val fldOps = quarantine.playlistFieldOps
                        .filter { it.playlistId == pl.id }
                        .map { PlaylistFieldOpEntity(0, it.fieldName, it.timestampMs, it.opSeq, it.deviceId, it.opId) }
                    QuarantinePlaylistBundle(
                        playlist = LocalPlaylistEntity(
                            cloudKey = pl.cloudKey,
                            title = pl.title,
                            description = pl.description,
                            coverUri = pl.coverUri,
                            remoteCoverUri = pl.remoteCoverUri,
                            coverRemoved = pl.coverRemoved,
                            createdAt = pl.createdAt,
                            updatedAt = pl.updatedAt,
                            syncRevision = pl.syncRevision
                        ),
                        tracks = tracks,
                        trackOps = trkOps,
                        fieldOps = fldOps
                    )
                }
                dao.restoreQuarantinedAccountDataAtomic(
                    likedSongs = liked,
                    blockedArtists = blocked,
                    tombstones = tombs,
                    playlists = bundles
                )
                cur = cur.copy(state = QuarantineResolutionState.ROOM_APPLIED)
                writeQuarantineResolutionJournalAtomically(cur)
            }

            if (cur.state == QuarantineResolutionState.ROOM_APPLIED && quarantine != null) {
                onAfterQuarantineRoomApplyBeforePrefs?.invoke(QuarantineResolutionAction.MERGE, userUid)
                val qProfile = quarantine.profilePrefs
                prefsRepo.commitQuarantineMergePrefs(
                    uid = userUid,
                    tasteProfile = quarantine.tasteProfile,
                    displayName = quarantine.displayName,
                    prefsUpdatedAtMs = quarantine.prefsUpdatedAtMs,
                    themePreset = qProfile?.let { idWrap ->
                        VelunaThemePreset.entries.find { it.id == idWrap.themePresetId }
                    },
                    themeMode = qProfile?.let { p -> runCatching { ThemeMode.valueOf(p.themeModeName) }.getOrNull() },
                    customAccentHex = qProfile?.customAccentHex,
                    adaptToArtwork = qProfile?.adaptToArtwork,
                    cornerRadiusDp = qProfile?.cornerRadiusDp,
                    audioQuality = qProfile?.let { p -> runCatching { AudioQuality.valueOf(p.audioQualityName) }.getOrNull() },
                    autoplay = qProfile?.autoplay,
                    loudnessNormalization = qProfile?.loudnessNormalization,
                    hideMusicVideos = qProfile?.hideMusicVideos,
                    applyQuarantinedThemeAndAudio = qProfile != null
                )
                cur = cur.copy(state = QuarantineResolutionState.PREFS_APPLIED)
                writeQuarantineResolutionJournalAtomically(cur)
            }

            if (cur.state == QuarantineResolutionState.PREFS_APPLIED) {
                onAfterQuarantinePrefsBeforeDeleteFile?.invoke(QuarantineResolutionAction.MERGE, userUid)
                cur = ensureRetainedArchiveBeforeDeletingQuarantine(cur)
                deleteQuarantineFileIfExists()
                onAfterQuarantineDeleteBeforeClearedState?.invoke(QuarantineResolutionAction.MERGE, userUid)
                cur = cur.copy(state = QuarantineResolutionState.QUARANTINE_CLEARED)
                writeQuarantineResolutionJournalAtomically(cur)
            }

            if (cur.state == QuarantineResolutionState.QUARANTINE_CLEARED) {
                onAfterQuarantineDeleteBeforeJournalComplete?.invoke(QuarantineResolutionAction.MERGE, userUid)
                val retained = retainedPayloadFile(cur)?.let(::readQuarantineFromFile)
                    ?: throw IllegalStateException("Retained quarantine archive missing or corrupt before pending cloud sync.")
                if (retained.createdAtMs < 0L) throw IllegalStateException("Invalid retained quarantine payload.")
                cur = cur.copy(state = QuarantineResolutionState.CLOUD_SYNC_PENDING)
                writeQuarantineResolutionJournalAtomically(cur)
                onAfterQuarantineCloudSyncPendingBeforeSync?.invoke(QuarantineResolutionAction.MERGE, userUid)
            }

            if (cur.state == QuarantineResolutionState.CLOUD_SYNC_PENDING) {
                // Verify retained archive remains intact and readable until remote cloud sync succeeds & commits.
                val retained = retainedPayloadFile(cur)
                    ?.takeIf { !retainedFilePresentAndCorrupt(cur) }
                    ?.let(::readQuarantineFromFile)
                    ?: throw IllegalStateException("Retained quarantine payload is missing or corrupt before remote sync commit.")
                if (retained.createdAtMs < 0L) {
                    throw IllegalStateException("Retained quarantine payload is invalid.")
                }
            }
        } else {
            if (cur.state == QuarantineResolutionState.PAYLOAD_ARCHIVED) {
                onBeforeQuarantineRoomApply?.invoke(QuarantineResolutionAction.DISCARD, userUid)
                dao.clearSyncedAccountData()
                cloudRepo.clearSessionCache()
                failedCollections.clear()
                cur = cur.copy(state = QuarantineResolutionState.ROOM_APPLIED)
                writeQuarantineResolutionJournalAtomically(cur)
            }

            if (cur.state == QuarantineResolutionState.ROOM_APPLIED) {
                onAfterQuarantineRoomApplyBeforePrefs?.invoke(QuarantineResolutionAction.DISCARD, userUid)
                prefsRepo.commitQuarantineDiscardPrefs(userUid)
                cur = cur.copy(state = QuarantineResolutionState.PREFS_APPLIED)
                writeQuarantineResolutionJournalAtomically(cur)
            }

            if (cur.state == QuarantineResolutionState.PREFS_APPLIED) {
                onAfterQuarantinePrefsBeforeDeleteFile?.invoke(QuarantineResolutionAction.DISCARD, userUid)
                cur = ensureRetainedArchiveBeforeDeletingQuarantine(cur)
                deleteQuarantineFileIfExists()
                onAfterQuarantineDeleteBeforeClearedState?.invoke(QuarantineResolutionAction.DISCARD, userUid)
                cur = cur.copy(state = QuarantineResolutionState.QUARANTINE_CLEARED)
                writeQuarantineResolutionJournalAtomically(cur)
            }

            if (cur.state == QuarantineResolutionState.QUARANTINE_CLEARED) {
                onAfterQuarantineDeleteBeforeJournalComplete?.invoke(QuarantineResolutionAction.DISCARD, userUid)
                cur = cur.copy(state = QuarantineResolutionState.COMPLETED)
                writeQuarantineResolutionJournalAtomically(cur)
                onAfterQuarantineJournalCompletedBeforeCleanup?.invoke(QuarantineResolutionAction.DISCARD, userUid)
                deleteRetainedPayloadIfExists(cur)
                onAfterQuarantinePayloadCleanupBeforeJournalDelete?.invoke(QuarantineResolutionAction.DISCARD, userUid)
                deleteQuarantineResolutionJournalStrict()
            }
        }
    }

    private fun ensureRetainedArchiveBeforeDeletingQuarantine(
        journal: UnownedQuarantineResolutionJournal
    ): UnownedQuarantineResolutionJournal {
        val existingRetained = retainedPayloadFile(journal)
        if (existingRetained != null && existingRetained.exists()) {
            val parsed = readQuarantineFromFile(existingRetained)
                ?: throw IllegalStateException("Existing retained quarantine archive is corrupt before quarantine file removal.")
            if (parsed.createdAtMs >= 0L) return journal
        }
        val payload = readQuarantineFile()
            ?: throw IllegalStateException("Quarantine payload missing or unreadable before quarantine file removal.")
        val retainedName = journal.retainedPayloadFilename.takeIf { sanitizeSafeChildFilename(it) != null }
            ?: "${UNOWNED_RETAINED_PREFIX}${journal.operationId}.json"
        writeRetainedPayloadAtomically(retainedName, payload)
        return if (retainedName != journal.retainedPayloadFilename) {
            journal.copy(retainedPayloadFilename = retainedName).also {
                writeQuarantineResolutionJournalAtomically(it)
            }
        } else {
            journal
        }
    }

    private suspend fun recoverIncompleteQuarantineResolutionLocked(uid: String): Boolean {
        val journalFile = quarantineResolutionJournalFile()
        if (journalFile.exists()) {
            val journal = readQuarantineResolutionJournal()
            if (journal == null) {
                // Corrupt journal file: fail closed without touching user data or syncing to cloud
                val conflictCount = readQuarantineFile()?.itemCount ?: 0
                _authState.update {
                    it.copy(
                        isSyncing = false,
                        hasUnownedDataConflict = quarantineFile().exists(),
                        unownedDataItemCount = conflictCount,
                        syncStatus = "Quarantine recovery error",
                        errorMessage = "Pending library resolution could not be read."
                    )
                }
                return false
            }
            if (isJournalCommittedGloballyLocked(journal)) {
                if (journal.targetUid == uid) {
                    try {
                        finalizeCommittedQuarantineCleanupLocked(uid, journal)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val safeMsg = SecurityValidators.safeUserErrorMessage(e, "Failed to finalize quarantine cleanup.")
                        _authState.update {
                            it.copy(
                                isSyncing = false,
                                syncStatus = "Sync failed: $safeMsg",
                                errorMessage = safeMsg
                            )
                        }
                        return false
                    }
                } else {
                    // Remote sync for foreign targetUid already committed; remove leftover journal & retained copy
                    // so normal account-switch wipe handles A -> B cleanly.
                    deleteRetainedPayloadIfExists(journal)
                    deleteQuarantineResolutionJournalStrict()
                }
            } else if (journal.targetUid != uid) {
                if (!rollbackStaleQuarantineResolutionForForeignUidLocked(journal)) {
                    val conflictCount = readQuarantineFile()?.itemCount ?: 0
                    _authState.update {
                        it.copy(
                            isSyncing = false,
                            hasUnownedDataConflict = quarantineFile().exists(),
                            unownedDataItemCount = conflictCount,
                            syncStatus = "Quarantine recovery error",
                            errorMessage = "Pending library resolution payload could not be restored."
                        )
                    }
                    return false
                }
            } else {
                try {
                    if (retainedFilePresentAndCorrupt(journal)) {
                        throw IllegalStateException("Retained quarantine payload file is corrupted.")
                    }
                    executeQuarantineResolutionWithJournalLocked(uid, journal)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val safeMsg = SecurityValidators.safeUserErrorMessage(e, "Failed to recover library resolution.")
                    val conflictCount = readQuarantineFile()?.itemCount ?: 0
                    val hasQ = quarantineFile().exists()
                    _authState.update {
                        it.copy(
                            isSyncing = false,
                            hasUnownedDataConflict = hasQ,
                            unownedDataItemCount = if (hasQ) conflictCount else 0,
                            syncStatus = "Quarantine recovery error",
                            errorMessage = safeMsg
                        )
                    }
                    return false
                }
            }
        }

        if (!quarantineResolutionJournalFile().exists()) {
            deleteAllRetainedPayloadFiles()
        }

        // If quarantine file exists at startup without a journal (or after foreign-uid rollback), fail closed cleanly
        if (quarantineFile().exists()) {
            val parsed = readQuarantineFile()
            _authState.update {
                it.copy(
                    isSyncing = false,
                    hasUnownedDataConflict = true,
                    unownedDataItemCount = parsed?.itemCount ?: 0,
                    syncStatus = if (parsed == null) "Quarantine recovery error" else "Resolve local library conflict",
                    errorMessage = if (parsed == null) "Unreadable quarantined local library file." else null
                )
            }
            return false
        }
        return true
    }

    private suspend fun ensureAccountOwnershipIsolatedLocked(uid: String): Boolean {
        if (repository.hasUncommittedRestoreJournal()) {
            failedCollections["startup_init"] = "Pending backup restore must recover before cloud sync."
            _authState.update {
                it.copy(
                    isSyncing = false,
                    syncStatus = "Sync failed: Pending backup restore incomplete.",
                    errorMessage = "Pending backup restore incomplete."
                )
            }
            return false
        }
        val provenance = prefsRepo.getLocalDataProvenance()
        if (provenance == null) {
            failedCollections["startup_init"] = "Local data provenance could not be verified."
            _authState.update {
                it.copy(
                    isSyncing = false,
                    syncStatus = "Sync failed: Local provenance missing.",
                    errorMessage = "Local data provenance could not be verified."
                )
            }
            return false
        }
        if (!recoverIncompleteQuarantineResolutionLocked(uid)) {
            return false
        }

        val pendingTransitionUid = prefsRepo.getPendingAccountTransitionUid()
        if (pendingTransitionUid != null) {
            withContext(NonCancellable) {
                dao.clearSyncedAccountData()
                prefsRepo.resetCloudSyncStateForSignOut()
                cloudRepo.clearSessionCache()
                failedCollections.clear()
                prefsRepo.setActiveCloudUid(uid)
                prefsRepo.setPendingAccountTransitionUid(null)
            }
            return true
        }

        val persistedUid = prefsRepo.getActiveCloudUid()
        if (persistedUid != null && persistedUid != uid) {
            withContext(NonCancellable) {
                prefsRepo.setPendingAccountTransitionUid(uid)
                onBeforeAccountTransitionWipe?.invoke(persistedUid, uid)
                dao.clearSyncedAccountData()
                prefsRepo.resetCloudSyncStateForSignOut()
                cloudRepo.clearSessionCache()
                failedCollections.clear()
                onAfterAccountTransitionWipeBeforeFinalize?.invoke(persistedUid, uid)
                prefsRepo.setActiveCloudUid(uid)
                prefsRepo.setPendingAccountTransitionUid(null)
            }
            return true
        }

        if (persistedUid == null) {
            if (quarantineFile().exists()) {
                val existingQuarantine = readQuarantineFile()
                _authState.update {
                    it.copy(
                        isSyncing = false,
                        hasUnownedDataConflict = true,
                        unownedDataItemCount = existingQuarantine?.itemCount ?: 0,
                        syncStatus = if (existingQuarantine == null) "Quarantine recovery error" else "Resolve local library conflict",
                        errorMessage = if (existingQuarantine == null) "Unreadable quarantined local library file." else null
                    )
                }
                return false
            }

            val hasForeignBaselines = dao.hasSyncBaselinesForOtherUsers(uid)
            val hasOwnBaselines = dao.getAllSyncBaselinesForUser(uid).isNotEmpty()
            val hasLegacySyncMarkers = prefsRepo.hasLegacyCloudSyncMarkers(uid)
            val playlists = dao.getPlaylistsOnce()
            val hasLegacyPlaylistUpgradeMarkers = playlists.any {
                it.cloudKey.startsWith("pl_local_")
            }
            val tombstones = dao.getAllTombstones()
            val localLiked = dao.getLikedSongsOnce().filter { !it.videoId.startsWith(LOCAL_SONG_PREFIX) }
            val localBlocked = dao.getBlockedArtistsOnce()
            val localTaste = prefsRepo.tasteProfileFlow.first()
            val localPrefs = prefsRepo.prefsFlow.first()
            val prefsUpdatedAtMs = prefsRepo.getPrefsUpdatedAt()
            val hasProfileState = prefsRepo.hasSyncableProfileState()
            val hasSyncedContent = playlists.isNotEmpty() ||
                localLiked.isNotEmpty() ||
                localBlocked.isNotEmpty() ||
                tombstones.isNotEmpty() ||
                localTaste.seedArtists.isNotEmpty() ||
                localTaste.seedSongs.isNotEmpty() ||
                hasProfileState

            val requiresFailClosedQuarantine = hasForeignBaselines ||
                (hasSyncedContent &&
                    !hasOwnBaselines &&
                    (hasLegacySyncMarkers ||
                        hasLegacyPlaylistUpgradeMarkers ||
                        tombstones.isNotEmpty() ||
                        provenance != LocalDataProvenance.FRESH_LOCAL))

            if (requiresFailClosedQuarantine) {
                val quarantine = UnownedAccountDataQuarantine(
                    createdAtMs = System.currentTimeMillis(),
                    likedSongs = localLiked.map { SerializableLikedSong(it.videoId, it.songJson, it.likedAt) },
                    playlists = playlists.map {
                        SerializableLocalPlaylist(
                            id = it.id,
                            cloudKey = it.cloudKey,
                            title = it.title,
                            description = it.description,
                            coverUri = it.coverUri,
                            remoteCoverUri = it.remoteCoverUri,
                            coverRemoved = it.coverRemoved,
                            createdAt = it.createdAt,
                            updatedAt = it.updatedAt,
                            syncRevision = it.syncRevision
                        )
                    },
                    playlistTracks = dao.getAllPlaylistTracksOnce().map {
                        SerializableLocalPlaylistTrack(it.id, it.playlistId, it.videoId, it.songJson, it.position, it.addedAt)
                    },
                    playlistTrackOps = dao.getAllPlaylistTrackOpsOnce().map {
                        SerializablePlaylistTrackOp(it.playlistId, it.videoId, it.deleted, it.timestampMs, it.opSeq, it.deviceId, it.opId)
                    },
                    playlistFieldOps = dao.getAllPlaylistFieldOpsOnce().map {
                        SerializablePlaylistFieldOp(it.playlistId, it.fieldName, it.timestampMs, it.opSeq, it.deviceId, it.opId)
                    },
                    blockedArtists = localBlocked.map { SerializableBlockedArtist(it.name, it.channelId, it.blockedAt) },
                    tombstones = tombstones.map {
                        SerializableSyncTombstone(it.entityType, it.entityKey, it.clientUpdatedAt, it.deletedAt)
                    },
                    tasteProfile = localTaste,
                    displayName = localPrefs.userDisplayName,
                    prefsUpdatedAtMs = prefsUpdatedAtMs,
                    profilePrefs = QuarantinedSyncableProfilePrefs(
                        themePresetId = localPrefs.themePreset.id,
                        themeModeName = localPrefs.themeMode.name,
                        customAccentHex = localPrefs.customAccentHex,
                        adaptToArtwork = localPrefs.adaptToArtwork,
                        cornerRadiusDp = localPrefs.cornerRadiusDp,
                        audioQualityName = localPrefs.audioQuality.name,
                        autoplay = localPrefs.autoplay,
                        loudnessNormalization = localPrefs.loudnessNormalization,
                        hideMusicVideos = localPrefs.hideMusicVideos
                    )
                )
                writeQuarantineAtomically(quarantine)
                withContext(NonCancellable) {
                    dao.clearSyncedAccountData()
                    prefsRepo.resetCloudSyncStateForSignOut()
                    cloudRepo.clearSessionCache()
                    failedCollections.clear()
                }
                _authState.update {
                    it.copy(
                        isSyncing = false,
                        hasUnownedDataConflict = true,
                        unownedDataItemCount = quarantine.itemCount,
                        syncStatus = "Resolve local library conflict",
                        errorMessage = null
                    )
                }
                return false
            }

            prefsRepo.setActiveCloudUid(uid)
        }
        return true
    }

    private suspend fun verifyAllSyncedCollectionsBaselinedForUidLocked(uid: String): Boolean {
        if (failedCollections.isNotEmpty()) return false
        if (auth.currentUser?.uid != uid) return false
        if (prefsRepo.getActiveCloudUid() != uid) return false
        if (dao.getSyncBaselinesByType(uid, SyncEntityTypes.PROFILE_PREFS).isEmpty()) return false
        return countUnsyncedChangesFor(uid) == 0
    }

    private suspend fun runBidirectionalMerge(
        user: FirebaseUser,
        allowDuringSignOut: Boolean = false
    ): Boolean {
        if (auth.currentUser?.uid != user.uid) return false
        if (!allowDuringSignOut && isSigningOut.get()) return false

        // Execute the single authoritative startup pipeline BEFORE acquiring syncMutex or talking to Firestore:
        // (1) recover incomplete restore -> (2) verify Room & DataStore stable -> (3) init provenance -> (4) verify provenance.
        try {
            awaitAuthoritativeStartupPipeline()
            failedCollections.remove("startup_init")
        } catch (e: CancellationException) {
            _authState.update { it.copy(isSyncing = false) }
            throw e
        } catch (e: Exception) {
            val safeMsg = SecurityValidators.safeUserErrorMessage(e, "Pending startup recovery failed.")
            failedCollections["startup_init"] = safeMsg
            _authState.update {
                it.copy(isSyncing = false, syncStatus = "Sync failed: $safeMsg", errorMessage = safeMsg)
            }
            return false
        }

        return syncMutex.withLock {
            if (auth.currentUser?.uid != user.uid) return@withLock false
            if (!allowDuringSignOut && isSigningOut.get()) return@withLock false

            _authState.update {
                it.copy(
                    isSyncing = true,
                    syncStatus = "Syncing…",
                    errorMessage = if (failedCollections.isEmpty()) null else it.errorMessage
                )
            }
            suppressLocalPushCount.incrementAndGet()
            try {
                if (!ensureAccountOwnershipIsolatedLocked(user.uid)) {
                    return@withLock false
                }
                scrubLocalLikesOnceIfNeeded()

                val now = System.currentTimeMillis()
                val tombstoneCutoff = now - TOMBSTONE_RETENTION_MS
                dao.purgeOldTombstones(tombstoneCutoff)

                val lastSuccessAt = prefsRepo.getLastSuccessfulSyncAtMs(user.uid)
                val hasDurableBaselines = dao.getAllSyncBaselinesForUser(user.uid).isNotEmpty()
                val hadLocalSyncedRecords = dao.getPlaylistsOnce().isNotEmpty() ||
                    dao.getLikedSongsOnce().any { !it.videoId.startsWith(LOCAL_SONG_PREFIX) } ||
                    dao.getBlockedArtistsOnce().isNotEmpty() ||
                    dao.getAllTombstones().isNotEmpty()
                var watermarks = if (hasDurableBaselines) {
                    SyncWatermarks(
                        likedMs = prefsRepo.getSyncWatermark(UserPrefsRepository.WATERMARK_LIKED),
                        playlistsMs = prefsRepo.getSyncWatermark(UserPrefsRepository.WATERMARK_PLAYLISTS),
                        seedsMs = prefsRepo.getSyncWatermark(UserPrefsRepository.WATERMARK_SEEDS),
                        hiddenMs = prefsRepo.getSyncWatermark(UserPrefsRepository.WATERMARK_HIDDEN)
                    )
                } else {
                    SyncWatermarks()
                }

                if (watermarks.likedMs == 0L && watermarks.playlistsMs == 0L) {
                    cloudRepo.purgeExpiredCloudTombstones(user.uid, tombstoneCutoff)
                }

                var snapshot = cloudRepo.fetchAccountSnapshot(watermarks)
                if (auth.currentUser?.uid != user.uid) return@withLock false
                if (!allowDuringSignOut && isSigningOut.get()) return@withLock false

                val cloudCompactedBefore = snapshot.profile?.tombstoneCompactedBeforeMs ?: 0L
                val staleDueToCompaction = cloudCompactedBefore > 0L && (
                    lastSuccessAt in 1L..cloudCompactedBefore ||
                        (lastSuccessAt == 0L && (hasDurableBaselines || hadLocalSyncedRecords))
                    )
                if (staleDueToCompaction && watermarks != SyncWatermarks()) {
                    watermarks = SyncWatermarks()
                    prefsRepo.clearAllSyncWatermarks()
                    snapshot = cloudRepo.fetchAccountSnapshot(watermarks)
                    if (auth.currentUser?.uid != user.uid) return@withLock false
                    if (!allowDuringSignOut && isSigningOut.get()) return@withLock false
                }
                val compactionCutoffForLocal = if (staleDueToCompaction) cloudCompactedBefore else 0L

                var resolvedDisplayName = prefsRepo.prefsFlow.first().userDisplayName
                failedCollections.remove("restore_recovery")
                failedCollections.remove("startup_init")
                failedCollections.remove("merge")
                failedCollections.remove("verify")

                try {
                    resolvedDisplayName = mergeProfileAndSeedsLocked(user, snapshot, now, compactionCutoffForLocal)
                    failedCollections.remove("profile")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedCollections["profile"] = SecurityValidators.safeUserErrorMessage(e, "Profile sync failed.")
                }

                try {
                    mergeLikedSongsLocked(user.uid, snapshot, watermarks, now, compactionCutoffForLocal)
                    failedCollections.remove("likes")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedCollections["likes"] = SecurityValidators.safeUserErrorMessage(e, "Liked songs sync failed.")
                }

                try {
                    mergePlaylistsLocked(user.uid, snapshot, watermarks, now, compactionCutoffForLocal)
                    failedCollections.remove("playlists")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedCollections["playlists"] = SecurityValidators.safeUserErrorMessage(e, "Playlists sync failed.")
                }

                try {
                    mergeHiddenArtistsLocked(user.uid, snapshot, watermarks, now, compactionCutoffForLocal)
                    failedCollections.remove("hidden")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedCollections["hidden"] = SecurityValidators.safeUserErrorMessage(e, "Hidden artists sync failed.")
                }

                if (!failedCollections.containsKey("playlists") && watermarks.playlistsMs == 0L) {
                    dao.getPlaylistsOnce().forEach { pl ->
                        cloudRepo.garbageCollectPlaylistSnapshots(pl.cloudKey)
                    }
                }

                if (failedCollections.isNotEmpty()) {
                    val firstErr = failedCollections.values.firstOrNull() ?: "Partial sync failure."
                    val pendingCount = suspendRunCatching { countUnsyncedChangesFor(user.uid) }
                        .getOrDefault(failedCollections.size)
                        .coerceAtLeast(1)
                    _authState.update {
                        it.copy(
                            displayName = resolvedDisplayName,
                            isSyncing = false,
                            syncStatus = "Sync failed: $firstErr",
                            errorMessage = firstErr,
                            pendingUnsyncedCount = pendingCount
                        )
                    }
                    if (!isSigningOut.get()) {
                        scheduleRetry(user)
                    }
                    false
                } else {
                    val pendingJournal = readQuarantineResolutionJournal()
                    if (pendingJournal != null) {
                        if (pendingJournal.targetUid != user.uid ||
                            pendingJournal.action != QuarantineResolutionAction.MERGE ||
                            pendingJournal.state != QuarantineResolutionState.CLOUD_SYNC_PENDING
                        ) {
                            throw IllegalStateException("Quarantine journal state mismatch during remote commit verification.")
                        }
                        if (retainedFilePresentAndCorrupt(pendingJournal) ||
                            retainedPayloadFile(pendingJournal)?.let(::readQuarantineFromFile) == null
                        ) {
                            throw IllegalStateException("Retained quarantine payload missing or corrupted during remote commit.")
                        }
                        if (!verifyAllSyncedCollectionsBaselinedForUidLocked(user.uid)) {
                            val errMsg = "Cloud sync verification incomplete across collections."
                            failedCollections["verify"] = errMsg
                            val pendingCount = suspendRunCatching { countUnsyncedChangesFor(user.uid) }
                                .getOrDefault(1)
                                .coerceAtLeast(1)
                            _authState.update {
                                it.copy(
                                    displayName = resolvedDisplayName,
                                    isSyncing = false,
                                    syncStatus = "Sync failed: $errMsg",
                                    errorMessage = errMsg,
                                    pendingUnsyncedCount = pendingCount
                                )
                            }
                            if (!isSigningOut.get()) {
                                scheduleRetry(user)
                            }
                            return@withLock false
                        }
                    }

                    retryAttempt = 0
                    val doneAt = System.currentTimeMillis()
                    prefsRepo.setLastSuccessfulSyncAtMs(user.uid, doneAt)

                    if (pendingJournal != null) {
                        val committed = pendingJournal.copy(state = QuarantineResolutionState.CLOUD_SYNC_COMMITTED)
                        writeQuarantineResolutionJournalAtomically(committed)
                        onAfterQuarantineCloudSyncCommitted?.invoke(QuarantineResolutionAction.MERGE, user.uid)
                        finalizeCommittedQuarantineCleanupLocked(user.uid, committed)
                    }

                    _authState.update {
                        it.copy(
                            displayName = resolvedDisplayName,
                            isSyncing = false,
                            syncStatus = formatUpToDate(doneAt),
                            lastSyncedAtMs = doneAt,
                            errorMessage = null,
                            pendingUnsyncedCount = 0,
                            hasUnownedDataConflict = false,
                            unownedDataItemCount = 0
                        )
                    }
                    true
                }
            } catch (e: CancellationException) {
                _authState.update { it.copy(isSyncing = false) }
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Cloud sync failed", e)
                val safeMsg = SecurityValidators.safeUserErrorMessage(e, "Cloud sync failed.")
                failedCollections["merge"] = safeMsg
                val pendingCount = suspendRunCatching { countUnsyncedChangesFor(user.uid) }
                    .getOrDefault(failedCollections.size)
                    .coerceAtLeast(1)
                _authState.update {
                    it.copy(
                        isSyncing = false,
                        syncStatus = "Sync failed: $safeMsg",
                        errorMessage = safeMsg,
                        pendingUnsyncedCount = pendingCount
                    )
                }
                if (!isSigningOut.get()) {
                    scheduleRetry(user)
                }
                false
            } finally {
                suppressLocalPushCount.decrementAndGet()
            }
        }
    }

    private suspend fun mergeProfileAndSeedsLocked(
        user: FirebaseUser,
        snapshot: CloudAccountSnapshot,
        now: Long,
        compactedBeforeMs: Long = 0L
    ): String {
        val uid = user.uid
        var localPrefs = prefsRepo.prefsFlow.first()
        var localTaste = prefsRepo.tasteProfileFlow.first()
        val cloudProfile = snapshot.profile
        val seedBaselines = dao.getSyncBaselinesByType(uid, SyncEntityTypes.SEED).associateBy { it.entityKey }
        val profBaseline = dao.getSyncBaselinesByType(uid, SyncEntityTypes.PROFILE_PREFS).firstOrNull()

        // Stamp any legacy TasteSeed entries that still have updatedAtMs == 0 once and persist cleanly.
        var seedsStamped = false
        val normalizedArtists = localTaste.seedArtists.map { seed ->
            val docId = CloudSyncRepository.resolveSeedDocId("art", seed)
            val realTs = seed.updatedAtMs.takeIf { it > 0L }
                ?: seedBaselines[docId]?.clientUpdatedAt?.takeIf { it > 0L }
                ?: now
            if (seed.updatedAtMs <= 0L || seed.id != docId) {
                seedsStamped = true
                seed.copy(id = docId, updatedAtMs = realTs)
            } else seed
        }
        val normalizedSongs = localTaste.seedSongs.map { seed ->
            val docId = CloudSyncRepository.resolveSeedDocId("sng", seed)
            val realTs = seed.updatedAtMs.takeIf { it > 0L }
                ?: seedBaselines[docId]?.clientUpdatedAt?.takeIf { it > 0L }
                ?: now
            if (seed.updatedAtMs <= 0L || seed.id != docId) {
                seedsStamped = true
                seed.copy(id = docId, updatedAtMs = realTs)
            } else seed
        }
        if (seedsStamped) {
            localTaste = localTaste.copy(seedArtists = normalizedArtists, seedSongs = normalizedSongs)
            prefsRepo.saveTasteProfile(localTaste, touchProfileTimestamp = false)
        }

        // --- Merge UserProfile & Syncable Preferences Bidirectionally (LWW via prefsUpdatedAt) ---
        val cloudDisplayName = cloudProfile?.displayName?.trim()?.takeIf { it.isNotBlank() && it != "Listener" }
        val userDisplayName = user.displayName?.trim()?.takeIf { it.isNotBlank() }
        var effectiveDisplayName = when {
            localPrefs.userDisplayName.isNotBlank() && localPrefs.userDisplayName != "Listener" -> localPrefs.userDisplayName
            cloudDisplayName != null -> cloudDisplayName
            userDisplayName != null -> userDisplayName
            else -> "Listener"
        }
        if (profBaseline == null && localPrefs.userDisplayName == "Listener" && effectiveDisplayName != "Listener") {
            prefsRepo.setDisplayName(effectiveDisplayName, updatedAtMs = prefsRepo.getPrefsUpdatedAt())
            localPrefs = prefsRepo.prefsFlow.first()
        }

        var localPrefsTs = prefsRepo.getPrefsUpdatedAt()
        val cloudPrefsTs = cloudProfile?.let {
            it.prefsUpdatedAt.takeIf { ts -> ts > 0L } ?: it.updatedAt?.toDate()?.time ?: 0L
        } ?: 0L
        val safePhotoUrl = SecurityValidators.sanitizeHttpsUrl(user.photoUrl?.toString(), 500)

        val localPrefSig = SyncSignatureHelper.syncableProfilePrefsSignature(localPrefs, localTaste, effectiveDisplayName)
        val cloudPrefSig = cloudProfile?.let { SyncSignatureHelper.cloudProfilePrefsSignature(it) }

        val localProfChanged = profBaseline == null || localPrefSig != profBaseline.signature
        val cloudProfChanged = cloudProfile != null && cloudPrefSig != null &&
            (profBaseline == null || cloudPrefSig != profBaseline.signature || cloudPrefsTs > profBaseline.clientUpdatedAt)

        var mergedTaste = localTaste
        val hasNonDefaultLocalProfile = prefsRepo.hasSyncableProfileState()
        val adoptCloudProfile: Boolean = when {
            cloudProfile == null -> false
            profBaseline == null &&
                !localTaste.onboardingCompleted &&
                !hasNonDefaultLocalProfile &&
                cloudProfile.onboardingCompleted &&
                localPrefsTs == 0L -> true
            cloudProfChanged && !localProfChanged -> true
            cloudProfChanged && localProfChanged -> cloudPrefsTs > localPrefsTs
            else -> false
        }

        if (adoptCloudProfile && cloudProfile != null) {
            val targetName = cloudProfile.displayName.trim().ifBlank { effectiveDisplayName }
            val adoptedTs = cloudPrefsTs.takeIf { it > 0L } ?: now
            prefsRepo.applyCloudProfilePrefs(
                displayName = targetName,
                themePreset = VelunaThemePreset.entries.find { it.id == cloudProfile.themePreset },
                themeMode = runCatching { ThemeMode.valueOf(cloudProfile.themeMode) }.getOrNull(),
                customAccentHex = cloudProfile.customAccentHex,
                adaptToArtwork = cloudProfile.adaptToArtwork,
                cornerRadiusDp = cloudProfile.cornerRadiusDp.toInt().coerceIn(4, 24),
                audioQuality = runCatching { AudioQuality.valueOf(cloudProfile.audioQuality) }.getOrNull(),
                autoplay = cloudProfile.autoplay,
                loudnessNormalization = cloudProfile.loudnessNormalization,
                hideMusicVideos = cloudProfile.hideMusicVideos,
                prefsUpdatedAtMs = adoptedTs
            )
            val cloudGenres = cloudProfile.selectedGenres
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(25)
            val updatedTaste = localTaste.copy(
                onboardingCompleted = cloudProfile.onboardingCompleted,
                selectedGenres = cloudGenres
            )
            if (updatedTaste != localTaste) {
                prefsRepo.saveTasteProfile(updatedTaste, touchProfileTimestamp = false)
                mergedTaste = updatedTaste
            }
            localPrefs = prefsRepo.prefsFlow.first()
            localPrefsTs = adoptedTs
            effectiveDisplayName = localPrefs.userDisplayName
            val finalSig = SyncSignatureHelper.syncableProfilePrefsSignature(localPrefs, mergedTaste, effectiveDisplayName)
            dao.upsertSyncBaseline(
                SyncBaselineEntity(
                    userId = uid,
                    entityType = SyncEntityTypes.PROFILE_PREFS,
                    entityKey = PROFILE_BASELINE_KEY,
                    signature = finalSig,
                    clientUpdatedAt = adoptedTs,
                    deleted = false,
                    updatedAt = now
                )
            )
        } else {
            val pushTs = localPrefsTs.takeIf { it > 0L } ?: now
            val metaChanged = cloudProfile == null || cloudProfile.email != null || cloudProfile.photoUrl != safePhotoUrl
            if (cloudProfile == null || localProfChanged || metaChanged || pushTs > cloudPrefsTs) {
                cloudRepo.upsertUserProfile(
                    prefs = localPrefs,
                    tasteProfile = mergedTaste,
                    photoUrl = safePhotoUrl,
                    overrideDisplayName = effectiveDisplayName,
                    prefsUpdatedAtMs = pushTs
                )
                if (localPrefsTs == 0L) {
                    prefsRepo.setPrefsUpdatedAt(pushTs)
                    localPrefsTs = pushTs
                }
            }
            dao.upsertSyncBaseline(
                SyncBaselineEntity(
                    userId = uid,
                    entityType = SyncEntityTypes.PROFILE_PREFS,
                    entityKey = PROFILE_BASELINE_KEY,
                    signature = localPrefSig,
                    clientUpdatedAt = pushTs,
                    deleted = false,
                    updatedAt = now
                )
            )
        }

        // --- Merge Taste Seeds Bidirectionally via Real Seed Timestamps + Durable Baselines ---
        val existingSeedTombs = dao.getTombstonesByType(SyncEntityTypes.SEED).associateBy { it.entityKey }.toMutableMap()
        val localArtistsById = mergedTaste.seedArtists.associateBy { CloudSyncRepository.resolveSeedDocId("art", it) }
        val localSongsById = mergedTaste.seedSongs.associateBy { CloudSyncRepository.resolveSeedDocId("sng", it) }
        val allLocalSeedIds = localArtistsById.keys + localSongsById.keys

        // Detect offline seed deletions from persistent baselines
        for ((key, base) in seedBaselines) {
            if (!base.deleted && key !in allLocalSeedIds && key !in existingSeedTombs) {
                val synth = SyncTombstoneEntity(SyncEntityTypes.SEED, key, now, now)
                dao.upsertTombstone(synth)
                existingSeedTombs[key] = synth
            }
        }

        val localSeedMergeMap = linkedMapOf<String, MergeItem<Pair<String, TasteSeed>>>()
        localArtistsById.forEach { (id, s) ->
            val ts = s.updatedAtMs.takeIf { it > 0L } ?: now
            localSeedMergeMap[id] = MergeItem(id, ts, false, "ARTIST" to s.copy(id = id, updatedAtMs = ts))
        }
        localSongsById.forEach { (id, s) ->
            val ts = s.updatedAtMs.takeIf { it > 0L } ?: now
            localSeedMergeMap[id] = MergeItem(id, ts, false, "SONG" to s.copy(id = id, updatedAtMs = ts))
        }
        existingSeedTombs.forEach { (id, t) ->
            val existing = localSeedMergeMap[id]
            if (existing == null || t.clientUpdatedAt >= existing.clientUpdatedAt) {
                localSeedMergeMap[id] = MergeItem(id, t.clientUpdatedAt, true, null)
            }
        }

        val cloudSeedMergeMap = snapshot.tasteSeeds.associate { c ->
            val safeThumb = SecurityValidators.sanitizeHttpsUrl(c.thumbnail, 500)
            val ts = c.clientUpdatedAt.takeIf { it > 0L } ?: (c.updatedAt?.toDate()?.time ?: 1L)
            val resolvedSeed = if (c.deleted) {
                null
            } else {
                c.kind.uppercase() to TasteSeed(c.seedId, c.name, c.subtitle, safeThumb, updatedAtMs = ts)
            }
            c.seedId to MergeItem(c.seedId, ts, c.deleted, resolvedSeed)
        }

        val compactedSeedsToDrop = mutableSetOf<String>()
        if (compactedBeforeMs > 0L) {
            for ((id, item) in localSeedMergeMap) {
                if (item.deleted || id in cloudSeedMergeMap || id in existingSeedTombs) continue
                val base = seedBaselines[id] ?: continue
                if (!base.deleted && item.clientUpdatedAt <= compactedBeforeMs && item.value != null &&
                    SyncSignatureHelper.tasteSeedSignature(item.value.first, item.value.second) == base.signature
                ) {
                    compactedSeedsToDrop.add(id)
                }
            }
        }

        val relevantLocalSeeds = localSeedMergeMap.filterKeys { id ->
            if (id in compactedSeedsToDrop) return@filterKeys false
            val item = localSeedMergeMap.getValue(id)
            val base = seedBaselines[id]
            id in cloudSeedMergeMap ||
                id in existingSeedTombs ||
                base == null ||
                base.deleted != item.deleted ||
                (!item.deleted && item.value != null &&
                    SyncSignatureHelper.tasteSeedSignature(item.value.first, item.value.second) != base.signature) ||
                item.clientUpdatedAt != base.clientUpdatedAt
        }

        val seedDecisions = MergeEngine.mergeMaps(relevantLocalSeeds, cloudSeedMergeMap) { a, b ->
            a?.first == b?.first &&
                a?.second?.name == b?.second?.name &&
                a?.second?.subtitle == b?.second?.subtitle
        }

        val nextArtistsMap = localArtistsById.toMutableMap()
        val nextSongsMap = localSongsById.toMutableMap()
        compactedSeedsToDrop.forEach { dropId ->
            nextArtistsMap.remove(dropId)
            nextSongsMap.remove(dropId)
            dao.deleteSyncBaseline(uid, SyncEntityTypes.SEED, dropId)
        }
        val dirtyArtistsToPush = mutableListOf<TasteSeed>()
        val dirtySongsToPush = mutableListOf<TasteSeed>()
        val dirtyTombsToPush = mutableListOf<SyncTombstoneEntity>()
        val baselinesToSave = mutableListOf<SyncBaselineEntity>()

        for (d in seedDecisions) {
            if (d.applyLocally) {
                if (d.deleted) {
                    nextArtistsMap.remove(d.key)
                    nextSongsMap.remove(d.key)
                    dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.SEED, d.key, d.clientUpdatedAt, now))
                    baselinesToSave.add(
                        SyncBaselineEntity(uid, SyncEntityTypes.SEED, d.key, 0L, "", d.clientUpdatedAt, true, now)
                    )
                } else if (d.value != null) {
                    dao.deleteTombstone(SyncEntityTypes.SEED, d.key)
                    val (kind, seed) = d.value
                    val updatedSeed = seed.copy(id = d.key, updatedAtMs = d.clientUpdatedAt)
                    if (kind == "ARTIST") {
                        nextArtistsMap[d.key] = updatedSeed
                    } else {
                        nextSongsMap[d.key] = updatedSeed
                    }
                    baselinesToSave.add(
                        SyncBaselineEntity(
                            uid, SyncEntityTypes.SEED, d.key, 0L,
                            SyncSignatureHelper.tasteSeedSignature(kind, updatedSeed),
                            d.clientUpdatedAt, false, now
                        )
                    )
                }
            } else if (d.pushToCloud && d.value != null) {
                val (kind, seed) = d.value
                val stamped = seed.copy(id = d.key, updatedAtMs = d.clientUpdatedAt)
                if (kind == "ARTIST") dirtyArtistsToPush.add(stamped) else dirtySongsToPush.add(stamped)
                baselinesToSave.add(
                    SyncBaselineEntity(
                        uid, SyncEntityTypes.SEED, d.key, 0L,
                        SyncSignatureHelper.tasteSeedSignature(kind, stamped),
                        d.clientUpdatedAt, false, now
                    )
                )
            } else if (d.pushTombstoneToCloud) {
                val tomb = SyncTombstoneEntity(SyncEntityTypes.SEED, d.key, d.clientUpdatedAt, now)
                dirtyTombsToPush.add(tomb)
                baselinesToSave.add(
                    SyncBaselineEntity(uid, SyncEntityTypes.SEED, d.key, 0L, "", d.clientUpdatedAt, true, now)
                )
            } else {
                // Already in sync between local & cloud; ensure durable baseline exists
                if (!d.deleted && d.value != null) {
                    val (kind, seed) = d.value
                    baselinesToSave.add(
                        SyncBaselineEntity(
                            uid, SyncEntityTypes.SEED, d.key, 0L,
                            SyncSignatureHelper.tasteSeedSignature(kind, seed),
                            d.clientUpdatedAt, false, now
                        )
                    )
                } else if (d.deleted) {
                    baselinesToSave.add(
                        SyncBaselineEntity(uid, SyncEntityTypes.SEED, d.key, 0L, "", d.clientUpdatedAt, true, now)
                    )
                }
            }
        }

        val finalArtists = nextArtistsMap.values.distinctBy { it.name.lowercase().trim() }.take(30)
        val finalSongs = nextSongsMap.values
            .distinctBy { it.name.lowercase().trim() + "::" + (it.subtitle?.lowercase()?.trim().orEmpty()) }
            .take(30)
        val finalTaste = mergedTaste.copy(seedArtists = finalArtists, seedSongs = finalSongs)
        if (finalTaste != mergedTaste) {
            prefsRepo.saveTasteProfile(finalTaste, touchProfileTimestamp = false)
        }

        if (dirtyArtistsToPush.isNotEmpty() || dirtySongsToPush.isNotEmpty() || dirtyTombsToPush.isNotEmpty()) {
            cloudRepo.upsertTasteSeeds(
                artists = dirtyArtistsToPush,
                songs = dirtySongsToPush,
                tombstones = dirtyTombsToPush,
                nowMs = now
            )
        }
        if (baselinesToSave.isNotEmpty()) {
            dao.upsertSyncBaselines(baselinesToSave)
        }
        val watermarks = prefsRepo.getSyncWatermark(UserPrefsRepository.WATERMARK_SEEDS)
        if (snapshot.maxSeedsUpdatedAtMs > watermarks) {
            prefsRepo.setSyncWatermark(UserPrefsRepository.WATERMARK_SEEDS, snapshot.maxSeedsUpdatedAtMs)
        }
        return effectiveDisplayName
    }

    private suspend fun mergeLikedSongsLocked(
        uid: String,
        snapshot: CloudAccountSnapshot,
        watermarks: SyncWatermarks,
        now: Long,
        compactedBeforeMs: Long = 0L
    ) {
        val localLikedList = dao.getLikedSongsOnce().filter { !it.videoId.startsWith(LOCAL_SONG_PREFIX) }
        val localLikedById = localLikedList.associateBy { it.videoId }
        val likedTombstones = dao.getTombstonesByType(SyncEntityTypes.LIKED).associateBy { it.entityKey }.toMutableMap()
        val likedBaselines = dao.getSyncBaselinesByType(uid, SyncEntityTypes.LIKED).associateBy { it.entityKey }

        // Synthesize tombstones for songs unliked while offline or after restart
        for ((key, base) in likedBaselines) {
            if (!base.deleted && key !in localLikedById && key !in likedTombstones) {
                val synth = SyncTombstoneEntity(SyncEntityTypes.LIKED, key, now, now)
                dao.upsertTombstone(synth)
                likedTombstones[key] = synth
            }
        }

        val localLikedMerge = linkedMapOf<String, MergeItem<SongItem>>()
        localLikedById.forEach { (id, entity) ->
            repository.decodeSong(entity.songJson)?.let { song ->
                localLikedMerge[id] = MergeItem(id, entity.likedAt, false, song)
            }
        }
        likedTombstones.forEach { (id, tomb) ->
            val existingLocal = localLikedMerge[id]
            if (existingLocal == null || tomb.clientUpdatedAt >= existingLocal.clientUpdatedAt) {
                localLikedMerge[id] = MergeItem(id, tomb.clientUpdatedAt, true, null)
            }
        }

        val cloudLikedMerge = snapshot.likedSongs
            .filter { it.videoId.isNotBlank() && !it.videoId.startsWith(LOCAL_SONG_PREFIX) }
            .associate { c ->
                val ts = c.clientUpdatedAt.takeIf { it > 0L } ?: c.likedAt.takeIf { it > 0L } ?: (c.updatedAt?.toDate()?.time ?: 1L)
                c.videoId to MergeItem(c.videoId, ts, c.deleted, if (c.deleted) null else c.toSongItem())
            }

        val compactedLikesToDrop = mutableSetOf<String>()
        if (compactedBeforeMs > 0L) {
            for ((id, item) in localLikedMerge) {
                if (item.deleted || id in cloudLikedMerge || id in likedTombstones) continue
                val base = likedBaselines[id] ?: continue
                if (!base.deleted && item.clientUpdatedAt <= compactedBeforeMs && item.value != null &&
                    SyncSignatureHelper.likedSongSignature(item.value) == base.signature
                ) {
                    compactedLikesToDrop.add(id)
                }
            }
        }
        compactedLikesToDrop.forEach { id ->
            dao.deleteLikedSong(id)
            dao.deleteSyncBaseline(uid, SyncEntityTypes.LIKED, id)
        }

        val effectiveLocalLiked = localLikedMerge.filterKeys { id ->
            if (id in compactedLikesToDrop) return@filterKeys false
            val item = localLikedMerge.getValue(id)
            val base = likedBaselines[id]
            id in cloudLikedMerge ||
                id in likedTombstones ||
                base == null ||
                base.deleted != item.deleted ||
                (!item.deleted && item.value != null &&
                    SyncSignatureHelper.likedSongSignature(item.value) != base.signature)
        }

        val likedDecisions = MergeEngine.mergeMaps(effectiveLocalLiked, cloudLikedMerge) { a, b ->
            a?.videoId == b?.videoId && a?.title == b?.title
        }

        for (d in likedDecisions) {
            if (d.applyLocally) {
                if (d.deleted) {
                    dao.deleteLikedSong(d.key)
                    dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.LIKED, d.key, d.clientUpdatedAt, now))
                    dao.upsertSyncBaseline(
                        SyncBaselineEntity(uid, SyncEntityTypes.LIKED, d.key, 0L, "", d.clientUpdatedAt, true, now)
                    )
                } else if (d.value != null) {
                    dao.deleteTombstone(SyncEntityTypes.LIKED, d.key)
                    dao.insertLikedSong(
                        LikedSongEntity(
                            videoId = d.key,
                            songJson = repository.encodeSong(d.value),
                            likedAt = d.clientUpdatedAt.takeIf { it > 0L } ?: now
                        )
                    )
                    dao.upsertSyncBaseline(
                        SyncBaselineEntity(
                            uid, SyncEntityTypes.LIKED, d.key, 0L,
                            SyncSignatureHelper.likedSongSignature(d.value),
                            d.clientUpdatedAt, false, now
                        )
                    )
                }
            } else if (d.pushToCloud && d.value != null) {
                cloudRepo.syncLikedSong(d.value, liked = true, likedAtMs = d.clientUpdatedAt, clientUpdatedAtMs = d.clientUpdatedAt)
                dao.upsertSyncBaseline(
                    SyncBaselineEntity(
                        uid, SyncEntityTypes.LIKED, d.key, 0L,
                        SyncSignatureHelper.likedSongSignature(d.value),
                        d.clientUpdatedAt, false, now
                    )
                )
            } else if (d.pushTombstoneToCloud) {
                cloudRepo.syncLikedSong(SongItem(videoId = d.key, title = "", artists = ""), liked = false, clientUpdatedAtMs = d.clientUpdatedAt)
                dao.upsertSyncBaseline(
                    SyncBaselineEntity(uid, SyncEntityTypes.LIKED, d.key, 0L, "", d.clientUpdatedAt, true, now)
                )
            } else {
                val base = likedBaselines[d.key]
                if (base == null) {
                    val sig = d.value?.let { SyncSignatureHelper.likedSongSignature(it) }.orEmpty()
                    dao.upsertSyncBaseline(
                        SyncBaselineEntity(uid, SyncEntityTypes.LIKED, d.key, 0L, sig, d.clientUpdatedAt, d.deleted, now)
                    )
                }
            }
        }
        if (snapshot.maxLikedUpdatedAtMs > watermarks.likedMs) {
            prefsRepo.setSyncWatermark(UserPrefsRepository.WATERMARK_LIKED, snapshot.maxLikedUpdatedAtMs)
        }
    }

    private suspend fun getLocalSyncDeviceId(): String =
        dao.getLocalDeviceClock()?.deviceId?.takeIf { it.isNotBlank() }
            ?: dao.allocateNextOperationStamp().deviceId

    private suspend fun loadLocalPlaylistCausalState(
        pl: LocalPlaylistEntity,
        baselineSnap: PlaylistBaselineSnapshot?,
        localDeviceId: String
    ): Triple<Map<String, SyncMutationStamp>, Map<String, BaselineTrackOp>, Boolean> {
        val rawFields = dao.getPlaylistFieldOps(pl.id).associate {
            it.fieldName to SyncMutationStamp(it.timestampMs, it.opSeq, it.deviceId, it.opId)
        }
        val rawTracks = dao.getPlaylistTrackOps(pl.id).associate {
            it.videoId to BaselineTrackOp(
                deleted = it.deleted,
                stamp = SyncMutationStamp(it.timestampMs, it.opSeq, it.deviceId, it.opId)
            )
        }
        val maxRecordedTs = (rawFields.values.map { it.timestampMs } + rawTracks.values.map { it.stamp.timestampMs })
            .maxOrNull() ?: 0L
        val rawDaoBypassed = rawFields.isEmpty() || (maxRecordedTs > 0L && pl.updatedAt > maxRecordedTs)
        val effectiveFields = rawFields.toMutableMap()
        if (rawDaoBypassed) {
            val fallback = SyncMutationStamp(pl.updatedAt.coerceAtLeast(0L), 0L, localDeviceId)
            if (baselineSnap == null || pl.title != baselineSnap.title) {
                effectiveFields[PlaylistFieldNames.TITLE] = fallback
            }
            if (baselineSnap == null || pl.description != baselineSnap.description) {
                effectiveFields[PlaylistFieldNames.DESCRIPTION] = fallback
            }
            val localRemoteCover = pl.remoteCoverUri ?: SecurityValidators.sanitizeHttpsUrl(pl.coverUri, 500)
            if (baselineSnap == null || localRemoteCover != baselineSnap.remoteCoverUri || pl.coverRemoved != baselineSnap.coverRemoved) {
                effectiveFields[PlaylistFieldNames.COVER] = fallback
            }
        }

        val hasNewerOps = if (baselineSnap == null) {
            false
        } else {
            effectiveFields.any { (field, stamp) ->
                val baseStamp = baselineSnap.fieldStamps[field]
                baseStamp == null || stamp > baseStamp
            } || rawTracks.any { (vid, op) ->
                val baseOp = baselineSnap.trackOps[vid]
                baseOp == null || baseOp.deleted != op.deleted || op.stamp > baseOp.stamp
            }
        }
        return Triple(effectiveFields, rawTracks, hasNewerOps)
    }

    private fun buildRoomFieldOps(
        playlistId: Long,
        fieldStamps: Map<String, SyncMutationStamp>
    ): List<PlaylistFieldOpEntity> = fieldStamps.map { (field, stamp) ->
        PlaylistFieldOpEntity(playlistId, field, stamp.timestampMs, stamp.seq, stamp.deviceId, stamp.opId)
    }

    private fun buildRoomTrackOps(
        playlistId: Long,
        addStamps: Map<String, SyncMutationStamp>,
        tombstones: Map<String, SyncMutationStamp>
    ): List<PlaylistTrackOpEntity> {
        val out = linkedMapOf<String, PlaylistTrackOpEntity>()
        addStamps.forEach { (vid, stamp) ->
            out[vid] = PlaylistTrackOpEntity(playlistId, vid, false, stamp.timestampMs, stamp.seq, stamp.deviceId, stamp.opId)
        }
        tombstones.forEach { (vid, stamp) ->
            val cur = out[vid]
            val curStamp = cur?.let { SyncMutationStamp(it.timestampMs, it.opSeq, it.deviceId, it.opId) }
            if (curStamp == null || stamp > curStamp) {
                out[vid] = PlaylistTrackOpEntity(playlistId, vid, true, stamp.timestampMs, stamp.seq, stamp.deviceId, stamp.opId)
            }
        }
        return out.values.toList()
    }

    private fun buildBaselineTrackOps(
        addStamps: Map<String, SyncMutationStamp>,
        tombstones: Map<String, SyncMutationStamp>
    ): Map<String, BaselineTrackOp> {
        val out = linkedMapOf<String, BaselineTrackOp>()
        addStamps.forEach { (vid, stamp) -> out[vid] = BaselineTrackOp(deleted = false, stamp = stamp) }
        tombstones.forEach { (vid, stamp) ->
            val cur = out[vid]
            if (cur == null || stamp > cur.stamp) {
                out[vid] = BaselineTrackOp(deleted = true, stamp = stamp)
            }
        }
        return out
    }

    private fun computePlaylistMutationStamp(
        fallbackUpdatedAt: Long,
        localDeviceId: String,
        fieldStamps: Map<String, SyncMutationStamp>,
        trackAddStamps: Map<String, SyncMutationStamp>,
        trackTombstones: Map<String, SyncMutationStamp>
    ): SyncMutationStamp = (fieldStamps.values + trackAddStamps.values + trackTombstones.values).maxOrNull()
        ?: SyncMutationStamp(fallbackUpdatedAt.coerceAtLeast(0L), 0L, localDeviceId)

    private fun encodePlaylistSnapshotJson(
        title: String,
        description: String,
        remoteCoverUri: String?,
        coverRemoved: Boolean,
        tracks: List<SongItem>,
        fieldStamps: Map<String, SyncMutationStamp> = emptyMap(),
        trackOps: Map<String, BaselineTrackOp> = emptyMap()
    ): String = PlaylistThreeWayMerger.encodeBaselineSnapshot(
        PlaylistBaselineSnapshot(
            title = title,
            description = description,
            remoteCoverUri = if (coverRemoved) null else SecurityValidators.sanitizeHttpsUrl(remoteCoverUri, 500),
            coverRemoved = coverRemoved,
            trackIds = tracks.map { it.videoId },
            fieldStamps = fieldStamps,
            trackOps = trackOps
        )
    )

    private suspend fun mergePlaylistsLocked(
        uid: String,
        snapshot: CloudAccountSnapshot,
        watermarks: SyncWatermarks,
        now: Long,
        compactedBeforeMs: Long = 0L
    ) {
        val localDeviceId = getLocalSyncDeviceId()
        val rawLocalPlaylists = dao.getPlaylistsOnce()
        val validLocalPlaylists = rawLocalPlaylists.map { pl ->
            if (pl.cloudKey.isBlank()) {
                val repairedKey = "pl_local_${pl.id}"
                dao.updatePlaylistCloudKey(pl.id, repairedKey)
                pl.copy(cloudKey = repairedKey)
            } else {
                pl
            }
        }
        val localByKey = validLocalPlaylists.associateBy { it.cloudKey }
        val plBaselines = dao.getSyncBaselinesByType(uid, SyncEntityTypes.PLAYLIST).associateBy { it.entityKey }
        val plTombstones = dao.getTombstonesByType(SyncEntityTypes.PLAYLIST).associateBy { it.entityKey }.toMutableMap()

        // Detect playlists deleted offline or while app was killed
        for ((key, base) in plBaselines) {
            if (!base.deleted && key !in localByKey && key !in plTombstones) {
                val synth = SyncTombstoneEntity(SyncEntityTypes.PLAYLIST, key, now, now)
                dao.upsertTombstone(synth)
                plTombstones[key] = synth
            }
        }

        val handledKeys = mutableSetOf<String>()

        for (cloudBundle in snapshot.playlists) {
            val cloudPl = cloudBundle.playlist
            val key = cloudPl.playlistKey
            if (key.isBlank()) continue
            handledKeys.add(key)

            val matchedLocal = localByKey[key]
            val localTomb = plTombstones[key]
            val baseline = plBaselines[key]

            val remoteTracks: List<SongItem> = if (cloudBundle.subcollectionTracks.isNotEmpty() || !cloudBundle.hasLegacyArrayTracks) {
                cloudBundle.subcollectionTracks
                    .filter { !it.deleted }
                    .sortedBy { it.position }
                    .mapNotNull { it.toSongItem() }
            } else {
                cloudPl.tracks.mapNotNull { CloudSyncRepository.songFromCloudMap(it) }
            }
            val remoteDeviceId = cloudPl.lastDeviceId.ifBlank { "cloud_remote" }
            val remoteFieldStamps = cloudPl.fieldStamps.mapValues { (_, v) -> v.toSyncStamp() }
            val remoteTrackAddStamps = cloudBundle.subcollectionTracks
                .filter { !it.deleted }
                .associate { it.videoId to it.effectiveOpStamp(cloudPl.clientUpdatedAt, remoteDeviceId) }
            val remoteTrackTombstones = cloudPl.trackTombstones.mapValues { (_, v) -> v.toSyncStamp() }.toMutableMap()
            cloudBundle.subcollectionTracks.filter { it.deleted }.forEach { trk ->
                val delStamp = trk.effectiveOpStamp(cloudPl.clientUpdatedAt, remoteDeviceId)
                val existing = remoteTrackTombstones[trk.videoId]
                if (existing == null || delStamp > existing) {
                    remoteTrackTombstones[trk.videoId] = delStamp
                }
            }

            if (cloudPl.deleted) {
                val cloudTs = cloudPl.clientUpdatedAt
                val localReeditedAfterDelete = matchedLocal != null &&
                    matchedLocal.updatedAt > cloudTs &&
                    (baseline == null || matchedLocal.syncRevision > baseline.revision)
                if (localReeditedAfterDelete) {
                    val localTracks = dao.getPlaylistTracks(matchedLocal.id)
                        .mapNotNull { repository.decodeSong(it.songJson) }
                        .filter { !it.isLocal }
                    val baseSnap = PlaylistThreeWayMerger.decodeBaselineSnapshot(baseline?.snapshotJson)
                    val (localFieldStamps, localTrackOps, _) = loadLocalPlaylistCausalState(matchedLocal, baseSnap, localDeviceId)
                    val addStamps = localTracks.associate { trk ->
                        val explicit = localTrackOps[trk.videoId]?.takeIf { !it.deleted }?.stamp
                        trk.videoId to (explicit ?: SyncMutationStamp(trk.addedAt ?: matchedLocal.updatedAt, 0L, localDeviceId))
                    }
                    val trackTombstones = localTrackOps.filterValues { it.deleted }.mapValues { it.value.stamp }
                    val targetRev = maxOf(matchedLocal.syncRevision, cloudPl.syncRevision + 1L, 1L)
                    if (targetRev != matchedLocal.syncRevision) {
                        dao.setPlaylistSyncRevision(matchedLocal.id, targetRev)
                    }
                    val remoteCover = matchedLocal.remoteCoverUri ?: SecurityValidators.sanitizeHttpsUrl(matchedLocal.coverUri, 500)
                    val winStamp = computePlaylistMutationStamp(matchedLocal.updatedAt, localDeviceId, localFieldStamps, addStamps, trackTombstones)
                    cloudRepo.upsertPlaylist(
                        playlistKey = key,
                        title = matchedLocal.title,
                        description = matchedLocal.description,
                        coverUri = remoteCover,
                        tracks = localTracks,
                        clientUpdatedAt = matchedLocal.updatedAt,
                        syncRevision = targetRev,
                        removeLegacyTracksField = cloudBundle.hasLegacyArrayTracks,
                        coverRemoved = matchedLocal.coverRemoved,
                        playlistMutationStamp = winStamp,
                        fieldStamps = localFieldStamps,
                        trackAddStamps = addStamps,
                        trackTombstones = trackTombstones
                    )
                    val sig = SyncSignatureHelper.playlistSignature(
                        key, matchedLocal.title, matchedLocal.description, remoteCover, localTracks, matchedLocal.coverRemoved
                    )
                    dao.upsertSyncBaseline(
                        SyncBaselineEntity(
                            uid, SyncEntityTypes.PLAYLIST, key, targetRev, sig, matchedLocal.updatedAt, false, now,
                            snapshotJson = encodePlaylistSnapshotJson(
                                matchedLocal.title, matchedLocal.description, remoteCover, matchedLocal.coverRemoved,
                                localTracks, localFieldStamps, buildBaselineTrackOps(addStamps, trackTombstones)
                            )
                        )
                    )
                } else {
                    if (matchedLocal != null) {
                        dao.deletePlaylistAtomic(matchedLocal.id, "$LOCAL_PLAYLIST_PREFIX${matchedLocal.id}")
                        matchedLocal.coverUri?.let { repository.deleteManagedPlaylistCover(it) }
                    }
                    dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.PLAYLIST, key, cloudTs, now))
                    val rev = maxOf(baseline?.revision ?: 0L, cloudPl.syncRevision)
                    dao.upsertSyncBaseline(
                        SyncBaselineEntity(uid, SyncEntityTypes.PLAYLIST, key, rev, "", cloudTs, true, now)
                    )
                }
                continue
            }

            if (localTomb != null && localTomb.clientUpdatedAt >= cloudPl.clientUpdatedAt) {
                val delRev = maxOf((baseline?.revision ?: 0L) + 1L, cloudPl.syncRevision + 1L)
                cloudRepo.deletePlaylist(
                    playlistKey = key,
                    clientUpdatedAt = localTomb.clientUpdatedAt,
                    syncRevision = delRev,
                    clearLegacyTracksArray = cloudBundle.hasLegacyArrayTracks
                )
                dao.upsertSyncBaseline(
                    SyncBaselineEntity(uid, SyncEntityTypes.PLAYLIST, key, delRev, "", localTomb.clientUpdatedAt, true, now)
                )
                continue
            }

            val cloudTitle = cloudPl.title.ifBlank { matchedLocal?.title ?: "Playlist" }
            val safeCloudCoverUri = if (cloudPl.coverRemoved) null else SecurityValidators.sanitizeHttpsUrl(cloudPl.coverUri, 500)
            val cloudSig = SyncSignatureHelper.playlistSignature(
                cloudKey = key,
                title = cloudTitle,
                description = cloudPl.description,
                coverUri = safeCloudCoverUri,
                tracks = remoteTracks,
                coverRemoved = cloudPl.coverRemoved
            )

            if (matchedLocal != null) {
                val localTracks = dao.getPlaylistTracks(matchedLocal.id)
                    .mapNotNull { repository.decodeSong(it.songJson) }
                    .filter { !it.isLocal }
                val localRemoteCover = matchedLocal.remoteCoverUri
                    ?: SecurityValidators.sanitizeHttpsUrl(matchedLocal.coverUri, 500)
                val localSig = SyncSignatureHelper.playlistSignature(
                    cloudKey = key,
                    title = matchedLocal.title,
                    description = matchedLocal.description,
                    coverUri = localRemoteCover,
                    tracks = localTracks,
                    coverRemoved = matchedLocal.coverRemoved
                )
                val baseSnap = PlaylistThreeWayMerger.decodeBaselineSnapshot(baseline?.snapshotJson)
                val (localFieldStamps, localTrackOps, hasNewerLocalOps) = loadLocalPlaylistCausalState(
                    matchedLocal, baseSnap, localDeviceId
                )
                val localDirty = baseline == null ||
                    baseline.deleted ||
                    matchedLocal.syncRevision > baseline.revision ||
                    localSig != baseline.signature ||
                    hasNewerLocalOps
                val cloudTrackCausalChanged = baseSnap != null && (
                    remoteFieldStamps != baseSnap.fieldStamps ||
                        remoteTrackTombstones.any { (vid, st) ->
                            val b = baseSnap.trackOps[vid]
                            b == null || !b.deleted || st > b.stamp
                        }
                    )
                val cloudDirty = baseline == null ||
                    cloudPl.syncRevision > baseline.revision ||
                    cloudSig != baseline.signature ||
                    cloudPl.clientUpdatedAt > baseline.clientUpdatedAt ||
                    cloudBundle.hasLegacyArrayTracks ||
                    cloudTrackCausalChanged

                if (localDirty && cloudDirty) {
                    val merged = PlaylistThreeWayMerger.merge(
                        baseline = baseSnap,
                        localTitle = matchedLocal.title,
                        localDescription = matchedLocal.description,
                        localRemoteCoverUri = localRemoteCover,
                        localCoverRemoved = matchedLocal.coverRemoved,
                        localTracks = localTracks,
                        localUpdatedAt = matchedLocal.updatedAt,
                        remoteTitle = cloudTitle,
                        remoteDescription = cloudPl.description,
                        remoteCoverUri = safeCloudCoverUri,
                        remoteCoverRemoved = cloudPl.coverRemoved,
                        remoteTracks = remoteTracks,
                        remoteUpdatedAt = cloudPl.clientUpdatedAt,
                        localDeviceId = localDeviceId,
                        localFieldStamps = localFieldStamps,
                        localTrackOps = localTrackOps,
                        remoteDeviceId = remoteDeviceId,
                        remoteFieldStamps = remoteFieldStamps,
                        remoteTrackAddStamps = remoteTrackAddStamps,
                        remoteTrackTombstones = remoteTrackTombstones
                    )
                    val mergedRemoteCover = merged.remoteCoverUri
                    val resolvedCover: String? = when {
                        merged.coverRemoved -> {
                            matchedLocal.coverUri?.let { repository.deleteManagedPlaylistCover(it) }
                            null
                        }
                        matchedLocal.coverUri?.startsWith("file://") == true &&
                            (mergedRemoteCover == null || mergedRemoteCover == localRemoteCover) -> matchedLocal.coverUri
                        else -> mergedRemoteCover ?: matchedLocal.coverUri?.takeIf { it.startsWith("file://") }
                    }
                    val mergedTs = maxOf(matchedLocal.updatedAt, cloudPl.clientUpdatedAt)
                    val targetRev = if (merged.needsCloudPush || cloudBundle.hasLegacyArrayTracks || cloudPl.activeSnapshotId.isNullOrBlank()) {
                        maxOf(matchedLocal.syncRevision, cloudPl.syncRevision + 1L, 1L)
                    } else {
                        maxOf(cloudPl.syncRevision, 1L)
                    }
                    val mergedSig = SyncSignatureHelper.playlistSignature(
                        cloudKey = key,
                        title = merged.title,
                        description = merged.description,
                        coverUri = mergedRemoteCover,
                        tracks = merged.tracks,
                        coverRemoved = merged.coverRemoved
                    )
                    val mergedTrackOps = buildRoomTrackOps(matchedLocal.id, merged.mergedTrackAddStamps, merged.mergedTrackTombstones)
                    val mergedFieldOps = buildRoomFieldOps(matchedLocal.id, merged.mergedFieldStamps)
                    val opsChanged = merged.mergedFieldStamps != localFieldStamps ||
                        mergedTrackOps.associateBy { it.videoId }.mapValues { BaselineTrackOp(it.value.deleted, SyncMutationStamp(it.value.timestampMs, it.value.opSeq, it.value.deviceId, it.value.opId)) } != localTrackOps

                    if (merged.needsLocalUpdate || resolvedCover != matchedLocal.coverUri || targetRev != matchedLocal.syncRevision || opsChanged) {
                        val trackEntities = merged.tracks.mapIndexed { idx, s ->
                            LocalPlaylistTrackEntity(
                                playlistId = matchedLocal.id,
                                videoId = s.videoId,
                                songJson = repository.encodeSong(s),
                                position = idx,
                                addedAt = s.addedAt ?: now
                            )
                        }
                        dao.replacePlaylistFromCloudAtomic(
                            playlistId = matchedLocal.id,
                            title = merged.title,
                            description = merged.description,
                            coverUri = resolvedCover,
                            remoteCoverUri = mergedRemoteCover,
                            coverRemoved = merged.coverRemoved,
                            updatedAt = mergedTs,
                            syncRevision = targetRev,
                            tracks = trackEntities,
                            trackOps = mergedTrackOps,
                            fieldOps = mergedFieldOps
                        )
                    }
                    if (merged.needsCloudPush || cloudBundle.hasLegacyArrayTracks || cloudPl.activeSnapshotId.isNullOrBlank()) {
                        cloudRepo.upsertPlaylist(
                            playlistKey = key,
                            title = merged.title,
                            description = merged.description,
                            coverUri = mergedRemoteCover,
                            tracks = merged.tracks,
                            clientUpdatedAt = mergedTs,
                            syncRevision = targetRev,
                            removeLegacyTracksField = cloudBundle.hasLegacyArrayTracks,
                            coverRemoved = merged.coverRemoved,
                            playlistMutationStamp = merged.winningPlaylistStamp,
                            fieldStamps = merged.mergedFieldStamps,
                            trackAddStamps = merged.mergedTrackAddStamps,
                            trackTombstones = merged.mergedTrackTombstones
                        )
                    }
                    dao.upsertSyncBaseline(
                        SyncBaselineEntity(
                            uid, SyncEntityTypes.PLAYLIST, key, targetRev, mergedSig, mergedTs, false, now,
                            snapshotJson = encodePlaylistSnapshotJson(
                                merged.title, merged.description, mergedRemoteCover, merged.coverRemoved, merged.tracks,
                                merged.mergedFieldStamps, buildBaselineTrackOps(merged.mergedTrackAddStamps, merged.mergedTrackTombstones)
                            )
                        )
                    )
                } else if (cloudDirty && !localDirty && (cloudSig != localSig || cloudPl.syncRevision > matchedLocal.syncRevision || cloudBundle.hasLegacyArrayTracks || cloudTrackCausalChanged)) {
                    val resolvedCover: String? = when {
                        cloudPl.coverRemoved -> {
                            matchedLocal.coverUri?.let { repository.deleteManagedPlaylistCover(it) }
                            null
                        }
                        safeCloudCoverUri != null && safeCloudCoverUri != localRemoteCover -> {
                            matchedLocal.coverUri?.let { repository.deleteManagedPlaylistCover(it) }
                            safeCloudCoverUri
                        }
                        matchedLocal.coverUri?.startsWith("file://") == true -> matchedLocal.coverUri
                        else -> safeCloudCoverUri
                    }
                    val resolvedRemoteCover = if (cloudPl.coverRemoved) null else (safeCloudCoverUri ?: localRemoteCover)
                    val adoptedTs = cloudPl.clientUpdatedAt.takeIf { it > 0L } ?: matchedLocal.updatedAt
                    val adoptedRev = maxOf(cloudPl.syncRevision, 1L)
                    val trackEntities = remoteTracks.mapIndexed { idx, s ->
                        LocalPlaylistTrackEntity(
                            playlistId = matchedLocal.id,
                            videoId = s.videoId,
                            songJson = repository.encodeSong(s),
                            position = idx,
                            addedAt = s.addedAt ?: now
                        )
                    }
                    val adoptedFieldOps = buildRoomFieldOps(matchedLocal.id, remoteFieldStamps)
                    val adoptedTrackOps = buildRoomTrackOps(matchedLocal.id, remoteTrackAddStamps, remoteTrackTombstones)
                    dao.replacePlaylistFromCloudAtomic(
                        playlistId = matchedLocal.id,
                        title = cloudTitle,
                        description = cloudPl.description,
                        coverUri = resolvedCover,
                        remoteCoverUri = resolvedRemoteCover,
                        coverRemoved = cloudPl.coverRemoved,
                        updatedAt = adoptedTs,
                        syncRevision = adoptedRev,
                        tracks = trackEntities,
                        trackOps = adoptedTrackOps,
                        fieldOps = adoptedFieldOps
                    )
                    if (cloudBundle.hasLegacyArrayTracks) {
                        cloudRepo.upsertPlaylist(
                            playlistKey = key,
                            title = cloudTitle,
                            description = cloudPl.description,
                            coverUri = resolvedRemoteCover,
                            tracks = remoteTracks,
                            clientUpdatedAt = adoptedTs,
                            syncRevision = adoptedRev,
                            removeLegacyTracksField = true,
                            coverRemoved = cloudPl.coverRemoved,
                            playlistMutationStamp = cloudPl.playlistMutationStamp(),
                            fieldStamps = remoteFieldStamps,
                            trackAddStamps = remoteTrackAddStamps,
                            trackTombstones = remoteTrackTombstones
                        )
                    }
                    dao.upsertSyncBaseline(
                        SyncBaselineEntity(
                            uid, SyncEntityTypes.PLAYLIST, key, adoptedRev, cloudSig, adoptedTs, false, now,
                            snapshotJson = encodePlaylistSnapshotJson(
                                cloudTitle, cloudPl.description, resolvedRemoteCover, cloudPl.coverRemoved, remoteTracks,
                                remoteFieldStamps, buildBaselineTrackOps(remoteTrackAddStamps, remoteTrackTombstones)
                            )
                        )
                    )
                } else if (
                    cloudBundle.hasLegacyArrayTracks ||
                    cloudPl.activeSnapshotId.isNullOrBlank() ||
                    (localDirty && (cloudSig != localSig || hasNewerLocalOps))
                ) {
                    val pushTs = maxOf(matchedLocal.updatedAt, cloudPl.clientUpdatedAt)
                    val targetRev = maxOf(matchedLocal.syncRevision, cloudPl.syncRevision + 1L, 1L)
                    if (targetRev != matchedLocal.syncRevision) {
                        dao.setPlaylistSyncRevision(matchedLocal.id, targetRev)
                    }
                    val addStamps = localTracks.associate { trk ->
                        val explicit = localTrackOps[trk.videoId]?.takeIf { !it.deleted }?.stamp
                        trk.videoId to (explicit ?: SyncMutationStamp(trk.addedAt ?: pushTs, 0L, localDeviceId))
                    }
                    val trackTombstones = localTrackOps.filterValues { it.deleted }.mapValues { it.value.stamp }
                    val winStamp = computePlaylistMutationStamp(pushTs, localDeviceId, localFieldStamps, addStamps, trackTombstones)
                    cloudRepo.upsertPlaylist(
                        playlistKey = key,
                        title = matchedLocal.title,
                        description = matchedLocal.description,
                        coverUri = localRemoteCover,
                        tracks = localTracks,
                        clientUpdatedAt = pushTs,
                        syncRevision = targetRev,
                        removeLegacyTracksField = cloudBundle.hasLegacyArrayTracks,
                        coverRemoved = matchedLocal.coverRemoved,
                        playlistMutationStamp = winStamp,
                        fieldStamps = localFieldStamps,
                        trackAddStamps = addStamps,
                        trackTombstones = trackTombstones
                    )
                    dao.upsertSyncBaseline(
                        SyncBaselineEntity(
                            uid, SyncEntityTypes.PLAYLIST, key, targetRev, localSig, pushTs, false, now,
                            snapshotJson = encodePlaylistSnapshotJson(
                                matchedLocal.title, matchedLocal.description, localRemoteCover, matchedLocal.coverRemoved, localTracks,
                                localFieldStamps, buildBaselineTrackOps(addStamps, trackTombstones)
                            )
                        )
                    )
                } else {
                    dao.upsertSyncBaseline(
                        SyncBaselineEntity(
                            uid, SyncEntityTypes.PLAYLIST, key,
                            maxOf(matchedLocal.syncRevision, cloudPl.syncRevision),
                            localSig, matchedLocal.updatedAt, false, now,
                            snapshotJson = encodePlaylistSnapshotJson(
                                matchedLocal.title, matchedLocal.description, localRemoteCover, matchedLocal.coverRemoved, localTracks,
                                localFieldStamps, localTrackOps
                            )
                        )
                    )
                }
            } else {
                dao.deleteTombstone(SyncEntityTypes.PLAYLIST, key)
                val initialRev = cloudPl.syncRevision.coerceAtLeast(1L)
                val initialTs = cloudPl.clientUpdatedAt.takeIf { it > 0L } ?: now
                val trackEntities = remoteTracks.mapIndexed { idx, s ->
                    LocalPlaylistTrackEntity(
                        playlistId = 0,
                        videoId = s.videoId,
                        songJson = repository.encodeSong(s),
                        position = idx,
                        addedAt = s.addedAt ?: now
                    )
                }
                val newFieldOps = buildRoomFieldOps(0L, remoteFieldStamps)
                val newTrackOps = buildRoomTrackOps(0L, remoteTrackAddStamps, remoteTrackTombstones)
                dao.createPlaylistWithTracks(
                    playlist = LocalPlaylistEntity(
                        cloudKey = key,
                        title = cloudTitle,
                        description = cloudPl.description,
                        coverUri = safeCloudCoverUri,
                        remoteCoverUri = safeCloudCoverUri,
                        coverRemoved = cloudPl.coverRemoved,
                        createdAt = now,
                        updatedAt = initialTs,
                        syncRevision = initialRev
                    ),
                    tracks = trackEntities,
                    trackOps = newTrackOps,
                    fieldOps = newFieldOps
                )
                if (cloudBundle.hasLegacyArrayTracks) {
                    cloudRepo.upsertPlaylist(
                        playlistKey = key,
                        title = cloudTitle,
                        description = cloudPl.description,
                        coverUri = safeCloudCoverUri,
                        tracks = remoteTracks,
                        clientUpdatedAt = initialTs,
                        syncRevision = initialRev,
                        removeLegacyTracksField = true,
                        coverRemoved = cloudPl.coverRemoved,
                        playlistMutationStamp = cloudPl.playlistMutationStamp(),
                        fieldStamps = remoteFieldStamps,
                        trackAddStamps = remoteTrackAddStamps,
                        trackTombstones = remoteTrackTombstones
                    )
                }
                dao.upsertSyncBaseline(
                    SyncBaselineEntity(
                        uid, SyncEntityTypes.PLAYLIST, key, initialRev, cloudSig, initialTs, false, now,
                        snapshotJson = encodePlaylistSnapshotJson(
                            cloudTitle, cloudPl.description, safeCloudCoverUri, cloudPl.coverRemoved, remoteTracks,
                            remoteFieldStamps, buildBaselineTrackOps(remoteTrackAddStamps, remoteTrackTombstones)
                        )
                    )
                )
            }
        }

        // Push local-only / offline-created / offline-edited playlists regardless of watermark > 0
        for (localPl in validLocalPlaylists) {
            if (localPl.cloudKey in handledKeys) continue
            val localTracks = dao.getPlaylistTracks(localPl.id)
                .mapNotNull { repository.decodeSong(it.songJson) }
                .filter { !it.isLocal }
            val localRemoteCover = localPl.remoteCoverUri
                ?: SecurityValidators.sanitizeHttpsUrl(localPl.coverUri, 500)
            val sig = SyncSignatureHelper.playlistSignature(
                cloudKey = localPl.cloudKey,
                title = localPl.title,
                description = localPl.description,
                coverUri = localRemoteCover,
                tracks = localTracks,
                coverRemoved = localPl.coverRemoved
            )
            val baseline = plBaselines[localPl.cloudKey]
            val baseSnap = PlaylistThreeWayMerger.decodeBaselineSnapshot(baseline?.snapshotJson)
            val (localFieldStamps, localTrackOps, hasNewerLocalOps) = loadLocalPlaylistCausalState(
                localPl, baseSnap, localDeviceId
            )
            val dirty = baseline == null ||
                baseline.deleted ||
                localPl.syncRevision > baseline.revision ||
                sig != baseline.signature ||
                hasNewerLocalOps

            if (compactedBeforeMs > 0L && baseline != null && !baseline.deleted &&
                !dirty && localPl.updatedAt <= compactedBeforeMs
            ) {
                dao.deletePlaylistAtomic(localPl.id, "$LOCAL_PLAYLIST_PREFIX${localPl.id}")
                localPl.coverUri?.let { repository.deleteManagedPlaylistCover(it) }
                dao.deleteSyncBaseline(uid, SyncEntityTypes.PLAYLIST, localPl.cloudKey)
                continue
            }

            if (dirty) {
                val targetRev = localPl.syncRevision.coerceAtLeast(1L)
                if (targetRev != localPl.syncRevision) {
                    dao.setPlaylistSyncRevision(localPl.id, targetRev)
                }
                val addStamps = localTracks.associate { trk ->
                    val explicit = localTrackOps[trk.videoId]?.takeIf { !it.deleted }?.stamp
                    trk.videoId to (explicit ?: SyncMutationStamp(trk.addedAt ?: localPl.updatedAt, 0L, localDeviceId))
                }
                val trackTombstones = localTrackOps.filterValues { it.deleted }.mapValues { it.value.stamp }
                val winStamp = computePlaylistMutationStamp(localPl.updatedAt, localDeviceId, localFieldStamps, addStamps, trackTombstones)
                cloudRepo.upsertPlaylist(
                    playlistKey = localPl.cloudKey,
                    title = localPl.title,
                    description = localPl.description,
                    coverUri = localRemoteCover,
                    tracks = localTracks,
                    clientUpdatedAt = localPl.updatedAt,
                    syncRevision = targetRev,
                    coverRemoved = localPl.coverRemoved,
                    playlistMutationStamp = winStamp,
                    fieldStamps = localFieldStamps,
                    trackAddStamps = addStamps,
                    trackTombstones = trackTombstones
                )
                dao.upsertSyncBaseline(
                    SyncBaselineEntity(
                        uid, SyncEntityTypes.PLAYLIST, localPl.cloudKey,
                        targetRev, sig, localPl.updatedAt, false, now,
                        snapshotJson = encodePlaylistSnapshotJson(
                            localPl.title, localPl.description, localRemoteCover, localPl.coverRemoved, localTracks,
                            localFieldStamps, buildBaselineTrackOps(addStamps, trackTombstones)
                        )
                    )
                )
            }
        }

        // Push offline deletions (tombstones not in incremental snapshot)
        for ((key, tomb) in plTombstones) {
            if (key in handledKeys) continue
            val baseline = plBaselines[key]
            if (baseline == null || !baseline.deleted || tomb.clientUpdatedAt > baseline.clientUpdatedAt) {
                val delRev = (baseline?.revision ?: 0L) + 1L
                cloudRepo.deletePlaylist(
                    playlistKey = key,
                    clientUpdatedAt = tomb.clientUpdatedAt,
                    syncRevision = delRev,
                    clearLegacyTracksArray = false
                )
                dao.upsertSyncBaseline(
                    SyncBaselineEntity(uid, SyncEntityTypes.PLAYLIST, key, delRev, "", tomb.clientUpdatedAt, true, now)
                )
            }
        }

        if (snapshot.maxPlaylistsUpdatedAtMs > watermarks.playlistsMs) {
            prefsRepo.setSyncWatermark(UserPrefsRepository.WATERMARK_PLAYLISTS, snapshot.maxPlaylistsUpdatedAtMs)
        }
    }

    private suspend fun mergeHiddenArtistsLocked(
        uid: String,
        snapshot: CloudAccountSnapshot,
        watermarks: SyncWatermarks,
        now: Long,
        compactedBeforeMs: Long = 0L
    ) {
        val localBlocked = dao.getBlockedArtistsOnce()
        val localByKey = localBlocked
            .filter { it.name.trim().isNotBlank() }
            .associateBy { CloudSyncRepository.stableDocId("blk", it.name.lowercase().trim()) }
        val hiddenTombstones = dao.getTombstonesByType(SyncEntityTypes.HIDDEN_ARTIST).associateBy { it.entityKey }.toMutableMap()
        val hiddenBaselines = dao.getSyncBaselinesByType(uid, SyncEntityTypes.HIDDEN_ARTIST).associateBy { it.entityKey }

        // Detect offline unblocks via persistent baselines
        for ((key, base) in hiddenBaselines) {
            if (!base.deleted && key !in localByKey && key !in hiddenTombstones) {
                val synth = SyncTombstoneEntity(SyncEntityTypes.HIDDEN_ARTIST, key, now, now)
                dao.upsertTombstone(synth)
                hiddenTombstones[key] = synth
            }
        }

        val localHiddenMerge = linkedMapOf<String, MergeItem<BlockedArtistEntity>>()
        localByKey.forEach { (key, entity) ->
            localHiddenMerge[key] = MergeItem(key, entity.blockedAt, false, entity)
        }
        hiddenTombstones.forEach { (key, tomb) ->
            val existing = localHiddenMerge[key]
            if (existing == null || tomb.clientUpdatedAt >= existing.clientUpdatedAt) {
                localHiddenMerge[key] = MergeItem(key, tomb.clientUpdatedAt, true, null)
            }
        }

        val cloudHiddenMerge = snapshot.hiddenArtists
            .filter { it.artistKey.isNotBlank() && (it.name.isNotBlank() || it.deleted) }
            .associate { c ->
                val ts = c.clientUpdatedAt.takeIf { it > 0L } ?: c.blockedAt.takeIf { it > 0L } ?: (c.updatedAt?.toDate()?.time ?: 1L)
                c.artistKey to MergeItem(
                    key = c.artistKey,
                    clientUpdatedAt = ts,
                    deleted = c.deleted,
                    value = if (c.deleted) null else BlockedArtistEntity(c.name, c.channelId, c.blockedAt.takeIf { it > 0L } ?: ts)
                )
            }

        val compactedHiddenToDrop = mutableSetOf<String>()
        if (compactedBeforeMs > 0L) {
            for ((key, item) in localHiddenMerge) {
                if (item.deleted || key in cloudHiddenMerge || key in hiddenTombstones) continue
                val base = hiddenBaselines[key] ?: continue
                if (!base.deleted && item.clientUpdatedAt <= compactedBeforeMs && item.value != null &&
                    SyncSignatureHelper.hiddenArtistSignature(item.value.name, item.value.channelId) == base.signature
                ) {
                    compactedHiddenToDrop.add(key)
                }
            }
        }
        compactedHiddenToDrop.forEach { key ->
            localByKey[key]?.let { dao.deleteBlockedArtist(it.name) }
            dao.deleteSyncBaseline(uid, SyncEntityTypes.HIDDEN_ARTIST, key)
        }

        val effectiveLocalHidden = localHiddenMerge.filterKeys { key ->
            if (key in compactedHiddenToDrop) return@filterKeys false
            val item = localHiddenMerge.getValue(key)
            val base = hiddenBaselines[key]
            key in cloudHiddenMerge ||
                key in hiddenTombstones ||
                base == null ||
                base.deleted != item.deleted ||
                (!item.deleted && item.value != null &&
                    SyncSignatureHelper.hiddenArtistSignature(item.value.name, item.value.channelId) != base.signature)
        }

        val hiddenDecisions = MergeEngine.mergeMaps(effectiveLocalHidden, cloudHiddenMerge) { a, b ->
            a?.name.equals(b?.name, ignoreCase = true)
        }

        for (d in hiddenDecisions) {
            if (d.applyLocally) {
                if (d.deleted) {
                    localByKey[d.key]?.let { dao.deleteBlockedArtist(it.name) }
                    dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.HIDDEN_ARTIST, d.key, d.clientUpdatedAt, now))
                    dao.upsertSyncBaseline(
                        SyncBaselineEntity(uid, SyncEntityTypes.HIDDEN_ARTIST, d.key, 0L, "", d.clientUpdatedAt, true, now)
                    )
                } else if (d.value != null) {
                    dao.deleteTombstone(SyncEntityTypes.HIDDEN_ARTIST, d.key)
                    dao.insertBlockedArtist(d.value)
                    dao.upsertSyncBaseline(
                        SyncBaselineEntity(
                            uid, SyncEntityTypes.HIDDEN_ARTIST, d.key, 0L,
                            SyncSignatureHelper.hiddenArtistSignature(d.value.name, d.value.channelId),
                            d.clientUpdatedAt, false, now
                        )
                    )
                }
            } else if (d.pushToCloud && d.value != null) {
                cloudRepo.syncHiddenArtist(
                    d.value.name, d.value.channelId,
                    blocked = true, blockedAtMs = d.value.blockedAt, clientUpdatedAtMs = d.clientUpdatedAt
                )
                dao.upsertSyncBaseline(
                    SyncBaselineEntity(
                        uid, SyncEntityTypes.HIDDEN_ARTIST, d.key, 0L,
                        SyncSignatureHelper.hiddenArtistSignature(d.value.name, d.value.channelId),
                        d.clientUpdatedAt, false, now
                    )
                )
            } else if (d.pushTombstoneToCloud) {
                cloudRepo.syncHiddenArtist(d.key, null, blocked = false, clientUpdatedAtMs = d.clientUpdatedAt)
                dao.upsertSyncBaseline(
                    SyncBaselineEntity(uid, SyncEntityTypes.HIDDEN_ARTIST, d.key, 0L, "", d.clientUpdatedAt, true, now)
                )
            } else {
                val base = hiddenBaselines[d.key]
                if (base == null) {
                    val sig = d.value?.let { SyncSignatureHelper.hiddenArtistSignature(it.name, it.channelId) }.orEmpty()
                    dao.upsertSyncBaseline(
                        SyncBaselineEntity(uid, SyncEntityTypes.HIDDEN_ARTIST, d.key, 0L, sig, d.clientUpdatedAt, d.deleted, now)
                    )
                }
            }
        }
        if (snapshot.maxHiddenUpdatedAtMs > watermarks.hiddenMs) {
            prefsRepo.setSyncWatermark(UserPrefsRepository.WATERMARK_HIDDEN, snapshot.maxHiddenUpdatedAtMs)
        }
    }

    @Suppress("OPT_IN_USAGE")
    private fun launchWatchersLocked(user: FirebaseUser) {
        val uid = user.uid

        // Watch Preferences & Taste Seeds
        val prefsJob = scope.launch {
            var initialized = false
            combine(prefsRepo.prefsFlow, prefsRepo.tasteProfileFlow) { prefs, taste -> prefs to taste }
                .debounce(800L)
                .collectLatest { (prefs, taste) ->
                    if (auth.currentUser?.uid != uid || isSigningOut.get() || suppressLocalPushCount.get() > 0) return@collectLatest
                    if (!initialized) {
                        initialized = true
                        return@collectLatest
                    }

                    syncMutex.withLock {
                        if (auth.currentUser?.uid != uid || isSigningOut.get() || suppressLocalPushCount.get() > 0) return@withLock
                        if (hasPendingQuarantineOrJournal() || prefsRepo.getActiveCloudUid() != uid) return@withLock

                        val now = System.currentTimeMillis()
                        val safePhotoUrl = SecurityValidators.sanitizeHttpsUrl(user.photoUrl?.toString(), 500)
                        val profSig = SyncSignatureHelper.syncableProfilePrefsSignature(prefs, taste, prefs.userDisplayName)
                        val profBaseline = dao.getSyncBaselinesByType(uid, SyncEntityTypes.PROFILE_PREFS).firstOrNull()
                        val profileDirty = profBaseline == null || profBaseline.signature != profSig

                        val seedBaselines = dao.getSyncBaselinesByType(uid, SyncEntityTypes.SEED).associateBy { it.entityKey }
                        val currentArtists = taste.seedArtists.map { seed ->
                            val id = CloudSyncRepository.resolveSeedDocId("art", seed)
                            val ts = seed.updatedAtMs.takeIf { it > 0L } ?: now
                            id to seed.copy(id = id, updatedAtMs = ts)
                        }
                        val currentSongs = taste.seedSongs.map { seed ->
                            val id = CloudSyncRepository.resolveSeedDocId("sng", seed)
                            val ts = seed.updatedAtMs.takeIf { it > 0L } ?: now
                            id to seed.copy(id = id, updatedAtMs = ts)
                        }
                        val currentSeedIds = (currentArtists.map { it.first } + currentSongs.map { it.first }).toSet()

                        val removedSeedIds = seedBaselines.values
                            .filter { !it.deleted }
                            .map { it.entityKey }
                            .toSet() - currentSeedIds
                        val removedTombs = removedSeedIds.map { removedId ->
                            SyncTombstoneEntity(SyncEntityTypes.SEED, removedId, now, now).also {
                                dao.upsertTombstone(it)
                            }
                        }
                        currentSeedIds.forEach { dao.deleteTombstone(SyncEntityTypes.SEED, it) }

                        val dirtyArtists = currentArtists.filter { (id, s) ->
                            val base = seedBaselines[id]
                            base == null || base.deleted || base.signature != SyncSignatureHelper.tasteSeedSignature("ARTIST", s)
                        }.map { it.second }
                        val dirtySongs = currentSongs.filter { (id, s) ->
                            val base = seedBaselines[id]
                            base == null || base.deleted || base.signature != SyncSignatureHelper.tasteSeedSignature("SONG", s)
                        }.map { it.second }

                        val seedsDirty = dirtyArtists.isNotEmpty() || dirtySongs.isNotEmpty() || removedTombs.isNotEmpty()
                        if (!profileDirty && !seedsDirty) return@withLock

                        _authState.update { it.copy(isSyncing = true, syncStatus = "Syncing…") }
                        val res = suspendRunCatching {
                            if (profileDirty) {
                                val prefsTs = prefsRepo.getPrefsUpdatedAt().takeIf { it > 0L } ?: now
                                cloudRepo.upsertUserProfile(
                                    prefs = prefs,
                                    tasteProfile = taste,
                                    photoUrl = safePhotoUrl,
                                    prefsUpdatedAtMs = prefsTs
                                )
                                dao.upsertSyncBaseline(
                                    SyncBaselineEntity(uid, SyncEntityTypes.PROFILE_PREFS, PROFILE_BASELINE_KEY, 0L, profSig, prefsTs, false, now)
                                )
                            }
                            if (seedsDirty) {
                                cloudRepo.upsertTasteSeeds(dirtyArtists, dirtySongs, removedTombs, now)
                                val newBaselines = mutableListOf<SyncBaselineEntity>()
                                dirtyArtists.forEach { s ->
                                    newBaselines.add(
                                        SyncBaselineEntity(
                                            uid, SyncEntityTypes.SEED, s.id, 0L,
                                            SyncSignatureHelper.tasteSeedSignature("ARTIST", s),
                                            s.updatedAtMs, false, now
                                        )
                                    )
                                }
                                dirtySongs.forEach { s ->
                                    newBaselines.add(
                                        SyncBaselineEntity(
                                            uid, SyncEntityTypes.SEED, s.id, 0L,
                                            SyncSignatureHelper.tasteSeedSignature("SONG", s),
                                            s.updatedAtMs, false, now
                                        )
                                    )
                                }
                                removedTombs.forEach { t ->
                                    newBaselines.add(
                                        SyncBaselineEntity(uid, SyncEntityTypes.SEED, t.entityKey, 0L, "", t.clientUpdatedAt, true, now)
                                    )
                                }
                                if (newBaselines.isNotEmpty()) dao.upsertSyncBaselines(newBaselines)
                            }
                        }
                        recordWatcherResult("profile", res, user)
                    }
                }
        }
        watcherJobs.add(prefsJob)

        // Watch Liked Songs
        val likesJob = scope.launch {
            dao.observeLikedSongs().collectLatest { rows ->
                if (auth.currentUser?.uid != uid || isSigningOut.get() || suppressLocalPushCount.get() > 0) return@collectLatest
                syncMutex.withLock {
                    if (auth.currentUser?.uid != uid || isSigningOut.get() || suppressLocalPushCount.get() > 0) return@withLock
                    if (hasPendingQuarantineOrJournal() || prefsRepo.getActiveCloudUid() != uid) return@withLock
                    val currentMap = rows.filter { !it.videoId.startsWith(LOCAL_SONG_PREFIX) }.associateBy { it.videoId }
                    val baselines = dao.getSyncBaselinesByType(uid, SyncEntityTypes.LIKED).associateBy { it.entityKey }

                    val toPush = currentMap.values.filter { entity ->
                        val song = repository.decodeSong(entity.songJson) ?: return@filter false
                        val sig = SyncSignatureHelper.likedSongSignature(song)
                        val base = baselines[entity.videoId]
                        base == null || base.deleted || base.signature != sig
                    }
                    val removed = baselines.values.filter { !it.deleted && it.entityKey !in currentMap }.map { it.entityKey }
                    if (toPush.isEmpty() && removed.isEmpty()) return@withLock

                    _authState.update { it.copy(isSyncing = true, syncStatus = "Syncing…") }
                    val now = System.currentTimeMillis()
                    val res = suspendRunCatching {
                        for (entity in toPush) {
                            val song = repository.decodeSong(entity.songJson) ?: continue
                            dao.deleteTombstone(SyncEntityTypes.LIKED, entity.videoId)
                            cloudRepo.syncLikedSong(song, liked = true, likedAtMs = entity.likedAt, clientUpdatedAtMs = entity.likedAt)
                            dao.upsertSyncBaseline(
                                SyncBaselineEntity(
                                    uid, SyncEntityTypes.LIKED, entity.videoId, 0L,
                                    SyncSignatureHelper.likedSongSignature(song),
                                    entity.likedAt, false, now
                                )
                            )
                        }
                        for (id in removed) {
                            dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.LIKED, id, now, now))
                            val dummy = SongItem(videoId = id, title = "", artists = "")
                            cloudRepo.syncLikedSong(dummy, liked = false, clientUpdatedAtMs = now)
                            dao.upsertSyncBaseline(
                                SyncBaselineEntity(uid, SyncEntityTypes.LIKED, id, 0L, "", now, true, now)
                            )
                        }
                    }
                    recordWatcherResult("likes", res, user)
                }
            }
        }
        watcherJobs.add(likesJob)

        // Watch Playlists + Track Counts
        val playlistsJob = scope.launch {
            combine(dao.observePlaylists(), dao.observePlaylistTrackCounts()) { pls, _ -> pls }
                .debounce(500L)
                .collectLatest { playlists ->
                    if (auth.currentUser?.uid != uid || isSigningOut.get() || suppressLocalPushCount.get() > 0) return@collectLatest
                    syncMutex.withLock {
                        if (auth.currentUser?.uid != uid || isSigningOut.get() || suppressLocalPushCount.get() > 0) return@withLock
                        if (hasPendingQuarantineOrJournal() || prefsRepo.getActiveCloudUid() != uid) return@withLock
                        val localDeviceId = getLocalSyncDeviceId()
                        val baselines = dao.getSyncBaselinesByType(uid, SyncEntityTypes.PLAYLIST).associateBy { it.entityKey }
                        val activeKeys = mutableSetOf<String>()
                        val dirtyPlaylists = mutableListOf<Triple<LocalPlaylistEntity, List<SongItem>, String>>()

                        for (pl in playlists) {
                            activeKeys.add(pl.cloudKey)
                            val tracks = dao.getPlaylistTracks(pl.id)
                                .mapNotNull { repository.decodeSong(it.songJson) }
                                .filter { !it.isLocal }
                            val localRemoteCover = pl.remoteCoverUri
                                ?: SecurityValidators.sanitizeHttpsUrl(pl.coverUri, 500)
                            val sig = SyncSignatureHelper.playlistSignature(
                                pl.cloudKey, pl.title, pl.description, localRemoteCover, tracks, pl.coverRemoved
                            )
                            val base = baselines[pl.cloudKey]
                            val baseSnap = PlaylistThreeWayMerger.decodeBaselineSnapshot(base?.snapshotJson)
                            val (_, _, hasNewerOps) = loadLocalPlaylistCausalState(pl, baseSnap, localDeviceId)
                            if (base == null || base.deleted || pl.syncRevision > base.revision || base.signature != sig || hasNewerOps) {
                                dirtyPlaylists.add(Triple(pl, tracks, sig))
                            }
                        }
                        val deletedBaselines = baselines.values.filter { !it.deleted && it.entityKey !in activeKeys }
                        if (dirtyPlaylists.isEmpty() && deletedBaselines.isEmpty()) return@withLock

                        _authState.update { it.copy(isSyncing = true, syncStatus = "Syncing…") }
                        val now = System.currentTimeMillis()
                        val res = suspendRunCatching {
                            for ((pl, tracks, sig) in dirtyPlaylists) {
                                dao.deleteTombstone(SyncEntityTypes.PLAYLIST, pl.cloudKey)
                                val base = baselines[pl.cloudKey]
                                val baseRev = base?.revision ?: 0L
                                val targetRev = maxOf(pl.syncRevision, if (baseRev > 0L) baseRev + 1L else 1L, 1L)
                                if (targetRev != pl.syncRevision) {
                                    dao.setPlaylistSyncRevision(pl.id, targetRev)
                                }
                                val localRemoteCover = pl.remoteCoverUri
                                    ?: SecurityValidators.sanitizeHttpsUrl(pl.coverUri, 500)
                                val baseSnap = PlaylistThreeWayMerger.decodeBaselineSnapshot(base?.snapshotJson)
                                val (localFieldStamps, localTrackOps, _) = loadLocalPlaylistCausalState(pl, baseSnap, localDeviceId)
                                val addStamps = tracks.associate { trk ->
                                    val explicit = localTrackOps[trk.videoId]?.takeIf { !it.deleted }?.stamp
                                    trk.videoId to (explicit ?: SyncMutationStamp(trk.addedAt ?: pl.updatedAt, 0L, localDeviceId))
                                }
                                val trackTombstones = localTrackOps.filterValues { it.deleted }.mapValues { it.value.stamp }
                                val winStamp = computePlaylistMutationStamp(pl.updatedAt, localDeviceId, localFieldStamps, addStamps, trackTombstones)
                                cloudRepo.upsertPlaylist(
                                    playlistKey = pl.cloudKey,
                                    title = pl.title,
                                    description = pl.description,
                                    coverUri = localRemoteCover,
                                    tracks = tracks,
                                    clientUpdatedAt = pl.updatedAt,
                                    syncRevision = targetRev,
                                    coverRemoved = pl.coverRemoved,
                                    playlistMutationStamp = winStamp,
                                    fieldStamps = localFieldStamps,
                                    trackAddStamps = addStamps,
                                    trackTombstones = trackTombstones
                                )
                                dao.upsertSyncBaseline(
                                    SyncBaselineEntity(
                                        uid, SyncEntityTypes.PLAYLIST, pl.cloudKey, targetRev, sig, pl.updatedAt, false, now,
                                        snapshotJson = encodePlaylistSnapshotJson(
                                            pl.title, pl.description, localRemoteCover, pl.coverRemoved, tracks,
                                            localFieldStamps, buildBaselineTrackOps(addStamps, trackTombstones)
                                        )
                                    )
                                )
                            }
                            for (delBase in deletedBaselines) {
                                val delRev = delBase.revision + 1L
                                dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.PLAYLIST, delBase.entityKey, now, now))
                                cloudRepo.deletePlaylist(delBase.entityKey, clientUpdatedAt = now, syncRevision = delRev)
                                dao.upsertSyncBaseline(
                                    SyncBaselineEntity(uid, SyncEntityTypes.PLAYLIST, delBase.entityKey, delRev, "", now, true, now)
                                )
                            }
                        }
                        recordWatcherResult("playlists", res, user)
                    }
                }
        }
        watcherJobs.add(playlistsJob)

        // Watch Hidden Artists
        val hiddenJob = scope.launch {
            dao.observeBlockedArtists().collectLatest { rows ->
                if (auth.currentUser?.uid != uid || isSigningOut.get() || suppressLocalPushCount.get() > 0) return@collectLatest
                syncMutex.withLock {
                    if (auth.currentUser?.uid != uid || isSigningOut.get() || suppressLocalPushCount.get() > 0) return@withLock
                    if (hasPendingQuarantineOrJournal() || prefsRepo.getActiveCloudUid() != uid) return@withLock
                    val byKey = rows
                        .filter { it.name.trim().isNotBlank() }
                        .associateBy { CloudSyncRepository.stableDocId("blk", it.name.lowercase().trim()) }
                    val baselines = dao.getSyncBaselinesByType(uid, SyncEntityTypes.HIDDEN_ARTIST).associateBy { it.entityKey }

                    val toPush = byKey.entries.filter { (key, row) ->
                        val sig = SyncSignatureHelper.hiddenArtistSignature(row.name, row.channelId)
                        val base = baselines[key]
                        base == null || base.deleted || base.signature != sig
                    }
                    val removed = baselines.values.filter { !it.deleted && it.entityKey !in byKey }.map { it.entityKey }
                    if (toPush.isEmpty() && removed.isEmpty()) return@withLock

                    _authState.update { it.copy(isSyncing = true, syncStatus = "Syncing…") }
                    val now = System.currentTimeMillis()
                    val res = suspendRunCatching {
                        for ((key, row) in toPush) {
                            dao.deleteTombstone(SyncEntityTypes.HIDDEN_ARTIST, key)
                            cloudRepo.syncHiddenArtist(
                                row.name, row.channelId,
                                blocked = true, blockedAtMs = row.blockedAt, clientUpdatedAtMs = row.blockedAt
                            )
                            dao.upsertSyncBaseline(
                                SyncBaselineEntity(
                                    uid, SyncEntityTypes.HIDDEN_ARTIST, key, 0L,
                                    SyncSignatureHelper.hiddenArtistSignature(row.name, row.channelId),
                                    row.blockedAt, false, now
                                )
                            )
                        }
                        for (key in removed) {
                            dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.HIDDEN_ARTIST, key, now, now))
                            cloudRepo.syncHiddenArtist(key, null, blocked = false, clientUpdatedAtMs = now)
                            dao.upsertSyncBaseline(
                                SyncBaselineEntity(uid, SyncEntityTypes.HIDDEN_ARTIST, key, 0L, "", now, true, now)
                            )
                        }
                    }
                    recordWatcherResult("hidden", res, user)
                }
            }
        }
        watcherJobs.add(hiddenJob)
    }

    private suspend fun recordWatcherResult(collection: String, result: Result<Unit>, user: FirebaseUser) {
        result.onSuccess {
            failedCollections.remove(collection)
            if (failedCollections.isEmpty()) {
                retryAttempt = 0
                val now = System.currentTimeMillis()
                _authState.update {
                    it.copy(
                        isSyncing = false,
                        lastSyncedAtMs = now,
                        syncStatus = formatUpToDate(now),
                        errorMessage = null,
                        pendingUnsyncedCount = 0
                    )
                }
            } else {
                val firstErr = failedCollections.values.firstOrNull() ?: "Sync error"
                val pending = suspendRunCatching { countUnsyncedChangesFor(user.uid) }
                    .getOrDefault(failedCollections.size)
                    .coerceAtLeast(1)
                _authState.update {
                    it.copy(
                        isSyncing = false,
                        syncStatus = "Sync failed: $firstErr",
                        errorMessage = firstErr,
                        pendingUnsyncedCount = pending
                    )
                }
            }
        }.onFailure { err ->
            if (err is CancellationException) throw err
            val msg = SecurityValidators.safeUserErrorMessage(err, "Cloud sync error")
            failedCollections[collection] = msg
            if (BuildConfig.DEBUG) Log.w(TAG, "Watcher [$collection] sync failed: $msg")
            val pending = suspendRunCatching { countUnsyncedChangesFor(user.uid) }
                .getOrDefault(failedCollections.size)
                .coerceAtLeast(1)
            _authState.update {
                it.copy(
                    isSyncing = false,
                    syncStatus = "Sync failed: $msg",
                    errorMessage = msg,
                    pendingUnsyncedCount = pending
                )
            }
            if (!isSigningOut.get()) {
                scheduleRetry(user)
            }
        }
    }

    private fun scheduleRetry(user: FirebaseUser) {
        synchronized(syncLifecycleLock) {
            if (isSigningOut.get() || activeUserId != user.uid || retryJob?.isActive == true) return
            val backoffMs = (5_000L * (1L shl retryAttempt.coerceAtMost(3))).coerceAtMost(60_000L)
            retryAttempt++
            retryJob = scope.launch {
                delay(backoffMs)
                if (!isSigningOut.get() && auth.currentUser?.uid == user.uid && failedCollections.isNotEmpty()) {
                    if (runBidirectionalMerge(user)) {
                        maybeLaunchWatchers(user)
                    }
                }
            }
        }
    }

    private fun formatUpToDate(epochMs: Long): String {
        val fmt = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
        return "Up to date at ${fmt.format(Instant.ofEpochMilli(epochMs))}"
    }

    companion object {
        private const val TAG = "CloudSyncCoordinator"
        private const val PROFILE_BASELINE_KEY = "profile"
        private const val TOMBSTONE_RETENTION_MS = 90L * 24L * 3600L * 1000L
        private const val UNOWNED_QUARANTINE_FILENAME = "unowned_account_quarantine.json"
        private const val UNOWNED_RESOLUTION_JOURNAL_FILENAME = "unowned_quarantine_resolution_journal.json"
        private const val UNOWNED_RETAINED_PREFIX = "unowned_quarantine_retained_"
    }
}
