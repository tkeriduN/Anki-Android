// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.jsapi.legacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidJsAPIConstants
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.pages.PostRequestUri
import com.ichi2.anki.previewer.CardViewerActivity
import com.ichi2.anki.ui.windows.reviewer.ReviewerFragment
import com.ichi2.anki.ui.windows.reviewer.ReviewerViewModel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import kotlin.test.assertEquals

/**
 * Card templates use `ankiGetCardODid()` to tell whether a card is shown from a filtered deck.
 */
@RunWith(AndroidJUnit4::class)
class LegacyJsApiFilteredDeckTest : RobolectricTest() {
    override fun getCollectionStorageMode() = CollectionStorageMode.IN_MEMORY_WITH_MEDIA

    @Test
    fun `card in its home deck has no original deck`() =
        runTest {
            val homeDeckId = addDeck("Language::Home", setAsSelected = true)
            addNoteToDeck(homeDeckId)
            withReviewer { reviewer ->
                assertEquals(0L, reviewer.request("cardODid").getLong("value"))
                assertEquals(homeDeckId, reviewer.request("cardDid").getLong("value"))
                assertEquals("Home", reviewer.request("deckName").getString("value"))
            }
        }

    @Test
    fun `card in a filtered deck reports its home deck as the original deck`() =
        runTest {
            val homeDeckId = addDeck("Language::Home")
            addNoteToDeck(homeDeckId)
            // selects the filtered deck
            val filteredDeckId = addDynamicDeck("Exercise::Listening", search = "\"deck:Language::Home\"")
            withReviewer { reviewer ->
                assertEquals(homeDeckId, reviewer.request("cardODid").getLong("value"))
                assertEquals(filteredDeckId, reviewer.request("cardDid").getLong("value"))
                assertEquals("Listening", reviewer.request("deckName").getString("value"))
            }
        }

    private suspend fun ReviewerViewModel.request(method: String): JSONObject {
        val contract =
            JSONObject()
                .put("version", AnkiDroidJsAPIConstants.CURRENT_JS_API_VERSION)
                .put("developer", "test@example.com")
                .put("data", "")
        val response = handlePostRequest(PostRequestUri(PostRequestUri.ANKIDROID_JS_PREFIX + method), contract.toString().toByteArray())
        return JSONObject(response.decodeToString())
    }

    private suspend fun TestScope.withReviewer(block: suspend (ReviewerViewModel) -> Unit) {
        Robolectric.buildActivity(CardViewerActivity::class.java, ReviewerFragment.getIntent(targetContext)).use { controller ->
            controller.setup()
            advanceUntilIdle()
            val reviewer = controller.get().fragment as ReviewerFragment
            reviewer.viewModel.onPageFinished(false)
            advanceUntilIdle()
            block(reviewer.viewModel)
        }
    }
}
