package com.gamemaster.agent.prefs

import android.content.Context
import com.gamemaster.agent.agent.AgentConfig

object Prefs {
    private const val FILE = "game_master_prefs"

    const val KEY_BASE_URL = "base_url"
    const val KEY_API_KEY = "api_key"
    const val KEY_MODEL = "model"
    const val KEY_TASK = "task"
    const val KEY_SHOULD_RUN = "agent_should_run"

    const val DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
    const val DEFAULT_MODEL = "glm-4v-flash"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getString(ctx: Context, key: String, def: String = ""): String =
        prefs(ctx).getString(key, def) ?: def

    fun setString(ctx: Context, key: String, value: String) {
        prefs(ctx).edit().putString(key, value).apply()
    }

    /** 任务是否应当处于运行中（用于进程被杀后自动恢复） */
    fun agentShouldRun(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SHOULD_RUN, false)

    fun setAgentShouldRun(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SHOULD_RUN, value).apply()
    }

    fun agentConfig(ctx: Context): AgentConfig = AgentConfig(
        baseUrl = getString(ctx, KEY_BASE_URL, DEFAULT_BASE_URL).trim().trimEnd('/'),
        apiKey = getString(ctx, KEY_API_KEY).trim(),
        model = getString(ctx, KEY_MODEL, DEFAULT_MODEL).trim(),
        task = getString(ctx, KEY_TASK).trim()
    )
}
