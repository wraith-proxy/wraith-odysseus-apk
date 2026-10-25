package com.thomaspeoples.wraithodysseus.demo

import java.time.Instant
import java.time.ZoneId

private val mailNow: Instant = Instant.now()
private val mailStartOfDay: Instant = mailNow.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()

private val mailT1: Instant = mailNow.minusSeconds(300)
private val mailT2: Instant = mailStartOfDay.plusSeconds((mailNow.epochSecond - mailStartOfDay.epochSecond) / 2 + 1)
private val mailT3: Instant = mailStartOfDay.minusSeconds(12 * 3600)
private val mailT4: Instant = mailStartOfDay.minusSeconds(36 * 3600)
private val mailT5: Instant = mailStartOfDay.minusSeconds(60 * 3600)

private val mailE1: Long = mailT1.epochSecond
private val mailE2: Long = mailT2.epochSecond
private val mailE3: Long = mailT3.epochSecond
private val mailE4: Long = mailT4.epochSecond
private val mailE5: Long = mailT5.epochSecond

private val mailSynced: String = mailNow.minusSeconds(120).toString()

internal val demoMail: Map<String, String> = mapOf(
    "GET /api/email/accounts" to """
{"accounts": [
  {"id": "acc-demo-1", "name": "Demo Personal", "is_default": true, "enabled": true, "from_address": "demo@example.com"},
  {"id": "acc-demo-2", "name": "Demo Work", "is_default": false, "enabled": true, "from_address": "demo.work@example.com"}
]}
""",
    "GET /api/email/folders" to """
{"folders": ["INBOX", "Sent", "Archive", "Trash"]}
""",
    "GET /api/email/unread-state" to """
{"unread_count": 3, "max_uid": 105}
""",
    "GET /api/email/scheduled" to """
{"scheduled": []}
""",
    "GET /api/email/list" to """
{"folder": "INBOX", "total": 5, "sync": {"updated_at": "${mailSynced}"}, "emails": [
  {"uid": "105", "folder": "INBOX", "message_id": "<m105@example.com>", "subject": "Q3 budget draft for your review", "from_name": "Priya Nair", "from_address": "priya.nair@example.com", "date_epoch": ${mailE1}, "has_attachments": true, "is_read": false, "is_answered": false, "is_flagged": false, "to": "Demo <demo@example.com>"},
  {"uid": "104", "folder": "INBOX", "message_id": "<m104@example.com>", "subject": "Welcome to the team workspace", "from_name": "Team Updates", "from_address": "updates@example.com", "date_epoch": ${mailE2}, "has_attachments": false, "is_read": false, "is_answered": false, "is_flagged": true, "to": "Demo <demo@example.com>"},
  {"uid": "103", "folder": "INBOX", "message_id": "<m103@example.com>", "subject": "Lunch on Thursday?", "from_name": "Marcus Lee", "from_address": "marcus.lee@example.com", "date_epoch": ${mailE3}, "has_attachments": false, "is_read": false, "is_answered": false, "is_flagged": false, "to": "Demo <demo@example.com>"},
  {"uid": "102", "folder": "INBOX", "message_id": "<m102@example.com>", "subject": "Re: Venue booking confirmation", "from_name": "Sofia Alvarez", "from_address": "sofia.alvarez@example.com", "date_epoch": ${mailE4}, "has_attachments": false, "is_read": true, "is_answered": true, "is_flagged": false, "to": "Demo <demo@example.com>"},
  {"uid": "101", "folder": "INBOX", "message_id": "<m101@example.com>", "subject": "Notes from Monday's planning call", "from_name": "Jonas Weber", "from_address": "jonas.weber@example.com", "date_epoch": ${mailE5}, "has_attachments": false, "is_read": true, "is_answered": false, "is_flagged": false, "to": "Demo <demo@example.com>"}
]}
""",
    "GET /api/email/search" to """
{"folder": "INBOX", "total": 2, "sync": {"updated_at": "${mailSynced}"}, "emails": [
  {"uid": "105", "folder": "INBOX", "message_id": "<m105@example.com>", "subject": "Q3 budget draft for your review", "from_name": "Priya Nair", "from_address": "priya.nair@example.com", "date_epoch": ${mailE1}, "has_attachments": true, "is_read": false, "is_answered": false, "is_flagged": false, "to": "Demo <demo@example.com>"},
  {"uid": "102", "folder": "INBOX", "message_id": "<m102@example.com>", "subject": "Re: Venue booking confirmation", "from_name": "Sofia Alvarez", "from_address": "sofia.alvarez@example.com", "date_epoch": ${mailE4}, "has_attachments": false, "is_read": true, "is_answered": true, "is_flagged": false, "to": "Demo <demo@example.com>"}
]}
""",
    "GET /api/email/read/105" to """
{"uid": "105", "folder": "INBOX", "message_id": "<m105@example.com>", "subject": "Q3 budget draft for your review", "from_name": "Priya Nair", "from_address": "priya.nair@example.com", "to": "Demo <demo@example.com>", "cc": "", "date": "${mailT1}", "references": "", "body": "Hi Demo,\n\nI attached the first draft of the Q3 budget. The main changes are the travel line and the new tooling costs.\n\nCould you look it over before Friday?\n\nThanks,\nPriya", "body_html": null, "mark_seen_failed": false, "attachments_deferred": false, "attachments": [
  {"index": 0, "filename": "Q3-budget-draft.pdf", "name": "Q3-budget-draft.pdf", "size": 184320, "content_type": "application/pdf", "mime": "application/pdf"}
]}
""",
    "GET /api/email/read/104" to """
{"uid": "104", "folder": "INBOX", "message_id": "<m104@example.com>", "subject": "Welcome to the team workspace", "from_name": "Team Updates", "from_address": "updates@example.com", "to": "Demo <demo@example.com>", "cc": "", "date": "${mailT2}", "references": "", "body": "Welcome to the team workspace!\n\nHere is how to get started:\n- Set up your profile\n- Join the channels you care about\n- Say hello\n\nSee you inside.", "body_html": "<html><body style=\"font-family:sans-serif\"><h2>Welcome to the team workspace!</h2><p>Here is how to get started:</p><ul><li>Set up your profile</li><li>Join the channels you care about</li><li>Say hello</li></ul><p><b>See you inside.</b></p></body></html>", "mark_seen_failed": false, "attachments_deferred": false, "attachments": []}
""",
    "GET /api/email/read/103" to """
{"uid": "103", "folder": "INBOX", "message_id": "<m103@example.com>", "subject": "Lunch on Thursday?", "from_name": "Marcus Lee", "from_address": "marcus.lee@example.com", "to": "Demo <demo@example.com>", "cc": "", "date": "${mailT3}", "references": "", "body": "Hey Demo,\n\nAre you free for lunch on Thursday? There is a new place near the office I would like to try.\n\nMarcus", "body_html": null, "mark_seen_failed": false, "attachments_deferred": false, "attachments": []}
""",
    "GET /api/email/read/102" to """
{"uid": "102", "folder": "INBOX", "message_id": "<m102@example.com>", "subject": "Re: Venue booking confirmation", "from_name": "Sofia Alvarez", "from_address": "sofia.alvarez@example.com", "to": "Demo <demo@example.com>", "cc": "", "date": "${mailT4}", "references": "<m100@example.com>", "body": "Hi Demo,\n\nThe venue is confirmed for the 14th, with setup from 9am. I will send the final headcount next week.\n\nBest,\nSofia", "body_html": null, "mark_seen_failed": false, "attachments_deferred": false, "attachments": []}
""",
    "GET /api/email/read/101" to """
{"uid": "101", "folder": "INBOX", "message_id": "<m101@example.com>", "subject": "Notes from Monday's planning call", "from_name": "Jonas Weber", "from_address": "jonas.weber@example.com", "to": "Demo <demo@example.com>", "cc": "priya.nair@example.com", "date": "${mailT5}", "references": "", "body": "Hi all,\n\nQuick summary of Monday:\n1. Launch date stays as planned.\n2. Priya owns the budget.\n3. Demo handles the vendor follow-ups.\n\nJonas", "body_html": null, "mark_seen_failed": false, "attachments_deferred": false, "attachments": []}
""",
    "GET /api/email/attachments/105" to """
{"attachments": [
  {"index": 0, "filename": "Q3-budget-draft.pdf", "name": "Q3-budget-draft.pdf", "size": 184320, "content_type": "application/pdf", "mime": "application/pdf"}
]}
""",
    "GET /api/email/attachments/104" to """
{"attachments": []}
""",
    "GET /api/email/attachments/103" to """
{"attachments": []}
""",
    "GET /api/email/attachments/102" to """
{"attachments": []}
""",
    "GET /api/email/attachments/101" to """
{"attachments": []}
""",
    "GET /api/email/contacts" to """
{"contacts": [
  {"name": "Priya Nair", "address": "priya.nair@example.com"},
  {"name": "Marcus Lee", "address": "marcus.lee@example.com"},
  {"name": "Sofia Alvarez", "address": "sofia.alvarez@example.com"},
  {"name": "Jonas Weber", "address": "jonas.weber@example.com"}
]}
""",
    "GET /api/contacts/search" to """
{"results": [
  {"name": "Priya Nair", "emails": ["priya.nair@example.com"]},
  {"name": "Marcus Lee", "emails": ["marcus.lee@example.com"]},
  {"name": "Sofia Alvarez", "emails": ["sofia.alvarez@example.com"]},
  {"name": "Jonas Weber", "emails": ["jonas.weber@example.com", "jonas.w@example.com"]}
]}
""",
)
