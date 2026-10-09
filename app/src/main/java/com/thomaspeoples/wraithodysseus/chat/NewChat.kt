package com.thomaspeoples.wraithodysseus.chat

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The model a new chat starts on: /api/default-chat when it names both an
 * endpoint and a model, otherwise the first chat model on its endpoint, then
 * the first chat model anywhere. Null means the account has no usable model
 * at all, and chat_stream would only answer "No model selected".
 */
fun pickNewChatModel(default: DefaultChat, models: List<ModelChoice>): ModelChoice? {
    if (default.endpointId.isNotEmpty() && default.model.isNotEmpty()) {
        return models.firstOrNull { it.endpointId == default.endpointId && it.model == default.model }
            ?: ModelChoice(default.endpointId, default.endpointId, default.model, default.model)
    }
    return models.firstOrNull { it.endpointId == default.endpointId } ?: models.firstOrNull()
}

/** The web UI's default name: the model's basename and the local time. */
fun newChatName(model: String, time: String): String = "${model.trimEnd('/').substringAfterLast('/')} $time"

/** H:mm:ss whatever the locale: the server only gives a chat an automatic
 * title while its name still ends in that shape, and a localized time (say
 * with a 下午 marker) would not. */
fun newChatTime(time: LocalTime): String = time.format(NEW_CHAT_TIME)

private val NEW_CHAT_TIME = DateTimeFormatter.ofPattern("H:mm:ss")

/** Creates a chat on the account's default model and returns its id; null
 * when the account has no usable model. */
suspend fun ChatApi.newChat(now: LocalTime = LocalTime.now()): String? {
    val model = pickNewChatModel(defaultChat(), models()) ?: return null
    return createSession(newChatName(model.model, newChatTime(now)), model.endpointId, model.model)
}

/** Creates a chat on [model], the one the open chat is on: "+ New" and /new. */
suspend fun ChatApi.newChatOn(model: ModelChoice, now: LocalTime = LocalTime.now()): String =
    createSession(newChatName(model.model, newChatTime(now)), model.endpointId, model.model)

/**
 * What the first send of a new chat carries into the chat it has just
 * created: the message, the files uploaded for it, the switches if the user
 * set them and the model chosen. A plain holder; the chat screen takes it
 * once.
 */
class FirstSend(val text: String, val files: List<UploadedFile>, val toggles: Toggles?, val model: ModelChoice?)
