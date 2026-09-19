package com.gamemaster.agent.tools

import android.content.Context
import android.util.Log
import com.gamemaster.agent.backend.BackendSelector

/**
 * 内置 Python 工具的注册入口。
 *
 * 启动时机：在 [com.gamemaster.agent.service.GameAccessibilityService.onServiceConnected]
 * 或自定义 Application 启动后调用 [registerAll]，把 4 个 Python 模块包成 [ToolSpec]
 * 注册到 [ToolRegistry]，让视觉模型在系统提示中看到工具清单、在 TOOL_CALL 动作里调用它们。
 *
 * 工具列表（按视觉模型在提示词中看到的顺序）：
 * 1. web_search(query, max_results?) — DuckDuckGo 搜索，返回标题/URL/摘要 JSON
 * 2. web_read(url, max_chars?) — 抓取网页正文，按 <article>/<main>/最长 <div> 提取
 * 3. apk_install(url) — 下载 APK 到 app 缓存目录，再用 shell 后端 `pm install` 安装
 * 4. file_ops(op, path, content?) — read/write/list，限 app uid 可达范围
 */
object BuiltInTools {

    private const val TAG = "GameMaster"

    /**
     * 注册全部内置工具。重复调用幂等（同名工具会被覆盖）。
     *
     * @param ctx 用于取 cacheDir 传给 Python 下载工具
     */
    fun registerAll(ctx: Context) {
        // 1. web_search
        ToolRegistry.register(
            ToolSpec(
                name = "web_search",
                description = "搜索网络并返回结果摘要（DuckDuckGo HTML 接口）",
                parameters = "query, max_results?",
                handler = { args ->
                    val query = args["query"].orEmpty()
                    if (query.isBlank()) {
                        ToolResult.fail("query 不能为空")
                    } else {
                        val maxResults = args["max_results"]?.toIntOrNull() ?: 8
                        val out = ChaquopyToolRunner.run(
                            "tools.web_search", "search",
                            query, maxResults
                        )
                        toolResult(out)
                    }
                }
            )
        )

        // 2. web_read
        ToolRegistry.register(
            ToolSpec(
                name = "web_read",
                description = "提取网页正文（最多 ~2KB，便于模型理解）",
                parameters = "url, max_chars?",
                handler = { args ->
                    val url = args["url"].orEmpty()
                    if (url.isBlank()) {
                        ToolResult.fail("url 不能为空")
                    } else {
                        val maxChars = args["max_chars"]?.toIntOrNull() ?: 2000
                        val out = ChaquopyToolRunner.run(
                            "tools.web_read", "read",
                            url, maxChars
                        )
                        toolResult(out)
                    }
                }
            )
        )

        // 3. apk_install
        ToolRegistry.register(
            ToolSpec(
                name = "apk_install",
                description = "下载并安装 APK（用 shell 后端执行 pm install）",
                parameters = "url",
                handler = { args ->
                    val url = args["url"].orEmpty()
                    if (url.isBlank()) {
                        ToolResult.fail("url 不能为空")
                    } else {
                        // 先用 Python 把 APK 下载到 app 缓存目录
                        val destDir = ctx.cacheDir.absolutePath
                        val dlResult = ChaquopyToolRunner.run(
                            "tools.apk_install", "download",
                            url, destDir
                        )
                        if (dlResult.startsWith("ERROR:")) {
                            ToolResult.fail("下载失败：$dlResult")
                        } else {
                            // 用 shell 后端安装（pm install 需要 shell 权限）
                            // 路径来自 cacheDir，正常不含特殊字符；用单引号包裹更稳妥
                            val shell = BackendSelector.bestForShell()
                            if (shell == null) {
                                ToolResult.fail("没有可用的 shell 后端（Root/Shizuku）来执行 pm install")
                            } else {
                                val installCmd = "pm install -r '$dlResult'"
                                val ok = shell.exec(installCmd)
                                if (ok) {
                                    ToolResult.ok("安装成功：$dlResult")
                                } else {
                                    ToolResult.fail("pm install 失败（exit code 非 0）")
                                }
                            }
                        }
                    }
                }
            )
        )

        // 4. file_ops
        ToolRegistry.register(
            ToolSpec(
                name = "file_ops",
                description = "设备文件操作（read/write/list；仅限 app 可达目录）",
                parameters = "op, path, content?",
                handler = { args ->
                    val op = args["op"].orEmpty()
                    val path = args["path"].orEmpty()
                    val content = args["content"] ?: ""
                    if (op.isBlank()) {
                        ToolResult.fail("op 不能为空（read/write/list）")
                    } else if (path.isBlank()) {
                        ToolResult.fail("path 不能为空")
                    } else {
                        val out = ChaquopyToolRunner.run(
                            "tools.file_ops", "operate",
                            op, path, content
                        )
                        toolResult(out)
                    }
                }
            )
        )

        Log.i(TAG, "[tools] 内置工具注册完成：${ToolRegistry.all().map { it.name }}")
    }

    /**
     * Python 返回的字符串形如 "ERROR: <原因>" 视为失败，否则成功。
     * 这样 Python 侧统一的错误约定直接传递到模型视野。
     */
    private fun toolResult(pyOut: String): ToolResult =
        if (pyOut.startsWith("ERROR:")) {
            ToolResult.fail(pyOut)
        } else {
            ToolResult.ok(pyOut)
        }
}
