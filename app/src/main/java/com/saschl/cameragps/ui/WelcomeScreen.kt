package com.saschl.cameragps.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.saschl.cameragps.R
import com.saschl.cameragps.shared.ui.welcome.SharedWelcomeScreen
import com.saschl.cameragps.shared.ui.welcome.WelcomeFeature

@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    SharedWelcomeScreen(
        title = stringResource(R.string.welcome_title),
        subtitle = stringResource(R.string.welcome_subtitle),
        getStartedText = stringResource(R.string.welcome_get_started_button),
        settingsNote = stringResource(R.string.welcome_settings_note),
        firstStepFeatures = listOf(
            WelcomeFeature(
                title = stringResource(R.string.welcome_feature_connect_title),
                description = stringResource(R.string.welcome_feature_connect_description),
            ),
            WelcomeFeature(
                title = stringResource(R.string.welcome_feature_gps_sync_title),
                description = stringResource(R.string.welcome_feature_gps_sync_description),
            ),
        ),
        secondStepFeatures = listOf(
            WelcomeFeature(
                title = stringResource(R.string.quick_start_feature_title),
                description = stringResource(R.string.quick_start_feature_description),
            ),
            WelcomeFeature(
                title = stringResource(R.string.always_on_quickstart),
                description = stringResource(R.string.always_on_quickstart_description),
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
