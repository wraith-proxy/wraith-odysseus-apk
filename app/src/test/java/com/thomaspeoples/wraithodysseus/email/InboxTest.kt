package com.thomaspeoples.wraithodysseus.email

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxTest {

    private fun mail(
        uid: String,
        epoch: String = "2026-10-08T10:00:00Z",
        flagged: Boolean = false,
        answered: Boolean = false,
        attachments: Boolean = false,
        folder: String = "INBOX",
        name: String = "Carol Sample",
    ) = EmailSummary(
        "acc", folder, uid, "<$uid@x>", "s$uid", name, "carol@example.com",
        Instant.parse(epoch).epochSecond.toDouble(), attachments, isFlagged = flagged, isAnswered = answered,
    )

    // -- folders and filters ---------------------------------------------

    @Test
    fun foldersGoInTheWebOrderThenTheRest() {
        val server = listOf("Drafts", "Receipts", "[Gmail]/Trash", "INBOX", "Junk", "Archive", "Starred", "Sent", "Projects")
        assertEquals(
            listOf("INBOX", "Sent", "Starred", "Archive", "Junk", "[Gmail]/Trash", "Drafts", "Receipts", "Projects"),
            sortedFolders(server),
        )
    }

    @Test
    fun aMissingTrashOrDraftsIsStillOfferedLast() {
        assertEquals(listOf("INBOX", "Sent", "Notes", "Trash", "Drafts"), sortedFolders(listOf("Notes", "Sent", "INBOX")))
    }

    @Test
    fun folderNamesReadLikeTheWebs() {
        assertEquals("Inbox", folderDisplayName("INBOX"))
        assertEquals("Archive / All Mail", folderDisplayName("[Gmail]/All Mail"))
        assertEquals("Sent", folderDisplayName("INBOX.Sent"))
        assertEquals("Scheduled", folderDisplayName(SCHEDULED_FOLDER))
        assertEquals("Receipts", folderDisplayName("Receipts"))
    }

    @Test
    fun filterValuesAreTheOnesTheServerKnows() {
        assertEquals(
            listOf("all", "unread", "favorites", "undone", "reminders", "unanswered", "pending_30d", "stale_30d"),
            InboxFilters.STATUS.map { it.value },
        )
        assertEquals(
            listOf("tag:urgent", "tag:reply-soon", "tag:bills", "tag:receipt", "tag:travel", "tag:spam"),
            InboxFilters.TAGS.map { it.value },
        )
        assertEquals("Reply soon", InboxFilters.label("tag:reply-soon"))
    }

    // -- rows -------------------------------------------------------------

    @Test
    fun senderHueMatchesTheWebsHash() {
        // Computed with the web's `(hash << 5) - hash + charCode | 0` loop.
        assertEquals(27, senderHue("GitHub"))
        assertEquals(27, senderHue("github"))
        assertEquals(184, senderHue("Thomas Peoples"))
        assertEquals(220, senderHue(""))
        // A hash that goes negative still lands in 0..359.
        assertTrue(senderHue("a rather long sender name that overflows an int").let { it in 0..359 })
    }

    @Test
    fun sentRowsShowTheRecipient() {
        val sent = mail("1").copy(to = "\"Dan Dee\" <dan@x.com>, e@x.com")
        assertEquals("Dan Dee", rowName(sent, sent = true))
        assertEquals("e", rowName(sent.copy(to = "e@x.com"), sent = true))
        assertEquals("Carol Sample", rowName(sent, sent = false))
        assertTrue(isSentFolder("[Gmail]/Sent Mail"))
    }

    @Test
    fun favouritesPinFirstWithinEachDateGroupOnly() {
        val now = Instant.parse("2026-10-09T12:00:00Z")
        val groups = groupByDate(
            listOf(
                mail("1", "2026-10-09T11:00:00Z"),
                mail("2", "2026-10-09T10:00:00Z", flagged = true),
                mail("3", "2026-10-09T09:00:00Z", flagged = true),
                mail("4", "2026-10-08T09:00:00Z"),
                mail("5", "2026-10-08T08:00:00Z", flagged = true),
            ),
            now, ZoneOffset.UTC, Locale.ENGLISH,
        )
        assertEquals(listOf("Today", "Yesterday"), groups.map { it.label })
        assertEquals(listOf("2", "3", "1"), groups[0].emails.map { it.uid })
        assertEquals(listOf("5", "4"), groups[1].emails.map { it.uid })
    }

    // -- the Last: line ---------------------------------------------------

    @Test
    fun lastSyncedUsesTheWebsSteps() {
        val now = Instant.parse("2026-10-09T12:00:00Z")
        assertEquals("Last: just now", lastSynced("2026-10-09T11:59:30Z", now))
        assertEquals("Last: 5m ago", lastSynced("2026-10-09T11:55:00Z", now))
        assertEquals("Last: 3h ago", lastSynced("2026-10-09T09:00:00Z", now))
        assertEquals("Last: 2d ago", lastSynced("2026-10-07T12:00:00Z", now))
        assertEquals("", lastSynced(null, now))
        assertEquals("", lastSynced("garbage", now))
    }

    // -- reminders --------------------------------------------------------

    @Test
    fun reminderPresetsFollowTheWeb() {
        // Friday 9 Oct 2026, 10:00: 18:00 is far enough away.
        val morning = reminderPresets(LocalDateTime.of(2026, 10, 9, 10, 0)).associate { it.label to it.due }
        assertEquals(LocalDateTime.of(2026, 10, 9, 18, 0), morning["Later today"])
        assertEquals(LocalDateTime.of(2026, 10, 10, 8, 0), morning["Tomorrow"])
        // Friday: the coming Monday.
        assertEquals(LocalDateTime.of(2026, 10, 12, 8, 0), morning["Next week"])
        // Within the hour before 18:00, or after it: three hours on.
        val late = reminderPresets(LocalDateTime.of(2026, 10, 9, 17, 30)).first().due
        assertEquals(LocalDateTime.of(2026, 10, 9, 20, 30), late)
        // On a Monday, "next week" is the following Monday.
        val monday = reminderPresets(LocalDateTime.of(2026, 10, 12, 9, 0)).last().due
        assertEquals(LocalDateTime.of(2026, 10, 19, 8, 0), monday)
    }

    @Test
    fun reminderNoteIsWhatTheWebPosts() {
        val e = mail("42", folder = "[Gmail]/All Mail", name = "carol jones")
            .copy(subject = "Dinner & plans")
        val note = reminderNote(e, LocalDateTime.of(2026, 10, 10, 8, 0), "https://odysseus.example")
        assertEquals("Reply: Dinner & plans", note.getString("title"))
        assertEquals("todo", note.getString("note_type"))
        val item = note.getJSONArray("items").getJSONObject(0)
        assertEquals("Reply to Carol: Dinner & plans", item.getString("text"))
        assertFalse(item.getBoolean("checked"))
        assertEquals("Open email: https://odysseus.example/#email=%5BGmail%5D%2FAll%20Mail:42", note.getString("content"))
        assertEquals("email reminder", note.getString("label"))
        assertEquals("email", note.getString("source"))
        assertEquals("2026-10-10T08:00", note.getString("due_date"))
    }

    @Test
    fun reminderNoteWithoutATimeHasNoDueDateAndFallsBackOnNames() {
        val e = mail("1", name = "").copy(subject = "", fromAddress = "")
        val note = reminderNote(e, null, "https://o.example")
        assertFalse(note.has("due_date"))
        assertEquals("Reply: (no subject)", note.getString("title"))
        assertEquals("Reply to someone: (no subject)", note.getJSONArray("items").getJSONObject(0).getString("text"))
    }

    // -- search -----------------------------------------------------------

    @Test
    fun searchMergesLocalFirstThenServerNewestFirstWithoutRepeats() {
        val local = listOf(mail("1", "2026-10-01T00:00:00Z"), mail("2", "2026-10-05T00:00:00Z"))
        val server = listOf(
            mail("3", "2026-10-02T00:00:00Z"),
            mail("2", "2026-10-05T00:00:00Z"),
            mail("4", "2026-10-07T00:00:00Z"),
        )
        assertEquals(listOf("1", "2", "4", "3"), mergeSearch(local, server).map { it.uid })
        assertEquals(listOf("4", "2", "3"), mergeSearch(emptyList(), server).map { it.uid })
    }

    @Test
    fun searchOptionsNarrowTheResults() {
        val rows = listOf(
            mail("1", "2026-10-01T12:00:00Z", answered = true),
            mail("2", "2026-10-05T12:00:00Z", attachments = true),
            mail("3", "2026-10-09T12:00:00Z"),
        )
        fun uids(o: SearchOptions) = applySearchOptions(rows, o, ZoneOffset.UTC).map { it.uid }
        assertEquals(listOf("1", "2", "3"), uids(SearchOptions()))
        assertEquals(listOf("2", "3"), uids(SearchOptions(notDone = true)))
        assertEquals(listOf("2"), uids(SearchOptions(attachments = true)))
        assertEquals(listOf("2"), uids(SearchOptions(from = LocalDate.of(2026, 10, 2), to = LocalDate.of(2026, 10, 5))))
        assertEquals(listOf("3"), uids(SearchOptions(from = LocalDate.of(2026, 10, 9))))
        assertTrue(SearchOptions(notDone = true).active)
        assertFalse(SearchOptions().active)
    }

    @Test
    fun theSearchMinimumIsTwo() {
        assertEquals(2, EmailApi.MIN_SEARCH)
    }
}
