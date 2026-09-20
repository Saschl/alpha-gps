package com.saschl.cameragps

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsColumnLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun differentlySizedCardsKeepScrollRangeStableWhenScrolled() {
        lateinit var state: ScrollState
        compose.setContent {
            state = rememberScrollState()
            MaterialTheme {
                SharedSettingsColumn(Modifier.size(300.dp, 300.dp), state = state) {
                    repeat(8) { index ->
                        Box(Modifier
                            .fillMaxWidth()
                            .height((80 + index * 30).dp)) {
                            Text("Setting $index")
                        }
                    }
                }
            }
        }
        var initialRange = 0
        compose.runOnIdle {
            initialRange = state.maxValue
            assertTrue(initialRange > 0)
        }
        compose.onNodeWithText("Setting 7").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(state.value > 0)
            assertEquals(initialRange, state.maxValue)
        }
        compose.onNodeWithText("Setting 0").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(initialRange, state.maxValue) }
    }

    @Test
    fun cardsAreInsetInsideTheFullWidthScrollViewport() {
        compose.setContent {
            MaterialTheme {
                SharedSettingsColumn(Modifier
                    .size(300.dp, 300.dp)
                    .testTag("viewport")) {
                    Box(Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .testTag("card"))
                }
            }
        }
        val viewport = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        val card = compose.onNodeWithTag("card").getUnclippedBoundsInRoot()
        assertEquals(16.dp, card.left - viewport.left)
        assertEquals(16.dp, viewport.right - card.right)
    }
}
