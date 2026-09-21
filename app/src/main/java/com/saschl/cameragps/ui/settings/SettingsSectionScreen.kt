package com.saschl.cameragps.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsColumn
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsScreen
import com.saschl.cameragps.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSectionScreen(
    title: String,
    onBackClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    SharedSettingsScreen(
        title = title,
        onBackClick = onBackClick,
        onTitleClick = {},
        navigationIcon = {
            Icon(
                painterResource(R.drawable.arrow_back_24px),
                contentDescription = null
            )
        }
    ) { paddingValues ->
        SharedSettingsColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            content()
        }
    }
}

