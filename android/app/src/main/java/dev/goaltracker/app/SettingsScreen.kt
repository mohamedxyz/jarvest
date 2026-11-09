package dev.goaltracker.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.IOException

private const val MAX_FILE_BYTES = 5_000_000

@Composable
fun SettingsScreen(store: Store, onBack: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pending by remember { mutableStateOf<List<Item>?>(null) }

    fun say(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ok = try {
                val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No file")
                out.use { it.write(Checklist.toJson(store.items).toByteArray()) }
                true
            } catch (e: IOException) {
                false
            }
            say(if (ok) "Exported ${countOf(store.items.size)}" else "Couldn't save the file.")
        }
    }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                pending = Checklist.fromJson(readText(context, uri), store.today())
            } catch (e: ImportError) {
                say(e.message ?: "That file isn't a Jarvest list.")
            } catch (e: IOException) {
                say("Couldn't read the file.")
            }
        }
    }

    Column(Modifier.fillMaxSize().background(p.grouped).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = p.blue) }
            Text("Settings", color = p.label, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Header("Delete finished tasks")
            Card {
                val options = listOf(-1 to "Never", 1 to "After 1 day", 7 to "After 7 days", 30 to "After 30 days")
                options.forEachIndexed { index, (days, label) ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = p.separator)
                    Row(
                        Modifier.fillMaxWidth().clickable { store.chooseAutoDelete(days) }.heightIn(min = 48.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, Modifier.weight(1f), color = p.label, fontSize = 17.sp)
                        if (store.autoDeleteDays == days) Icon(Icons.Default.Check, "Selected", tint = p.blue)
                    }
                }
            }
            Footer("A finished task is deleted by itself once it has been done this long. Tasks you haven't finished are never deleted.")

            Header("Your tasks")
            Card {
                Action("Export tasks") { export.launch("jarvest-tasks.json") }
                HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = p.separator)
                Action("Import tasks") { import.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
            }
            Footer("Export saves a file you can keep. Import replaces every task on this phone with the ones in a file.")

            val version = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } catch (e: Exception) {
                    ""
                }
            }
            Text(
                "Jarvest $version",
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                color = p.secondary,
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        SnackbarHost(snackbar)
    }

    pending?.let { incoming ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Replace your tasks?") },
            text = {
                Text("Your ${countOf(store.items.size)} will be replaced by the ${countOf(incoming.size)} in the file.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val before = store.items
                    store.replaceAll(incoming)
                    pending = null
                    scope.launch {
                        val result = snackbar.showSnackbar("Imported ${countOf(incoming.size)}", actionLabel = "Undo")
                        if (result == SnackbarResult.ActionPerformed) store.replaceAll(before)
                    }
                }) { Text("Replace", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel", color = p.blue) } },
        )
    }
}

private fun countOf(n: Int) = if (n == 1) "1 task" else "$n tasks"

/** Reads a small text file, and refuses a big one. */
private fun readText(context: Context, uri: Uri): String {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No file")
    input.use {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = it.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_FILE_BYTES) throw ImportError("That file is too big.")
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
        color = LocalPalette.current.secondary,
        fontSize = 13.sp,
    )
}

@Composable
private fun Footer(text: String) {
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), color = LocalPalette.current.secondary, fontSize = 13.sp)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LocalPalette.current.card)) { content() }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 13.dp),
        color = LocalPalette.current.blue,
        fontSize = 17.sp,
    )
}
