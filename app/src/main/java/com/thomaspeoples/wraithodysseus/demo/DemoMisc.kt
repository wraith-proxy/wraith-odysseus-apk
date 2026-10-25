package com.thomaspeoples.wraithodysseus.demo

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val isoFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
private fun ago(hours: Long): String = LocalDateTime.now().minusHours(hours).format(isoFmt)

private val galleryT1 = ago(2)
private val galleryT2 = ago(30)
private val galleryT3 = ago(72)
private val galleryT4 = ago(120)

private val galleryItem1 = """{"id":"demo-img-1","filename":"demo-img-1.png","url":"/api/generated-image/demo-img-1.png","prompt":"A lighthouse on a cliff at dusk, watercolor","favorite":true,"album_id":"demo-album-1","width":1024,"height":1024,"file_size":1482930,"created_at":"${galleryT1}","taken_at":null}"""
private val galleryItem2 = """{"id":"demo-img-2","filename":"demo-img-2.png","url":"/api/generated-image/demo-img-2.png","prompt":"Isometric tiny workshop with glowing lanterns","favorite":false,"album_id":"demo-album-1","width":1024,"height":768,"file_size":1190442,"created_at":"${galleryT2}","taken_at":null}"""
private val galleryItem3 = """{"id":"demo-img-3","filename":"demo-img-3.jpg","url":"/api/generated-image/demo-img-3.jpg","prompt":"trip-photo.jpg","favorite":false,"album_id":"demo-album-2","width":1600,"height":1200,"file_size":842113,"created_at":"${galleryT3}","taken_at":"${galleryT3}"}"""
private val galleryItem4 = """{"id":"demo-img-4","filename":"demo-img-4.png","url":"/api/generated-image/demo-img-4.png","prompt":"Minimal mountain logo, two colors","favorite":true,"album_id":null,"width":768,"height":768,"file_size":356871,"created_at":"${galleryT4}","taken_at":null}"""

internal val demoMisc: Map<String, String> = mapOf(
    "GET /api/gallery/library" to """{"items":[${galleryItem1},${galleryItem2},${galleryItem3},${galleryItem4}],"total":4}""",
    "GET /api/gallery/albums" to """{"albums":[{"id":"demo-album-1","name":"Concept art","count":2},{"id":"demo-album-2","name":"Photos","count":1}]}""",
    "GET /api/gallery/demo-img-1" to galleryItem1,
    "GET /api/gallery/demo-img-2" to galleryItem2,
    "GET /api/gallery/demo-img-3" to galleryItem3,
    "GET /api/gallery/demo-img-4" to galleryItem4,
    "GET /api/prefs/theme" to """{"value":null}""",
    "GET /api/prefs/custom-themes" to """{"value":null}""",
    "GET /api/prefs/memory_enabled" to """{"value":true}""",
    "GET /api/prefs/auto_memory" to """{"value":true}""",
    "GET /api/prefs/skills_enabled" to """{"value":true}""",
    "GET /api/prefs/default_endpoint_id" to """{"value":null}""",
    "GET /api/prefs/default_model" to """{"value":null}""",
    "GET /api/version" to """{"version":"demo","build":"demo"}""",
    "GET /api/auth/status" to """{"configured":true,"authenticated":true,"username":"Demo","is_admin":false}""",
    "GET /api/auth/2fa/status" to """{"enabled":false}""",
    "GET /api/auth/settings" to """{"default_endpoint_id":null,"default_model":null}""",
    "POST /api/auth/logout" to """{"success":true}""",
)
