package com.saschl.cameragps.ui.welcome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.welcome_get_started_button
import cameragps.sharednew.generated.resources.welcome_gps_body
import cameragps.sharednew.generated.resources.welcome_gps_title
import cameragps.sharednew.generated.resources.welcome_next
import cameragps.sharednew.generated.resources.welcome_pair_body
import cameragps.sharednew.generated.resources.welcome_pair_title
import cameragps.sharednew.generated.resources.welcome_ready_body
import cameragps.sharednew.generated.resources.welcome_ready_title
import cameragps.sharednew.generated.resources.welcome_remote_body
import cameragps.sharednew.generated.resources.welcome_remote_title
import cameragps.sharednew.generated.resources.welcome_skip
import com.sasch.cameragps.sharednew.ui.welcome.SharedWelcomeScreen
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WelcomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var next: String
    private lateinit var skip: String
    private lateinit var finish: String
    private lateinit var titles: List<String>
    private lateinit var bodies: List<String>
    private lateinit var readyBody: String
    private var completions = 0

    private fun showWelcome(
        compact: Boolean = false,
        viewport: DpSize? = null,
    ): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            next = stringResource(Res.string.welcome_next)
            skip = stringResource(Res.string.welcome_skip)
            finish = stringResource(Res.string.welcome_get_started_button)
            titles = listOf(
                stringResource(Res.string.welcome_gps_title),
                stringResource(Res.string.welcome_remote_title),
                stringResource(Res.string.welcome_pair_title),
                stringResource(Res.string.welcome_ready_title),
            )
            bodies = listOf(
                stringResource(Res.string.welcome_gps_body),
                stringResource(Res.string.welcome_remote_body),
                stringResource(Res.string.welcome_pair_body),
                stringResource(Res.string.welcome_ready_body),
            )
            readyBody = bodies.last()
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density.density,
                    if (compact) 1.5f else 1f
                )
            ) {
                MaterialTheme {
                    Box(
                        when {
                            viewport != null -> Modifier.size(viewport)
                            compact -> Modifier.size(320.dp, 480.dp)
                            else -> Modifier
                        }
                    ) {
                        SharedWelcomeScreen(onGetStarted = { completions++ })
                    }
                }
            }
        }
        return restoration
    }

    @Test
    fun nextAndSwipeBackOnlyCompleteOnTheLastPage() {
        showWelcome()
        compose.onNodeWithText(titles[0]).assertIsDisplayed()
        compose.onNodeWithText(next).performClick()
        compose.onNodeWithText(titles[1]).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .performTouchInput { swipeRight() }
        compose.onNodeWithText(titles[0]).assertIsDisplayed()
        repeat(3) { compose.onNodeWithText(next).performClick() }
        compose.onNodeWithText(titles[3]).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, completions) }
        compose.onNodeWithText(finish).performClick()
        compose.runOnIdle { assertEquals(1, completions) }
    }

    @Test
    fun swipingBothWaysAndRestoringKeepTheCurrentPage() {
        val restoration = showWelcome()
        val pager =
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        pager.performTouchInput { swipeLeft() }
        compose.onNodeWithText(titles[1]).assertIsDisplayed()
        pager.performTouchInput { swipeLeft() }
        compose.onNodeWithText(titles[2]).assertIsDisplayed()
        pager.performTouchInput { swipeRight() }
        compose.onNodeWithText(titles[1]).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(titles[1]).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, completions) }
    }

    @Test
    fun skipCompletesWithoutRequiringEveryPage() {
        showWelcome()
        compose.onNodeWithText(skip).performClick()
        compose.runOnIdle { assertEquals(1, completions) }
    }

    @Test
    fun everySlideIsCenteredAndFitsAnIphoneSeSizedViewportWithoutScrolling() {
        showWelcome(viewport = DpSize(375.dp, 627.dp))
        titles.indices.forEach { index ->
            compose.onNodeWithText(titles[index]).assertIsDisplayed()
            val slide = compose.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                        hasAnyDescendant(hasText(titles[index])),
            )
            val bounds = slide.getUnclippedBoundsInRoot()
            val center = (bounds.top + bounds.bottom) / 2
            val title = compose.onNodeWithText(titles[index]).getUnclippedBoundsInRoot()
            val body = compose.onNodeWithText(bodies[index]).getUnclippedBoundsInRoot()
            assertTrue(
                "Slide $index should span the center",
                title.top < center && body.bottom > center
            )
            val range =
                slide.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            compose.runOnIdle {
                assertEquals("Slide $index should fit without scrolling", 0f, range.maxValue(), 0f)
            }
            compose.onNodeWithText(if (index == titles.lastIndex) finish else next)
                .assertIsDisplayed()
            if (index < titles.lastIndex) compose.onNodeWithText(next).performClick()
        }
    }

    @Test
    fun tallScreenBalancesContentBetweenHeaderAndNavigation() {
        showWelcome(viewport = DpSize(412.dp, 860.dp))
        titles.indices.forEach { index ->
            val slide = compose.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                        hasAnyDescendant(hasText(titles[index])),
            ).getUnclippedBoundsInRoot()
            val title = compose.onNodeWithText(titles[index]).getUnclippedBoundsInRoot()
            val body = compose.onNodeWithText(bodies[index]).getUnclippedBoundsInRoot()
            assertTrue(
                "Slide $index should use the space on a tall screen",
                title.top - slide.top > 80.dp
            )
            assertTrue(
                "Slide $index should span the center of the available space",
                title.top < (slide.top + slide.bottom) / 2
            )
            assertTrue(
                "Slide $index should not leave the lower half empty",
                body.bottom > (slide.top + slide.bottom) / 2
            )
            compose.onNodeWithText(if (index == titles.lastIndex) finish else next)
                .assertIsDisplayed()
            if (index < titles.lastIndex) compose.onNodeWithText(next).performClick()
        }
    }

    @Test
    fun compactScreenWithLargeTextKeepsNavigationAndCopyReachable() {
        showWelcome(compact = true)
        repeat(3) { compose.onNodeWithText(next).assertIsDisplayed().performClick() }
        compose.onNodeWithText(readyBody).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(finish).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, completions) }
    }
}
