package dev.goaltracker.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * Keeps the list in memory and in one file (`tasks.json`) on the phone. Every change goes through
 * [Checklist], then is saved at once. The list is small, so saving the whole file is quick.
 */
class Store(context: Context) {
    private val file = File(context.filesDir, "tasks.json")
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var items by mutableStateOf(load())
        private set

    /** Days after finishing that a main task is deleted by itself. Negative means never. */
    var autoDeleteDays by mutableIntStateOf(prefs.getInt("auto_delete_days", -1))
        private set

    /** Set when the last save failed, so the screen can say so. */
    var saveFailed by mutableStateOf(false)

    fun today(): Long = LocalDate.now().toEpochDay()

    private fun load(): List<Item> {
        if (!file.exists()) return emptyList()
        return try {
            Checklist.fromJson(file.readText(), today())
        } catch (e: Exception) {
            // Keep the unreadable file instead of overwriting it with an empty list.
            try {
                file.copyTo(File(file.parentFile, "tasks.broken.json"), overwrite = true)
            } catch (_: IOException) {
            }
            emptyList()
        }
    }

    private fun set(list: List<Item>) {
        items = list
        try {
            val temp = File(file.parentFile, "tasks.json.tmp")
            temp.writeText(Checklist.toJson(list))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            saveFailed = false
        } catch (e: IOException) {
            saveFailed = true
        }
    }

    /** Adds a task and returns its id. A blank [title] makes the empty row you type into. */
    fun add(title: String, parentId: Long?, afterId: Long?): Long {
        val (list, id) = Checklist.add(items, title, parentId, afterId, today())
        set(list)
        return id
    }

    fun toggle(id: Long) = set(Checklist.toggle(items, id, today()))

    fun rename(id: Long, title: String) = set(Checklist.rename(items, id, title, today()))

    fun delete(ids: Set<Long>) = set(Checklist.delete(items, ids, today()))

    /** Swaps in a whole list: for Undo and for Import. */
    fun replaceAll(list: List<Item>) = set(Checklist.normalize(list, today()))

    fun chooseAutoDelete(days: Int) {
        autoDeleteDays = days
        prefs.edit().putInt("auto_delete_days", days).apply()
        runAutoDelete()
    }

    /** Deletes finished tasks that are old enough. Runs when the app opens and when the setting changes. */
    fun runAutoDelete() {
        val next = Checklist.autoDelete(items, autoDeleteDays, today())
        if (next.size != items.size) set(next)
    }
}
