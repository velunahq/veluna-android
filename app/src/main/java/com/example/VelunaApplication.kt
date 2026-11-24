package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.size.Precision
import com.example.data.cloud.CloudSyncCoordinator
import com.example.data.cloud.CloudSyncRepository
import com.example.data.cloud.StartupInitializationCoordinator
import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaDatabase
import com.example.data.network.InnerTubeClient
import com.example.data.network.LyricsRepository
import com.example.data.network.ScrobbleClient
import com.example.data.repository.TasteEngine
import com.example.data.repository.VelunaRepository
import com.example.playback.PlayerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VelunaAppContainer(
    application: Application,
    appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    val database: VelunaDatabase = VelunaDatabase.getInstance(application)
    val dao = database.velunaDao()
    val prefsRepository: UserPrefsRepository = UserPrefsRepository(application)
    val innerTubeClient: InnerTubeClient = InnerTubeClient()
    val lyricsRepository: LyricsRepository = LyricsRepository(dao, innerTubeClient)
    val scrobbleClient: ScrobbleClient = ScrobbleClient(
        httpClient = innerTubeClient.httpClient,
        dao = dao,
        prefsRepo = prefsRepository
    )
    val tasteEngine: TasteEngine = TasteEngine(
        dao = dao,
        prefsRepo = prefsRepository,
        innerTube = innerTubeClient
    )
    val repository: VelunaRepository = VelunaRepository(
        context = application,
        dao = dao,
        prefsRepo = prefsRepository,
        innerTube = innerTubeClient,
        database = database
    )
    val startupCoordinator: StartupInitializationCoordinator = StartupInitializationCoordinator(
        repository = repository,
        prefsRepo = prefsRepository,
        dao = dao
    )
    val cloudSyncRepository: CloudSyncRepository = CloudSyncRepository(application)
    val cloudSyncCoordinator: CloudSyncCoordinator = CloudSyncCoordinator(
        context = application,
        dao = dao,
        prefsRepo = prefsRepository,
        repository = repository,
        cloudRepo = cloudSyncRepository,
        awaitStartupProvenance = { startupCoordinator.ensureInitialized() }
    )
    val playerController: PlayerController = PlayerController(
        context = application,
        dao = dao,
        repository = repository,
        prefsRepo = prefsRepository,
        innerTube = innerTubeClient,
        scrobbleClient = scrobbleClient,
        tasteEngine = tasteEngine
    )
    val startupJob: Job

    init {
        repository.runWithSyncSuppressed = { block ->
            cloudSyncCoordinator.runWithLocalSyncSuppressed(block)
        }
        startupJob = appScope.launch {
            try {
                startupCoordinator.ensureInitialized()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Leave startupCoordinator uninitialized so callers fail closed loudly on retry/await.
            }
        }
    }
}

class VelunaApplication : Application(), ImageLoaderFactory {
    lateinit var container: VelunaAppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = VelunaAppContainer(this, appScope)
        container.scrobbleClient.registerConnectivityFlush(this)
        appScope.launch {
            container.startupJob.join()
            if (container.startupCoordinator.isInitialized()) {
                runCatching { container.prefsRepository.migrateSecretsAndPruneLegacyKeys() }
                runCatching { container.scrobbleClient.flushPendingScrobbles() }
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("veluna_image_cache"))
                    .maxSizeBytes(64L * 1024L * 1024L)
                    .build()
            }
            .respectCacheHeaders(false)
            .allowHardware(true)
            .precision(Precision.INEXACT)
            .crossfade(false)
            .build()
    }
}
