package com.thomaspeoples.wraithodysseus

import android.app.Application
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionStore

/** Holds the two process-wide singletons every screen shares: one OkHttp
 * client (one connection pool) and one view of the stored session. */
class WraithOdysseusApp : Application() {
    val session by lazy { SessionStore(this) }
    val http by lazy { Http(session) }
}
