package com.saschl.cameragps.ui.device

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sasch.cameragps.sharednew.remote.wifi.PhotoDateRange
import com.sasch.cameragps.sharednew.ui.remote.PhotoDateFilter
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import kotlin.time.Clock

class WifiPhotoDateFilterTest {
    @get:Rule val compose = createComposeRule()
    private val selected = mutableStateOf<PhotoDateRange?>(null)

    private fun show() {
        compose.setContent {
            MaterialTheme {
                PhotoDateFilter(selected.value, enabled = true) { selected.value = it }
            }
        }
    }

    @Test
    fun todayAndAllDatesApplyDirectly() {
        show()
        compose.onNodeWithText("All dates").performClick()
        compose.onNodeWithText("Today").performClick()
        val day = Clock.System.todayIn(TimeZone.currentSystemDefault())
        compose.runOnIdle { assertEquals(PhotoDateRange(day, day), selected.value) }
        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("All dates").performClick()
        compose.runOnIdle { assertNull(selected.value) }
    }

    @Test
    fun cancellingAndApplyingCalendarEdits() {
        val original = PhotoDateRange(LocalDate(2026, 11, 2), LocalDate(2026, 11, 5))
        selected.value = original
        show()
        fun edit() {
            compose.onNodeWithText("2026-11-02 – 2026-11-05").performClick()
            compose.onNodeWithText("Choose dates…").performClick()
            compose.onNodeWithText("November 8", substring = true).performClick()
            compose.onNodeWithText("November 9", substring = true).performClick()
        }
        edit()
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(original, selected.value) }
        edit()
        compose.onNodeWithText("Show photos").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(PhotoDateRange(LocalDate(2026, 11, 8), LocalDate(2026, 11, 9)), selected.value)
        }
    }
}
