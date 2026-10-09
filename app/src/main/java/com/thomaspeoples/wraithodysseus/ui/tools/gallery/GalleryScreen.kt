package com.thomaspeoples.wraithodysseus.ui.tools.gallery

import androidx.compose.runtime.Composable
import com.thomaspeoples.wraithodysseus.ui.tools.Tool
import com.thomaspeoples.wraithodysseus.ui.tools.ToolEnv
import com.thomaspeoples.wraithodysseus.ui.tools.WebOnlyScreen

/** Not built yet: until it is, the tool opens the web UI. */
@Composable
fun GalleryScreen(env: ToolEnv) = WebOnlyScreen(Tool.Gallery, env)
