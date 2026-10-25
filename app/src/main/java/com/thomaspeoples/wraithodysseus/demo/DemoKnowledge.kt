package com.thomaspeoples.wraithodysseus.demo

import java.time.Instant

private val nowSec: Long = System.currentTimeMillis() / 1000

private fun secAgo(seconds: Long): Long = nowSec - seconds

private fun isoAgo(seconds: Long): String = Instant.now().minusSeconds(seconds).toString()

private const val HOUR = 3600L
private const val DAY = 86400L

private const val DOC_1 = "demo-doc-launch-plan"
private const val DOC_2 = "demo-doc-meeting-notes"
private const val DOC_3 = "demo-doc-reply-draft"
private const val DOC_4 = "demo-doc-backup-script"

private const val RESEARCH_1 = "demo-research-ebike"
private const val RESEARCH_2 = "demo-research-notes-apps"

internal val demoKnowledge: Map<String, String> = mapOf(
    "GET /api/memory" to """
{"memory":[
{"id":"mem-1","text":"Demo prefers short, plain answers with the key point first.","category":"preference","pinned":true,"timestamp":${secAgo(2 * HOUR)},"uses":14},
{"id":"mem-2","text":"Demo works on a small hiking-trail app called Trailhead and ships a build every other Friday.","category":"project","pinned":true,"timestamp":${secAgo(1 * DAY + 3 * HOUR)},"uses":9},
{"id":"mem-3","text":"Demo's work address is demo@example.com.","category":"contact","pinned":false,"timestamp":${secAgo(3 * DAY)},"uses":3},
{"id":"mem-4","text":"Goal for this quarter: run a 10 km race in under 55 minutes.","category":"goal","pinned":false,"timestamp":${secAgo(6 * DAY)},"uses":5},
{"id":"mem-5","text":"Demo lives in a flat with a small balcony garden and grows herbs.","category":"fact","pinned":false,"timestamp":${secAgo(12 * DAY)},"uses":2}
]}
""".trimIndent(),

    "GET /api/skills" to """
{"skills":[
{"name":"weekly-summary","description":"Turn a week of notes into a short summary with wins, blockers and next steps.","category":"writing","tags":["summary","weekly","notes"],"status":"published","uses":18,"confidence":0.92,"version":"1.2","source":"user"},
{"name":"plain-email-reply","description":"Draft a polite, brief reply to an email in a neutral tone.","category":"communication","tags":["email","reply"],"status":"published","uses":11,"confidence":0.85,"version":"1.0","source":"user"},
{"name":"trip-checklist","description":"Build a packing and booking checklist for a short trip.","category":"planning","tags":["travel","checklist"],"status":"draft","uses":2,"confidence":0.6,"version":"0.3","source":"user"}
]}
""".trimIndent(),

    "GET /api/skills/weekly-summary/markdown" to """
{"markdown":"---\nname: weekly-summary\ndescription: Turn a week of notes into a short summary.\n---\n# Weekly summary\n\nWhen asked for a weekly summary:\n\n1. Read the notes the user pasted.\n2. List up to three wins.\n3. List blockers and who can unblock them.\n4. Finish with next steps for the coming week.\n\nKeep it under 200 words."}
""".trimIndent(),

    "GET /api/skills/plain-email-reply/markdown" to """
{"markdown":"---\nname: plain-email-reply\ndescription: Draft a polite, brief reply to an email.\n---\n# Plain email reply\n\n- Greet the sender by name.\n- Answer the question in the first sentence.\n- Offer one clear next step.\n- Sign off with just the first name."}
""".trimIndent(),

    "GET /api/skills/trip-checklist/markdown" to """
{"markdown":"---\nname: trip-checklist\ndescription: Build a packing and booking checklist for a short trip.\n---\n# Trip checklist\n\nAsk for the dates, the place and the weather. Then produce three groups: bookings, packing and things to do before leaving."}
""".trimIndent(),

    "GET /api/documents/library" to """
{"total":4,"documents":[
{"id":"$DOC_1","title":"Trailhead launch plan","language":"markdown","preview":"# Trailhead launch plan\n\nGoal: ship version 1.0 to the first 200 testers.\n\n## Milestones\n- Beta build ready\n- Feedback round\n- Store listing","version_count":4,"session_name":"Launch planning","updated_at":"${isoAgo(3 * HOUR)}"},
{"id":"$DOC_2","title":"Team sync notes","language":"markdown","preview":"# Team sync\n\nAttendees: Demo, Sam, Priya\n\n## Decisions\n- Freeze features on Thursday\n- Priya owns the release notes","version_count":2,"session_name":"Weekly sync","updated_at":"${isoAgo(1 * DAY + 2 * HOUR)}"},
{"id":"$DOC_3","title":"Reply to the venue","language":"email","preview":"Subject: Booking for 12 people\n\nHello,\n\nThank you for the quick answer. We would like to confirm the table for 12 on Friday at 19:00.","version_count":1,"session_name":null,"updated_at":"${isoAgo(3 * DAY)}"},
{"id":"$DOC_4","title":"backup.sh","language":"bash","preview":"#!/usr/bin/env bash\nset -euo pipefail\n\nrsync -a --delete /home/demo/documents/ /mnt/backup/documents/","version_count":3,"session_name":"Shell help","updated_at":"${isoAgo(8 * DAY)}"}
]}
""".trimIndent(),

    "GET /api/document/$DOC_1" to """
{"id":"$DOC_1","title":"Trailhead launch plan","language":"markdown","current_content":"# Trailhead launch plan\n\nGoal: ship version 1.0 to the first 200 testers.\n\n## Milestones\n- Beta build ready\n- Feedback round\n- Store listing\n\n## Risks\n- Map tiles load slowly on weak connections\n- Only two people can review the build\n\n## Owners\n- Demo: build and release\n- Sam: testers and feedback\n- Priya: release notes","version_count":4,"archived":false,"updated_at":"${isoAgo(3 * HOUR)}"}
""".trimIndent(),

    "GET /api/document/$DOC_2" to """
{"id":"$DOC_2","title":"Team sync notes","language":"markdown","current_content":"# Team sync\n\nAttendees: Demo, Sam, Priya\n\n## Decisions\n- Freeze features on Thursday\n- Priya owns the release notes\n\n## Actions\n- Sam: send the tester invites by Wednesday\n- Demo: fix the slow map tiles\n- Priya: draft the store text","version_count":2,"archived":false,"updated_at":"${isoAgo(1 * DAY + 2 * HOUR)}"}
""".trimIndent(),

    "GET /api/document/$DOC_3" to """
{"id":"$DOC_3","title":"Reply to the venue","language":"email","current_content":"Subject: Booking for 12 people\n\nHello,\n\nThank you for the quick answer. We would like to confirm the table for 12 on Friday at 19:00.\n\nCould you also tell us whether a vegetarian set menu is available? We will pay a deposit if needed.\n\nKind regards,\nDemo\ndemo@example.com","version_count":1,"archived":false,"updated_at":"${isoAgo(3 * DAY)}"}
""".trimIndent(),

    "GET /api/document/$DOC_4" to """
{"id":"$DOC_4","title":"backup.sh","language":"bash","current_content":"#!/usr/bin/env bash\nset -euo pipefail\n\nrsync -a --delete /home/demo/documents/ /mnt/backup/documents/\necho \"Backup finished\"","version_count":3,"archived":false,"updated_at":"${isoAgo(8 * DAY)}"}
""".trimIndent(),

    "GET /api/auth/features" to """
{"deep_research":true}
""".trimIndent(),

    "GET /api/model-endpoints" to """
[
{"id":"ep-demo-1","name":"Demo Endpoint","base_url":"https://llm.example.com/v1","is_enabled":true,"model_type":"llm","models":["demo-large","demo-fast","demo-mini"]}
]
""".trimIndent(),

    "GET /api/research/library" to """
{"research":[
{"id":"$RESEARCH_1","query":"Which commuter e-bike gives the best value under a mid-range budget?","category":"comparison","mode":"research","source_count":6,"status":"done","duration":"4m 12s","started_at":${secAgo(5 * HOUR + 252)},"completed_at":${secAgo(5 * HOUR)}},
{"id":"$RESEARCH_2","query":"How do the popular note-taking apps compare for offline use?","category":"comparison","mode":"research","source_count":5,"status":"done","duration":"3m 40s","started_at":${secAgo(2 * DAY + 220)},"completed_at":${secAgo(2 * DAY)}}
]}
""".trimIndent(),

    "GET /api/research/active" to """
{"active":[]}
""".trimIndent(),

    "GET /api/research/status/$RESEARCH_1" to """
{"status":"done","session_id":"$RESEARCH_1"}
""".trimIndent(),

    "GET /api/research/status/$RESEARCH_2" to """
{"status":"done","session_id":"$RESEARCH_2"}
""".trimIndent(),

    "POST /api/research/result-peek/$RESEARCH_1" to """
{"result":"# Commuter e-bike value\n\nFor a daily commute of 10 to 20 km, the best value comes from a mid-drive or rear-hub bike with a removable 500 Wh battery.\n\n## Key points\n- A 500 Wh battery covers a typical week of short commutes.\n- Hydraulic disc brakes and mudguards matter more than top speed.\n- Check that spare parts and a local service point are easy to find.\n\n## Recommendation\nPick the lightest model with a replaceable battery and a two-year warranty.","sources":[
{"title":"Commuter e-bike buying guide","url":"https://www.example.com/ebike-guide","source_kind":"guide","retrieval":"fetch","source_score":88},
{"title":"Battery capacity explained","url":"https://www.example.com/battery-capacity","source_kind":"article","retrieval":"fetch","source_score":81},
{"title":"Owner reviews roundup","url":"https://www.example.com/owner-reviews","source_kind":"reviews","retrieval":"search","source_score":69}
],"raw_findings":[
{"title":"Battery size","url":"https://www.example.com/battery-capacity","summary":"A 500 Wh pack is enough for about 60 km of mixed riding."},
{"title":"Brakes","url":"https://www.example.com/ebike-guide","summary":"Hydraulic discs give steadier stopping in the wet."}
],"source_state":"verified","category":"comparison","mode":"research","action_trace":[
{"round":1,"tool":"search","query":"commuter e-bike value guide","source":"web"},
{"round":2,"tool":"fetch","url":"https://www.example.com/ebike-guide","source":"web"}
],"navigation_trace":[
{"tool":"search","query":"commuter e-bike value guide","status":"ok","results":8},
{"tool":"fetch","url":"https://www.example.com/ebike-guide","title":"Commuter e-bike buying guide","status":"ok","source_kind":"guide","source_score":88,"retrieval":"fetch"}
]}
""".trimIndent(),

    "POST /api/research/result-peek/$RESEARCH_2" to """
{"result":"# Note-taking apps offline\n\nMost popular note apps keep a full local copy, but sync behaviour differs.\n\n## Key points\n- Apps that store plain files are the most reliable offline.\n- Some apps only cache recently opened notes.\n- Search often needs a connection unless the index is local.\n\n## Recommendation\nChoose an app that stores notes as plain files if offline access is a priority.","sources":[
{"title":"Offline note apps compared","url":"https://www.example.com/notes-compared","source_kind":"article","retrieval":"fetch","source_score":84},
{"title":"Local-first software","url":"https://www.example.com/local-first","source_kind":"article","retrieval":"fetch","source_score":77},
{"title":"User forum thread on sync","url":"https://www.example.com/forum/sync","source_kind":"forum","retrieval":"search","source_score":55}
],"raw_findings":[
{"title":"Plain files","url":"https://www.example.com/notes-compared","summary":"Plain-file apps stay fully usable with no connection."},
{"title":"Search index","url":"https://www.example.com/local-first","summary":"A local index keeps search working offline."}
],"source_state":"verified","category":"comparison","mode":"research","action_trace":[
{"round":1,"tool":"search","query":"note taking apps offline support","source":"web"}
],"navigation_trace":[
{"tool":"search","query":"note taking apps offline support","status":"ok","results":7},
{"tool":"fetch","url":"https://www.example.com/notes-compared","title":"Offline note apps compared","status":"ok","source_kind":"article","source_score":84,"retrieval":"fetch"}
]}
""".trimIndent(),

    "GET /api/research/report/$RESEARCH_1" to """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Commuter e-bike value</title>
<style>body{font-family:sans-serif;margin:16px;line-height:1.5;color:#222}h1{font-size:1.4em}li{margin:4px 0}</style></head>
<body><h1>Commuter e-bike value</h1>
<p>For a daily commute of 10 to 20 km, a mid-drive or rear-hub bike with a removable 500 Wh battery gives the best value.</p>
<ul><li>500 Wh covers a typical week of short commutes.</li><li>Hydraulic disc brakes and mudguards matter more than top speed.</li><li>Check that spare parts and a service point are close by.</li></ul>
<p>Sources: example.com guides and reviews.</p></body></html>
""".trimIndent(),

    "GET /api/research/report/$RESEARCH_2" to """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Note-taking apps offline</title>
<style>body{font-family:sans-serif;margin:16px;line-height:1.5;color:#222}h1{font-size:1.4em}li{margin:4px 0}</style></head>
<body><h1>Note-taking apps offline</h1>
<p>Most popular note apps keep a local copy, but sync behaviour differs.</p>
<ul><li>Plain-file apps are the most reliable offline.</li><li>Some apps only cache recently opened notes.</li><li>Search may need a connection unless the index is local.</li></ul>
<p>Sources: example.com comparisons and forum threads.</p></body></html>
""".trimIndent(),
)
