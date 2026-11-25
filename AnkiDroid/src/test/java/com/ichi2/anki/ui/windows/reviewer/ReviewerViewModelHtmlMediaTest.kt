// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.pages.PostRequestUri
import com.ichi2.anki.preferences.reviewer.ViewerAction
import com.ichi2.anki.reviewer.CardSide
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Auto advance with 'Wait for audio': media played by the HTML of the card,
 * reported by `ankidroid-reviewer.js` with `htmlMediaStateChanged`, holds the countdown.
 */
@RunWith(AndroidJUnit4::class)
class ReviewerViewModelHtmlMediaTest : RobolectricTest() {
    private val viewModelStore = ViewModelStore()
    private var sequence = 0L

    override fun getCollectionStorageMode() = CollectionStorageMode.IN_MEMORY_WITH_MEDIA

    @After
    fun clearViewModels() {
        viewModelStore.clear()
    }

    @Test
    fun `question countdown runs if no HTML media plays`() =
        runTest {
            val viewModel = startReviewer(secondsToShowQuestion = 5.0)
            enableAutoAdvance(viewModel)

            advance(4.seconds)
            assertFalse(viewModel.showingAnswer.value)
            advance(2.seconds)
            assertTrue(viewModel.showingAnswer.value)
        }

    @Test
    fun `countdown is held while HTML media plays and starts when it stops`() =
        runTest {
            val viewModel = startReviewer(secondsToShowQuestion = 5.0)
            viewModel.htmlMedia(isPlaying = true)
            enableAutoAdvance(viewModel)

            advance(60.seconds)
            assertFalse(viewModel.showingAnswer.value, "held while the media plays")

            viewModel.htmlMedia(isPlaying = false)
            advance(4.seconds)
            assertFalse(viewModel.showingAnswer.value, "the whole countdown runs after the media")
            advance(2.seconds)
            assertTrue(viewModel.showingAnswer.value)
        }

    @Test
    fun `HTML media starting during the countdown cancels it`() =
        runTest {
            val viewModel = startReviewer(secondsToShowQuestion = 5.0)
            enableAutoAdvance(viewModel)

            advance(3.seconds)
            viewModel.htmlMedia(isPlaying = true)
            advance(60.seconds)
            assertFalse(viewModel.showingAnswer.value)
        }

    @Test
    fun `countdown restarts after each clip of a sequence`() =
        runTest {
            val viewModel = startReviewer(secondsToShowQuestion = 5.0)
            enableAutoAdvance(viewModel)

            viewModel.htmlMedia(isPlaying = true)
            advance(10.seconds)
            viewModel.htmlMedia(isPlaying = false)
            // gap between the clips
            advance(1.seconds)
            viewModel.htmlMedia(isPlaying = true)
            advance(10.seconds)
            assertFalse(viewModel.showingAnswer.value, "the countdown of the gap was canceled")

            viewModel.htmlMedia(isPlaying = false)
            advance(4.seconds)
            assertFalse(viewModel.showingAnswer.value)
            advance(2.seconds)
            assertTrue(viewModel.showingAnswer.value)
        }

    @Test
    fun `out of order notifications are ignored`() =
        runTest {
            val viewModel = startReviewer(secondsToShowQuestion = 5.0)
            enableAutoAdvance(viewModel)

            viewModel.htmlMedia(isPlaying = true, sequence = 7)
            viewModel.htmlMedia(isPlaying = false, sequence = 6)
            advance(60.seconds)
            assertFalse(viewModel.showingAnswer.value, "the older 'stopped' didn't release the countdown")
        }

    @Test
    fun `HTML media does not hold the countdown if not waiting for audio`() =
        runTest {
            val viewModel = startReviewer(secondsToShowQuestion = 5.0, waitForAudio = false)
            viewModel.htmlMedia(isPlaying = true)
            enableAutoAdvance(viewModel)

            advance(6.seconds)
            assertTrue(viewModel.showingAnswer.value)
        }

    @Test
    fun `answer countdown waits for HTML media started by the answer`() =
        runTest {
            val viewModel = startReviewer(secondsToShowAnswer = 5.0, noteCount = 2)
            val firstCardId = viewModel.currentCard.await().id
            enableAutoAdvance(viewModel)

            showAnswer(viewModel)
            completeSoundTags(viewModel, CardSide.ANSWER)
            // the script of the template starts the clip shortly after the answer is shown
            advance(1.seconds)
            viewModel.htmlMedia(isPlaying = true)
            advance(60.seconds)
            assertEquals(firstCardId, viewModel.currentCard.await().id, "held while the media plays")

            viewModel.htmlMedia(isPlaying = false)
            advance(4.seconds)
            assertEquals(firstCardId, viewModel.currentCard.await().id)
            advance(2.seconds)
            assertNotEquals(firstCardId, viewModel.currentCard.await().id, "the answer action ran")
            assertFalse(viewModel.showingAnswer.value)
        }

    @Test
    fun `HTML media stopping before the sound tags does not start the countdown`() =
        runTest {
            val viewModel = startReviewer(secondsToShowAnswer = 5.0, noteCount = 2)
            val firstCardId = viewModel.currentCard.await().id
            enableAutoAdvance(viewModel)

            showAnswer(viewModel)
            viewModel.htmlMedia(isPlaying = true)
            viewModel.htmlMedia(isPlaying = false)
            advance(60.seconds)
            assertEquals(firstCardId, viewModel.currentCard.await().id, "the sound tags are still playing")

            completeSoundTags(viewModel, CardSide.ANSWER)
            advance(6.seconds)
            assertNotEquals(firstCardId, viewModel.currentCard.await().id)
        }

    @Test
    fun `media of the previous side holds the countdown until the page reports it as stopped`() =
        runTest {
            val viewModel = startReviewer(secondsToShowAnswer = 5.0, noteCount = 3)
            val firstCardId = viewModel.currentCard.await().id
            enableAutoAdvance(viewModel)

            // the question plays a clip, and the answer is shown before it ends
            viewModel.htmlMedia(isPlaying = true)
            showAnswer(viewModel)
            completeSoundTags(viewModel, CardSide.ANSWER)
            advance(60.seconds)
            assertEquals(firstCardId, viewModel.currentCard.await().id)

            // the page reports it once the question is removed
            viewModel.htmlMedia(isPlaying = false)
            advance(6.seconds)
            val secondCardId = viewModel.currentCard.await().id
            assertNotEquals(firstCardId, secondCardId)
            assertFalse(viewModel.showingAnswer.value)

            // only one answer action ran: the second card wasn't answered from its question
            advance(60.seconds)
            assertEquals(secondCardId, viewModel.currentCard.await().id)
        }

    @Test
    fun `HTML media does not resume auto advance after the screen is left`() =
        runTest {
            val viewModel = startReviewer(secondsToShowQuestion = 5.0)
            enableAutoAdvance(viewModel)
            viewModel.htmlMedia(isPlaying = true)

            // ReviewerFragment.onStop
            viewModel.stopAutoAdvance()
            viewModel.htmlMedia(isPlaying = false)
            advance(60.seconds)
            assertFalse(viewModel.showingAnswer.value)

            viewModel.htmlMedia(isPlaying = true)
            viewModel.htmlMedia(isPlaying = false)
            advance(60.seconds)
            assertFalse(viewModel.showingAnswer.value)
        }

    /**
     * Autoplay is disabled, so the sound tags aren't played from another thread, and the tests
     * are deterministic. Their completion is simulated with [completeSoundTags].
     */
    private fun TestScope.startReviewer(
        secondsToShowQuestion: Double = 0.0,
        secondsToShowAnswer: Double = 0.0,
        waitForAudio: Boolean = true,
        noteCount: Int = 1,
    ): ReviewerViewModel {
        updateDeckConfig(col.decks.selected()) {
            this.secondsToShowQuestion = secondsToShowQuestion
            this.secondsToShowAnswer = secondsToShowAnswer
            autoplay = false
            jsonObject.put("waitForAudio", waitForAudio)
        }
        repeat(noteCount) { addBasicNote("front $it", "back $it") }
        val viewModel = ReviewerViewModel(SavedStateHandle()).also { viewModelStore.put("reviewer", it) }
        viewModel.onPageFinished(false)
        runCurrent()
        assertFalse(viewModel.showingAnswer.value)
        return viewModel
    }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    /** The countdown of the current side starts, as no sound tags are playing */
    private fun TestScope.enableAutoAdvance(viewModel: ReviewerViewModel) {
        viewModel.executeAction(ViewerAction.TOGGLE_AUTO_ADVANCE)
        runCurrent()
        assertTrue(viewModel.isAutoAdvanceEnabledFlow.value)
    }

    private fun TestScope.showAnswer(viewModel: ReviewerViewModel) {
        viewModel.executeAction(ViewerAction.SHOW_ANSWER)
        runCurrent()
        assertTrue(viewModel.showingAnswer.value)
    }

    /**
     * Plays the `[sound:]` tags of [side]: there are none, so they complete at once,
     * which starts the countdown if 'Wait for audio' is enabled.
     */
    private suspend fun completeSoundTags(
        viewModel: ReviewerViewModel,
        side: CardSide,
    ) {
        val player = viewModel.cardMediaPlayer
        player.playAllForSide(side)
        // the listener is called in the job, from another thread
        player.playAvTagsJob?.join()
        player.playAvTagsJob = null
    }

    /** Simulates the notification sent by `ankidroid-reviewer.js` */
    private suspend fun ReviewerViewModel.htmlMedia(
        isPlaying: Boolean,
        sequence: Long = this@ReviewerViewModelHtmlMediaTest.sequence,
    ) {
        this@ReviewerViewModelHtmlMediaTest.sequence = maxOf(this@ReviewerViewModelHtmlMediaTest.sequence, sequence) + 1
        val body = JSONObject().put("isPlaying", isPlaying).put("sequence", sequence)
        handlePostRequest(PostRequestUri("/ankidroid/htmlMediaStateChanged"), body.toString().toByteArray())
    }
}
