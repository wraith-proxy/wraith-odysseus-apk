package com.thomaspeoples.wraithodysseus.chat

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
