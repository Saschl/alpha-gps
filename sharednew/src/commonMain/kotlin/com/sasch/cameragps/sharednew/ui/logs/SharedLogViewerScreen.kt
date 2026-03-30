package com.sasch.cameragps.sharednew.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.arrow_back_24px
import cameragps.sharednew.generated.resources.back
import cameragps.sharednew.generated.resources.logs_clear_all
import cameragps.sharednew.generated.resources.logs_empty
import cameragps.sharednew.generated.resources.logs_title
import com.sasch.cameragps.sharednew.database.logging.LogEntry
import com.sasch.cameragps.sharednew.database.logging.LogRepository
import com.sasch.cameragps.sharednew.logging.LogFormatter
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

@OptIn(FormatStringsInDatetimeFormats::class)
private val LOG_TIMESTAMP_FORMAT = LocalDateTime.Format {
    byUnicodePattern("dd-MM-yyyy HH:mm:ss.SSS")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedLogViewerScreen(
    logFormatter: LogFormatter,
    logRepository: LogRepository,
    onBackClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val logs by remember(logFormatter) { logFormatter.format() }.collectAsState(emptyList())

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.logs_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(Res.drawable.arrow_back_24px),
                            contentDescription = stringResource(Res.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    scope.launch {
                        logRepository.clearAllLogs()
                    }
                }
            ) {
                Text(stringResource(Res.string.logs_clear_all))
            }

            SelectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.logs_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        val logText = logs.joinToString("\n\n")

                        Text(
                            text = logText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun LogEntry.toDisplayString(): String {
    val exceptionText = if (exception.isNullOrBlank()) "" else "\n$exception"
    return "[${timestamp.toDisplayTimestamp()}] [${priority.toDisplayPriority()}] ${tag ?: "App"}: $message$exceptionText"
}

private fun Long.toDisplayTimestamp(): String {
    val localDateTime =
        Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault())
    return LOG_TIMESTAMP_FORMAT.format(localDateTime)
}

private fun Int.toDisplayPriority(): String = when (this) {
    1, 2 -> "V"
    3 -> "D"
    4 -> "I"
    5 -> "W"
    6 -> "E"
    7 -> "A"
    else -> toString()
}




