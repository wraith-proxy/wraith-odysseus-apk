package com.thomaspeoples.wraithodysseus.ui.tools.skills

import androidx.compose.runtime.Composable
import com.thomaspeoples.wraithodysseus.ui.tools.Tool
import com.thomaspeoples.wraithodysseus.ui.tools.ToolEnv
import com.thomaspeoples.wraithodysseus.ui.tools.WebOnlyScreen

/** Not built yet: until it is, the tool opens the web UI. */
@Composable
fun SkillsScreen(env: ToolEnv) = WebOnlyScreen(Tool.Skills, env)
