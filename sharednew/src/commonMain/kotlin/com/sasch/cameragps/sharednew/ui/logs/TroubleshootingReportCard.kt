package com.sasch.cameragps.sharednew.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.report_copied
import cameragps.sharednew.generated.resources.report_copy
import cameragps.sharednew.generated.resources.report_description
import cameragps.sharednew.generated.resources.report_error
import cameragps.sharednew.generated.resources.report_preparing
import cameragps.sharednew.generated.resources.report_share
import com.sasch.cameragps.sharednew.diagnostics.TroubleshootingEmail
import com.sasch.cameragps.sharednew.diagnostics.TroubleshootingReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun TroubleshootingReportCard(reporter: TroubleshootingReporter) {
    val scope = rememberCoroutineScope()
    var busy by remember(reporter) { mutableStateOf(false) }
    var failed by remember(reporter) { mutableStateOf(false) }
    var copied by remember(reporter) { mutableStateOf(false) }

    fun export(copy: Boolean) {
        if (busy) return
        busy = true
        failed = false
        copied = false
        scope.launch {
            try {
                if (copy) reporter.copySummary() else reporter.share()
                copied = copy
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.report_description, TroubleshootingEmail.RECIPIENT),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { export(copy = false) }, enabled = !busy) {
                    Text(stringResource(Res.string.report_share))
                }
                OutlinedButton(onClick = { export(copy = true) }, enabled = !busy) {
                    Text(stringResource(Res.string.report_copy))
                }
            }
            when {
                busy -> Text(stringResource(Res.string.report_preparing))
                failed -> Text(
                    stringResource(Res.string.report_error),
                    color = MaterialTheme.colorScheme.error
                )

                copied -> Text(stringResource(Res.string.report_copied))
            }
        }
    }
}
