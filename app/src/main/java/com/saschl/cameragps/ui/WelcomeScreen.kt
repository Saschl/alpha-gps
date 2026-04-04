package com.saschl.cameragps.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.always_on_quickstart
import cameragps.sharednew.generated.resources.always_on_quickstart_description
import cameragps.sharednew.generated.resources.quick_start_feature_description
import cameragps.sharednew.generated.resources.quick_start_feature_title
import cameragps.sharednew.generated.resources.welcome_feature_connect_description
import cameragps.sharednew.generated.resources.welcome_feature_connect_title
import cameragps.sharednew.generated.resources.welcome_feature_gps_sync_description
import cameragps.sharednew.generated.resources.welcome_feature_gps_sync_title
import cameragps.sharednew.generated.resources.welcome_get_started_button
import cameragps.sharednew.generated.resources.welcome_settings_note
import cameragps.sharednew.generated.resources.welcome_subtitle
import cameragps.sharednew.generated.resources.welcome_title
import com.sasch.cameragps.sharednew.ui.welcome.SharedWelcomeScreen
import com.sasch.cameragps.sharednew.ui.welcome.WelcomeFeature
import com.saschl.cameragps.R
import org.jetbrains.compose.resources.stringResource

@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    SharedWelcomeScreen(
        title = stringResource(Res.string.welcome_title),
        subtitle = stringResource(Res.string.welcome_subtitle),
        getStartedText = stringResource(Res.string.welcome_get_started_button),
        settingsNote = stringResource(Res.string.welcome_settings_note),
        firstStepFeatures = listOf(
            WelcomeFeature(
                title = stringResource(Res.string.welcome_feature_connect_title),
                description = stringResource(Res.string.welcome_feature_connect_description),
            ),
            WelcomeFeature(
                title = stringResource(Res.string.welcome_feature_gps_sync_title),
                description = stringResource(Res.string.welcome_feature_gps_sync_description),
            ),
        ),
        secondStepFeatures = listOf(
            WelcomeFeature(
                title = stringResource(Res.string.quick_start_feature_title),
                description = stringResource(Res.string.quick_start_feature_description),
            ),
            WelcomeFeature(
                title = stringResource(Res.string.always_on_quickstart),
                description = stringResource(Res.string.always_on_quickstart_description),
            ),
        ),
        onGetStarted = onGetStarted,
        iconContent = {
            Image(
                painter = painterResource(R.drawable.baseline_photo_camera_24),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
    )
}
