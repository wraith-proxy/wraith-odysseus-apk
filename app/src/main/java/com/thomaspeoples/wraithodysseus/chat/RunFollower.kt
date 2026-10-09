package com.thomaspeoples.wraithodysseus.chat

import com.thomaspeoples.wraithodysseus.net.SseEvent
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

sealed interface RunEnd {
    /** [DONE] arrived: the reply is saved and history has it. */
    data object Done : RunEnd
    /** Resume said 404: the run finished (or died) while we were away. */
    data object Gone : RunEnd
    /** Stop was accepted: the server ends the stream without [DONE], and
     * the partial reply is saved. */
    data object Stopped : RunEnd
    data class Failed(val message: String) : RunEnd
}

/**
 * Follows one run to its end across dropped connections. Every (re)subscribe
 * starts the turn from empty, because resume replays the run's whole buffer
 * from the first event -- appending to what was already shown would double
 * the text. Until the replay starts, the old text stays on screen.
 */
class RunFollower(
    private val api: ChatApi,
    private val sessionId: String,
    /** The first stream is a resume of a run begun earlier, so its events are a replay. */
    private val resumed: Boolean = false,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val state = MutableStateFlow(TurnState(replayed = resumed))
    val turn: StateFlow<TurnState> = state

    /** What stop needs. Null on an approval-deny stream, which has no run. */
    @Volatile var runId: String? = null
        private set

    /** Set while a stop is being asked for (and kept once it is accepted):
     * the stream end that follows is the run ending, not a drop to resume. */
    @Volatile var stopping = false

    /** Set once the server has accepted the stop. A connection that drops
     * while the stop is still being asked for is only a drop: the stop
     * request may well have failed with it. */
    @Volatile var stopped = false

    suspend fun follow(first: RunStream): RunEnd {
        var stream = first
        while (true) {
            runId = stream.runId
            // Only a turn whose thinking was timed live keeps its clocks; a
            // replay lands in milliseconds and would print a bogus time.
            val prev = state.value
            state.value = when {
                first === stream -> TurnState(replayed = resumed)
                !prev.replayed && prev.thinkEndMs != null -> TurnState(startedMs = prev.startedMs, thinkStartMs = prev.thinkStartMs, thinkEndMs = prev.thinkEndMs, replayed = true)
                else -> TurnState(replayed = true)
            }
            var dropped = false
            try {
                val done = stream.events
                    .onEach { e -> state.update { it.apply(e) } }
                    .firstOrNull { it is SseEvent.Done }
                if (done != null) return RunEnd.Done
            } catch (_: IOException) {
                // Dropped mid-run; fall through to resume.
                dropped = true
            }
            if (if (dropped) stopped else stopping) return RunEnd.Stopped
            // A stream that just ends without [DONE] is a drop too.
            stream = reconnect() ?: return RunEnd.Gone
        }
    }

    /** Resume with 2, 4, 8... second waits, capped at 30, until the server
     * either hands back the run or says there is none. Any other HTTP error
     * (ApiException) ends the run for the caller to report. */
    private suspend fun reconnect(): RunStream? {
        var attempt = 0
        while (true) {
            sleep(backoffMs(attempt++))
            try {
                return api.resume(sessionId)
            } catch (_: IOException) {
                // Still offline; wait longer.
            }
        }
    }

    companion object {
        internal fun backoffMs(attempt: Int): Long = minOf(2_000L shl minOf(attempt, 4), 30_000L)
    }
}
