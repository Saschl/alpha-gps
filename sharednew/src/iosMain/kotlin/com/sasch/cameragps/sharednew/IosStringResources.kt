package com.sasch.cameragps.sharednew

import androidx.compose.runtime.Composable
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.always_on_quickstart
import cameragps.sharednew.generated.resources.always_on_quickstart_description
import cameragps.sharednew.generated.resources.quick_start_feature_description
import cameragps.sharednew.generated.resources.quick_start_feature_title
import cameragps.sharednew.generated.resources.welcome_feature_connect_description
import cameragps.sharednew.generated.resources.welcome_feature_connect_title
import cameragps.sharednew.generated.resources.welcome_feature_gps_sync_description
import cameragps.sharednew.generated.resources.welcome_feature_gps_sync_title
import com.sasch.cameragps.sharednew.ui.welcome.WelcomeFeature
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun firstStepFeatures(): List<WelcomeFeature> = listOf(
    WelcomeFeature(
        title = stringResource(Res.string.welcome_feature_connect_title),
        description = stringResource(Res.string.welcome_feature_connect_description),
    ),
    WelcomeFeature(
        title = stringResource(Res.string.welcome_feature_gps_sync_title),
        description = stringResource(Res.string.welcome_feature_gps_sync_description),
    ),
)

@Composable
internal fun secondStepFeatures(): List<WelcomeFeature> = listOf(
    WelcomeFeature(
        title = stringResource(Res.string.quick_start_feature_title),
        description = stringResource(Res.string.quick_start_feature_description),
    ),
    WelcomeFeature(
        title = stringResource(Res.string.always_on_quickstart),
        description = stringResource(Res.string.always_on_quickstart_description),
    ),
)


