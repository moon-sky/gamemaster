package com.gamemaster.agent.agent

import android.graphics.Bitmap
import android.util.Base64
import com.gamemaster.agent.service.GameAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

/**
 * AI 智能体主循环：
 *   截屏 → 压缩编码 → 发给视觉大模型 → 解析动作 JSON → 通过无障碍服务执行 → 等待 → 继续
 */
class GameAgent(
    private val config: AgentConfig,
    private val service: GameAccessibilityService
) {
    /** 繁忙/异常时的备用视觉模型（仅非思考模型，保证速度；思考模型思维链太长会吃光 token） */
    private val fallbackModels = ArrayDeque(
        listOf(
            "glm-4v-flash",
            "glm-4.6v-flash"
        ).filter { it != config.model.trim() }
    )

    @Volatile
    private var client = VisionApiClient(config.baseUrl, config.apiKey, config.model)

    /** 最近操作历史（文本），带给模型做上下文 */
    private val history = ArrayDeque<String>()

    /** 连续失败计数，超过阈值自动停止 */
    private var consecutiveErrors = 0

    /** 连续限流计数（免费模型常见，单独处理，不长时间累计错误） */
    private var consecutiveRateLimit = 0

    /** 防死循环：最近一次动作签名与连续重复次数（坐标按 40px 分桶，近似相同也算重复） */
    private var lastActionSig: String? = null
    private var repeatCount = 0

    /** 动作执行连续失败计数（如输入写不进、点不中）：超过阈值直接报错停止，不再傻转 */
    private var consecutiveActionFail = 0

    /** 连续"无目标点击"计数（模型既不给 index 也不给坐标）：3 次换模型，7 次报错停止 */
    private var invalidActionCount = 0
    private var rotatedForInvalid = false

    /** finish 复核被驳回次数：模型说完成但页面上找不到目标关键词/计划未走完，3 次后放行 */
    private var wrongFinishCount = 0

    /** 最近连续相同方向的滑动序列：模型陷入机械重复（尤其弱模型）时强制纠偏 */
    private val recentSwipeDirs = ArrayDeque<String>()

    /** 上一次"重复滑动"纠偏时的方向：连续两次水平纠偏说明需要强制改用垂直方向 */
    private var lastRepeatDir: String? = null

    /** 开工前的任务理解与分步计划（规划失败可为 null，退化为无计划执行） */
    @Volatile
    private var plan: TaskPlan? = null

    /** 当前已推进到的计划步骤（1 起）；只随模型在屏幕上确认后上报的 plan_step 前进 */
    private var currentStep = 1

    /** 上次已播报的步骤，用于在悬浮窗显示"计划第 N/M 步" */
    private var lastAnnouncedStep = 0

    /** 本任务中真正提交过的搜索词；只在真正搜过之后才允许做"搜错词"纠偏 */
    private var lastSubmittedKeyword: String? = null

    /** 当前使用的模型名（轮换时同步更新） */
    private var currentModelName: String = config.model.trim()

    /** 从任务描述中解析出的搜索关键词（如"搜索宫保鸡丁的做法，搜到就停止"→"宫保鸡丁的做法"） */
    private val expectedSearchKeyword: String? = extractSearchKeyword(config.task)

    /** 从自然语言任务中提取"搜索 XXX"里的关键词 */
    private fun extractSearchKeyword(task: String): String? {
        val m = Regex(
            """搜索[一下]*[：:]?[「"'“”]?(.+?)[」"'“”]?(?:[，,。；;、\s]|搜到|找到|然后|并|再|$)"""
        ).find(task) ?: return null
        val kw = m.groupValues[1].trim()
        // 过滤"一下/内容/东西"这类无意义宾语和过短词
        if (kw.length < 2 || kw in setOf("一下", "内容", "东西", "什么")) return null
        return kw
    }

    /**
     * 搜索词纠偏：当前界面顶部搜索框显示的词与任务关键词不一致时，
     * 生成一条强制提示，要求模型直接用 search 动作提交正确关键词。
     */
    private fun searchMismatchHint(elements: List<com.gamemaster.agent.service.UiElement>): String? {
        val kw = expectedSearchKeyword ?: return null
        // 只有本任务真的提交过一次搜索之后才检测"搜错词"——
        // 否则首页的城市选择器（如"北京"）、热搜词等顶部文字会被误判成错误搜索词
        if (lastSubmittedKeyword == null) return null
        val box = elements
            .filter {
                it.top in 0..400 && it.clickable &&
                    it.text.isNotBlank() && it.text.length in 2..30 &&
                    it.text.trim() != "搜索" && !it.text.startsWith("搜索")
            }
            .maxByOrNull { it.right - it.left }
            ?: return null
        val cur = box.text.trim()
        if (cur == kw || cur.contains(kw) || kw.contains(cur)) return null
        return "系统提示：检测到当前搜索词是「$cur」，与任务要求的「$kw」不一致，已经搜错了。" +
            "请立即且只输出一个动作：{\"action\":\"search\",\"text\":\"$kw\"}——" +
            "系统会自动点顶部显示当前关键词的框进入编辑页、写入正确词并提交；" +
            "不要再点右侧的「搜索」按钮（那会重复错误关键词），也不要点任何历史/推荐词。"
    }

    /** 视觉兜底剩余步数：解析连续失败或死循环时，强制截图让模型"看一眼"几轮 */
    private var visualFallbackLeft = 0

    /**
     * 死循环节点兜底时按文字查找的"弹窗解除"按钮白名单。
     * 只保留真正的中性弹窗按钮，禁止放"搜索/发送/关闭"等业务按钮——
     * 否则模型在搜索页卡住时代点会用错误关键词重新搜索，帮倒忙。
     */
    private val UNSTUCK_KEYWORDS = listOf(
        "恢复运行", "继续游戏", "我知道了", "知道了",
        "始终允许", "允许本次使用", "允许本次", "允许"
    )

    private data class PreparedImage(
        val base64: String,
        val scaledWidth: Int,
        val scaledHeight: Int,
        /** 模型看到的是缩放图，坐标乘这个系数还原为真实屏幕坐标 */
        val scaleToReal: Float
    )

    /** 把 View Tree 元素列表格式化成给模型看的控件清单文本 */
    private fun formatUiInfo(elements: List<com.gamemaster.agent.service.UiElement>, hasImage: Boolean): String {
        if (elements.isEmpty()) {
            return "界面控件清单：系统未读取到标准控件（当前很可能是游戏、视频或自绘画面），请完全依据截图用 0~999 归一化坐标操作。"
        }
        return buildString {
            appendLine("界面控件清单（系统直接读取，坐标是真机像素；点击时优先用 index 编号）：")
            elements.forEach { appendLine("[${it.index}] ${it.describe()}") }
            if (hasImage) {
                appendLine("请优先从上表选择目标的 index 编号点击；表中确实没有目标时才用截图归一化坐标。")
            } else {
                appendLine("本轮没有提供截图（系统判断这是标准原生界面）。请只依据上面的控件清单决策：点击用 index 编号，配合 back/home/input/wait/finish，禁止输出坐标（没有截图坐标无法定位）。如果清单信息确实不足以完成任务（比如全是无文字的图标），输出 {\"thought\":\"需要看屏幕\",\"action\":\"wait\"}，系统下一轮会自动提供截图。")
            }
        }
    }

    /** 把计划格式化成每轮发给模型的"计划书"文本 */
    private fun formatPlan(p: TaskPlan): String = buildString {
        appendLine("任务执行计划（你在开工前拆解，必须严格按顺序完成；每个动作 JSON 都要带 plan_step）：")
        appendLine("最终目标：${p.goal}")
        p.steps.forEachIndexed { i, s -> appendLine("第${i + 1}步：$s") }
    }

    private fun formatCurrentStep(p: TaskPlan): String {
        val text = p.steps.getOrNull(currentStep - 1) ?: p.steps.first()
        return "当前进度：第 $currentStep/${p.steps.size} 步 —— $text\n" +
            "请先确认屏幕是否已满足上一步的验证标志、当前页是否正确；" +
            "只有本步的验证标志真实出现在屏幕上后，才允许把 plan_step 推进到下一步。"
    }

    /**
     * 从任务里提取用户的硬性约束：
     *  - 数字（≥10，如 128、9-26 里的 9 不算）
     *  - "不要/不加/忌/去掉 XX"这类忌口或排除项（如"不要可乐"→"可乐"）
     */
    private fun extractConstraints(task: String): List<String> {
        val out = LinkedHashSet<String>()
        Regex("\\d{2,}").findAll(task).forEach { out.add(it.value) }
        Regex("""(?:不要|不用|不加|别要|别加|忌口?|去掉|不要放)\s*([一-龥A-Za-z0-9]{2,6})""")
            .findAll(task).forEach { m ->
                // 去掉句尾语气/标点字
                out.add(m.groupValues[1].trimEnd('的', '了', '，', '。', '、', ' '))
            }
        return out.toList().filter { it.isNotBlank() }
    }

    /** 计划文本里缺失的约束数 */
    private fun missingConstraints(p: TaskPlan, constraints: List<String>): List<String> {
        val text = p.goal + " " + p.steps.joinToString(" ")
        return constraints.filterNot { text.contains(it) }
    }

    /** 开工前先规划：理解任务 → 选 App → 拆步骤。
     *  规划质量不达标（丢了用户硬约束）时带纠错提示重规划，必要时换备用模型；全失败则无计划执行 */
    private suspend fun makePlan() {
        service.postStatus("正在理解任务并拆解执行步骤…")
        val constraints = extractConstraints(config.task)
        val repairHint = if (constraints.isEmpty()) "" else
            "上一版计划丢失了用户明确提出的硬性约束：${constraints.joinToString("、")}。" +
                "请重新拆解，goal 必须复述这些约束，并且必须有专门的步骤去达成并在屏幕上核对它们。"

        // 候选顺序：主模型首规划 → 主模型带纠错重试 → 备用模型带纠错重试
        data class Candidate(val model: String, val hint: String)
        val candidates = mutableListOf(Candidate(currentModelName, ""))
        if (repairHint.isNotBlank()) {
            candidates.add(Candidate(currentModelName, repairHint))
            fallbackModels.forEach { candidates.add(Candidate(it, repairHint)) }
        } else {
            fallbackModels.forEach { candidates.add(Candidate(it, "")) }
        }

        var best: TaskPlan? = null
        var bestMissing = Int.MAX_VALUE
        for (c in candidates) {
            try {
                val p = VisionApiClient(config.baseUrl, config.apiKey, c.model).planTask(config.task, c.hint)
                if (p != null) {
                    val missing = missingConstraints(p, constraints)
                    android.util.Log.i(
                        "GameMaster",
                        "[plan] 模型 ${c.model} 给出 ${p.steps.size} 步，约束命中=${constraints.size - missing.size}/${constraints.size}，缺失=${missing.joinToString("、")}"
                    )
                    if (missing.size < bestMissing) { best = p; bestMissing = missing.size }
                    if (missing.isEmpty()) break
                }
            } catch (e: Exception) {
                android.util.Log.w("GameMaster", "[plan] 模型 ${c.model} 规划失败：${e.message?.take(120)}")
            }
        }

        val chosen = best
        if (chosen != null) {
            plan = chosen
            currentStep = 1
            service.postStatus("任务已拆解为 ${chosen.steps.size} 步：${chosen.goal}")
            android.util.Log.i(
                "GameMaster",
                "[plan] 采用计划：${chosen.steps.size} 步，目标 App=${chosen.targetApp}，人工接管=${chosen.humanHandover}：" +
                    chosen.steps.joinToString(" / ")
            )
            delay(600)
            return
        }
        service.postStatus("规划服务暂不可用，直接开始执行（建议稍后重试以获得分步计划）…")
        delay(800)
    }

    suspend fun run() {
        // 第 0 阶段：先理解任务、拆解计划，再动手
        makePlan()

        service.postStatus("AI 助手已启动，正在观察屏幕…")
        var step = 0

        while (coroutineContext.isActive) {
            step++

            // 0. 系统级"是否允许打开 XX"确认弹窗（HyperOS/MIUI 后台启动拦截）：
            //    先自动点"允许/始终允许/继续"，避免任务卡死在系统弹窗上
            if (service.dismissLaunchConfirmDialog()) {
                service.postStatus("检测到系统确认弹窗，已自动点「允许」…")
                delay(1500)
                continue
            }

            // 1. 先读无障碍 View Tree（原生界面定位的第一信息源，零成本、不受锁屏影响）
            val elements = service.collectUiElements()
            val labeledCount = elements.count { it.text.isNotBlank() || it.desc.isNotBlank() }
            // 存在"无文字无描述的可点击控件"（放大镜/齿轮/叉号等纯图标）时，
            // View Tree 无法表达其语义，需要截图让模型看一眼，否则会认错图标
            val unlabeledClickables = elements.count {
                it.clickable && it.text.isBlank() && it.desc.isBlank()
            }

            // 2. 判定本轮是否需要截图视觉：
            //    无控件 / 无任何文字描述（游戏、视频、纯图标画面）/ 存在纯图标可点项 /
            //    死循环 / 视觉兜底剩余轮次
            val needImage = elements.isEmpty() || labeledCount == 0 ||
                unlabeledClickables > 0 ||
                repeatCount >= 3 || visualFallbackLeft > 0
            if (visualFallbackLeft > 0) visualFallbackLeft--

            // 3. 需要时才截屏 + 编码
            var image: PreparedImage? = null
            if (needImage) {
                val screenshot = service.captureScreenshot()
                if (screenshot == null) {
                    // 多半是屏幕熄灭/锁屏：自动唤醒解锁后重试
                    if (consecutiveErrors == 1 || consecutiveErrors == 5) {
                        service.wakeAndUnlock()
                        service.postStatus("截屏失败，正在唤醒屏幕并解锁…")
                        delay(3000)
                        consecutiveErrors++
                        continue
                    }
                    if (++consecutiveErrors > 10) {
                        service.setState(
                            com.gamemaster.agent.service.AgentState.ERROR,
                            "连续截屏失败，已停止。请确认无障碍服务仍在运行。"
                        )
                        break
                    }
                    service.postStatus("第 $step 步：截屏失败，1.5 秒后重试…（$consecutiveErrors/10）")
                    delay(1500)
                    continue
                }
                image = prepare(screenshot)
                screenshot.recycle()
            }

            val uiInfo = formatUiInfo(elements, hasImage = image != null)
            val p = plan
            val planText = p?.let { formatPlan(it) }.orEmpty()
            val currentStepText = p?.let { formatCurrentStep(it) }.orEmpty()
            android.util.Log.i(
                "GameMaster",
                "[uitree] 元素=${elements.size} 有文字=$labeledCount 截图=${image != null}" +
                    if (elements.isNotEmpty())
                        "：" + elements.joinToString(" | ") {
                            "[${it.index}]${it.text.ifBlank { it.desc }.ifBlank { it.viewId }}${if (it.clickable) "✓" else ""}"
                        }.take(400) else ""
            )

            // 搜索词纠偏：顶部搜索框里的词和任务关键词不一致时，强制要求模型用 search 重搜
            searchMismatchHint(elements)?.let { hint ->
                if (history.lastOrNull() != hint) {
                    history.addLast(hint)
                    service.postStatus("检测到搜索词错误，已要求重新搜索正确关键词…")
                }
            }

            // 4. 调用大模型（连续重复同一动作时附加破环警告）
            val loopNote = if (repeatCount >= 3) {
                "系统提醒：你已连续 $repeatCount 次执行几乎完全相同的动作，但画面没有任何变化，这是无效死循环！" +
                    "说明你点的位置不对或当前点法行不通。请重新仔细观察截图，换一个准确位置，或改用滑动、返回、点其他入口等完全不同的操作。"
            } else ""
            val output = try {
                client.decide(
                    task = config.task,
                    imageBase64 = image?.base64.orEmpty(),
                    imageWidth = image?.scaledWidth ?: 0,
                    imageHeight = image?.scaledHeight ?: 0,
                    history = history.toList(),
                    extraNote = loopNote,
                    foregroundPackage = service.currentPackageName(),
                    foregroundAppLabel = service.currentAppLabel(),
                    uiInfo = uiInfo,
                    planText = planText,
                    currentStepText = currentStepText
                )
            } catch (e: Exception) {
                val msg = e.message ?: ""
                val transient = msg.contains("429") ||
                    msg.contains("rate-lim", ignoreCase = true) ||
                    msg.contains("in_flight") ||
                    msg.contains("Retry-After", ignoreCase = true) ||
                    msg.contains("temporarily", ignoreCase = true) ||
                    msg.contains("EMPTY_RESPONSE") ||
                    msg.contains("模型返回为空") ||
                    msg.contains("返回中没有 choices")
                if (transient) {
                    // 先尝试轮换到备用模型
                    if (fallbackModels.isNotEmpty()) {
                        val next = fallbackModels.removeFirst()
                        client = VisionApiClient(config.baseUrl, config.apiKey, next)
                        android.util.Log.i("GameMaster", "[agent] 网络异常/限流，轮换到模型 $next（原因：${msg.take(60)}）")
                        service.postStatus("网络不稳或模型繁忙，自动切换到备用模型：$next")
                        delay(1500)
                        continue
                    }
                    if (++consecutiveRateLimit >= 40) {
                        service.setState(
                            com.gamemaster.agent.service.AgentState.ERROR,
                            "所有模型长时间不可用，已停止。请检查网络后稍后再试。"
                        )
                        break
                    }
                    service.postStatus("第 $step 步：网络不稳/模型繁忙，15 秒后重试…（$consecutiveRateLimit/40）")
                    delay(15000)
                    continue
                }
                consecutiveRateLimit = 0
                if (++consecutiveErrors >= 5) {
                    service.setState(
                        com.gamemaster.agent.service.AgentState.ERROR,
                        "AI 接口连续失败，已停止。请检查 API 地址、Key、模型名和网络。($msg)"
                    )
                    break
                }
                service.postStatus("第 $step 步：接口错误，2 秒后重试…（${msg.take(120)}）")
                delay(2000)
                continue
            }

            // 4. 解析动作
            val action = AgentAction.parse(output)
            if (action == null || action.type == AgentAction.Type.UNKNOWN) {
                // 纯控件树模式下模型可能无从判断，强制接下来两轮带截图视觉兜底
                visualFallbackLeft = 2
                if (++consecutiveErrors >= 4) {
                    service.setState(
                        com.gamemaster.agent.service.AgentState.ERROR,
                        "多次无法解析 AI 返回，已停止。返回内容：${output.take(120)}"
                    )
                    break
                }
                service.postStatus("第 $step 步：无法解析 AI 返回，下一轮自动提供截图重试…")
                delay(2000)
                continue
            }
            // 无图模式下模型主动要求"看屏幕"，下一轮起给截图
            if (image == null && action.type == AgentAction.Type.WAIT &&
                action.thought.contains("看")
            ) {
                visualFallbackLeft = 2
            }
            consecutiveErrors = 0
            consecutiveRateLimit = 0
            android.util.Log.i(
                "GameMaster",
                "[agent] 第 $step 步解析成功：${action.summary()}" +
                    if (p != null && action.planStep > 0) "（模型自报计划第 ${action.planStep}/${p.steps.size} 步，当前=$currentStep）" else ""
            )

            // 计划进度推进：一次只允许前进一格，模型跳步（2→4）或超出范围时忽略，
            // 防止它没验证屏幕就一口气把计划走完然后提前 finish
            if (p != null && action.planStep in 1..p.steps.size) {
                if (action.planStep > currentStep + 1) {
                    history.addLast(
                        "系统：你上报的 plan_step=${action.planStep} 跳步了（当前是第 $currentStep 步）。" +
                            "必须先在屏幕上完成并验证当前第 $currentStep 步「${p.steps.getOrNull(currentStep - 1).orEmpty()}」，" +
                            "确认验证标志出现后才能推进到下一步；本轮仍按第 $currentStep 步执行。"
                    )
                    service.postStatus("计划 $currentStep/${p.steps.size}：模型试图跳步到 ${action.planStep}，已要求逐格推进")
                } else if (action.planStep == currentStep + 1) {
                    currentStep = action.planStep
                    val stepText = p.steps.getOrNull(currentStep - 1).orEmpty()
                    history.addLast(
                        "系统：你已进入计划第 $currentStep/${p.steps.size} 步——$stepText。" +
                            "请确认屏幕上已出现该步要求的验证标志后再操作；完成后才能继续推进。"
                    )
                }
                if (currentStep != lastAnnouncedStep) {
                    lastAnnouncedStep = currentStep
                    service.postStatus("计划 $currentStep/${p.steps.size}：${p.steps.getOrNull(currentStep - 1).orEmpty().take(40)}")
                }
            }

            // 无效动作闸门：点击类动作既没有有效 index 也没有坐标（glm-4v-flash 会刷 (-1,-1)），
            // 这种动作没有任何意义，不允许执行；先强反馈纠正，3 次换模型，7 次报错停止
            val targetlessTap = (action.type == AgentAction.Type.TAP ||
                action.type == AgentAction.Type.DOUBLE_TAP ||
                action.type == AgentAction.Type.LONG_PRESS) &&
                action.elementIndex < 1 && (action.x < 0f || action.y < 0f)
            if (targetlessTap) {
                invalidActionCount++
                history.addLast(
                    "系统：第 $invalidActionCount 次无目标点击（没有 index、没有坐标），该动作无效且不会执行。" +
                        "请立即改用控件清单中的编号 index 点击，或给出 0~999 真实坐标；" +
                        "搜索任务请点顶部搜索框进入编辑页后，直接输出 search 并原样提交用户要求的完整关键词，" +
                        "不要点历史记录/猜你想搜，不要重复无效点击。"
                )
                service.postStatus("第 $step 步：模型输出了无目标点击，已要求纠正（$invalidActionCount/7）")
                when {
                    invalidActionCount >= 7 -> {
                        service.setState(
                            com.gamemaster.agent.service.AgentState.ERROR,
                            "模型连续 $invalidActionCount 次输出无目标点击，无法继续，已自动停止，请手动接管。"
                        )
                        break
                    }
                    invalidActionCount >= 3 && !rotatedForInvalid && fallbackModels.isNotEmpty() -> {
                        val next = fallbackModels.removeFirst()
                        fallbackModels.addLast(currentModelName)
                        client = VisionApiClient(config.baseUrl, config.apiKey, next)
                        currentModelName = next
                        rotatedForInvalid = true
                        visualFallbackLeft = 2
                        android.util.Log.i("GameMaster", "[agent] 无目标点击过多，轮换到模型 $next")
                        service.postStatus("无目标点击过多，切换备用模型：$next")
                    }
                }
                delay(800)
                continue
            } else if (action.type != AgentAction.Type.WAIT) {
                invalidActionCount = 0
            }

            // 防死循环统计：WAIT 是合法的连续等待，不参与判定
            if (action.type != AgentAction.Type.WAIT) {
                val sig = "${action.type}:" +
                    "${(action.x / 40).toInt()}:${(action.y / 40).toInt()}:" +
                    "${(action.x2 / 40).toInt()}:${(action.y2 / 40).toInt()}"
                if (sig == lastActionSig) repeatCount++ else {
                    lastActionSig = sig
                    repeatCount = 1
                }
            }

            // 死循环升级 A：连续 4 次相同动作，视觉模型多半被弹窗卡住且点不中按钮，
            // 用无障碍节点树按文字精确查找并点击（如"恢复运行"）
            if (repeatCount >= 4) {
                val ok = service.findAndTapByText(UNSTUCK_KEYWORDS)
                if (ok) {
                    history.addLast(
                        "$step. （系统备注：上一步的弹窗按钮是系统自动代点的，弹窗已关闭，游戏仍在进行中。请继续正常游玩，禁止输出 finish。）"
                    )
                    service.postStatus("检测到死循环：已通过界面节点自动点击弹窗按钮，继续观察…")
                    delay(1500)
                    continue
                }
            }

            // 死循环升级 B：连续 6 次相同动作且节点兜底无效，轮换备用模型换脑子
            if (repeatCount >= 6 && fallbackModels.isNotEmpty()) {
                val next = fallbackModels.removeFirst()
                fallbackModels.addLast(currentModelName)
                client = VisionApiClient(config.baseUrl, config.apiKey, next)
                currentModelName = next
                android.util.Log.i("GameMaster", "[agent] 死循环未解开，轮换到模型 $next")
                service.postStatus("连续重复操作未解开，切换备用模型：$next")
                repeatCount = 0
                delay(1000)
                continue
            }

            // 死循环升级 C：连续 8 次相同动作（点按钮、换模型都没解开），大概率游戏卡死（ANR），
            // 用 root 直接重启前台游戏进程，任务不中断
            if (repeatCount >= 8) {
                if (service.restartTopApp()) {
                    history.addLast(
                        "$step. （系统备注：游戏刚才卡死已被系统自动重启，现在回到游戏了。请重新观察画面，继续正常游玩，禁止输出 finish。）"
                    )
                    service.postStatus("检测到游戏卡死，已自动重启游戏，继续任务…")
                    repeatCount = 0
                    lastActionSig = null
                    delay(5000)
                    continue
                }
                repeatCount = 4  // 重启失败则降档，继续尝试节点点击兜底
            }

            // 4.8 finish 前的双重复核（硬闸门）：
            //     ① 计划复核：必须已推进到计划最后一步（人工接管计划到达倒数第二步的支付页即可）；
            //     ② 页面复核：搜索类任务的目标关键词必须真实出现在当前 View Tree 上。
            //     防止模型在错误 App / 错误页面 / 中间步骤提前宣布完成。
            if (action.type == AgentAction.Type.FINISH) {
                val blockedSensitive = action.thought.contains(Regex("登录|验证码|实名|无法|不能"))
                val kw = expectedSearchKeyword
                val handover = p != null && p.humanHandover && currentStep >= p.steps.size - 1

                var rejectReason: String? = null
                if (p != null && !handover) {
                    val needReach = if (p.humanHandover) p.steps.size - 1 else p.steps.size
                    if (currentStep < needReach) {
                        rejectReason = "计划还没有走完：你只推进到第 $currentStep/${p.steps.size} 步，" +
                            "还需要继续完成「${p.steps.getOrNull(needReach - 1)}」等后续步骤。" +
                            "请严格按计划继续操作（每步动作带正确的 plan_step，看到验证标志后再推进），全部完成前禁止 finish。"
                    }
                }
                if (rejectReason == null && !blockedSensitive && !handover && kw != null &&
                    !service.screenContainsText(kw)
                ) {
                    rejectReason = "当前屏幕上完全没有出现「$kw」，说明这不是任务目标页面" +
                        "（可能进错了 App、停在错误的页面，或搜索结果是错的）。请核对当前应用和页面内容，" +
                        "直到屏幕上真正出现「$kw」相关内容后再 finish。"
                }

                if (rejectReason != null) {
                    wrongFinishCount++
                    history.addLast("系统：第 $wrongFinishCount 次「完成」判定被驳回——$rejectReason")
                    service.postStatus("第 $step 步：模型想提前结束，已驳回（$wrongFinishCount/3）：${rejectReason.take(50)}")
                    if (wrongFinishCount < 3) {
                        delay(800)
                        continue
                    }
                    android.util.Log.i("GameMaster", "[agent] finish 复核连续 3 次未通过，放行结束（可能为自绘区域或模型步数上报偏差）")
                }
                // 计划中的人工接管：记为正常完成到最后一步，悬浮窗绿色，提示用户接手
                if (handover) {
                    service.setState(
                        com.gamemaster.agent.service.AgentState.FINISHED,
                        "已按计划完成到最后一步，当前页面需要你接管（付款/密码等敏感操作不会自动执行）。"
                    )
                    break
                }
            }

            // 5. 记录历史 + 上报状态
            val el = elements.getOrNull(action.elementIndex - 1)
            val reason = action.thought.ifBlank { action.summary() }
                .let { if (el != null) "$it →[${el.text.ifBlank { el.desc.ifBlank { "控件#${el.index}" } }}]" else it }
            history.addLast("$step. $reason → ${action.summary()}")
            while (history.size > 8) history.removeFirst()
            service.postStatus("第 $step 步：$reason")

            // 6. 执行（无截图轮次用真机屏幕分辨率作为归一化换算基准）
            val actionOk = if (image != null) {
                execute(action, image.scaleToReal, image.scaledWidth, image.scaledHeight, elements)
            } else {
                val dm = service.realScreenSize()
                execute(action, 1f, dm.first, dm.second, elements)
            }

            // 连续执行失败止损：与其在一个动作上无限乱点/乱输，不如报错停下让人介入
            if (actionOk) {
                consecutiveActionFail = 0
            } else {
                consecutiveActionFail++
                if (consecutiveActionFail >= 4) {
                    service.setState(
                        com.gamemaster.agent.service.AgentState.ERROR,
                        "连续 $consecutiveActionFail 次执行失败（${action.summary()}），已自动停止，请手动接管。"
                    )
                    break
                }
            }

            // 7. 动作后的等待节奏
            when (action.type) {
                AgentAction.Type.FINISH -> {
                    // 模型因登录/验证码/支付等敏感界面主动结束，视为出错中止而非完成
                    val blocked = action.thought.contains(Regex("登录|验证|支付|充值|实名|无法|不能"))
                    if (blocked) {
                        service.setState(
                            com.gamemaster.agent.service.AgentState.ERROR,
                            "任务中止：$reason"
                        )
                    } else {
                        service.setState(
                            com.gamemaster.agent.service.AgentState.FINISHED,
                            "任务结束：$reason"
                        )
                    }
                    break
                }
                AgentAction.Type.WAIT -> delay(action.duration)
                AgentAction.Type.SWIPE -> delay(action.duration + 700L)
                AgentAction.Type.LONG_PRESS -> delay(action.duration + 700L)
                AgentAction.Type.BACK, AgentAction.Type.HOME -> delay(1200L)
                AgentAction.Type.INPUT -> delay(800L)
                AgentAction.Type.OPEN_APP -> delay(3500L)
                AgentAction.Type.SEARCH -> delay(2500L)
                else -> delay(1100L)
            }
        }

        // 循环结束（正常完成/自动停止）后清除"应运行"标记，避免被自动恢复机制重新拉起
        service.onAgentLoopEnded()
        service.postStatus("AI 助手已停止。")
    }

    private fun prepare(bitmap: Bitmap): PreparedImage {
        val maxEdge = 768
        val w = bitmap.width
        val h = bitmap.height
        val longest = maxOf(w, h)
        val ratio = if (longest > maxEdge) maxEdge.toFloat() / longest else 1f

        val scaledWidth = (w * ratio).toInt().coerceAtLeast(1)
        val scaledHeight = (h * ratio).toInt().coerceAtLeast(1)

        val scaled = if (ratio < 1f) {
            Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        } else {
            bitmap
        }

        val out = ByteArrayOutputStream(maxEdge * 2)
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (scaled !== bitmap) scaled.recycle()

        val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return PreparedImage(
            base64 = base64,
            scaledWidth = scaledWidth,
            scaledHeight = scaledHeight,
            scaleToReal = w.toFloat() / scaledWidth
        )
    }

    private suspend fun execute(
        action: AgentAction,
        scale: Float,
        imgW: Int,
        imgH: Int,
        elements: List<com.gamemaster.agent.service.UiElement>
    ): Boolean {
        // 真机分辨率（截图按最长边 768 缩放，scale 还原，保证与设备像素 1:1）
        val realW = imgW * scale
        val realH = imgH * scale

        // 坐标判定：模型默认输出 0~999 的相对坐标；只要有任意一个坐标 >999，
        // 说明它误用了缩放图像素坐标，按像素模式兼容
        val coords = listOf(action.x, action.y, action.x2, action.y2)
        val normalized = coords.none { it > 999f }

        // 归一化 → 真机；像素 → 钳制在截图内再乘缩放系数
        fun mx(v: Float, span: Float, pixelMax: Float): Float =
            if (normalized) (v.coerceIn(0f, 999f) / 999f) * span
            else v.coerceIn(1f, pixelMax) * scale
        fun cx(v: Float): Float = mx(v, realW, (imgW - 2).coerceAtLeast(2).toFloat())
        fun cy(v: Float): Float = mx(v, realH, (imgH - 2).coerceAtLeast(2).toFloat())

        val coordMode = if (normalized) "归一化" else "像素"

        /**
         * 统一的点击落点解析：
         *  - 有有效 index：直接用控件清单里的真机像素中心（零误差）
         *  - 无 index：归一化坐标换算成真机像素后，交给无障碍服务做命中测试/就近吸附
         */
        suspend fun resolvePoint(): Pair<Float, Float>? {
            if (action.elementIndex >= 1) {
                val el = elements.getOrNull(action.elementIndex - 1)
                if (el == null) {
                    android.util.Log.w("GameMaster", "[gesture] 模型给的 index=${action.elementIndex} 超出清单范围(共${elements.size}个)，放弃本次点击")
                    return null
                }
                // 编号路径：按元素身份精确点击，不做坐标命中/吸附，避免点错旁边控件
                val target = service.clickElement(el)
                if (target != null) {
                    return if (target.nodeClicked) PAIR_NODE_CLICKED
                    else target.x.toFloat() to target.y.toFloat()
                }
                android.util.Log.i("GameMaster", "[gesture] index=${el.index} 无法精确命中，放弃本次点击")
                return null
            }
            if (action.x < 0f || action.y < 0f) {
                android.util.Log.w("GameMaster", "[gesture] 动作既无有效 index 也无坐标，放弃本次点击")
                return null
            }
            val px = cx(action.x)
            val py = cy(action.y)
            val target = service.resolveTap(px, py)
            if (target != null) {
                if (target.nodeClicked) {
                    android.util.Log.i("GameMaster", "[gesture] $coordMode(${action.x.toInt()},${action.y.toInt()}) → 命中节点「${target.label}」已直接点击")
                    return PAIR_NODE_CLICKED
                }
                android.util.Log.i("GameMaster", "[gesture] $coordMode(${action.x.toInt()},${action.y.toInt()}) → 吸附到(${target.x},${target.y})「${target.label}」")
                return target.x.toFloat() to target.y.toFloat()
            }
            android.util.Log.i("GameMaster", "[gesture] $coordMode(${action.x.toInt()},${action.y.toInt()}) → 真机(${px.toInt()},${py.toInt()}) 直接手势")
            return px to py
        }

        val ok: Boolean = when (action.type) {
            AgentAction.Type.TAP -> {
                val p = resolvePoint()
                when {
                    p == null -> {
                        history.addLast(
                            "系统：上一步是无目标点击（既没有有效 index 也没有坐标），动作无效。" +
                                "请重新观察后选择一个屏幕上真实可见的目标，用它的编号 index 或 0~999 坐标点击；" +
                                "若是搜索任务，进入顶部搜索编辑页后直接输出 search 并原样提交用户要求的完整关键词，" +
                                "严禁点击历史记录/猜你想搜等推荐词。"
                        )
                        false
                    }
                    p === PAIR_NODE_CLICKED -> true
                    else -> service.tap(p.first, p.second)
                }
            }

            AgentAction.Type.DOUBLE_TAP -> {
                val p = resolvePoint()
                when {
                    p == null -> false
                    p === PAIR_NODE_CLICKED -> true
                    else -> service.doubleTap(p.first, p.second)
                }
            }

            AgentAction.Type.LONG_PRESS -> {
                val p = resolvePoint()
                when {
                    p == null -> false
                    p === PAIR_NODE_CLICKED -> true
                    else -> service.longPress(p.first, p.second, action.duration)
                }
            }

            AgentAction.Type.SWIPE -> {
                // 模型常给"短滑"（一两百像素），2048/列表按 fling 识别容易漏判。
                // 在归一化坐标里规整：取主导轴为唯一方向、滑满至少 45% 屏宽/高、
                // 起止点收进安全边距内，保证任何 App 都能识别为明确的方向手势。
                val lo = 70f; val hi = 930f; val minLen = 450f
                var sx = action.x.coerceIn(lo, hi)
                var sy = action.y.coerceIn(lo, hi)
                var ex = action.x2.coerceIn(lo, hi)
                var ey = action.y2.coerceIn(lo, hi)
                val dx = ex - sx
                val dy = ey - sy
                if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
                    ey = sy
                    if (kotlin.math.abs(dx) < minLen) {
                        ex = sx + (if (dx >= 0) minLen else -minLen)
                    }
                } else {
                    ex = sx
                    if (kotlin.math.abs(dy) < minLen) {
                        ey = sy + (if (dy >= 0) minLen else -minLen)
                    }
                }
                // 平移整条手势使其落在安全边距内
                if (ex < lo) { val d = lo - ex; ex += d; sx += d }
                if (ex > hi) { val d = hi - ex; ex += d; sx += d }
                if (ey < lo) { val d = lo - ey; ey += d; sy += d }
                if (ey > hi) { val d = hi - ey; ey += d; sy += d }
                sx = sx.coerceIn(lo, hi); sy = sy.coerceIn(lo, hi)
                // 方向重复检测：连续 4 次同方向说明模型在瞎滑（如一直左滑），强制纠偏
                val dir = if (kotlin.math.abs(ex - sx) >= kotlin.math.abs(ey - sy)) {
                    if (ex >= sx) "右" else "左"
                } else {
                    if (ey >= sy) "下" else "上"
                }
                if (recentSwipeDirs.lastOrNull() != dir) recentSwipeDirs.clear()
                recentSwipeDirs.addLast(dir)
                if (dir == "上" || dir == "下") lastRepeatDir = null
                if (recentSwipeDirs.size >= 4) {
                    // 连续两次都在水平方向上卡死（左↔右横跳），这次直接强制垂直方向
                    val stuckHorizontal = (dir == "左" || dir == "右") &&
                        (lastRepeatDir == "左" || lastRepeatDir == "右")
                    val hint = if (stuckHorizontal) {
                        "系统：你一直在左右横跳，水平滑动已经无法产生合并——这一步必须改用垂直方向（向上或向下）滑动，" +
                            "看看哪些相同数字在同一列（比如同一列上下相邻的两个相同方块），让它们纵向相撞；" +
                            "接下来两步也优先做纵向合并，把大数字集中到同一列的角落。"
                    } else {
                        "系统：你已连续 ${recentSwipeDirs.size} 次只向$dir 滑动，但棋盘没有产生合并——这个方向当前无效，属于机械重复。" +
                            "请仔细看清棋盘：找出同一行或同一列上相邻/只隔空格的相同数字，立即换一个能让它们相撞合并的方向（上/下/左/右中的另一个方向），" +
                            "并在后续几步持续围绕大数字所在的角落布局；不要再重复向$dir 滑动。"
                    }
                    history.addLast(hint)
                    android.util.Log.i("GameMaster", "[anti-repeat] 检测到连续 ${recentSwipeDirs.size} 次$dir 滑，已注入换方向纠偏（强制垂直=$stuckHorizontal）")
                    lastRepeatDir = dir
                    recentSwipeDirs.clear()
                }
                service.swipe(
                    cx(sx), cy(sy),
                    cx(ex), cy(ey),
                    action.duration.coerceAtMost(300)
                ).also {
                    android.util.Log.i("GameMaster", "[gesture] swipe 规整后归一化(${sx.toInt()},${sy.toInt()})→(${ex.toInt()},${ey.toInt()}) → 真机(${cx(sx).toInt()},${cy(sy).toInt()})→(${cx(ex).toInt()},${cy(ey).toInt()}) ok=$it")
                }
            }

            AgentAction.Type.BACK -> { service.globalBack(); true }
            AgentAction.Type.HOME -> { service.globalHome(); true }
            AgentAction.Type.INPUT -> {
                val inputOk = service.inputText(action.text)
                if (!inputOk) {
                    history.addLast("系统：输入失败——当前界面没有可输入的文本框，请先用编号或坐标点击输入框使其出现光标/键盘，再重新输入；不要重复直接输入")
                }
                inputOk
            }

            AgentAction.Type.OPEN_APP -> {
                val label = service.launchApp(action.appName)
                if (label != null) {
                    // 把真实启动结果反馈给模型，下一轮它就知道当前在哪个应用
                    history.addLast("系统：已成功打开应用「$label」")
                    true
                } else {
                    history.addLast("系统：设备上没有找到名为「${action.appName}」的应用，请确认应用名或换别的方式")
                    service.postStatus("没找到应用「${action.appName}」")
                    false
                }
            }

            AgentAction.Type.SEARCH -> {
                val ok = service.submitSearch(action.text)
                if (ok) {
                    // 记录本任务已真实提交的搜索词，之后才允许做"顶部关键词不一致"纠偏
                    lastSubmittedKeyword = action.text.trim()
                    // 里程碑确认：让模型下一轮明确知道"现在应该是 X 的结果页"，并据此核对页面
                    history.addLast(
                        "系统：已提交搜索「${action.text}」。下一轮你必须核对——当前页面顶部关键词应显示「${action.text}」、" +
                            "页面内容应与「${action.text}」相关；若顶部是别的词或页面不对，说明搜错了，按规则⑥立即重搜。"
                    )
                } else {
                    history.addLast("系统：搜索提交失败——请先点击页面上的搜索框/放大镜使其进入可输入状态，再重新执行 search；不要在没有搜索框的页面直接搜索")
                }
                ok
            }

            else -> true
        }
        if (!ok) android.util.Log.w("GameMaster", "[gesture] 动作执行失败：${action.summary()}")
        return ok
    }

    /** 哨兵：节点已通过 ACTION_CLICK 消费，无需再发手势 */
    private val PAIR_NODE_CLICKED: Pair<Float, Float> = Float.NaN to Float.NaN
}
