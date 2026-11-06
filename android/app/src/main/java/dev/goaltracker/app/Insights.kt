package dev.goaltracker.app

import androidx.compose.ui.res.stringResource
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.CalendarKind
import dev.goaltracker.core.Period

/** A look back at a week or a month, with a button to share it as text. Only reads data. */
@Composable
fun InsightsCard(controller: AppController) {
    var month by rememberSaveable { mutableStateOf(false) }
    var back by rememberSaveable { mutableIntStateOf(0) }
    val period = if (month) Period.MONTH else Period.WEEK
    val insights = remember(controller.version, controller.today, month, back) {
        controller.read(null) { insights(period, back.toUInt(), controller.today) }
    } ?: return
    val context = LocalContext.current
    SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ui_insights), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(!month, { month = false; back = 0 }, { Text(stringResource(R.string.ui_week)) })
                FilterChip(month, { month = true; back = 0 }, { Text(stringResource(R.string.ui_month)) })
                TextButton(onClick = { back += 1 }) { Text(stringResource(R.string.ui_earlier)) }
                if (back > 0) TextButton(onClick = { back -= 1 }) { Text(stringResource(R.string.ui_later)) }
            }
            Text("${formatDate(insights.from)} to ${formatDate(insights.to)}", style = MaterialTheme.typography.labelLarge)
            Text("${insights.points} points over ${insights.activeDays} active days")
            Text("${insights.titanDays} Titan days · ${insights.focusMinutes} minutes of focus · ${insights.bonusXp} bonus XP")
            val diff = insights.points.toInt() - insights.previousPoints.toInt()
            Text(
                when {
                    insights.previousPoints == 0u -> "Nothing recorded before this to compare with."
                    diff > 0 -> "$diff points more than the period before."
                    diff < 0 -> "${-diff} points fewer than the period before."
                    else -> "The same as the period before."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            insights.bestDate?.let {
                Text(
                    "Best day: ${formatDate(it)} with ${insights.bestPoints} points",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Mood lines show only with enough notes, and they are never part of the text that is shared.
            insights.averageMood?.let { avg ->
                Text(
                    "Mood: ${"%.1f".format(avg)} of 5 on average over ${insights.moodNotes} days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val good = insights.moodGoodDays
                val other = insights.moodOtherDays
                if (good != null && other != null) {
                    Text(
                        "${"%.1f".format(good)} on Blaze and Titan days, ${"%.1f".format(other)} on the others",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, insights.shareText)
                }
                context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text(stringResource(R.string.ui_share_as_text)) }
        }
    }
}

/** Suggested start times for plans without one. Nothing is saved until the person taps Apply. */
@Composable
fun SuggestTimesDialog(controller: AppController, date: String, onDismiss: () -> Unit) {
    val suggestions = remember(controller.version, date) { controller.read(emptyList()) { suggestBlocks(date) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_suggested_times)) },
        text = {
            if (suggestions.isEmpty()) {
                Text(stringResource(R.string.ui_nothing_to_place_everything_plan))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    suggestions.forEach {
                        Text("${Reminders.formatMinutes(it.startMinutes.toInt())}  ${it.title} (${it.minutes} min)")
                    }
                }
            }
        },
        confirmButton = {
            if (suggestions.isNotEmpty()) TextButton(onClick = {
                controller.act {
                    suggestions.forEach {
                        when (it.kind) {
                            CalendarKind.TASK -> setTaskTime(it.id, it.startMinutes, it.minutes)
                            CalendarKind.GOAL -> setGoalBlock(it.id, it.startMinutes, it.minutes)
                            CalendarKind.EVENT -> Unit
                        }
                    }
                }
                onDismiss()
            }) { Text(stringResource(R.string.ui_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) } },
    )
}
