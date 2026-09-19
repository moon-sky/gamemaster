package com.gamemaster.agent.agent

import org.json.JSONObject

/** 运行所需的全部配置 */
data class AgentConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val task: String
)

/** 视觉模型返回的一个动作 */
data class AgentAction(
    val thought: String,
    val type: Type,
    val x: Float = 0f,
    val y: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val duration: Long = 0L,
    val text: String = "",
    /** 引用本轮控件清单中的元素编号（从 1 开始）；-1 表示未使用 */
    val elementIndex: Int = -1,
    /** open_app 动作的目标应用名（如"抖音"）或包名 */
    val appName: String = "",
    /** TOOL_CALL 动作的工具名（对应 ToolRegistry 中注册的 spec.name） */
    val toolName: String = "",
    /** TOOL_CALL 动作的参数（来自 JSON args 对象，全部按字符串处理；工具 handler 内自行转换类型） */
    val toolArgs: Map<String, String> = emptyMap(),
    /** 模型自报的当前计划步骤编号（从 1 开始）；0 表示模型未提供 */
    val planStep: Int = 0
) {
    enum class Type {
        TAP, DOUBLE_TAP, LONG_PRESS, SWIPE,
        WAIT, BACK, HOME, INPUT, OPEN_APP, SEARCH,
        TOOL_CALL,
        FINISH, UNKNOWN
    }

    /** 给悬浮窗/历史记录看的一句话描述 */
    fun summary(): String = when (type) {
        Type.TAP -> "点击(${x.toInt()}, ${y.toInt()})"
        Type.DOUBLE_TAP -> "双击(${x.toInt()}, ${y.toInt()})"
        Type.LONG_PRESS -> "长按(${x.toInt()}, ${y.toInt()}) ${duration}ms"
        Type.SWIPE -> "滑动(${x.toInt()}, ${y.toInt()}) → (${x2.toInt()}, ${y2.toInt()})"
        Type.WAIT -> "等待 ${duration}ms"
        Type.BACK -> "按返回键"
        Type.HOME -> "按主页键"
        Type.INPUT -> "输入文字：$text"
        Type.OPEN_APP -> "打开应用：$appName"
        Type.SEARCH -> "搜索：$text"
        Type.TOOL_CALL -> "调用工具 $toolName(${toolArgs.entries.joinToString(", ") { "${it.key}=${it.value}" }})"
        Type.FINISH -> "任务结束"
        Type.UNKNOWN -> "无法解析动作"
    }

    companion object {
        /**
         * 从模型输出中提取候选 JSON 对象：跳过字符串与转义，按花括号配对截取，
         * 最多取 3 个候选（模型常在 JSON 后面继续碎碎念，甚至再给一个修正版）。
         */
        private fun jsonCandidates(text: String): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < text.length && out.size < 3) {
                val start = text.indexOf('{', i)
                if (start < 0) break
                var depth = 0
                var inStr = false
                var esc = false
                var j = start
                var closed = false
                while (j < text.length) {
                    val c = text[j]
                    if (inStr) {
                        if (esc) esc = false
                        else if (c == '\\') esc = true
                        else if (c == '"') inStr = false
                    } else when (c) {
                        '"' -> inStr = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) { closed = true; break }
                        }
                    }
                    j++
                }
                if (!closed) break
                out.add(text.substring(start, j + 1))
                i = j + 1
            }
            return out
        }

        /**
         * 把 JSON 里的加减乘除算式替换为计算结果，如 "y2": 216 - 72 → "y2": 144。
         * 循环执行以支持连续算式（如 216 - 72 - 8）。
         */
        private fun sanitizeArithmetic(s: String): String {
            val regex = Regex("""(\d+(?:\.\d+)?)\s*([+\-*/])\s*(\d+(?:\.\d+)?)""")
            var out = s
            while (true) {
                val m = regex.find(out) ?: break
                val a = m.groupValues[1].toDouble()
                val b = m.groupValues[3].toDouble()
                val v = when (m.groupValues[2]) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    else -> if (b == 0.0) a else a / b
                }
                val text = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
                out = out.replaceRange(m.range, text)
            }
            return out
        }

        fun parse(modelOutput: String): AgentAction? {
            var fallback: AgentAction? = null
            for (cand in jsonCandidates(modelOutput)) {
                val action = parseJson(cand) ?: continue
                if (action.type != Type.UNKNOWN) return action
                if (fallback == null) fallback = action
            }
            return fallback
        }

        private fun parseJson(json: String): AgentAction? {
            val obj = try {
                JSONObject(json)
            } catch (e: Exception) {
                // 模型偶尔把坐标写成算式（如 "y2": 216 - 72），不是合法 JSON；
                // 先把加减乘除算出结果再重试解析
                val fixed = try {
                    JSONObject(sanitizeArithmetic(json))
                } catch (e2: Exception) {
                    return null
                }
                fixed
            }

            val thought = obj.optString("thought", "")
            val action = obj.optString("action", obj.optString("type", ""))
                .lowercase().trim()

            fun num(default: Float, vararg names: String): Float {
                for (n in names) {
                    if (obj.has(n)) return obj.optDouble(n, default.toDouble()).toFloat()
                }
                return default
            }

            /** 取数组形式的坐标点，如 "coordinate":[x,y]、"start":[x1,y1]、"end":[x2,y2] */
            fun arrPair(vararg names: String): Pair<Float, Float>? {
                for (n in names) {
                    val a = obj.optJSONArray(n) ?: continue
                    if (a.length() >= 2) {
                        val px = a.optDouble(0, -1.0)
                        val py = a.optDouble(1, -1.0)
                        if (px >= 0 && py >= 0) return px.toFloat() to py.toFloat()
                    }
                }
                return null
            }

            val pair1 = arrPair(
                "coordinate", "coordinates", "coord", "coords",
                "position", "pos", "point", "location", "xy",
                "start", "from", "p1"
            )
            val pair2 = arrPair("end", "to", "target", "destination", "p2")

            // 起点：优先 x/y 字段，兼容 coordinate 数组与 from_x 等写法
            var sx = num(-1f, "x", "x1", "startx", "start_x", "fromx", "from_x")
            var sy = num(-1f, "y", "y1", "starty", "start_y", "fromy", "from_y")
            if (sx < 0 && pair1 != null) { sx = pair1.first; sy = pair1.second }

            // 终点：优先 x2/y2，兼容 end/to 数组与 to_x 等写法
            var ex = num(-1f, "x2", "endx", "end_x", "ex", "tox", "to_x")
            var ey = num(-1f, "y2", "endy", "end_y", "ey", "toy", "to_y")
            if (ex < 0 && pair2 != null) { ex = pair2.first; ey = pair2.second }

            // 兼容四元素数组 [x1,y1,x2,y2]
            val fullArr = obj.optJSONArray("coordinate")
                ?: obj.optJSONArray("coordinates") ?: obj.optJSONArray("points")
            if (fullArr != null && fullArr.length() >= 4 && ex < 0) {
                ex = fullArr.optDouble(2, -1.0).toFloat()
                ey = fullArr.optDouble(3, -1.0).toFloat()
            }

            val duration = num(350f, "duration").toLong().coerceIn(50L, 10000L)
            val inputText = obj.optString("text", obj.optString("content", "")).trim()

            // 目标应用名：app / app_name / target / package 等
            val appTarget = obj.optString("app", "")
                .ifBlank { obj.optString("app_name", "") }
                .ifBlank { obj.optString("target", "") }
                .ifBlank { obj.optString("package", "") }
                .ifBlank { obj.optString("package_name", "") }
                .ifBlank { obj.optString("name", "") }
                .trim()

            // 元素编号：index / element / element_index / target_index（id 仅在为纯数字时）
            var elIndex = -1
            for (n in listOf("index", "element_index", "element", "target_index", "id")) {
                if (obj.has(n)) {
                    val v = obj.opt(n)
                    when (v) {
                        is Number -> elIndex = v.toInt()
                        is String -> v.toIntOrNull()?.let { elIndex = it }
                    }
                    if (elIndex >= 0) break
                }
            }

            fun pointValid(): Boolean = sx in 1f..100000f && sy in 1f..100000f

            // 当前计划步骤编号：plan_step / step / current_step
            var parsedStep = 0
            for (n in listOf("plan_step", "current_step", "step_no", "step")) {
                if (obj.has(n)) {
                    val v = obj.opt(n)
                    when (v) {
                        is Number -> parsedStep = v.toInt()
                        is String -> v.toIntOrNull()?.let { parsedStep = it }
                    }
                    if (parsedStep >= 1) break
                }
            }

            return when (action) {
                "tap", "click", "touch", "press", "single_tap" ->
                    if (pointValid() || elIndex >= 0)
                        AgentAction(thought, Type.TAP, x = sx, y = sy, elementIndex = elIndex)
                    else AgentAction(thought, Type.UNKNOWN)

                "double_tap", "doubletap", "dblclick", "double_click" ->
                    if (pointValid() || elIndex >= 0)
                        AgentAction(thought, Type.DOUBLE_TAP, x = sx, y = sy, elementIndex = elIndex)
                    else AgentAction(thought, Type.UNKNOWN)

                "long_press", "longpress", "hold", "long_click" ->
                    if (pointValid() || elIndex >= 0) AgentAction(
                        thought, Type.LONG_PRESS, x = sx, y = sy,
                        duration = duration.coerceAtLeast(300L),
                        elementIndex = elIndex
                    ) else AgentAction(thought, Type.UNKNOWN)

                "swipe", "drag", "scroll", "slide", "flick", "flip" ->
                    if (pointValid() && ex in 1f..100000f && ey in 1f..100000f)
                        AgentAction(
                            thought, Type.SWIPE,
                            x = sx, y = sy, x2 = ex, y2 = ey,
                            duration = duration.coerceIn(100L, 3000L)
                        )
                    else AgentAction(thought, Type.UNKNOWN)

                "wait", "sleep", "idle", "delay" ->
                    AgentAction(thought, Type.WAIT, duration = duration.coerceIn(200L, 10000L))

                "back", "press_back", "goback", "key_back", "return" ->
                    AgentAction(thought, Type.BACK)

                "home", "press_home", "key_home", "main" ->
                    AgentAction(thought, Type.HOME)

                "input", "type", "enter_text", "input_text", "fill" ->
                    if (inputText.isNotBlank()) AgentAction(thought, Type.INPUT, text = inputText)
                    else AgentAction(thought, Type.UNKNOWN)

                "open_app", "open", "launch", "launch_app", "start_app", "app" ->
                    if (appTarget.isNotBlank())
                        AgentAction(thought, Type.OPEN_APP, appName = appTarget)
                    else AgentAction(thought, Type.UNKNOWN)

                "search", "submit", "search_text", "query" ->
                    if (inputText.isNotBlank()) AgentAction(thought, Type.SEARCH, text = inputText)
                    else AgentAction(thought, Type.UNKNOWN)

                "tool_call", "call_tool", "tool", "invoke" -> {
                    // 期望格式：{"action":"tool_call","tool_name":"web_search","args":{"query":"..."}}
                    val tName = obj.optString("tool_name", obj.optString("tool", "")).trim()
                    val tArgs = mutableMapOf<String, String>()
                    obj.optJSONObject("args")?.let { argsObj ->
                        for (k in argsObj.keys()) {
                            tArgs[k] = argsObj.optString(k, "")
                        }
                    }
                    // 没写 args 对象时，把除 thought/action/tool_name 外的标量字段当参数兜底
                    if (tArgs.isEmpty()) {
                        val known = setOf("thought", "action", "type", "tool_name", "tool", "plan_step", "current_step", "step_no", "step")
                        for (k in obj.keys()) {
                            if (k !in known) tArgs[k] = obj.optString(k, "")
                        }
                    }
                    if (tName.isNotBlank()) {
                        AgentAction(thought, Type.TOOL_CALL, toolName = tName, toolArgs = tArgs)
                    } else {
                        AgentAction(thought, Type.UNKNOWN)
                    }
                }

                "finish", "done", "complete", "task_complete", "finished", "end", "stop" ->
                    AgentAction(thought, Type.FINISH)

                else -> AgentAction(thought, Type.UNKNOWN)
            }.let { if (parsedStep >= 1) it.copy(planStep = parsedStep) else it }
        }
    }
}
