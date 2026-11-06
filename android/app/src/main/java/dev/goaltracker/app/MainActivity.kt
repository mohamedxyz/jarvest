package dev.goaltracker.app

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedContent
import dev.goaltracker.core.GoalKind
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
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
    Tab("habits", "Habits", Icons.Filled.Repeat),
    Tab("stats", "Stats", Icons.Filled.BarChart),
    Tab("treats", "Rewards", Icons.Filled.CardGiftcard),
)

class MainActivity : ComponentActivity() {
    private val controller get() = (application as GoalTrackerApp).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GoalTrackerTheme(controller.themeMode, controller.accent) {
                // The quick-add widget opens the app straight on the new task screen.
                AppEntry(controller, startOnNewTask = (intent?.action == ACTION_QUICK_ADD || intent?.getBooleanExtra(EXTRA_QUICK_ADD, false) == true))
            }
        }
    }

    companion object {
        const val ACTION_QUICK_ADD = "dev.goaltracker.app.QUICK_ADD"
        const val EXTRA_QUICK_ADD = "quick_add"
    }

    override fun onResume() {
        super.onResume()
        controller.onResume()
    }
}

/** Routes are strings so the back stack survives rotation: `goal/<id>`, `edit/<id>`, and so on. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AppRoot(controller: AppController, startOnNewTask: Boolean = false) {
    val stack = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { if (startOnNewTask) mutableStateListOf("today", "newtask") else mutableStateListOf("today") }
    val route = stack.last()
    // Hide the bottom bar while the keyboard is up, so it does not eat the screen.
    val imeVisible = WindowInsets.isImeVisible
    val isTab = tabs.any { it.route == route }
    val push: (String) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val selectTab: (String) -> Unit = { tab ->
        stack.clear()
        stack.add(tab)
    }
    BackHandler(stack.size > 1) { pop() }

    var addMenu by remember { mutableStateOf(false) }
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text(titleFor(route), style = MaterialTheme.typography.headlineSmall) },
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
            if (isTab && !imeVisible) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        val selected = route == tab.route
                        // The selected icon grows a little, so the active tab is easy to spot.
                        val scale by animateFloatAsState(
                            if (selected) 1.15f else 1f, motionTween(LocalMotion.current, 200), label = "tab",
                        )
                        NavigationBarItem(
                            selected = selected,
                            onClick = { selectTab(tab.route) },
                            icon = { Icon(tab.icon, null, Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            // The button always says what it makes, because goals and tasks are different things.
            when (route) {
                "today" -> Box {
                    ExtendedFloatingActionButton(
                        onClick = { addMenu = true },
                        icon = { Icon(Icons.Filled.Add, null) },
                        text = { Text("Add") },
                    )
                    DropdownMenu(addMenu, { addMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("New task (a one-off to-do)") },
                            onClick = { addMenu = false; push("newtask") },
                        )
                        DropdownMenuItem(
                            text = { Text("New goal (a habit or target)") },
                            onClick = { addMenu = false; push("new") },
                        )
                    }
                }
                "goals" -> ExtendedFloatingActionButton(
                    onClick = { push("newgoal") },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("New goal") },
                )
                "habits" -> ExtendedFloatingActionButton(
                    onClick = { push("newhabit") },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("New habit") },
                )
                "tasks" -> ExtendedFloatingActionButton(
                    onClick = { push("newtask") },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("New task") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val motion = LocalMotion.current
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
            // Tabs cross-fade. Opening a screen slides it in from the right; going back slides it out.
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    val toTab = tabs.any { it.route == targetState }
                    val fromTab = tabs.any { it.route == initialState }
                    when {
                        !motion -> EnterTransition.None togetherWith ExitTransition.None
                        toTab && fromTab ->
                            fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(90))
                        toTab ->
                            (slideInHorizontally(tween(260)) { -it / 5 } + fadeIn(tween(260))) togetherWith
                                (slideOutHorizontally(tween(260)) { it / 5 } + fadeOut(tween(160)))
                        else ->
                            (slideInHorizontally(tween(260)) { it / 5 } + fadeIn(tween(260))) togetherWith
                                (slideOutHorizontally(tween(260)) { -it / 5 } + fadeOut(tween(160)))
                    }
                },
                label = "screens",
            ) { route ->
            when {
                route == "today" -> TodayScreen(
                    controller,
                    onOpenGoal = { push("goal/$it") },
                    onEditGoal = { push("edit/$it") },
                    onEditTask = { push("edittask/$it") },
                    onOpenTasks = { selectTab("tasks") },
                    onOpenRewards = { selectTab("treats") },
                    onAddGoal = { push("new") },
                    onAddTask = { push("newtask") },
                    onOpenSettings = { push("settings") },
                )
                route == "tasks" -> TasksScreen(controller, onEdit = { push("edittask/$it") })
                route == "newtask" -> TaskEditorScreen(controller, taskId = null, presetGoalId = null, onDone = pop)
                route.startsWith("newtask/") -> TaskEditorScreen(
                    controller,
                    taskId = null,
                    presetGoalId = route.removePrefix("newtask/"),
                    onDone = pop,
                )
                route.startsWith("edittask/") ->
                    TaskEditorScreen(controller, taskId = route.removePrefix("edittask/"), presetGoalId = null, onDone = pop)
                route == "goals" -> GoalsScreen(controller, habits = false, onOpenGoal = { push("goal/$it") })
                route == "habits" -> GoalsScreen(controller, habits = true, onOpenGoal = { push("goal/$it") })
                route == "newgoal" -> GoalEditorScreen(controller, goalId = null, startKind = GoalKind.YES_NO, onDone = pop)
                route == "newhabit" -> GoalEditorScreen(controller, goalId = null, startKind = GoalKind.HABIT, onDone = pop)
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
                    onAddTask = { push("newtask/$it") },
                    onEditTask = { push("edittask/$it") },
                    onClose = pop,
                )
            }
            }
        }
    }

    controller.pendingDraw?.let { RewardReveal(it, controller::dismissDraw) }
    CelebrationBurst()
}

private fun titleFor(route: String): String = when {
    route == "settings" -> "Settings"
    route == "new" || route == "newgoal" -> "New goal"
    route == "newhabit" -> "New habit"
    route == "newtask" || route.startsWith("newtask/") -> "New task"
    route.startsWith("edittask/") -> "Edit task"
    route.startsWith("edit/") -> "Edit goal"
    route.startsWith("goal/") -> "Goal"
    // firstOrNull, not first: an unlisted route must show a plain title, never crash the app.
    else -> tabs.firstOrNull { it.route == route }?.label ?: "Jarvest"
}

@Composable
private fun RewardReveal(draw: RewardDraw, onDismiss: () -> Unit) {
    var shown by remember(draw.id) { mutableStateOf(false) }
    // With animations off on the phone, the dialog simply appears at full size.
    val reduced = Feel.reducedMotion(androidx.compose.ui.platform.LocalContext.current)
    LaunchedEffect(draw.id) { shown = true }
    val scale by animateFloatAsState(
        targetValue = if (shown || reduced) 1f else 0.5f,
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
