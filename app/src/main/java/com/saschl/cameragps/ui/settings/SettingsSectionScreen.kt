package com.saschl.cameragps.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { content() }
        }
    }
}

