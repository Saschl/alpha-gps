package com.sasch.cameragps.sharednew.ui.dialog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialogQueueTest {
    @Test
    fun dialogsWaitUntilTheHeadIsRemoved() {
        val queue = DialogQueue<String>()
        assertNull(queue.activeDialog)
        assertTrue(queue.isEmpty)

        queue.enqueue("consent")
        queue.enqueue("release notes")
        queue.enqueue("donation")
        assertEquals("consent", queue.activeDialog)
        assertFalse(queue.isEmpty)

        queue.remove("consent")
        assertEquals("release notes", queue.activeDialog)
        queue.remove("release notes")
        assertEquals("donation", queue.activeDialog)
        queue.remove("donation")
        assertNull(queue.activeDialog)
        assertTrue(queue.isEmpty)
    }

    @Test
    fun repeatedRequestsDoNotDuplicateOrReorderDialogs() {
        val queue = DialogQueue<String>()
        queue.enqueue("first")
        queue.enqueue("second")
        queue.enqueue("first")
        queue.enqueue("second")

        queue.remove("first")
        assertEquals("second", queue.activeDialog)
        queue.remove("second")
        assertTrue(queue.isEmpty)
    }

    @Test
    fun obsoleteWaitingRequestIsSkipped() {
        val queue = DialogQueue<String>()
        queue.enqueue("migration")
        queue.enqueue("location permission")
        queue.enqueue("release notes")

        queue.remove("location permission")
        assertEquals("migration", queue.activeDialog)
        queue.remove("migration")
        assertEquals("release notes", queue.activeDialog)
    }

    @Test
    fun repeatedRemovalCannotDismissTheNextDialog() {
        val queue = DialogQueue<String>()
        queue.enqueue("first")
        queue.enqueue("second")

        queue.remove("first")
        queue.remove("first")
        queue.remove("unknown")
        assertEquals("second", queue.activeDialog)
    }

    @Test
    fun newRequestForRemovedDialogGoesToTheBack() {
        val queue = DialogQueue<String>()
        queue.enqueue("location")
        queue.enqueue("release notes")
        queue.remove("location")
        queue.enqueue("location")

        assertEquals("release notes", queue.activeDialog)
        queue.remove("release notes")
        assertEquals("location", queue.activeDialog)
    }

    @Test
    fun dismissOnlyClosesTheExpectedActiveRequest() {
        val queue = DialogQueue<String>()
        queue.enqueue("first")
        queue.enqueue("second")

        queue.dismiss("second")
        assertEquals("first", queue.activeDialog)
        queue.dismiss("first")
        queue.dismiss("first")
        assertEquals("second", queue.activeDialog)
        queue.dismiss("second")
        assertTrue(queue.isEmpty)
    }

    @Test
    fun obsoletePayloadsCanBeCancelledWithoutRemovingOtherRequests() {
        data class Failure(val deviceName: String)

        val queue = DialogQueue<Any>()
        queue.enqueue("consent")
        queue.enqueue(Failure("old camera"))
        queue.enqueue("release notes")
        queue.removeAll { it is Failure }
        queue.enqueue(Failure("new camera"))

        queue.dismiss("consent")
        assertEquals("release notes", queue.activeDialog)
        queue.dismiss("release notes")
        assertEquals(Failure("new camera"), queue.activeDialog)
    }
}
