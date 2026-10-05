package com.saschl.cameragps.ui.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.background_location_required_error
import cameragps.sharednew.generated.resources.step2_title
import com.saschl.cameragps.ui.EnhancedLocationPermissionBox
import com.saschl.cameragps.utils.PreferencesManager
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LocationPermissionBoxTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var previouslyIgnored = false

    @Before
    fun grantForegroundPermissions() {
        previouslyIgnored = PreferencesManager.isPermissionsIgnored(context)
        PreferencesManager.setPermissionsIgnored(context, false)
        val permissions = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissions.forEach {
            val result =
                instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $it")
            ParcelFileDescriptor.AutoCloseInputStream(result).bufferedReader().use { output ->
                assertEquals("", output.readText().trim())
            }
        }
    }

    @After
    fun restoreIgnoredPreference() {
        PreferencesManager.setPermissionsIgnored(context, previouslyIgnored)
    }

    @Test
    fun foregroundGrantRequiresSeparateBackgroundAccessOnlyFromAndroid10() {
        assertEquals(
            "Run with background location ungranted to exercise the permission gate",
            PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ),
        )
        lateinit var backgroundStep: String
        lateinit var backgroundError: String
        compose.setContent {
            backgroundStep = stringResource(Res.string.step2_title)
            backgroundError = stringResource(Res.string.background_location_required_error)
            MaterialTheme {
                EnhancedLocationPermissionBox { Text("Permission-gated content") }
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            compose.onNodeWithText("Permission-gated content").assertIsDisplayed()
            compose.onNodeWithText(backgroundStep).assertDoesNotExist()
            compose.onNodeWithText(backgroundError).assertDoesNotExist()
        } else {
            compose.onNodeWithText("Permission-gated content").assertDoesNotExist()
            compose.onNodeWithText(backgroundStep).assertIsDisplayed()
        }
    }
}
