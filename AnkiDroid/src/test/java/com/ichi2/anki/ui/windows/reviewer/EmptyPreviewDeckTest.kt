// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer

import androidx.core.content.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.preferences.reviewer.ViewerAction
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 'Empty preview decks on exit' setting of the study screen */
@RunWith(AndroidJUnit4::class)
class EmptyPreviewDeckTest : RobolectricTest() {
    private val viewModelStore = ViewModelStore()

    override fun getCollectionStorageMode() = CollectionStorageMode.IN_MEMORY_WITH_MEDIA

    @After
    fun clearViewModels() {
        viewModelStore.clear()
    }

    @Test
    fun `closing the screen returns the cards of the studied preview deck unchanged`() =
        runTest {
            setEmptyPreviewDecksOnExit(true)
            val decks = addHomeDeckWithFilteredDeck(reschedule = false)
            studyOneCardAndLeaveItInTheFilteredDeck(decks)

            closeStudyScreen()

            assertEquals(0, col.decks.cardCount(decks.filtered))
            assertEquals(decks.cardsBeforeFiltering, decks.cardStates())
            assertTrue(decks.cardStates().all { it.deckId == decks.home && it.originalDeckId == 0L })
        }

    @Test
    fun `closing the screen keeps the cards in the preview deck if the setting is disabled`() =
        runTest {
            setEmptyPreviewDecksOnExit(false)
            val decks = addHomeDeckWithFilteredDeck(reschedule = false)
            studyOneCardAndLeaveItInTheFilteredDeck(decks)

            closeStudyScreen()

            assertEquals(CARD_COUNT, col.decks.cardCount(decks.filtered))
        }

    @Test
    fun `closing the screen keeps the cards in a filtered deck that reschedules`() =
        runTest {
            setEmptyPreviewDecksOnExit(true)
            val decks = addHomeDeckWithFilteredDeck(reschedule = true)
            startReviewer()

            closeStudyScreen()

            assertEquals(CARD_COUNT, col.decks.cardCount(decks.filtered))
        }

    @Test
    fun `only filtered decks that do not reschedule are emptied`() =
        runTest {
            val preview = addHomeDeckWithFilteredDeck(reschedule = false, name = "Preview")
            val rescheduling = addHomeDeckWithFilteredDeck(reschedule = true, name = "Rescheduling")

            assertFalse(emptyPreviewDeck(preview.home), "a regular deck")
            assertFalse(emptyPreviewDeck(rescheduling.filtered))
            assertEquals(CARD_COUNT, col.decks.cardCount(rescheduling.filtered))

            assertTrue(emptyPreviewDeck(preview.filtered))
            assertEquals(0, col.decks.cardCount(preview.filtered))
            assertFalse(emptyPreviewDeck(preview.filtered), "already empty")
        }

    private data class CardState(
        val id: CardId,
        val deckId: DeckId,
        val originalDeckId: DeckId,
        val type: Int,
        val queue: Int,
        val due: Int,
        val interval: Int,
    )

    private inner class FilteredSetup(
        val home: DeckId,
        val filtered: DeckId,
        private val cardIds: List<CardId>,
        val cardsBeforeFiltering: List<CardState>,
    ) {
        fun cardStates() = cardIds.map { cardState(it) }
    }

    private fun cardState(id: CardId) =
        col.getCard(id).let { card ->
            CardState(
                id = card.id,
                deckId = card.did,
                originalDeckId = card.oDid,
                type = card.type.code,
                queue = card.queue.code,
                due = card.due,
                interval = card.ivl,
            )
        }

    /** Adds a deck with [CARD_COUNT] new cards, and a filtered deck with them, which is selected */
    private fun addHomeDeckWithFilteredDeck(
        reschedule: Boolean,
        name: String = "Listening",
    ): FilteredSetup {
        val home = addDeck("$name home")
        // no `[sound:]` tags are played from another thread
        updateDeckConfig(home) { autoplay = false }
        addNoteToDeck(home, count = CARD_COUNT)
        val cardIds = col.findCards("\"deck:$name home\"")
        assertEquals(CARD_COUNT, cardIds.size)
        val cardsBeforeFiltering = cardIds.map { cardState(it) }

        val filtered = addDynamicDeck("$name exercise", search = "\"deck:$name home\"")
        val deck = col.decks.getLegacy(filtered)!!
        deck.resched = reschedule
        col.decks.save(deck)
        col.sched.rebuildFilteredDeck(filtered)
        assertEquals(CARD_COUNT, col.decks.cardCount(filtered))
        return FilteredSetup(home, filtered, cardIds, cardsBeforeFiltering)
    }

    private fun setEmptyPreviewDecksOnExit(value: Boolean) {
        targetContext.sharedPrefs().edit {
            putBoolean(targetContext.getString(R.string.empty_preview_decks_on_exit_key), value)
        }
    }

    private fun TestScope.startReviewer(): ReviewerViewModel {
        val viewModel = ReviewerViewModel(SavedStateHandle()).also { viewModelStore.put("reviewer", it) }
        viewModel.onPageFinished(false)
        runCurrent()
        return viewModel
    }

    /** Answers a card with 'Hard', which shows it again in the filtered deck after a delay */
    private suspend fun TestScope.studyOneCardAndLeaveItInTheFilteredDeck(decks: FilteredSetup) {
        val viewModel = startReviewer()
        val cardId = viewModel.currentCard.await().id
        viewModel.executeAction(ViewerAction.SHOW_ANSWER)
        runCurrent()
        viewModel.executeAction(ViewerAction.ANSWER_HARD)
        runCurrent()

        assertNotEquals(cardId, viewModel.currentCard.await().id, "the next card is shown")
        assertEquals(CARD_COUNT, col.decks.cardCount(decks.filtered), "the answered card stays in the filtered deck")
        assertNotEquals(decks.cardsBeforeFiltering, decks.cardStates())
    }

    private fun TestScope.closeStudyScreen() {
        // calls ViewModel.onCleared()
        viewModelStore.clear()
        runCurrent()
    }

    companion object {
        private const val CARD_COUNT = 2
    }
}
