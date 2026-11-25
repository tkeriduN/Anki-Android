// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer

import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.observability.undoableOp
import timber.log.Timber

/**
 * Returns the cards of [deckId] to their home decks, if it is a filtered deck
 * that doesn't reschedule cards based on the answers ("preview").
 *
 * Cards answered in such a deck stay in it until their preview delay passes,
 * so they are missing from their home decks until the deck is emptied.
 * The scheduling of the cards isn't changed by a preview, so nothing is lost by emptying it.
 *
 * Other decks aren't changed: emptying a deck that reschedules would discard
 * the learning steps of its cards.
 *
 * @return whether the deck was emptied
 */
suspend fun emptyPreviewDeck(deckId: DeckId): Boolean {
    val isPreviewDeckWithCards =
        withCol {
            val deck = decks.getLegacy(deckId) ?: return@withCol false
            deck.isFiltered && !deck.resched && !decks.isEmpty(deckId)
        }
    if (!isPreviewDeckWithCards) return false
    Timber.i("emptying preview deck %d", deckId)
    undoableOp { sched.emptyFilteredDeck(deckId) }
    return true
}
