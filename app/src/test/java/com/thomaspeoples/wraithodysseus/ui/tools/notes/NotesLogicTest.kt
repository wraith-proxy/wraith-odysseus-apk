package com.thomaspeoples.wraithodysseus.ui.tools.notes

import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing, ordering, filtering and dates, against the live server's shapes. */
class NotesLogicTest {

    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-09T12:00:00Z")

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private val notes: List<Note> = JSONObject(fixture("notes_list.json")).getJSONArray("notes").let { a ->
        (0 until a.length()).map { NotesApi.parseNote(a.getJSONObject(it)) }
    }

    private fun note(id: String) = notes.first { it.id == id }

    @Test
    fun aChecklistNoteParsesItsItemsAndDueDate() {
        val n = note("n1")
        assertTrue(n.isChecklist)
        assertEquals("todo", n.type)
        assertEquals("", n.content)
        assertEquals(listOf("Milk", "Eggs", "Reply"), n.items.map { it.text })
        assertEquals(listOf(true, false, false), n.items.map { it.done })
        assertEquals(1, n.items[1].indent)
        assertEquals("2026-10-12T09:30", n.dueDate)
        assertEquals("weekly:1", n.repeat)
        assertEquals("green", n.color)
        assertEquals("1/3", progress(n))
    }

    @Test
    fun aTextNoteHasNoItemsAndNullsBecomeDefaults() {
        val n = note("n2")
        assertFalse(n.isChecklist)
        assertEquals("Write the thing", n.content)
        assertTrue(n.items.isEmpty())
        assertEquals("", n.label)
        assertEquals("none", n.repeat)
        assertNull(n.dueDate)
        assertTrue(n.pinned)
        assertEquals("", progress(n))
    }

    @Test
    fun missingTypeIsANoteAndIndentIsCapped() {
        val n = NotesApi.parseNote(JSONObject("""{"id":"z","items":[{"text":"a","indent":9}],"note_type":null}"""))
        assertEquals("note", n.type)
        assertEquals(NotesApi.MAX_INDENT, n.items[0].indent)
    }

    @Test
    fun colourNamesMapToTheWebsPaleFills() {
        assertEquals(0xCCE0BC, noteColorRgb("green"))
        assertEquals(0xE2BCEE, noteColorRgb("purple"))
        assertNull(noteColorRgb(""))
        assertNull(noteColorRgb("bg:/uploads/x.png"))
        assertEquals(6, NOTE_COLORS.size)
    }

    @Test
    fun tagsSplitOnSpacesDropHashesAndAddReminderForATimedDueDate() {
        assertEquals(listOf("home", "errands", "reminder"), noteTags(note("n1")))
        assertTrue(noteTags(note("n2")).isEmpty())
        assertEquals(listOf("errands", "home", "reminder"), allTags(notes.filterNot(::isCalendarNote)))
        assertEquals("a b c", normaliseLabel(" #a  b #a  ##c "))
        assertEquals("", normaliseLabel("   "))
    }

    @Test
    fun pinnedComesFirstThenManualOrderThenNewest() {
        val sorted = sortNotes(notes.filterNot(::isCalendarNote), archivedView = false, now = now, zone = utc)
        // n2 pinned; n1 and n3 share sort_order 2 so the newer n3 leads.
        assertEquals(listOf("n2", "n3", "n1"), sorted.map { it.id })
    }

    @Test
    fun aDueReminderRanksUnderThePinnedBlock() {
        val due = note("n1").copy(dueDate = "2026-10-09T09:00")
        val sorted = sortNotes(listOf(note("n3"), due, note("n2")), false, now, utc)
        assertEquals(listOf("n2", "n1", "n3"), sorted.map { it.id })
        assertTrue(hasActiveReminder(due, now, utc))
        assertFalse(hasActiveReminder(due.copy(archived = true), now, utc))
        assertFalse(hasActiveReminder(due.copy(items = due.items.map { it.copy(done = true) }), now, utc))
        assertFalse(hasActiveReminder(note("n1"), now, utc))
    }

    @Test
    fun theArchivedViewIsNewestChangedFirstIgnoringPins() {
        val sorted = sortNotes(listOf(note("n2"), note("n1"), note("n3")), archivedView = true, now = now, zone = utc)
        assertEquals(listOf("n3", "n1", "n2"), sorted.map { it.id })
    }

    @Test
    fun filterHidesCalendarNotesAndMatchesTagsAndSearch() {
        assertEquals(listOf("n1", "n2", "n3"), filterNotes(notes, null, "").map { it.id })
        assertEquals(listOf("n1", "n3"), filterNotes(notes, "home", "").map { it.id })
        assertEquals(listOf("n1"), filterNotes(notes, null, "EGGS").map { it.id })
        assertEquals(listOf("n2"), filterNotes(notes, null, "thing").map { it.id })
        assertEquals(listOf("n1"), filterNotes(notes, "home", "shop").map { it.id })
        assertEquals(listOf("n1"), filterNotes(notes, "reminder", "").map { it.id })
    }

    @Test
    fun dueTextFollowsTheWebsWording() {
        fun text(due: String) = dueText(due, now, utc, Locale.UK)
        assertEquals(DueText("overdue", true), text("2026-10-09T11:00"))
        assertEquals(DueText("14:30", false), text("2026-10-09T14:30"))
        assertEquals(DueText("today", false), text("2026-10-09"))
        assertEquals(DueText("tomorrow", false), text("2026-10-10"))
        assertEquals(DueText("tomorrow 09:30", false), text("2026-10-10T09:30"))
        assertEquals(DueText("overdue", true), text("2026-10-08"))
        assertEquals(DueText("12 Oct 09:30", false), text("2026-10-12T09:30"))
        assertEquals(DueText("12 Oct", false), text("2026-10-12"))
        assertNull(dueText("soon", now, utc))
        assertNull(dueText(null, now, utc))
    }

    @Test
    fun anOffsetDueDateIsReadAsThatInstant() {
        val at = parseDue("2026-10-12T09:30:00Z", ZoneOffset.ofHours(2))!!
        assertEquals(11, at.hour)
    }

    @Test
    fun repeatIsPutInWords() {
        assertEquals("", repeatText("none"))
        assertEquals("weekly", repeatText("weekly:1"))
        assertEquals("monthly", repeatText("monthly:nth:2:3"))
        assertEquals("daily", repeatText("daily"))
    }

    @Test
    fun textAndChecklistConvertBothWays() {
        var n = 0
        val items = textToItems("  one\n\n two \n", { "id${n++}" })
        assertEquals(listOf("one", "two"), items.map { it.text })
        assertEquals(listOf("id0", "id1"), items.map { it.id })
        assertEquals("one\ntwo", itemsToText(items))
    }

    @Test
    fun movingAnIdStopsAtTheEnds() {
        assertEquals(listOf("b", "a", "c"), moveId(listOf("a", "b", "c"), "a", 1))
        assertEquals(listOf("a", "c", "b"), moveId(listOf("a", "b", "c"), "c", -1))
        assertEquals(listOf("a", "b"), moveId(listOf("a", "b"), "a", -1))
        assertEquals(listOf("a", "b"), moveId(listOf("a", "b"), "z", 1))
    }

    @Test
    fun aSavedNoteGoesAboveTheUnpinnedOnes() {
        val list = listOf(note("n1").copy(sortOrder = -3), note("n2").copy(sortOrder = -9, pinned = true))
        assertEquals(-4, topSortOrder(list, null))
        assertEquals(-1, topSortOrder(list, "n1"))
        assertEquals(-1, topSortOrder(emptyList(), null))
    }
}
