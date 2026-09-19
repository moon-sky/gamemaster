package com.gamemaster.agent.tools

/**
 * 工具调用结果：[output] 会被写进 Agent 的 history 给模型下一轮参考；
 * [ok]=false 时 Agent 会按 consecutiveErrors 触发自救机制。
 */
data class ToolResult(
    val ok: Boolean,
    val output: String,
    val error: String? = null
) {
    companion object {
        fun ok(output: String) = ToolResult(true, output)
        fun fail(error: String) = ToolResult(false, "", error)
    }
}

/**
 * 工具定义。每个工具绑定一个 suspend handler，内部调 ChaquopyToolRunner 或直接执行。
 *
 * [description] + [parameters] 给模型看的工具说明，由 [ToolRegistry.describeForPrompt]
 * 拼接成一段提示词文本注入到 VisionApiClient 的系统提示。
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: String,
    val handler: suspend (args: Map<String, String>) -> ToolResult
)

/**
 * 工具注册表与调用入口。
 *
 * 启动流程：
 * 1. GameAccessibilityService.onServiceConnected 或 Application 初始化时调
 *    [BuiltInTools.registerAll] 注册内置 Python 工具
 * 2. VisionApiClient 系统提示通过 [describeForPrompt] 拼接工具清单
 * 3. GameAgent 主循环遇到 TOOL_CALL 动作时调 [call] 路由到 handler
 */
object ToolRegistry {

    private val tools: MutableMap<String, ToolSpec> = linkedMapOf()

    fun register(spec: ToolSpec) {
        tools[spec.name] = spec
    }

    fun get(name: String): ToolSpec? = tools[name]

    fun all(): List<ToolSpec> = tools.values.toList()

    /**
     * 拼装给视觉模型看的工具清单文本：
     * ```
     * - web_search(query): 搜索网络并返回结果摘要
     * - web_read(url): 提取网页正文
     * - apk_install(url): 下载并安装 APK
     * - file_ops(op, path, content?): 设备文件操作
     *
     * 调用工具时输出 JSON：{"action":"tool_call","tool_name":"web_search","args":{"query":"..."}}
     * ```
     */
    fun describeForPrompt(): String {
        if (tools.isEmpty()) return ""
        return buildString {
            tools.values.forEach { spec ->
                val argList = spec.parameters.trim()
                if (argList.isEmpty()) {
                    appendLine("- ${spec.name}: ${spec.description}")
                } else {
                    appendLine("- ${spec.name}($argList): ${spec.description}")
                }
            }
            appendLine()
            append("调用工具时输出 JSON：{\"action\":\"tool_call\",\"tool_name\":\"<名字>\",\"args\":{<参数名>:\"<值>\"}}")
        }
    }

    /**
     * 路由工具调用：查 spec → 调 handler → 返回结果。
     * 工具不存在时返回 fail，由调用方决定是否计入 consecutiveErrors。
     */
    suspend fun call(name: String, args: Map<String, String>): ToolResult {
        val spec = tools[name] ?: return ToolResult.fail("未知工具：$name")
        return try {
            spec.handler(args)
        } catch (e: Exception) {
            ToolResult.fail("工具 $name 执行异常：${e.message}")
        }
    }
}
