package dev.goaltracker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.RewardDraw

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("today", "Today", Icons.Filled.Today),
    Tab("tasks", "Tasks", Icons.Filled.Checklist),
    Tab("goals", "Goals", Icons.Filled.Flag),
    Tab("stats", "Stats", Icons.Filled.BarChart),
    Tab("treats", "Treats", Icons.Filled.CardGiftcard),
)

class MainActivity : ComponentActivity() {
    private val controller get() = (application as GoalTrackerApp).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GoalTrackerTheme(controller.themeMode, controller.accent) { AppRoot(controller) }
        }
    }

    override fun onResume() {
        super.onResume()
        controller.onResume()
    }
}

/** Routes are strings so the back stack survives rotation: `goal/<id>`, `edit/<id>`, and so on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(controller: AppController) {
    val stack = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableStateListOf("today") }
    val route = stack.last()
    val isTab = tabs.any { it.route == route }
    val push: (String) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val selectTab: (String) -> Unit = { tab ->
        stack.clear()
        stack.add(tab)
    }
    BackHandler(stack.size > 1) { pop() }

    val snackbar = remember { SnackbarHostState() }
    // A prompt from one screen must not cover the controls of the next.
    LaunchedEffect(route) { snackbar.currentSnackbarData?.dismiss() }
    LaunchedEffect(controller.message) {
        controller.message?.let {
            snackbar.showSnackbar(it)
            controller.message = null
        }
    }
    LaunchedEffect(controller.undo) {
        controller.undo?.let { offer ->
            controller.undo = null
            val result = snackbar.showSnackbar(offer.text, actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) offer.action()
        }
    }
    LaunchedEffect(controller.showJarPrompt) {
        if (controller.showJarPrompt) {
            val result = snackbar.showSnackbar(
                "Target complete! Add a treat to your jar to start winning rewards.",
                actionLabel = "Add",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            controller.showJarPrompt = false
            if (result == SnackbarResult.ActionPerformed) selectTab("treats")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(route)) },
                navigationIcon = {
                    if (!isTab) {
                        IconButton(onClick = pop) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    if (isTab) {
                        IconButton(onClick = { push("settings") }) { Icon(Icons.Filled.Settings, "Settings") }
                    }
                },
            )
        },
        bottomBar = {
            if (isTab) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { selectTab(tab.route) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (route == "today" || route == "goals") {
                FloatingActionButton(onClick = { push("new") }) { Icon(Icons.Filled.Add, "Add goal") }
            } else if (route == "tasks") {
                FloatingActionButton(onClick = { push("newtask") }) { Icon(Icons.Filled.Add, "Add task with details") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
            when {
                route == "today" -> TodayScreen(
                    controller,
                    onOpenGoal = { push("goal/$it") },
                    onEditGoal = { push("edit/$it") },
                    onEditTask = { push("edittask/$it") },
                    onOpenTasks = { selectTab("tasks") },
                )
                route == "tasks" -> TasksScreen(controller, onEdit = { push("edittask/$it") })
                route == "newtask" -> TaskEditorScreen(controller, taskId = null, onDone = pop)
                route.startsWith("edittask/") ->
                    TaskEditorScreen(controller, taskId = route.removePrefix("edittask/"), onDone = pop)
                route == "goals" -> GoalsScreen(controller, onOpenGoal = { push("goal/$it") })
                route == "stats" -> StatsScreen(controller, onOpenGoal = { push("goal/$it") })
                route == "treats" -> TreatsScreen(controller)
                route == "settings" -> SettingsScreen(controller)
                route == "new" -> GoalEditorScreen(controller, goalId = null, onDone = pop)
                route.startsWith("edit/") ->
                    GoalEditorScreen(controller, goalId = route.removePrefix("edit/"), onDone = pop)
                route.startsWith("goal/") -> GoalDetailScreen(
                    controller,
                    goalId = route.removePrefix("goal/"),
                    onEdit = { push("edit/$it") },
                    onClose = pop,
                )
            }
        }
    }

    controller.pendingDraw?.let { RewardReveal(it, controller::dismissDraw) }
}

private fun titleFor(route: String): String = when {
    route == "settings" -> "Settings"
    route == "new" -> "New goal"
    route == "newtask" -> "New task"
    route.startsWith("edittask/") -> "Edit task"
    route.startsWith("edit/") -> "Edit goal"
    route.startsWith("goal/") -> "Goal"
    else -> tabs.first { it.route == route }.label
}

@Composable
private fun RewardReveal(draw: RewardDraw, onDismiss: () -> Unit) {
    var shown by remember(draw.id) { mutableStateOf(false) }
    LaunchedEffect(draw.id) { shown = true }
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.5f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "reveal",
    )
    AlertDialog(
        modifier = Modifier.scale(scale),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.CardGiftcard, null) },
        title = { Text("You drew a treat!", textAlign = TextAlign.Center) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    draw.treatTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (draw.comeback) "Comeback boost! You got back on track with ${draw.goalTitle}."
                    else "Earned by ${draw.goalTitle}.",
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Nice!") } },
    )
}
