package com.saschl.cameragps.ui.device

import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WifiRemoteScreenAwakeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun activeSessionKeepsScreenOnAndDisconnectRestoresTimeout() {
        val active = mutableStateOf(false)
        val visible = mutableStateOf(true)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            if (visible.value) KeepScreenOnWhileRemoteActive(active.value)
        }
        compose.runOnIdle {
            assertFalse(view.keepScreenOn)
            active.value = true
        }
        compose.runOnIdle {
            assertTrue(view.keepScreenOn)
            active.value = false
        }
        compose.runOnIdle {
            assertFalse(view.keepScreenOn)
            active.value = true
        }
        compose.runOnIdle {
            assertTrue(view.keepScreenOn)
            visible.value = false
        }
        compose.runOnIdle { assertFalse(view.keepScreenOn) }
    }

    @Test
    fun leavingRemotePreservesAnExistingScreenOnRequest() {
        val active = mutableStateOf(false)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            KeepScreenOnWhileRemoteActive(active.value)
        }
        compose.runOnIdle {
            view.keepScreenOn = true
            active.value = true
        }
        compose.runOnIdle { active.value = false }
        compose.runOnIdle {
            assertTrue(view.keepScreenOn)
            view.keepScreenOn = false
        }
    }
}
