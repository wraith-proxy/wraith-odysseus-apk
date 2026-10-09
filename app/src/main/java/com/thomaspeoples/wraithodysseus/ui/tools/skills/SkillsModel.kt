package com.thomaspeoples.wraithodysseus.ui.tools.skills

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONException

/** What the reader shows for the skill being viewed. */
data class SkillDoc(val name: String, val markdown: String? = null, val problem: String? = null)

data class SkillsState(
    val skills: List<Skill> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val problem: String? = null,
    val query: String = "",
    val filter: SkillFilter = SkillFilter.All,
    val doc: SkillDoc? = null,
) {
    val visible: List<Skill> get() = filterSkills(skills, query, filter)
}

class SkillsViewModel(private val api: SkillsApi) : ViewModel() {
    var state by mutableStateOf(SkillsState())
        private set

    private val say = Channel<String>(Channel.BUFFERED)

    /** Short notices (a failed action) for the screen to put in the snackbar. */
    val messages = say.receiveAsFlow()

    private var loadJob: Job? = null
    private var docJob: Job? = null

    init {
        load()
    }

    fun load(pull: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            state = state.copy(loading = !pull && !state.loaded, refreshing = pull, problem = null)
            try {
                state = state.copy(skills = api.list(), loading = false, refreshing = false, loaded = true)
            } catch (e: ApiException) {
                failed(e)
            } catch (e: IOException) {
                failed(e)
            }
        }
    }

    private fun failed(e: Exception) {
        state = state.copy(loading = false, refreshing = false, loaded = true, problem = if (state.skills.isEmpty()) userMessage(e) else null)
        if (state.skills.isNotEmpty()) say.trySend(userMessage(e))
    }

    fun setQuery(query: String) {
        state = state.copy(query = query)
    }

    fun setFilter(filter: SkillFilter) {
        state = state.copy(filter = filter)
    }

    /** Open the reader on a skill's SKILL.md. */
    fun view(skill: Skill) {
        docJob?.cancel()
        state = state.copy(doc = SkillDoc(skill.name))
        docJob = viewModelScope.launch {
            val doc = try {
                SkillDoc(skill.name, markdown = stripFrontMatter(api.markdown(skill.name)))
            } catch (e: ApiException) {
                SkillDoc(skill.name, problem = userMessage(e))
            } catch (e: IOException) {
                SkillDoc(skill.name, problem = userMessage(e))
            }
            state = state.copy(doc = doc)
        }
    }

    fun closeDoc() {
        docJob?.cancel()
        state = state.copy(doc = null)
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiException) {
                say.trySend(userMessage(e))
            } catch (e: IOException) {
                say.trySend(userMessage(e))
            } catch (e: JSONException) {
                say.trySend(userMessage(e))
            }
        }
    }

    /** Publish a draft or return a published skill to draft. */
    fun togglePublished(skill: Skill) = act {
        val status = if (skill.published) "draft" else "published"
        api.setStatus(skill.name, status)
        state = state.copy(skills = state.skills.map { if (it.name == skill.name) it.copy(status = status) else it })
    }

    fun delete(skill: Skill) = act {
        api.delete(skill.name)
        state = state.copy(skills = state.skills.filter { it.name != skill.name }, doc = state.doc?.takeIf { it.name != skill.name })
    }
}
