package com.thomaspeoples.wraithodysseus.chat

/** The provider the picker shows: "Local" for the user's own servers, else the endpoint's name (modelPicker.js:974). */
val ModelChoice.provider: String get() = if (category == "local") "Local" else endpointName

/** "Local · qwen3-30b": provider, then the model's short name (the web cuts its display name at the last "/"). */
val ModelChoice.label: String get() = "$provider · ${display.substringAfterLast('/')}"

/** One endpoint's models under its provider name. */
data class ModelGroup(val endpointId: String, val provider: String, val models: List<ModelChoice>)

/**
 * The picker's list, grouped by endpoint in the order the server gave them
 * (modelPicker.js:413 keys groups on the endpoint id): two endpoints serving
 * the same model stay apart.
 */
fun groupModels(models: List<ModelChoice>): List<ModelGroup> =
    models.groupBy { it.endpointId }.map { (id, list) -> ModelGroup(id, list.first().provider, list) }

/** [m] is the model a chat is on, as history names it. */
fun isCurrentModel(m: ModelChoice, model: String?, endpointId: String?): Boolean =
    m.model == model && (endpointId.isNullOrEmpty() || m.endpointId == endpointId)

/**
 * The model for the "same model" cases (a new chat, /model): the user's pick,
 * else the chat's own model found in [models], else a bare choice from what
 * history reports (enough for creating a chat, which needs only the ids).
 */
fun currentModel(models: List<ModelChoice>, selected: ModelChoice?, model: String?, endpointId: String?): ModelChoice? {
    selected?.let { return it }
    if (model.isNullOrEmpty()) return null
    return models.firstOrNull { isCurrentModel(it, model, endpointId) }
        ?: models.firstOrNull { it.model == model }
        ?: endpointId?.takeIf { it.isNotEmpty() }?.let { ModelChoice(it, it, model, model) }
}
