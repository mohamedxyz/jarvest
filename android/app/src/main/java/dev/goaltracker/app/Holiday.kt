package dev.goaltracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.Holiday
import java.time.LocalDate

/** "Nov 9 to Nov 15", or one date when it is a single day. */
private fun Holiday.range(): String =
    if (startDate == endDate) formatDate(startDate) else "${formatDate(startDate)} to ${formatDate(endDate)}"

/**
 * Holiday mode: pause every goal and habit for some days in one go. Streaks wait, the days stay out of the
 * rates, and tasks are not moved. Ending a holiday brings the goals back, and only undoes what it added.
 */
@Composable
fun HolidayScreen(controller: AppController) {
    val holidays = remember(controller.version, controller.today) {
        controller.read(emptyList()) { listHolidays(controller.today) }
    }
    var picking by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf<Holiday?>(null) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "A holiday pauses every goal and habit for the days you choose. Your streaks wait for you, and the " +
                "days don't count against your rates. Tasks are not moved, and a Blaze streak carries across the " +
                "days off. Goals you make later are not part of it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { picking = true }) { Text("Start a holiday") }
        if (holidays.isEmpty()) {
            Text("No holidays yet.", style = MaterialTheme.typography.titleMedium)
        }
        holidays.forEach { holiday ->
            val over = holiday.endDate < controller.today
            SoftCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("🏖️ ${holiday.range()}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                holiday.active -> "On now · ${holiday.goalsCovered} paused"
                                over -> "Over · ${holiday.goalsCovered} were paused"
                                else -> "Starts ${formatDate(holiday.startDate)} · ${holiday.goalsCovered} will pause"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!over) {
                        OutlinedButton(onClick = { ending = holiday }) {
                            Text(if (holiday.active) "End now" else "Cancel")
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        val today = LocalDate.parse(controller.today)
        RangePickerDialog(
            min = today.toString(),
            max = today.plusYears(1).toString(),
            title = "Pick the days off",
            confirm = "Start",
            onPick = { from, to ->
                picking = false
                if (controller.act { startHoliday(from, to, controller.today) }) {
                    controller.message = "Holiday set. Your habits and goals are paused from ${formatDate(from)}."
                }
            },
            onDismiss = { picking = false },
        )
    }
    ending?.let { holiday ->
        ConfirmDialog(
            title = if (holiday.active) "End the holiday now?" else "Cancel this holiday?",
            body = if (holiday.active) {
                "Your habits and goals come back from today. The days already past stay paused, so your history doesn't change."
            } else {
                "Nothing will be paused for these days."
            },
            confirm = if (holiday.active) "End now" else "Cancel holiday",
            onConfirm = {
                ending = null
                controller.act { endHoliday(holiday.id, controller.today) }
            },
            onDismiss = { ending = null },
        )
    }
}

/** A small card on Today while a holiday is on, so a quiet screen makes sense. */
@Composable
fun HolidayBanner(controller: AppController, modifier: Modifier = Modifier) {
    val active = remember(controller.version, controller.today) {
        controller.read(emptyList()) { listHolidays(controller.today) }.firstOrNull { it.active }
    } ?: return
    SoftCard(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(
            "🏖️ Holiday until ${formatDate(active.endDate)}. Your habits and goals are paused, and your streaks wait for you.",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
