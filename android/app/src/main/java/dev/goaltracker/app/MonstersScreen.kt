package dev.goaltracker.app

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.Monster
import dev.goaltracker.core.MonsterEvent
import dev.goaltracker.core.MonsterLinkKind
import dev.goaltracker.core.MonsterStatus
import dev.goaltracker.core.TaskFilter
import dev.goaltracker.core.TaskSort
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions

private val monsterRed = Color(0xFFC62828)

private fun powerLabel(power: UInt) = when (power) {
    5u -> "Light"
    20u -> "Heavy"
    else -> "Medium"
}

private fun Monster.subtitle(): String = when (status) {
    MonsterStatus.RETIRED -> "Slain forever"
    MonsterStatus.RESTING -> "Resting until ${formatDate(returnsDate ?: "")}"
    MonsterStatus.FIGHTING -> (if (finalBoss) "Final boss" else "Round $round") + " · $health of $maxHealth health"
}

/** The monster's health as a bar that drains smoothly. */
@Composable
private fun HealthBar(monster: Monster) {
    val motion = LocalMotion.current
    val target = if (monster.maxHealth == 0u) 0f else monster.health.toFloat() / monster.maxHealth.toFloat()
    var started by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { started = true }
    val shown by animateFloatAsState(if (started) target else 1f, motionTween(motion, 700), label = "health")
    LinearProgressIndicator(
        progress = { shown },
        modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)),
        color = if (monster.finalBoss) monsterRed else MaterialTheme.colorScheme.primary,
        strokeCap = StrokeCap.Round,
    )
}

/** What to show for a monster's name outside the Monsters screens, if the person wants it private. */
fun AppController.shownMonsterName(name: String): String = if (hideMonsterNames) "a monster" else name

@Composable
fun MonstersScreen(controller: AppController, onOpen: (String) -> Unit) {
    val monsters = remember(controller.version, controller.today) { controller.read(emptyList()) { listMonsters(controller.today) } }
    val trophies = remember(controller.version) { controller.read(emptyList()) { monsterTrophies() } }
    var creating by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "A monster is a struggle you name, such as a habit you want to beat. Link the goals, habits and tasks that fight it. Every time you do one, the monster loses health. Slips heal it a little, but you never lose anything.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { creating = true }) { Text(stringResource(R.string.ui_new_monster)) }
        if (monsters.isEmpty()) {
            Text(stringResource(R.string.ui_no_monsters_yet), style = MaterialTheme.typography.titleMedium)
        }
        monsters.forEach { monster ->
            SoftCard(Modifier.fillMaxWidth(), onClick = { onOpen(monster.id) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        (if (monster.finalBoss && monster.status != MonsterStatus.RETIRED) "👹 " else "👾 ") + monster.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (monster.status == MonsterStatus.FIGHTING) HealthBar(monster)
                    Text(monster.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val bosses = remember(controller.version, controller.today) { controller.read(emptyList()) { bossHistory() } }
        if (bosses.isNotEmpty()) {
            SectionTitle("Weekly bosses")
            bosses.forEach { boss ->
                Text(
                    (if (boss.defeatedDate != null) "🏆 " else "👹 ") +
                        "Week of ${formatDate(boss.weekStart)} · ${boss.name} · " +
                        if (boss.defeatedDate != null) "beaten ${formatDate(boss.defeatedDate!!)}" else "${boss.damage} of ${boss.health}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (trophies.isNotEmpty()) {
            SectionTitle("Trophies")
            trophies.forEach { trophy ->
                Text(
                    (if (trophy.slainForever) "☠️ Slain forever: " else "🏆 ") +
                        "${trophy.monsterName} · round ${trophy.round} · ${formatDate(trophy.defeatedDate)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Hide monster names in the treat list and when a treat is revealed.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(controller.hideMonsterNames, { controller.chooseHideMonsterNames(it) }, modifier = Modifier.label("Hide monster names"))
        }
    }

    if (creating) {
        var name by rememberSaveable { mutableStateOf("") }
        var health by rememberSaveable { mutableStateOf("100") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.ui_new_monster)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.ui_name)) }, singleLine = true, modifier = Modifier.autoFocus())
                    OutlinedTextField(
                        health,
                        { health = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.ui_health_10_to_10000)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text(
                        "Light, medium and heavy tasks take 5, 10 and 20 off. 100 is a good start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && (health.toIntOrNull() ?: 0) in 10..10000,
                    onClick = {
                        val value = health.toUInt()
                        val id = controller.change { createMonster(name, value, controller.today) }
                        if (id != null) {
                            creating = false
                            onOpen(id)
                        }
                    },
                ) { Text(stringResource(R.string.ui_create)) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonsterDetailScreen(controller: AppController, monsterId: String, onClose: () -> Unit) {
    val monster = remember(controller.version, controller.today) {
        controller.read(emptyList()) { listMonsters(controller.today) }.firstOrNull { it.id == monsterId }
    }
    if (monster == null) {
        EmptyState("Monster not found", "It may have been deleted.")
        return
    }
    val treats = remember(controller.version) { controller.read(emptyList()) { listTreats() } }
    var renaming by remember { mutableStateOf(false) }
    var pickGoal by remember { mutableStateOf(false) }
    var pickTask by remember { mutableStateOf(false) }
    var pickPrize by remember { mutableStateOf(false) }
    var confirmFinal by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val gone = monster.status == MonsterStatus.RETIRED

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SoftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    (if (gone) "☠️ " else if (monster.finalBoss) "👹 " else "👾 ") + monster.name,
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (monster.status == MonsterStatus.FIGHTING) HealthBar(monster)
                Text(monster.subtitle(), style = MaterialTheme.typography.bodyMedium)
                if (monster.defeats > 0u) {
                    Text("Beaten ${monster.defeats} ${if (monster.defeats == 1u) "time" else "times"}", style = MaterialTheme.typography.bodySmall)
                }
                if (monster.status == MonsterStatus.RESTING) {
                    Text(
                        "It is resting. Damage during the rest does not count. It comes back stronger.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (!gone) {
            SectionTitle("What hurts it")
            if (monster.links.isEmpty()) {
                Text(stringResource(R.string.ui_nothing_is_linked_yet_add_a_habi), style = MaterialTheme.typography.bodyMedium)
            }
            monster.links.forEach { link ->
                SoftCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (link.kind == MonsterLinkKind.TASK) "Task: " else "") + link.title.ifBlank { "(deleted)" },
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            IconButton(onClick = { controller.act { unlinkMonsterItem(monster.id, link.kind, link.itemId) } }) {
                                Icon(Icons.Filled.Close, "Unlink ${link.title}")
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5u, 10u, 20u).forEach { power ->
                                FilterChip(
                                    selected = link.power == power,
                                    onClick = { controller.act { linkMonsterItem(monster.id, link.kind, link.itemId, power) } },
                                    label = { Text("${powerLabel(power)} · $power") },
                                )
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickGoal = true }) { Text(stringResource(R.string.ui_add_goal_or_habit)) }
                OutlinedButton(onClick = { pickTask = true }) { Text(stringResource(R.string.ui_add_task)) }
            }
            Text(
                "A quit habit hurts it for each clean day. A slip heals it a little, and you never lose anything.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Prize")
            Text(
                "Beating it wins a treat from your jar" + (monster.prizeTreat?.let { ". The first one is always: $it" } ?: "") + ".",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { pickPrize = true }) { Text(if (monster.prizeTreat == null) "Pick a prize treat" else "Change prize treat") }

            SectionTitle("Final boss")
            Text(
                if (monster.finalBoss) "This is the final boss. It never comes back. Beat it for three treats and a Slain forever trophy."
                else "Permanent kill: it becomes the final boss with full health, 5 times stronger, and never respawns. Beating it wins three treats.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!monster.finalBoss) {
                Button(
                    onClick = { confirmFinal = true },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = monsterRed),
                ) { Text(stringResource(R.string.ui_permanent_kill_final_boss)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!gone) OutlinedButton(onClick = { renaming = true }) { Text(stringResource(R.string.ui_rename)) }
            OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.ui_delete), color = MaterialTheme.colorScheme.error) }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(monster.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.ui_rename_monster)) },
            text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true, modifier = Modifier.autoFocus()) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    if (controller.act { renameMonster(monster.id, name) }) renaming = false
                }) { Text(stringResource(R.string.ui_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }
    if (pickGoal) {
        val goals = remember { controller.read(emptyList()) { listGoals(false) } }
        val linked = monster.links.filter { it.kind == MonsterLinkKind.GOAL }.map { it.itemId }.toSet()
        PickerDialog("Add goal or habit", goals.filter { it.id !in linked }.map { it.id to it.title }, "No other goals to add.", { pickGoal = false }) { id ->
            controller.act { linkMonsterItem(monster.id, MonsterLinkKind.GOAL, id, 10u) }
        }
    }
    if (pickTask) {
        val tasks = remember { controller.read(emptyList()) { listTasks(TaskFilter.ALL, TaskSort.NEWEST, controller.today) } }
        val linked = monster.links.filter { it.kind == MonsterLinkKind.TASK }.map { it.itemId }.toSet()
        PickerDialog("Add task", tasks.filter { it.id !in linked && !it.done }.map { it.id to it.shownTitle() }, "No open tasks to add.", { pickTask = false }) { id ->
            controller.act { linkMonsterItem(monster.id, MonsterLinkKind.TASK, id, 10u) }
        }
    }
    if (pickPrize) {
        PickerDialog(
            "Prize treat",
            listOf("" to "No set prize (a random treat)") + treats.map { it.id to it.title },
            "Your jar is empty. Add treats on the Rewards tab.",
            { pickPrize = false },
        ) { id -> controller.act { setMonsterPrize(monster.id, id.ifEmpty { null }) } }
    }
    if (confirmFinal) {
        val strength = if (monster.status == MonsterStatus.FIGHTING) "${monster.maxHealth * 5u} health" else "5 times more health"
        ConfirmDialog(
            title = "Permanent kill?",
            body = "${monster.name} becomes the final boss with $strength and full health. It never respawns after this. Beating it wins three treats and a Slain forever trophy. This can't be undone.",
            confirm = "Make it the final boss",
            onConfirm = { confirmFinal = false; controller.act { makeFinalBoss(monster.id, controller.today) } },
            onDismiss = { confirmFinal = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this monster?",
            body = "\"${monster.name}\", its rounds and trophies will be deleted. Treats you already won stay in your list. This can't be undone.",
            confirm = "Delete",
            onConfirm = { confirmDelete = false; if (controller.act { deleteMonster(monster.id) }) onClose() },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** A dialog listing choices as (id, label). Picking one closes it. */
@Composable
private fun PickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    emptyText: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (options.isEmpty()) Text(emptyText)
                options.forEach { (id, label) ->
                    TextButton(onClick = { onPick(id); onDismiss() }, Modifier.fillMaxWidth()) {
                        Text(label, Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}

/** Shown once when a monster is beaten. */
@Composable
fun MonsterVictoryDialog(event: MonsterEvent, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (event.finalBoss) "Slain forever!" else "You beat ${event.name}!") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (event.finalBoss) "${event.name} was the final boss, and it is gone for good."
                    else "Round ${event.round} is won. It rests for a few days, then comes back stronger.",
                )
                Text(
                    when {
                        event.prizes == 0u -> "No treat this time. Add treats to your jar on the Rewards tab to win prizes."
                        event.prizes == 1u -> "You won a treat. See it in your treat list."
                        else -> "You won ${event.prizes} treats. See them in your treat list."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_nice_2)) } },
    )
}

@Composable
fun BossVictoryDialog(event: dev.goaltracker.core.BossEvent, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("You beat ${event.name}!") },
        text = {
            Text(
                if (event.prizes > 0u) "This week's boss is down, and you won a treat. See it in your treat list."
                else "This week's boss is down. Add treats to your jar on the Rewards tab to win prizes.",
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_nice_2)) } },
    )
}
