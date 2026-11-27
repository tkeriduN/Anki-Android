// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.preferences.reviewer.ViewerAction
import com.ichi2.anki.previewer.CardViewerActivity
import com.ichi2.anki.settings.Prefs
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowPowerManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 'Continue auto advance in the background' */
@RunWith(AndroidJUnit4::class)
class BackgroundListeningTest : RobolectricTest() {
    override fun getCollectionStorageMode() = CollectionStorageMode.IN_MEMORY_WITH_MEDIA

    @Test
    fun `auto advance and media continue when the screen is turned off`() =
        runTest {
            Prefs.continueAutoAdvanceInBackground = true
            withReviewer { controller ->
                enableAutoAdvance()
                assertTrue(viewModel.isBackgroundListeningActive)
                assertServiceStarted()

                // screen turned off
                controller.pause().stop()
                assertTrue(viewModel.cardMediaPlayer.isEnabled, "the media player wasn't disabled")
                advance(6.seconds)
                assertTrue(viewModel.showingAnswer.value, "the countdown continued")
            }
        }

    @Test
    fun `auto advance stops when the screen is turned off if the setting is disabled`() =
        runTest {
            Prefs.continueAutoAdvanceInBackground = false
            withReviewer { controller ->
                enableAutoAdvance()
                assertFalse(viewModel.isBackgroundListeningActive)

                controller.pause().stop()
                assertFalse(viewModel.cardMediaPlayer.isEnabled)
                advance(6.seconds)
                assertFalse(viewModel.showingAnswer.value)
            }
        }

    @Test
    fun `the service is not started if auto advance is off`() =
        runTest {
            Prefs.continueAutoAdvanceInBackground = true
            withReviewer {
                assertFalse(viewModel.isBackgroundListeningActive)
                assertNoServiceStarted()
            }
        }

    @Test
    fun `turning off auto advance stops the service`() =
        runTest {
            Prefs.continueAutoAdvanceInBackground = true
            withReviewer {
                enableAutoAdvance()
                assertServiceStarted()

                viewModel.executeAction(ViewerAction.TOGGLE_AUTO_ADVANCE)
                runCurrent()
                assertFalse(viewModel.isBackgroundListeningActive)
                assertServiceStopped()
            }
        }

    @Test
    fun `toolbar action toggles the setting`() =
        runTest {
            Prefs.continueAutoAdvanceInBackground = false
            withReviewer {
                viewModel.executeAction(ViewerAction.TOGGLE_BACKGROUND_LISTENING)
                runCurrent()
                assertTrue(Prefs.continueAutoAdvanceInBackground)
                assertTrue(viewModel.isBackgroundListeningEnabledFlow.value)

                viewModel.executeAction(ViewerAction.TOGGLE_BACKGROUND_LISTENING)
                runCurrent()
                assertFalse(Prefs.continueAutoAdvanceInBackground)
                assertFalse(viewModel.isBackgroundListeningEnabledFlow.value)
            }
        }

    @Test
    fun `stop action of the notification turns off auto advance and silences the card`() =
        runTest {
            Prefs.continueAutoAdvanceInBackground = true
            withReviewer { controller ->
                enableAutoAdvance()
                controller.pause().stop()
                val evals = async { viewModel.eval.first { it == ReviewerViewModel.PAUSE_HTML_MEDIA_JS } }
                runCurrent()

                BackgroundListening.requestStop()
                runCurrent()

                assertFalse(viewModel.isAutoAdvanceEnabledFlow.value)
                assertEquals(ReviewerViewModel.PAUSE_HTML_MEDIA_JS, evals.await())
                assertServiceStopped()
                advance(60.seconds)
                assertFalse(viewModel.showingAnswer.value)
            }
        }

    @Test
    fun `service runs in the foreground and holds a wake lock until it is destroyed`() {
        val controller =
            Robolectric
                .buildService(BackgroundListeningService::class.java, Intent(targetContext, BackgroundListeningService::class.java))
                .create()
                .startCommand(0, 1)
        val service = controller.get()

        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertEquals(BackgroundListeningService.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        val wakeLock = assertNotNull(ShadowPowerManager.getLatestWakeLock())
        assertTrue(wakeLock.isHeld)

        controller.destroy()
        assertFalse(wakeLock.isHeld)
    }

    @Test
    fun `stop action of the service requests a stop and stops the service`() =
        runTest {
            val stopRequest = async { BackgroundListening.stopRequests.first() }
            runCurrent()

            val intent = Intent(targetContext, BackgroundListeningService::class.java).setAction(BackgroundListeningService.ACTION_STOP)
            val service =
                Robolectric
                    .buildService(BackgroundListeningService::class.java, intent)
                    .create()
                    .startCommand(0, 1)
                    .get()
            runCurrent()

            assertTrue(stopRequest.isCompleted)
            assertTrue(shadowOf(service).isStoppedBySelf)
        }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private fun TestScope.enableAutoAdvanceOf(reviewer: ReviewerFragment) {
        reviewer.viewModel.executeAction(ViewerAction.TOGGLE_AUTO_ADVANCE)
        runCurrent()
        assertTrue(reviewer.viewModel.isAutoAdvanceEnabledFlow.value)
    }

    private val serviceComponent get() = ComponentName(targetContext, BackgroundListeningService::class.java)

    private fun assertServiceStarted() {
        val started = assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, "a service was started")
        assertEquals(serviceComponent, started.component)
    }

    private fun assertNoServiceStarted() {
        val started = shadowOf(RuntimeEnvironment.getApplication()).peekNextStartedService()
        assertFalse(started?.component == serviceComponent, "the service wasn't started")
    }

    private fun assertServiceStopped() {
        val stopped = assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStoppedService, "a service was stopped")
        assertEquals(serviceComponent, stopped.component)
    }

    /**
     * Opens the study screen with a card that shows its question for 5 seconds with auto advance.
     * Autoplay is disabled, so no `[sound:]` tags are played from another thread.
     */
    private suspend fun TestScope.withReviewer(block: suspend ReviewerContext.(ActivityController<CardViewerActivity>) -> Unit) {
        updateDeckConfig(col.decks.selected()) {
            secondsToShowQuestion = 5.0
            autoplay = false
        }
        addBasicNote("front", "back")
        val intent = ReviewerFragment.getIntent(targetContext)
        Robolectric.buildActivity(CardViewerActivity::class.java, intent).use { controller ->
            controller.setup().windowFocusChanged(true)
            advanceUntilIdle()
            val reviewer = controller.get().fragment as ReviewerFragment
            reviewer.viewModel.onPageFinished(false)
            runCurrent()
            assertFalse(reviewer.viewModel.showingAnswer.value)
            // stopping the service with no service started is recorded too
            @Suppress("ControlFlowWithEmptyBody")
            while (shadowOf(RuntimeEnvironment.getApplication()).nextStoppedService != null) {
                // drain
            }
            ReviewerContext(this, reviewer).block(controller)
        }
    }

    private inner class ReviewerContext(
        val scope: TestScope,
        val reviewer: ReviewerFragment,
    ) {
        val viewModel get() = reviewer.viewModel

        fun enableAutoAdvance() = scope.enableAutoAdvanceOf(reviewer)
    }
}
