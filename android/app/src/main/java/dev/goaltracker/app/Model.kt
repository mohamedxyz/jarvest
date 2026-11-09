package dev.goaltracker.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One line in the list. A task with `parentId == null` is a main task; otherwise it is a subtask of
 * that main task. Subtasks can't have subtasks. `doneDay` is the phone's date (as days since 1970)
 * the task was finished, which automatic deleting reads.
 */
data class Item(
    val id: Long,
    val parentId: Long?,
    val title: String,
    val done: Boolean,
    val doneDay: Long?,
)

/** The file isn't a list this app can read. The message is safe to show. */
class ImportError(message: String) : Exception(message)

/**
 * All the rules of the list, as plain functions on plain lists, so they are easy to test.
 * The order of the list is the order on screen. `today` always comes from the caller.
 *
 * The one rule that matters: a main task with subtasks is done exactly when all its subtasks are.
 */
object Checklist {
    const val MAX_TITLE = 500
    const val MAX_ITEMS = 10_000

    private fun Item.withDone(done: Boolean, today: Long): Item =
        if (done) copy(done = true, doneDay = (if (this.done) doneDay else null) ?: today)
        else copy(done = false, doneDay = null)

    /** Makes a main task match its subtasks. Does nothing for a task without subtasks. */
    private fun syncParent(items: List<Item>, parentId: Long, today: Long): List<Item> {
        val kids = items.filter { it.parentId == parentId }
        if (kids.isEmpty()) return items
        val allDone = kids.all { it.done }
        return items.map { if (it.id == parentId && it.done != allDone) it.withDone(allDone, today) else it }
    }

    /**
     * Adds a task and returns the new list with the new id. The title may be blank: that is the empty
     * row you type into. A new subtask reopens its main task. With [afterId] the task goes right
     * after that one, otherwise at the end.
     */
    fun add(items: List<Item>, title: String, parentId: Long?, afterId: Long?, today: Long): Pair<List<Item>, Long> {
        if (parentId != null) {
            require(items.any { it.id == parentId && it.parentId == null }) { "A subtask needs a main task" }
        }
        val id = (items.maxOfOrNull { it.id } ?: 0L) + 1L
        val item = Item(id, parentId, title.take(MAX_TITLE), done = false, doneDay = null)
        val list = items.toMutableList()
        val at = list.indexOfFirst { it.id == afterId }
        if (at >= 0) list.add(at + 1, item) else list.add(item)
        val result = if (parentId == null) list else list.map { if (it.id == parentId) it.withDone(false, today) else it }
        return result to id
    }

    /**
     * Ticks or unticks a task. A main task takes all its subtasks with it. A subtask updates its
     * main task: the last one ticked finishes it, and unticking any of them reopens it.
     */
    fun toggle(items: List<Item>, id: Long, today: Long): List<Item> {
        val item = items.firstOrNull { it.id == id } ?: return items
        val parentId = item.parentId
        return if (parentId == null) {
            val done = !item.done
            items.map { if (it.id == id || it.parentId == id) it.withDone(done, today) else it }
        } else {
            syncParent(items.map { if (it.id == id) it.withDone(!it.done, today) else it }, parentId, today)
        }
    }

    /** Renames a task. A blank name deletes it, which is how an empty new row goes away. */
    fun rename(items: List<Item>, id: Long, title: String, today: Long): List<Item> {
        val clean = title.trim().take(MAX_TITLE)
        if (clean.isEmpty()) return delete(items, setOf(id), today)
        return items.map { if (it.id == id) it.copy(title = clean) else it }
    }

    /**
     * Deletes tasks, and the subtasks of any main task deleted. If that leaves a main task whose
     * remaining subtasks are all done, it becomes done too.
     */
    fun delete(items: List<Item>, ids: Set<Long>, today: Long): List<Item> {
        val gone = items.filter { it.id in ids || it.parentId in ids }.map { it.id }.toSet()
        val parents = items
            .filter { it.id in gone && it.parentId != null && it.parentId !in gone }
            .mapNotNull { it.parentId }
            .toSet()
        var result = items.filter { it.id !in gone }
        for (parent in parents) result = syncParent(result, parent, today)
        return result
    }

    /** Deletes finished main tasks (with their subtasks) done [afterDays] or more days ago. Negative means never. */
    fun autoDelete(items: List<Item>, afterDays: Int, today: Long): List<Item> {
        if (afterDays < 0) return items
        val cutoff = today - afterDays
        val old = items
            .filter { it.parentId == null && it.done && (it.doneDay ?: today) <= cutoff }
            .map { it.id }
            .toSet()
        return if (old.isEmpty()) items else items.filter { it.id !in old && it.parentId !in old }
    }

    /**
     * Cleans a list that came from a file: drops blank titles, repeated ids, subtasks of nothing or
     * of subtasks, and anything past the limit, then makes every main task match its subtasks.
     */
    fun normalize(raw: List<Item>, today: Long): List<Item> {
        val seen = HashSet<Long>()
        val named = raw
            .map { it.copy(title = it.title.trim().take(MAX_TITLE)) }
            .filter { it.title.isNotEmpty() && seen.add(it.id) }
            .take(MAX_ITEMS)
        val mains = named.filter { it.parentId == null }.map { it.id }.toSet()
        var items = named
            .filter { it.parentId == null || it.parentId in mains }
            .map { it.copy(doneDay = if (it.done) it.doneDay ?: today else null) }
        for (main in mains) items = syncParent(items, main, today)
        return items
    }

    fun toJson(items: List<Item>): String {
        val tasks = JSONArray()
        for (item in items) {
            val o = JSONObject()
            o.put("id", item.id)
            if (item.parentId != null) o.put("parent", item.parentId)
            o.put("title", item.title)
            o.put("done", item.done)
            if (item.doneDay != null) o.put("doneDay", item.doneDay)
            tasks.put(o)
        }
        return JSONObject().put("app", "jarvest").put("version", 1).put("tasks", tasks).toString(2)
    }

    /** Reads a list written by [toJson]. Throws [ImportError] for anything else. */
    fun fromJson(text: String, today: Long): List<Item> {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw ImportError("That file isn't a Jarvest list.")
        }
        val array = root.optJSONArray("tasks") ?: throw ImportError("That file isn't a Jarvest list.")
        if (array.length() > MAX_ITEMS) throw ImportError("That list is too big (more than $MAX_ITEMS tasks).")
        val raw = ArrayList<Item>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.opt("id") !is Number) continue
            raw.add(
                Item(
                    id = o.getLong("id"),
                    parentId = if (o.opt("parent") is Number) o.getLong("parent") else null,
                    title = o.optString("title", ""),
                    done = o.optBoolean("done", false),
                    doneDay = if (o.opt("doneDay") is Number) o.getLong("doneDay") else null,
                ),
            )
        }
        return normalize(raw, today)
    }
}
