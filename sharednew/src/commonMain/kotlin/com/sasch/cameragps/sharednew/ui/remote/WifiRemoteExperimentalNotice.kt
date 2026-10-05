package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.cancel_button
import cameragps.sharednew.generated.resources.continue_button
import cameragps.sharednew.generated.resources.wifi_remote_experimental_message
import cameragps.sharednew.generated.resources.wifi_remote_experimental_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WifiRemoteExperimentalNotice(onContinue: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.wifi_remote_experimental_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(Res.string.wifi_remote_experimental_message))
                val linkStyle = TextLinkStyles(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )
                )
                Text(buildAnnotatedString {
                    withLink(LinkAnnotation.Url("mailto:saschl.ra@web.de", linkStyle)) {
                        append("saschl.ra@web.de")
                    }
                    append("\n")
                    withLink(
                        LinkAnnotation.Url(
                            "https://github.com/Saschl/camera-gps/issues",
                            linkStyle
                        )
                    ) {
                        append("GitHub")
                    }
                })
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) { Text(stringResource(Res.string.continue_button)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel_button)) }
        },
    )
}
