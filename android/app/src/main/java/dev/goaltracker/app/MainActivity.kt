package dev.goaltracker.app

import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
    Tab("calendar", "Calendar", Icons.Filled.CalendarMonth),
    Tab("goals", "Goals", Icons.Filled.Flag),
    Tab("habits", "Habits", Icons.Filled.Repeat),
    Tab("more", "Hero", Icons.Filled.Shield),
)

/**
 * The four bottom tabs. Plan and Quests each hold two screens, shown with a switcher at the top,
 * so the old six tabs fit in four. `home` is where a tab opens the first time.
 */
private class BottomTab(val label: String, val icon: ImageVector, val home: String, val routes: Set<String>)

private val bottomTabs = listOf(
    BottomTab("Today", Icons.Filled.Today, "today", setOf("today")),
    BottomTab("Plan", Icons.Filled.Checklist, "tasks", setOf("tasks", "calendar")),
    BottomTab("Quests", Icons.Filled.Flag, "goals", setOf("goals", "habits")),
    BottomTab("Hero", Icons.Filled.Shield, "more", setOf("more", "stats", "treats", "history", "routines", "holiday")),
)

/** The two-screen tabs: which routes they switch between, and what each is called. */
private val switchers = listOf(
    listOf("tasks" to "Tasks", "calendar" to "Calendar"),
    listOf("goals" to "Goals", "habits" to "Habits"),
)

/** Screens opened from the More tab. They still show the bottom bar, with More selected. */
private val moreRoutes = setOf("more", "stats", "treats", "history", "routines", "holiday")

class MainActivity : ComponentActivity() {
    private val controller get() = (application as GoalTrackerApp).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Text shared from another app only fills the quick-add box. Nothing is saved until it is confirmed.
        // It is read once, so turning the phone does not fill the box again.
        val shared = if (savedInstanceState == null) sharedTextFrom(intent) else null
        if (shared != null) controller.pendingQuickAdd = shared
        setContent {
            GoalTrackerTheme(controller.themeMode) {
                // The quick-add widget opens the app straight on the new task screen.
                AppEntry(
                    controller,
                    startOnNewTask = (intent?.action == ACTION_QUICK_ADD || intent?.getBooleanExtra(EXTRA_QUICK_ADD, false) == true),
                    startOnTasks = shared != null,
                )
            }
        }
    }

    /** Shared text is untrusted: one line, at most 500 characters, or nothing. */
    private fun sharedTextFrom(intent: android.content.Intent?): String? {
        if (intent?.action != android.content.Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return null
        return intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            ?.replace(Regex("\\s+"), " ")?.trim()?.take(500)?.takeIf { it.isNotEmpty() }
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
internal fun AppRoot(controller: AppController, startOnNewTask: Boolean = false, startOnTasks: Boolean = false) {
    val stack = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) {
        when {
            startOnNewTask -> mutableStateListOf("today", "newtask")
            startOnTasks -> mutableStateListOf("today", "tasks")
            else -> mutableStateListOf("today")
        }
    }
    val route = stack.last()
    // Where each two-screen tab was last, so coming back to Plan reopens Calendar if that was in use.
    val lastIn = remember { mutableMapOf<String, String>() }
    bottomTabs.firstOrNull { route in it.routes }?.let { lastIn[it.label] = route }
    // Hide the bottom bar while the keyboard is up, so it does not eat the screen.
    val imeVisible = WindowInsets.isImeVisible
    val isTab = tabs.any { it.route == route } || route in moreRoutes
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
            // The offer is cleared only after the snackbar is gone. Clearing it first would change this
            // effect's key, which cancels the coroutine and dismisses the snackbar the moment it appears.
            val result = snackbar.showSnackbar(offer.text, actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) offer.action()
            if (controller.undo === offer) controller.undo = null
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
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                    title = { Text(titleFor(route, controller), style = MaterialTheme.typography.headlineSmall) },
                    navigationIcon = {
                        if (!isTab) {
                            IconButton(onClick = pop) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        }
                    },
                    actions = {
                        if (isTab) {
                            val hero = remember(controller.version) { controller.read(null) { hero() } }
                            if (hero != null) LevelBadge(hero.level.toInt(), Modifier.padding(end = 4.dp), size = 38.dp)
                            IconButton(onClick = { push("settings") }) { Icon(Icons.Filled.Settings, "Settings") }
                        }
                    },
                )
                if (isTab) {
                    // A thin strip of experience under the title, so progress is always in view.
                    val hero = remember(controller.version) { controller.read(null) { hero() } }
                    if (hero != null && hero.xpForNext > 0u) {
                        XpBar(
                            hero.xpIntoLevel.toFloat() / hero.xpForNext.toFloat(),
                            "Level ${hero.level}, ${hero.xpIntoLevel} of ${hero.xpForNext} XP to the next level",
                            Modifier.padding(horizontal = 16.dp),
                            height = 5.dp,
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (isTab && !imeVisible) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    bottomTabs.forEach { tab ->
                        val selected = route in tab.routes
                        // The selected icon grows a little, so the active tab is easy to spot.
                        val scale by animateFloatAsState(
                            if (selected) 1.15f else 1f, motionTween(LocalMotion.current, 200), label = "tab",
                        )
                        NavigationBarItem(
                            selected = selected,
                            onClick = { if (!selected) selectTab(lastIn[tab.label]?.takeIf { it in tab.routes } ?: tab.home) },
                            icon = { StyledIcon(tabIcon(LocalStyle.current, tab.label, tab.icon), Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.secondary,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
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
                        text = { Text(stringResource(R.string.ui_add)) },
                    )
                    DropdownMenu(addMenu, { addMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_new_task_a_one_off_to_do)) },
                            onClick = { addMenu = false; push("newtask") },
                        )
                        DropdownMenuItem(
                            text = { Text("New habit (something you repeat)") },
                            onClick = { addMenu = false; push("newhabit") },
                        )
                        DropdownMenuItem(
                            text = { Text("New goal (a target to reach)") },
                            onClick = { addMenu = false; push("newgoal") },
                        )
                    }
                }
                "goals" -> ExtendedFloatingActionButton(
                    onClick = { push("newgoal") },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.ui_new_goal)) },
                )
                "habits" -> ExtendedFloatingActionButton(
                    onClick = { push("newhabit") },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.ui_new_habit)) },
                )
                "tasks" -> ExtendedFloatingActionButton(
                    onClick = { push("newtask") },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.ui_new_task)) },
                )
                "calendar" -> ExtendedFloatingActionButton(
                    onClick = { push("newevent") },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.ui_new_event)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) { SwipeableSnackbar(it) } },
    ) { padding ->
        val motion = LocalMotion.current
        // On a tablet or in landscape the content stays a readable width, centered.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            switchers.firstOrNull { options -> options.any { it.first == route } }?.let { options ->
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    options.forEachIndexed { index, (target, label) ->
                        SegmentedButton(
                            selected = route == target,
                            onClick = { if (route != target) selectTab(target) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(label) }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
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
                    onAddGoal = { push("newgoal") },
                    onAddHabit = { push("newhabit") },
                    onAddTask = { push("newtask") },
                    onOpenSettings = { push("settings") },
                    onStartReview = { push("review") },
                )
                route == "routines" -> RoutinesScreen(controller)
                route == "holiday" -> HolidayScreen(controller)
                route == "review" -> WeeklyReviewScreen(controller, onDone = pop)
                route == "tasks" -> TasksScreen(controller, onEdit = { push("edittask/$it") }, onFocus = { push("focus/task/$it") })
                route == "newtask" -> TaskEditorScreen(controller, taskId = null, onDone = pop)
                route.startsWith("edittask/") ->
                    TaskEditorScreen(controller, taskId = route.removePrefix("edittask/"), onDone = pop)
                route == "goals" -> GoalsScreen(controller, habits = false, onOpenGoal = { push("goal/$it") })
                route == "habits" -> GoalsScreen(controller, habits = true, onOpenGoal = { push("goal/$it") })
                route == "newgoal" -> GoalEditorScreen(controller, goalId = null, startKind = GoalKind.YES_NO, onDone = pop)
                route == "newhabit" -> GoalEditorScreen(controller, goalId = null, startKind = GoalKind.HABIT, onDone = pop)
                route.startsWith("focus/") -> route.removePrefix("focus/").split("/").let { parts ->
                    FocusScreen(
                        controller,
                        if (parts.getOrNull(0) == "goal") dev.goaltracker.core.FocusKind.GOAL else dev.goaltracker.core.FocusKind.TASK,
                        parts.getOrNull(1) ?: "",
                        onDone = pop,
                    )
                }
                route == "history" -> HistoryScreen(controller)
                route == "more" -> MoreScreen(controller, onOpen = { push(it) })
                route == "calendar" -> CalendarScreen(
                    controller,
                    onEditEvent = { push("event/$it") },
                    onEditTask = { push("edittask/$it") },
                    onOpenGoal = { push("goal/$it") },
                    onFocus = { kind, id -> push("focus/$kind/$id") },
                )
                route == "newevent" -> EventEditorScreen(controller, eventId = null, onDone = pop)
                route.startsWith("event/") -> EventEditorScreen(controller, eventId = route.removePrefix("event/"), onDone = pop)
                route == "stats" -> StatsScreen(controller, onOpenGoal = { push("goal/$it") })
                route == "treats" -> TreatsScreen(controller, onOpenMonsters = { push("monsters") })
                route == "monsters" -> MonstersScreen(controller, onOpen = { push("monster/$it") })
                route.startsWith("monster/") -> MonsterDetailScreen(controller, route.removePrefix("monster/"), onClose = pop)
                route == "settings" -> SettingsScreen(controller)
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
        }
        }
    }

    controller.monsterVictory?.let { MonsterVictoryDialog(it) { controller.monsterVictory = null } }
    controller.bossVictory?.let { BossVictoryDialog(it) { controller.bossVictory = null } }
    controller.pendingDraw?.let { RewardReveal(controller, it, controller::dismissDraw) }
    CelebrationBurst()
}

private fun titleFor(route: String, controller: AppController): String = when {
    route == "settings" -> "Settings"
    route == "stats" -> "Stats"
    route == "history" -> "History"
    route == "routines" -> "Routines"
    route == "holiday" -> "Holiday mode"
    route == "review" -> "Weekly review"
    route.startsWith("focus/") -> "Focus"
    route == "treats" -> "Rewards"
    route == "newevent" -> "New event"
    route.startsWith("event/") -> "Edit event"
    route == "monsters" -> "Monsters"
    route.startsWith("monster/") -> "Monster"
    route == "newgoal" -> "New goal"
    route == "newhabit" -> "New habit"
    route == "newtask" -> "New task"
    route.startsWith("edittask/") -> "Edit task"
    route.startsWith("edit/") -> "Edit ${goalNoun(controller, route.removePrefix("edit/"))}"
    route.startsWith("goal/") -> goalNoun(controller, route.removePrefix("goal/")).replaceFirstChar { it.uppercase() }
    // firstOrNull, not first: an unlisted route must show a plain title, never crash the app.
    else -> tabs.firstOrNull { it.route == route }?.label ?: "Jarvest"
}

@Composable
private fun RewardReveal(controller: AppController, draw: RewardDraw, onDismiss: () -> Unit) {
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
        title = { Text(stringResource(R.string.ui_you_drew_a_treat), textAlign = TextAlign.Center) },
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
                    else "Earned by ${if (draw.fromMonster) controller.shownMonsterName(draw.goalTitle) else draw.goalTitle}.",
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_nice)) } },
    )
}

/** "habit" or "goal" for the item with this id, so a habit's page never calls itself a goal. */
private fun goalNoun(controller: AppController, id: String): String =
    controller.read("goal") { getGoal(id).noun() }

/** A snackbar that can be swiped left or right to close it. */
@Composable
private fun SwipeableSnackbar(data: androidx.compose.material3.SnackbarData) {
    var offset by remember(data) { mutableStateOf(0f) }
    val width = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }
    androidx.compose.material3.Snackbar(
        data,
        Modifier
            .graphicsLayer {
                translationX = offset
                alpha = 1f - minOf(kotlin.math.abs(offset) / (width * 2), 0.8f)
            }
            .draggable(
                rememberDraggableState { offset += it },
                androidx.compose.foundation.gestures.Orientation.Horizontal,
                onDragStopped = { if (kotlin.math.abs(offset) > width) data.dismiss() else offset = 0f },
            ),
    )
}
