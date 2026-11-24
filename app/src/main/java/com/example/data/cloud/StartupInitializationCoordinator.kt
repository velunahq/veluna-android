package com.example.data.cloud

import com.example.data.local.LocalDataProvenance
import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaDao
import com.example.data.repository.VelunaRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single authoritative startup pipeline shared by [com.example.VelunaApplication] and
 * [CloudSyncCoordinator]. Guarantees strict fail-closed ordering:
 * 1. Recover any incomplete backup restore journal.
 * 2. Verify Room and DataStore are stable (no lingering/corrupt restore journal).
 * 3. Initialize local-data provenance against the recovered, stable database state.
 * 4. Verify local-data provenance is present and valid before permitting ownership checks,
 *    Firestore synchronization, or live watchers.
 *
 * Errors propagate directly to callers; initialization is never marked complete on failure.
 */
class StartupInitializationCoordinator(
    private val repository: VelunaRepository,
    private val prefsRepo: UserPrefsRepository,
    private val dao: VelunaDao
) {
    private val mutex = Mutex()

    @Volatile
    private var initialized: Boolean = false

    internal var onAfterRestoreRecoveryBeforeVerification: (suspend () -> Unit)? = null
    internal var onBeforeProvenanceInitialization: (suspend () -> Unit)? = null
    internal var onAfterProvenanceInitialized: (suspend () -> Unit)? = null

    fun isInitialized(): Boolean = initialized

    suspend fun ensureInitialized(): LocalDataProvenance {
        if (initialized) {
            return prefsRepo.getLocalDataProvenance()
                ?: throw IllegalStateException("Local data provenance is missing after startup initialization.")
        }
        return mutex.withLock {
            if (initialized) {
                return@withLock prefsRepo.getLocalDataProvenance()
                    ?: throw IllegalStateException("Local data provenance is missing after startup initialization.")
            }

            // 1. Recover any incomplete backup restore.
            repository.recoverIncompleteRestoreIfNeeded()
            onAfterRestoreRecoveryBeforeVerification?.invoke()

            // 2. Verify that Room and DataStore are stable before scanning for provenance.
            repository.verifyRestoreStateStableForStartup()

            // 3. Initialize local-data provenance against the stable post-recovery state.
            onBeforeProvenanceInitialization?.invoke()
            prefsRepo.initializeInstallProvenanceIfClean(dao)

            // 4. Verify that provenance is present and valid.
            val provenance = prefsRepo.getLocalDataProvenance()
                ?: throw IllegalStateException("Local data provenance is missing or invalid.")
            onAfterProvenanceInitialized?.invoke()

            initialized = true
            provenance
        }
    }
}
