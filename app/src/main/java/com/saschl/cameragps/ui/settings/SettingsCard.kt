package com.saschl.cameragps.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.saschl.cameragps.shared.ui.settings.SharedSettingsCard

/** Thin Android alias – delegates directly to the shared KMP implementation. */
@Composable
internal fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = SharedSettingsCard(title = title, modifier = modifier, content = content)
