package com.example.playback

import android.app.Application
import android.os.Bundle
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutionException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
@OptIn(UnstableApi::class)
class VelunaPlaybackServiceTest {

    private lateinit var serviceController: ServiceController<VelunaPlaybackService>
    private lateinit var service: VelunaPlaybackService
    private lateinit var callback: VelunaPlaybackService.VelunaMediaSessionCallback
    private lateinit var session: MediaSession
    private lateinit var controller: MediaSession.ControllerInfo

    @Before
    fun setUp() {
        serviceController = Robolectric.buildService(VelunaPlaybackService::class.java).create()
        service = serviceController.get()
        controller = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            service.packageName,
            Process.myPid(),
            Process.myUid(),
            0,
            0,
            false,
            Bundle.EMPTY
        )
        session = requireNotNull(service.onGetSession(controller))
        callback = service.VelunaMediaSessionCallback()
    }

    @After
    fun tearDown() {
        serviceController.destroy()
    }

    @Test
    fun onSetMediaItems_rejectsDisallowedHttpsHost() {
        val mediaItem = MediaItem.fromUri("https://example.com/audio.mp3")

        val failure = assertThrows(ExecutionException::class.java) {
            callback.onSetMediaItems(session, controller, listOf(mediaItem), 0, 0L).get()
        }

        assertThat(failure.cause).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun onSetMediaItems_acceptsKnownStreamHostAndPreservesStartPosition() {
        val mediaItem = MediaItem.fromUri("https://rr1.googlevideo.com/videoplayback")

        val result = callback.onSetMediaItems(session, controller, listOf(mediaItem), 0, 1_234L).get()

        assertThat(result.mediaItems).containsExactly(mediaItem)
        assertThat(result.startIndex).isEqualTo(0)
        assertThat(result.startPositionMs).isEqualTo(1_234L)
    }
}
