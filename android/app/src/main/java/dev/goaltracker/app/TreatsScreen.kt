package dev.goaltracker.app

import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TreatsScreen(controller: AppController, onOpenMonsters: () -> Unit = {}) {
    val treats = remember(controller.version) { controller.read(emptyList()) { listTreats() } }
    val draws = remember(controller.version) { controller.read(emptyList()) { listDraws() } }
    val enabled = remember(controller.version) { controller.read(true) { rewardsEnabled() } }
    val badges = remember(controller.version, controller.today) { controller.read(emptyList()) { badges(controller.today) } }
    val progress = remember(controller.version) { controller.read(null) { rewardProgress() } }
    val stock = remember(controller.version) { controller.read(emptyList()) { treatStock() } }
    val coins = remember(controller.version) { controller.read(null) { coins() } }
    var pricing by remember { mutableStateOf<dev.goaltracker.core.Treat?>(null) }
    var newTreat by rememberSaveable { mutableStateOf("") }
    var newOnce by rememberSaveable { mutableStateOf(false) }
    var treatToRemove by remember { mutableStateOf<dev.goaltracker.core.Treat?>(null) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!enabled) {
            Text(
                "Rewards are turned off, so nothing is drawn. You can turn them back on in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            EmptyJarBanner(controller, onAddTreat = null)
            progress?.let { NextTreatCard(it) }
        }

        SoftCard(Modifier.fillMaxWidth(), onClick = onOpenMonsters) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.ui_monsters), style = MaterialTheme.typography.titleMedium)
                Text(
                    "Name a struggle and fight it with your goals, habits and tasks. Beating it wins treats.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Treats won but not used yet. Using one marks the oldest win as enjoyed.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Your stock")
            if (stock.isEmpty()) {
                Text("Nothing saved up. Treats you win wait here until you use them.", style = MaterialTheme.typography.bodyMedium)
            }
            stock.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.treatTitle, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("×${item.count}", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = {
                        val id = item.nextDrawId
                        if (controller.act { toggleRedeemed(id, controller.today) }) {
                            controller.undo = UndoOffer("Enjoy your ${item.treatTitle}!") {
                                controller.act { toggleRedeemed(id, controller.today) }
                            }
                        }
                    }) { Text("Use one") }
                }
            }
        }

        // The save-up shop: coins come from XP, and a bought treat goes into the stock.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val balance = (coins?.balance ?: 0L).coerceAtLeast(0L)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Shop", Modifier.weight(1f))
                Text("🪙 $balance", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "You get 1 coin for every 10 XP. Give a treat a price below to save up for it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            treats.filter { it.price != null }.sortedBy { it.price }.forEach { treat ->
                val price = treat.price!!
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(treat.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (balance >= price.toLong()) "🪙 $price" else "🪙 $price · ${price.toLong() - balance} to go",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(enabled = balance >= price.toLong(), onClick = {
                        val id = controller.change { buyTreat(treat.id, controller.today) }
                        if (id != null) {
                            Feel.celebrate(null)
                            controller.undo = UndoOffer("Bought ${treat.title}. It's in your stock.") {
                                controller.act { refundPurchase(id) }
                            }
                        }
                    }) { Text("Buy") }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Your jar")
            if (treats.isEmpty()) Text(stringResource(R.string.ui_your_jar_is_empty), style = MaterialTheme.typography.bodyMedium)
            treats.forEach { treat ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(treat.title, Modifier.weight(1f))
                    // Tap to switch: a one-time treat leaves the jar once won.
                    FilterChip(
                        selected = treat.once,
                        onClick = { controller.act { setTreatOnce(treat.id, !treat.once) } },
                        label = { Text(if (treat.once) "1× only" else "Repeat") },
                        modifier = Modifier.label(if (treat.once) "${treat.title}: one time only. Tap to make it repeatable" else "${treat.title}: repeatable. Tap to make it one time only"),
                    )
                    TextButton(onClick = { pricing = treat }) {
                        Text(treat.price?.let { "🪙 $it" } ?: "Price", modifier = Modifier.label(
                            treat.price?.let { "${treat.title} costs $it coins in the shop. Change price" } ?: "Put ${treat.title} in the shop",
                        ))
                    }
                    IconButton(onClick = { treatToRemove = treat }) {
                        Icon(Icons.Filled.Close, "Remove ${treat.title}")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val addTreat = {
                    if (newTreat.isNotBlank() && controller.act { addTreatOnce(newTreat, newOnce) }) {
                        newTreat = ""
                        newOnce = false
                    }
                }
                OutlinedTextField(
                    newTreat, { newTreat = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.ui_new_treat_for_example_a_movie_ni)) }, singleLine = true,
                    keyboardOptions = doneKey, keyboardActions = onDone(addTreat),
                )
                TextButton(enabled = newTreat.isNotBlank(), onClick = { addTreat() }) { Text(stringResource(R.string.ui_add)) }
            }
            FilterChip(selected = newOnce, onClick = { newOnce = !newOnce }, label = { Text("One time only") })
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("History")
            if (draws.isNotEmpty()) {
                Text(
                    "${draws.size} won, ${draws.count { it.redeemedDate != null }} enjoyed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (draws.isEmpty()) Text(stringResource(R.string.ui_none_yet_keep_hitting_your_targe), style = MaterialTheme.typography.bodyMedium)
            draws.forEach { draw ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        modifier = Modifier.label(if (draw.redeemedDate != null) "Mark ${draw.treatTitle} as not enjoyed" else "Mark ${draw.treatTitle} as enjoyed"),
                        checked = draw.redeemedDate != null,
                        onCheckedChange = { controller.act { toggleRedeemed(draw.id, controller.today) } },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(draw.treatTitle)
                        Text(
                            "${if (draw.fromMonster) controller.shownMonsterName(draw.goalTitle) else draw.goalTitle} · ${formatDate(draw.localDate)}" + if (draw.comeback) " · Comeback" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (draws.isNotEmpty()) {
                Text(stringResource(R.string.ui_tick_a_treat_once_you_ve_enjoyed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Badges (${badges.count { it.earned }} of ${badges.size})")
            badges.sortedByDescending { it.earned }.forEach { badge ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (badge.earned) "Earned" else "Locked", Modifier.widthIn(min = 56.dp), style = MaterialTheme.typography.labelMedium,
                        color = if (badge.earned) positiveColor() else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column {
                        Text(badge.title, style = MaterialTheme.typography.bodyMedium)
                        Text(badge.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    pricing?.let { treat ->
        var text by remember(treat.id) { mutableStateOf(treat.price?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { pricing = null },
            title = { Text("Price for ${treat.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "In coins. You get 1 coin per 10 XP, so a good day is a few coins.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Quick picks, so nothing has to be typed.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(20, 50, 100, 250).forEach { n ->
                            FilterChip(text == n.toString(), { text = n.toString() }, label = { Text("$n") })
                        }
                    }
                    OutlinedTextField(
                        text, { value -> text = value.filter { it.isDigit() }.take(6) },
                        label = { Text("Coins") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = text.toUIntOrNull()?.let { it > 0u } == true, onClick = {
                    if (controller.act { setTreatPrice(treat.id, text.toUInt()) }) pricing = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (treat.price == null || controller.act { setTreatPrice(treat.id, null) }) pricing = null
                }) { Text(if (treat.price != null) "Not for sale" else "Cancel") }
            },
        )
    }
    treatToRemove?.let { treat ->
        ConfirmDialog(
            title = "Remove this treat?",
            body = "\"${treat.title}\" leaves your jar. Treats you already won stay in your list.",
            confirm = "Remove",
            onConfirm = { treatToRemove = null; controller.act { removeTreat(treat.id) } },
            onDismiss = { treatToRemove = null },
        )
    }
}

@Composable
private fun NextTreatCard(progress: dev.goaltracker.core.RewardProgress) {
    var help by remember { mutableStateOf(false) }
    SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Your next treat", Modifier.weight(1f))
                TextButton(onClick = { help = true }) { Text(stringResource(R.string.ui_how_it_works)) }
            }
            if (progress.treatsInJar == 0u) {
                Text(stringResource(R.string.ui_add_a_treat_to_your_jar_to_start), style = MaterialTheme.typography.bodyMedium)
            } else {
                // A countdown, not a bar: draws are random, so only the guarantee is certain.
                Text(
                    if (progress.guaranteedIn <= 1u) "Your next target is guaranteed to win a treat."
                    else "A treat is guaranteed within ${progress.guaranteedIn} more targets.",
                    style = MaterialTheme.typography.titleMedium,
                )
                // Fills as misses pile up toward the guaranteed treat. A win can still come sooner.
                val total = (progress.missesSinceDraw + progress.guaranteedIn).toFloat()
                val target = if (total > 0f) progress.missesSinceDraw.toFloat() / total else 0f
                val motion = LocalMotion.current
                var started by remember { mutableStateOf(!motion) }
                LaunchedEffect(Unit) { started = true }
                val filled by animateFloatAsState(
                    if (started) target else 0f, motionTween(motion, 700), label = "guarantee",
                )
                LinearProgressIndicator(
                    progress = { filled },
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                    strokeCap = StrokeCap.Round,
                )
                Text(
                    "Guarantee meter",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Each target you meet can also win one sooner, with a ${progress.basePercent}% chance " +
                        "(${progress.comebackPercent}% when you get back on track after a miss).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (help) {
        InfoSheet("How rewards work", onDismiss = { help = false }) {
            Text(stringResource(R.string.ui_1_fill_your_jar_with_treats_you), style = MaterialTheme.typography.bodyLarge)
            Text("2. Every time you meet a target, it rolls for a treat: a ${progress.basePercent}% chance, or ${progress.comebackPercent}% if you are getting back on track after missing the period before.", style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.ui_3_you_always_win_one_within_10_t), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.ui_4_if_you_undo_a_log_and_the_targ), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
