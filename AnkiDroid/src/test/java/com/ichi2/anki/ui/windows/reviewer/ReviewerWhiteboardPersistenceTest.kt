// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer

import android.graphics.Path
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.preferences.reviewer.ViewerAction
import com.ichi2.anki.previewer.CardViewerActivity
import com.ichi2.anki.ui.windows.reviewer.whiteboard.WhiteboardAttachment
import com.ichi2.anki.ui.windows.reviewer.whiteboard.WhiteboardDraftStore
import com.ichi2.anki.ui.windows.reviewer.whiteboard.WhiteboardFragment
import com.ichi2.anki.ui.windows.reviewer.whiteboard.WhiteboardPersistence
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.io.File
import kotlin.reflect.jvm.jvmName
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReviewerWhiteboardPersistenceTest : RobolectricTest() {
    override fun getCollectionStorageMode() = CollectionStorageMode.ON_DISK

    @Test
    fun drawingSurvivesLeavingReviewerAndReturning() =
        runTest {
            addBasicNote()
            StudyScreenRepository().isWhiteboardEnabled = true
            withReviewer { _, whiteboard ->
                whiteboard.viewModel.addPath(line(80f))
                assertEquals(1, whiteboard.drawingActions().size)
            }
            withReviewer { _, whiteboard ->
                assertEquals(1, whiteboard.drawingActions().size)
                whiteboard.viewModel.clearCanvas()
            }
            withReviewer { _, whiteboard ->
                assertTrue(whiteboard.drawingActions().isEmpty(), "Clearing must survive exiting too")
            }
        }

    @Test
    fun navigatingAndUndoingDoesNotMixDrawings() =
        runTest {
            addBasicNote("First question")
            addBasicNote("Second question")
            StudyScreenRepository().isWhiteboardEnabled = true
            withReviewer { reviewer, whiteboard ->
                val firstId = reviewer.viewModel.getCardId()
                whiteboard.viewModel.addPath(line(80f))
                reviewer.viewModel.onShowAnswer()
                advanceUntilIdle()
                reviewer.viewModel.answerCard(Rating.GOOD)
                advanceUntilIdle()
                advanceRobolectricLooper()
                assertTrue(reviewer.viewModel.getCardId() != firstId)
                assertTrue(whiteboard.drawingActions().isEmpty())
                whiteboard.viewModel.addPath(line(40f))
                whiteboard.viewModel.addPath(line(60f))

                reviewer.viewModel.executeAction(ViewerAction.UNDO)
                advanceUntilIdle()
                advanceRobolectricLooper()
                assertEquals(firstId, reviewer.viewModel.getCardId())
                assertEquals(1, whiteboard.drawingActions().size, "The first card must retain only its own drawing")
                whiteboard.viewModel.undo()
                assertEquals(1, whiteboard.drawingActions().size, "The next card's undo history must not leak")
            }
        }

    @Test
    fun savingADrawingKeepsAnsweringAndReviewUndoWorking() =
        runTest {
            addBasicNote("First question")
            addBasicNote("Second question")
            StudyScreenRepository().isWhiteboardEnabled = true
            withReviewer { reviewer, whiteboard ->
                val card = reviewer.viewModel.currentCard.await()
                whiteboard.viewModel.addPath(line(80f))
                val store = WhiteboardDraftStore(File(targetContext.applicationContext.filesDir, "whiteboard-drafts"))
                val target = WhiteboardPersistence.bind(card, store).target
                WhiteboardPersistence.enableSync(getPreferences(), target, "Back", store)
                WhiteboardPersistence.syncNow(target, store, getPreferences(), reviewer.viewModel)
                advanceUntilIdle()
                assertNotNull(WhiteboardAttachment.find(col.getNote(card.nid).fields, card.ord))

                reviewer.viewModel.onShowAnswer()
                advanceUntilIdle()
                reviewer.viewModel.answerCard(Rating.GOOD)
                advanceUntilIdle()
                advanceRobolectricLooper()
                assertTrue(reviewer.viewModel.getCardId() != card.id)

                reviewer.viewModel.executeAction(ViewerAction.UNDO)
                advanceUntilIdle()
                advanceRobolectricLooper()
                assertEquals(card.id, reviewer.viewModel.getCardId())
                assertEquals(1, whiteboard.drawingActions().size)
            }
        }

    private fun line(end: Float): Path =
        Path().apply {
            moveTo(10f, 10f)
            lineTo(end, end)
        }

    private suspend fun TestScope.withReviewer(block: suspend TestScope.(ReviewerFragment, WhiteboardFragment) -> Unit) {
        val intent = ReviewerFragment.getIntent(targetContext)
        Robolectric.buildActivity(CardViewerActivity::class.java, intent).use { controller ->
            controller.setup().windowFocusChanged(true)
            advanceUntilIdle()
            advanceRobolectricLooper()
            val reviewer = assertIs<ReviewerFragment>(controller.get().fragment)
            reviewer.viewModel.onPageFinished(false)
            advanceUntilIdle()
            advanceRobolectricLooper()
            val whiteboard =
                assertIs<WhiteboardFragment>(reviewer.childFragmentManager.findFragmentByTag(WhiteboardFragment::class.jvmName))
            whiteboard.binding.whiteboardView.layout(0, 0, 300, 400)
            advanceUntilIdle()
            assertTrue(whiteboard.binding.whiteboardView.isEnabled)
            block(reviewer, whiteboard)
        }
    }
}
