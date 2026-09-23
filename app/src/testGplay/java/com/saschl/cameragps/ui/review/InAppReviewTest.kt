package com.saschl.cameragps.ui.review

import com.google.android.play.core.review.ReviewException
import com.google.android.play.core.review.model.ReviewErrorCode
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

class InAppReviewTest {
    private val tree = RecordingTree()

    @Before
    fun setUp() {
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
    }

    @Test
    fun completionHandlerFailureIsLoggedWithItsCauseAndResetsTheAttempt() {
        val cause = IllegalStateException("Completion callback failed")
        val job = Job()
        job.invokeOnCompletion { throw cause }
        val error = runCatching { job.complete() }.exceptionOrNull() as Exception
        assertEquals("CompletionHandlerException", error.javaClass.simpleName)

        assertFailureHandled(error)

        assertSame(cause, tree.entries.single().error?.cause)
        assertTrue(tree.entries.single().message.contains("unavailable"))
    }

    @Test
    fun reviewFailureRetainsThePlayErrorCodeAndResetsTheAttempt() {
        val error = ReviewException(ReviewErrorCode.PLAY_STORE_NOT_FOUND)

        assertFailureHandled(error)

        assertTrue(tree.entries.single().message.contains("review error code: ${error.errorCode}"))
    }

    @Test
    fun missingExceptionStillLogsAndResetsTheAttempt() {
        assertFailureHandled(null)

        assertTrue(tree.entries.single().message.contains("unavailable"))
    }

    private fun assertFailureHandled(error: Exception?) {
        var resetCount = 0
        handleReviewRequestFailure(error) { resetCount++ }
        assertEquals(1, resetCount)
        assertSame(error, tree.entries.single().error)
    }

    private data class Entry(val message: String, val error: Throwable?)

    private class RecordingTree : Timber.Tree() {
        val entries = mutableListOf<Entry>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            entries += Entry(message, t)
        }
    }
}
