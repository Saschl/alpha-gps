package com.sasch.cameragps.sharednew.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrollbarThumbTest {
    @Test
    fun hidesForEmptyContentOrContentThatFits() {
        assertNull(scrollbarThumb(0, 0, 0, 200f, 24f))
        assertNull(scrollbarThumb(0, 100, 200, 200f, 24f))
        assertNull(scrollbarThumb(0, 200, 200, 200f, 24f))
    }

    @Test
    fun hidesUntilMetricsAreKnown() {
        assertNull(scrollbarThumb(Int.MAX_VALUE, 1000, 200, 200f, 24f))
        assertNull(scrollbarThumb(0, Int.MAX_VALUE, 200, 200f, 24f))
        assertNull(scrollbarThumb(0, 1000, Int.MAX_VALUE, 200f, 24f))
        assertNull(scrollbarThumb(0, 1000, 200, 0f, 24f))
    }

    @Test
    fun thumbTracksStartMiddleAndEnd() {
        assertEquals(ScrollbarThumb(0f, 40f), scrollbarThumb(0, 1000, 200, 200f, 24f))
        assertEquals(ScrollbarThumb(80f, 40f), scrollbarThumb(400, 1000, 200, 200f, 24f))
        assertEquals(ScrollbarThumb(160f, 40f), scrollbarThumb(800, 1000, 200, 200f, 24f))
    }

    @Test
    fun longLogsKeepMinimumThumbAndReachBottom() {
        assertEquals(ScrollbarThumb(176f, 24f), scrollbarThumb(999800, 1000000, 200, 200f, 24f))
    }

    @Test
    fun staleOffsetsAfterContentChangesStayInsideTrack() {
        assertEquals(ScrollbarThumb(160f, 40f), scrollbarThumb(2000, 1000, 200, 200f, 24f))
        assertEquals(ScrollbarThumb(0f, 40f), scrollbarThumb(-10, 1000, 200, 200f, 24f))
    }

    @Test
    fun smallViewportsConstrainMinimumThumbSize() {
        assertEquals(ScrollbarThumb(0f, 10f), scrollbarThumb(800, 1000, 200, 10f, 24f))
    }
}
