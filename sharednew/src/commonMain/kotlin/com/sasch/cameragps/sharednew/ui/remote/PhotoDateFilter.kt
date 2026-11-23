package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.cancel
import cameragps.sharednew.generated.resources.wifi_remote_all_dates
import cameragps.sharednew.generated.resources.wifi_remote_choose_dates
import cameragps.sharednew.generated.resources.wifi_remote_show_photos
import cameragps.sharednew.generated.resources.wifi_remote_today
import com.sasch.cameragps.sharednew.remote.wifi.PhotoDateRange
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private const val MILLIS_PER_DAY = 86_400_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDateFilter(range: PhotoDateRange?, enabled: Boolean, onSelect: (PhotoDateRange?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val allDates = stringResource(Res.string.wifi_remote_all_dates)
    val today = stringResource(Res.string.wifi_remote_today)
    Box {
        FilterChip(
            selected = range != null, enabled = enabled, onClick = { menu = true },
            label = {
                val label = when {
                    range == null -> allDates
                    range.start != range.endInclusive -> "${range.start} – ${range.endInclusive}"
                    range.start == Clock.System.todayIn(TimeZone.currentSystemDefault()) -> today
                    else -> range.start.toString()
                }
                Text(label)
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menu) },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(allDates) }, onClick = { menu = false; onSelect(null) })
            DropdownMenuItem(text = { Text(today) }, onClick = {
                menu = false
                val day = Clock.System.todayIn(TimeZone.currentSystemDefault())
                onSelect(PhotoDateRange(day, day))
            })
            DropdownMenuItem(text = { Text(stringResource(Res.string.wifi_remote_choose_dates)) }, onClick = {
                menu = false
                picking = true
            })
        }
    }
    if (picking) {
        val picker = rememberDateRangePickerState(
            initialSelectedStartDateMillis = range?.start?.toEpochDays()?.times(MILLIS_PER_DAY),
            initialSelectedEndDateMillis = range?.endInclusive?.toEpochDays()?.times(MILLIS_PER_DAY),
        )
        val start = picker.selectedStartDateMillis
        val end = picker.selectedEndDateMillis
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(enabled = start != null && end != null, onClick = {
                    if (start != null && end != null) {
                        picking = false
                        onSelect(PhotoDateRange(
                            LocalDate.fromEpochDays(start / MILLIS_PER_DAY),
                            LocalDate.fromEpochDays(end / MILLIS_PER_DAY),
                        ))
                    }
                }) { Text(stringResource(Res.string.wifi_remote_show_photos)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(Res.string.cancel)) }
            },
        ) {
            DateRangePicker(picker, modifier = Modifier.weight(1f, fill = false))
        }
    }
}
