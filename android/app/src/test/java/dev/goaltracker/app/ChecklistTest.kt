package dev.goaltracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChecklistTest {
    private val today = 20_000L

    /** Builds a list from titles: "Main" or "-Sub" (a subtask of the main task above it). */
    private fun list(vararg lines: String): List<Item> {
        var items = emptyList<Item>()
        var main: Long? = null
        for (line in lines) {
            val sub = line.startsWith("-")
            val (next, id) = Checklist.add(items, line.removePrefix("-"), if (sub) main else null, null, today)
            items = next
            if (!sub) main = id
        }
        return items
    }

    private fun Item?.isDone() = this!!.done
    private fun List<Item>.titled(title: String) = firstOrNull { it.title == title }

    @Test
    fun finishingTheLastSubtaskFinishesTheMainTask() {
        var items = list("Trip", "-Passport", "-Tickets")
        items = Checklist.toggle(items, items.titled("Passport")!!.id, today)
        assertFalse(items.titled("Trip").isDone())
        items = Checklist.toggle(items, items.titled("Tickets")!!.id, today)
        assertTrue(items.titled("Trip").isDone())
        assertEquals(today, items.titled("Trip")!!.doneDay)
    }

    @Test
    fun untickingASubtaskReopensTheMainTask() {
        var items = list("Trip", "-Passport")
        items = Checklist.toggle(items, items.titled("Passport")!!.id, today)
        assertTrue(items.titled("Trip").isDone())
        items = Checklist.toggle(items, items.titled("Passport")!!.id, today)
        assertFalse(items.titled("Trip").isDone())
        assertNull(items.titled("Trip")!!.doneDay)
    }

    @Test
    fun tickingAMainTaskTicksAllItsSubtasksAndUntickingClearsThem() {
        var items = list("Trip", "-Passport", "-Tickets", "Other")
        items = Checklist.toggle(items, items.titled("Trip")!!.id, today)
        assertTrue(items.filter { it.title != "Other" }.all { it.done })
        assertFalse(items.titled("Other").isDone())
        items = Checklist.toggle(items, items.titled("Trip")!!.id, today)
        assertTrue(items.none { it.done })
    }

    @Test
    fun aNewSubtaskReopensAFinishedMainTask() {
        var items = list("Trip")
        items = Checklist.toggle(items, items.titled("Trip")!!.id, today)
        val (next, _) = Checklist.add(items, "Pack", items.titled("Trip")!!.id, null, today)
        assertFalse(next.titled("Trip").isDone())
    }

    @Test
    fun deletingAMainTaskDeletesItsSubtasks() {
        val items = list("Trip", "-Passport", "Other")
        val left = Checklist.delete(items, setOf(items.titled("Trip")!!.id), today)
        assertEquals(listOf("Other"), left.map { it.title })
    }

    @Test
    fun deletingTheLastOpenSubtaskFinishesTheMainTask() {
        var items = list("Trip", "-Passport", "-Tickets")
        items = Checklist.toggle(items, items.titled("Passport")!!.id, today)
        items = Checklist.delete(items, setOf(items.titled("Tickets")!!.id), today)
        assertTrue(items.titled("Trip").isDone())
    }

    @Test
    fun aBlankNameDeletesTheTask() {
        val items = list("Trip", "Other")
        val left = Checklist.rename(items, items.titled("Trip")!!.id, "   ", today)
        assertEquals(listOf("Other"), left.map { it.title })
    }

    @Test
    fun aNewTaskGoesAfterTheOneGiven() {
        val items = list("A", "B", "C")
        val (next, _) = Checklist.add(items, "X", null, items.titled("A")!!.id, today)
        assertEquals(listOf("A", "X", "B", "C"), next.map { it.title })
    }

    @Test
    fun aSubtaskNeedsAMainTask() {
        val items = list("Trip", "-Passport")
        try {
            Checklist.add(items, "Nested", items.titled("Passport")!!.id, null, today)
            fail("a subtask of a subtask should be refused")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun autoDeleteTakesOldFinishedMainTasksOnly() {
        var items = list("Old", "Recent", "Open", "Parent", "-Child")
        items = Checklist.toggle(items, items.titled("Old")!!.id, today - 10)
        items = Checklist.toggle(items, items.titled("Recent")!!.id, today - 1)
        assertEquals(items, Checklist.autoDelete(items, -1, today))
        val week = Checklist.autoDelete(items, 7, today)
        assertEquals(listOf("Recent", "Open", "Parent", "Child"), week.map { it.title })
        val day = Checklist.autoDelete(items, 1, today)
        assertEquals(listOf("Open", "Parent", "Child"), day.map { it.title })
    }

    @Test
    fun autoDeleteTakesTheSubtasksOfAFinishedTask() {
        var items = list("Trip", "-Passport")
        items = Checklist.toggle(items, items.titled("Passport")!!.id, today - 5)
        assertTrue(Checklist.autoDelete(items, 3, today).isEmpty())
    }

    @Test
    fun aListSurvivesExportAndImport() {
        var items = list("Trip", "-Passport", "-Tickets", "Other")
        items = Checklist.toggle(items, items.titled("Passport")!!.id, today)
        val back = Checklist.fromJson(Checklist.toJson(items), today)
        assertEquals(items, back)
    }

    @Test
    fun importRefusesFilesThatAreNotAList() {
        for (text in listOf("", "hello", "[]", "{\"tasks\": 5}", "{}")) {
            try {
                Checklist.fromJson(text, today)
                fail("should refuse: $text")
            } catch (e: ImportError) {
                // expected
            }
        }
    }

    @Test
    fun importCleansBadRows() {
        val text = """{"tasks":[
            {"id":1,"title":"Main","done":false},
            {"id":2,"parent":1,"title":"Sub","done":true},
            {"id":2,"title":"Repeated id"},
            {"id":3,"title":"   "},
            {"id":4,"parent":99,"title":"Orphan"},
            {"id":5,"parent":2,"title":"Sub of a sub"},
            {"title":"No id"},
            "junk"
        ]}"""
        val items = Checklist.fromJson(text, today)
        assertEquals(listOf("Main", "Sub"), items.map { it.title })
        // The only subtask is done, so the main task is too.
        assertTrue(items.titled("Main").isDone())
        assertEquals(today, items.titled("Sub")!!.doneDay)
    }

    @Test
    fun importRefusesTooManyTasks() {
        val rows = (1..Checklist.MAX_ITEMS + 1).joinToString(",") { """{"id":$it,"title":"t"}""" }
        try {
            Checklist.fromJson("""{"tasks":[$rows]}""", today)
            fail("should refuse a huge list")
        } catch (e: ImportError) {
            // expected
        }
    }
}
