package com.thomaspeoples.wraithodysseus.demo

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val nowInstant: Instant = Instant.now()
private val naiveFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/** Server-style naive UTC time, [m] minutes ago. */
private val naive: (Long) -> String = { m ->
    LocalDateTime.ofInstant(nowInstant.minus(m, ChronoUnit.MINUTES), ZoneOffset.UTC).format(naiveFmt)
}

/** Instant with Z (history metadata.timestamp), [m] minutes ago. */
private val isoZ: (Long) -> String = { m ->
    nowInstant.minus(m, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS).toString()
}

private val userRow: (String, Long, String) -> String = { id, m, t ->
    """{"role":"user","content":"${t}","metadata":{"_db_id":"${id}","timestamp":"${isoZ(m)}"}}"""
}

private val botRow: (String, Long, String, String) -> String = { id, m, t, extra ->
    """{"role":"assistant","content":"${t}","metadata":{"_db_id":"${id}","timestamp":"${isoZ(m)}","model":"demo-llm","mode":"chat","response_time":2.4,"tokens_per_second":41.5,"tps_source":"computed","input_tokens":128,"output_tokens":96,"usage_source":"real","context_percent":3.2,"context_length":32768${extra}}}"""
}

private val page: (String, Int, String) -> String = { name, total, rows ->
    """{"history":[${rows}],"offset":0,"total":${total},"has_more_before":false,"model":"demo-llm","endpoint_id":"ep-demo","name":"${name}"}"""
}

private val ctx: (Int, Int) -> String = { msgs, used ->
    """{"used_tokens":${used},"context_length":32768,"context_percent":${used * 100.0 / 32768},"messages":${msgs},"model":"demo-llm","can_compact":${msgs >= 6},"memory_extraction_enabled":true,"memory_injection_enabled":true,"skill_injection_enabled":true,"thinking_mode":"off","thinking_supported":false,"temperature_override":null,"max_tokens_override":null}"""
}

/** The canned chat reply; Demo also keeps it in a chat's history once sent. */
internal const val DEMO_REPLY = "This is demo mode, so replies are canned. On your own Odysseus server this is where the model answers."

private const val NO_JOBS = """{"jobs":[]}"""

private val sessionRow: (String, String, Long, Long, Int, Boolean, String) -> String = { id, name, upd, created, count, star, folder ->
    """{"id":"${id}","name":"${name}","model":"demo-llm","endpoint_id":"ep-demo","mode":"chat","is_important":${star},"updated_at":"${naive(upd)}","last_message_at":"${naive(upd)}","created_at":"${naive(created)}","folder":${folder},"message_count":${count},"has_documents":false,"has_images":false}"""
}

private val archivedRow: (String, String, Long, Int) -> String = { id, name, upd, count ->
    """{"id":"${id}","name":"${name}","model":"demo-llm","message_count":${count},"created_at":"${naive(upd + 600)}","updated_at":"${naive(upd)}","is_important":false}"""
}

internal val demoChat: Map<String, String> = mapOf(
    "GET /api/sessions" to """[
${sessionRow("demo-s1", "Weekly planning", 12, 40, 6, true, "\"Work\"")},
${sessionRow("demo-s2", "CSV cleanup script", 180, 200, 4, false, "null")},
${sessionRow("demo-s3", "Why is the sky blue", 1500, 1520, 4, false, "null")},
${sessionRow("demo-s4", "Lisbon trip ideas", 5760, 5800, 5, false, "\"Personal\"")}
]""",

    "GET /api/sessions/archived" to """{"sessions":[
${archivedRow("demo-a1", "Old meeting notes", 28800, 2)},
${archivedRow("demo-a2", "Recipe swaps", 36000, 2)},
${archivedRow("demo-a3", "Birthday gift list", 43200, 2)}
],"total":3}""",

    "GET /api/history/demo-s1" to page(
        "Weekly planning", 6,
        listOf(
            userRow("m-1-1", 40, "Help me plan my week. I have a team sync Monday, a design review Wednesday and a deadline Friday."),
            botRow("m-1-2", 39, "Happy to help. Which matters most, the deadline or the review?", ""),
            userRow("m-1-3", 20, "The Friday deadline. Can you lay it out as a table?"),
            botRow(
                "m-1-4", 19,
                """Here is a draft plan:\n\n| Day | Focus | Hours |\n|---|---|---|\n| Mon | Team sync, prep Friday deliverable | 6 |\n| Tue | Deep work on the deliverable | 7 |\n| Wed | Design review, feedback fixes | 6 |\n| Thu | Final polish and testing | 7 |\n| Fri | Ship and retro | 5 |\n\nTuesday and Thursday are kept clear of meetings.""",
                "",
            ),
            userRow("m-1-5", 13, "Perfect, thanks."),
            botRow("m-1-6", 12, "You are welcome. Good luck with the deadline.", ""),
        ).joinToString(",")
    ),

    "GET /api/history/demo-s2" to page(
        "CSV cleanup script", 4,
        listOf(
            userRow("m-2-1", 200, "Write a short Python function that drops empty rows from a CSV file."),
            botRow(
                "m-2-2", 199,
                """Here is a small version:\n\n```python\nimport csv\n\ndef drop_empty_rows(src, dst):\n    with open(src, newline='') as f, open(dst, 'w', newline='') as out:\n        writer = csv.writer(out)\n        for row in csv.reader(f):\n            if any(cell.strip() for cell in row):\n                writer.writerow(row)\n```\n\nIt keeps any row with at least one non-blank cell.""",
                "",
            ),
            userRow("m-2-3", 181, "Nice. Does it keep the header?"),
            botRow("m-2-4", 180, "Yes. The header has text in it, so it is treated like any other non-empty row.", ""),
        ).joinToString(",")
    ),

    "GET /api/history/demo-s3" to page(
        "Why is the sky blue", 4,
        listOf(
            userRow("m-3-1", 1520, "Why is the sky blue?"),
            botRow(
                "m-3-2", 1519,
                """<think>The user wants a simple physics answer. Rayleigh scattering: shorter wavelengths scatter more. Mention violet is absorbed and our eyes favour blue.</think>Sunlight is made of many colours. Air molecules scatter short wavelengths such as blue much more than long ones such as red, so blue light reaches your eyes from every direction of the sky.""",
                ""","thinking_time":3.2""",
            ),
            userRow("m-3-3", 1502, "And why are sunsets red?"),
            botRow("m-3-4", 1500, "At sunset the light crosses far more air, so most of the blue is scattered away before it reaches you. What is left is the orange and red end of the spectrum.", ""),
        ).joinToString(",")
    ),

    "GET /api/history/demo-s4" to page(
        "Lisbon trip ideas", 5,
        listOf(
            userRow("m-4-1", 5800, "I have three days in Lisbon. What should I not miss?"),
            botRow("m-4-2", 5799, "Walk through Alfama, ride tram 28, visit Belem for the tower and the monastery, and leave an evening for a miradouro at sunset.", ""),
            userRow("m-4-3", 5780, "Any food tips?"),
            botRow("m-4-4", 5779, "Try grilled sardines, bacalhau dishes and a warm custard tart. Small family-run tascas are usually the best value.", ""),
            userRow("m-4-5", 5760, "Great, I will build an itinerary from that."),
        ).joinToString(",")
    ),

    "GET /api/history/demo-a1" to page(
        "Old meeting notes", 2,
        listOf(
            userRow("m-a1-1", 28810, "Summarise: we agreed to move the launch to next month and split the QA work."),
            botRow("m-a1-2", 28800, "Launch moves to next month, and QA work is split between the team.", ""),
        ).joinToString(",")
    ),

    "GET /api/history/demo-a2" to page(
        "Recipe swaps", 2,
        listOf(
            userRow("m-a2-1", 36010, "What can I use instead of butter in a cake?"),
            botRow("m-a2-2", 36000, "Neutral oil, applesauce or plain yoghurt all work. Use about three quarters of the butter amount in oil.", ""),
        ).joinToString(",")
    ),

    "GET /api/history/demo-a3" to page(
        "Birthday gift list", 2,
        listOf(
            userRow("m-a3-1", 43210, "Gift ideas for a friend who loves hiking?"),
            botRow("m-a3-2", 43200, "A light rain shell, a good water filter, trail socks or a printed map of a favourite area.", ""),
        ).joinToString(",")
    ),

    "GET /api/session/demo-s1/context" to ctx(6, 1850),
    "GET /api/session/demo-s2/context" to ctx(4, 1200),
    "GET /api/session/demo-s3/context" to ctx(4, 950),
    "GET /api/session/demo-s4/context" to ctx(5, 1100),
    "GET /api/session/demo-a1/context" to ctx(2, 300),
    "GET /api/session/demo-a2/context" to ctx(2, 280),
    "GET /api/session/demo-a3/context" to ctx(2, 260),

    "GET /api/research/chat-jobs/demo-s1" to NO_JOBS,
    "GET /api/research/chat-jobs/demo-s2" to NO_JOBS,
    "GET /api/research/chat-jobs/demo-s3" to NO_JOBS,
    "GET /api/research/chat-jobs/demo-s4" to NO_JOBS,
    "GET /api/research/chat-jobs/demo-a1" to NO_JOBS,
    "GET /api/research/chat-jobs/demo-a2" to NO_JOBS,
    "GET /api/research/chat-jobs/demo-a3" to NO_JOBS,

    "GET /api/models" to """{"items":[
{"endpoint_id":"ep-demo","endpoint_name":"Demo Server","url":"https://demo.example.com/v1/chat/completions","category":"local","model_type":"chat","offline":false,"models":["demo-llm","demo-mini"],"models_display":["Demo LLM","Demo Mini"],"models_extra":[],"models_extra_display":[],"models_metadata":{}},
{"endpoint_id":"ep-reason","endpoint_name":"Demo Reasoning","url":"https://reason.example.com/v1/chat/completions","category":"cloud","model_type":"chat","offline":false,"models":["demo-reasoner"],"models_display":["Demo Reasoner"],"models_extra":[],"models_extra_display":[],"models_metadata":{"demo-reasoner":{"supported_reasoning_levels":["low","medium","high"]}}}
]}""",

    "GET /api/default-chat" to """{"endpoint_id":"ep-demo","model":"demo-llm"}""",

    "GET /api/presets" to """{"balanced":{"name":"Balanced"},"concise":{"name":"Concise"},"creative":{"name":"Creative"},"coder":{"name":"Coder"}}""",

    "GET /api/skills/slash-catalog" to """{"skills":[
{"name":"summarize","help":"Summarize text or a document","category":"writing"},
{"name":"translate","help":"Translate text into another language","category":"writing"},
{"name":"explain","help":"Explain a topic in plain words","category":"learning"},
{"name":"review-code","help":"Review a snippet for bugs and style","category":"coding"}
]}""",

    "GET /api/tts/stats" to """{"available":false,"ready":false,"provider":"disabled"}""",

    "GET /api/search" to """[
{"message_id":"m-1-4","session_id":"demo-s1","session_name":"Weekly planning","role":"assistant","content_snippet":"Here is a draft plan: | Day | Focus | Hours |","timestamp":"${naive(19)}"},
{"message_id":"m-2-2","session_id":"demo-s2","session_name":"CSV cleanup script","role":"assistant","content_snippet":"Here is a small version: import csv def drop_empty_rows","timestamp":"${naive(199)}"},
{"message_id":"m-3-2","session_id":"demo-s3","session_name":"Why is the sky blue","role":"assistant","content_snippet":"Sunlight is made of many colours. Air molecules scatter","timestamp":"${naive(1519)}"},
{"message_id":"m-4-2","session_id":"demo-s4","session_name":"Lisbon trip ideas","role":"assistant","content_snippet":"Walk through Alfama, ride tram 28, visit Belem","timestamp":"${naive(5799)}"}
]""",

    "POST /api/session" to """{"id":"demo-new"}""",

    "POST /api/chat_stream" to """data: {"type":"model_info","model":"demo-llm"}

data: {"delta":"This is demo mode, "}

data: {"delta":"so replies are canned. "}

data: {"delta":"On your own Odysseus "}

data: {"delta":"server this is where "}

data: {"delta":"the model answers."}

data: {"type":"final_response","content":"${DEMO_REPLY}","delta":"${DEMO_REPLY}"}

data: {"type":"metrics","data":{"response_time":1.2,"tokens_per_second":38.0,"input_tokens":42,"output_tokens":24,"usage_source":"real","context_percent":0.2,"context_length":32768,"model":"demo-llm"}}

data: {"type":"message_saved","id":"demo-msg-new"}

data: [DONE]

""",
)
