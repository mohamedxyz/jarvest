package dev.goaltracker.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** Only days from [min] to [max] (inclusive, as YYYY-MM-DD) can be picked. */
@OptIn(ExperimentalMaterial3Api::class)
private fun between(min: String, max: String) = object : SelectableDates {
    private val first = LocalDate.parse(min)
    private val last = LocalDate.parse(max)
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        utcTimeMillis.toLocalDate() in first..last

    override fun isSelectableYear(year: Int): Boolean = year in first.year..last.year
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPickerDialog(min: String, max: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(selectableDates = remember(min, max) { between(min, max) })
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = { state.selectedDateMillis?.let { onPick(it.toLocalDate().toString()) } },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RangePickerDialog(min: String, max: String, onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDateRangePickerState(selectableDates = remember(min, max) { between(min, max) })
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis ?: start
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = start != null && end != null,
                onClick = { if (start != null && end != null) onPick(start.toLocalDate().toString(), end.toLocalDate().toString()) },
            ) { Text("Pause") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(
            state,
            Modifier.fillMaxWidth().padding(top = 8.dp),
            title = { Text("Pick the days to skip", Modifier.padding(start = 24.dp, top = 16.dp)) },
        )
    }
}
