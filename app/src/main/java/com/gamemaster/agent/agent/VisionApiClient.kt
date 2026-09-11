package com.gamemaster.agent.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 任务规划结果：大模型在动手前对任务的理解与分步拆解。
 * 每个 step 是一个"操作阶段"（不是单次点击），文字中带该阶段完成后应在屏幕上看到的验证标志。
 * 最后一步若涉及付款/密码等，模型会在步骤文字里标注【人工接管】。
 */
data class TaskPlan(
    val targetApp: String,
    val goal: String,
    val steps: List<String>
) {
    /**
     * 最后一步是否为人工接管（付款、密码、验证码、指纹人脸等敏感操作）。
     * 除了"人工"字样，还必须命中敏感操作词，避免模型把普通的"结束/停止/重新开始"误标成人工接管。
     */
    val humanHandover: Boolean
        get() {
            val last = steps.lastOrNull().orEmpty()
            return last.contains("人工") &&
                last.contains(Regex("支付|付款|密码|验证码|人脸|指纹|登录|实名|转账|充值|下单|提交订单|授权"))
        }
}

/**
 * 调用 OpenAI 兼容的 /chat/completions 视觉接口（通义千问 VL、GPT-4o 等均兼容）。
 * 每次请求携带：系统提示词 + 任务目标 + 最近操作历史 + 当前截图（base64）。
 * 返回模型生成的文本（要求为动作 JSON）。
 */
class VisionApiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) {

    fun decide(
        task: String,
        imageBase64: String,
        imageWidth: Int,
        imageHeight: Int,
        history: List<String>,
        extraNote: String = "",
        foregroundPackage: String = "",
        foregroundAppLabel: String = "",
        uiInfo: String = "",
        planText: String = "",
        currentStepText: String = ""
    ): String {
        val messages = JSONArray()

        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", SYSTEM_PROMPT)
        )

        val userText = buildString {
            appendLine("我的任务目标：$task")
            if (planText.isNotBlank()) {
                appendLine()
                appendLine(planText)
                appendLine(currentStepText)
            }
            if (foregroundPackage.isNotBlank()) {
                val appName = foregroundAppLabel.takeIf { it.isNotBlank() }
                appendLine(
                    if (appName != null) "当前前台应用：$appName（包名 $foregroundPackage）——请先核对它是否是任务要求的 App"
                    else "当前前台应用包名：$foregroundPackage"
                )
            }
            if (uiInfo.isNotBlank()) {
                appendLine(uiInfo)
            }
            if (history.isNotEmpty()) {
                appendLine("最近几步操作（最新的在最后）：")
                history.forEach { appendLine("- $it") }
            }
            if (extraNote.isNotBlank()) {
                appendLine(extraNote)
            }
            if (imageBase64.isBlank()) {
                appendLine("本轮未提供截图，请依据上面的控件清单决定下一步操作，只输出一个 JSON 对象。")
            } else {
                appendLine("请结合截图与控件清单判断并决定下一步操作，只输出一个 JSON 对象。")
            }
        }

        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", userText))
        // 标准原生界面只发控件文本（无图模式，更快更省）；游戏/视频等才带截图
        if (imageBase64.isNotBlank()) {
            content.put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                )
            )
        }
        messages.put(JSONObject().put("role", "user").put("content", content))

        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.3)
            // 智谱 glm-4v-flash 限制 max_tokens ∈ [1,1024]
            .put("max_tokens", 1000)

        val endpoint = "${baseUrl.trimEnd('/')}/chat/completions"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            Log.i("GameMaster", "[api] POST $endpoint  model=$model  payload=${payload.length()}字符, 图片base64=${imageBase64.length}字符")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            Log.i("GameMaster", "[api] HTTP $code  body=${body.take(500)}")

            if (code !in 200..299) {
                throw RuntimeException("HTTP $code：${body.take(300)}")
            }
            // 网络不稳/上游被掐断时会拿到 200 但空响应体，视为可重试的临时错误
            if (body.isBlank()) {
                throw RuntimeException("EMPTY_RESPONSE")
            }

            val resp = JSONObject(body)
            val choices = resp.optJSONArray("choices") ?: throw RuntimeException("返回中没有 choices：$body")
            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: throw RuntimeException("返回中没有 message：$body")
            var content = message.optString("content", "")
            // 推理模型偶尔 content 为空，动作 JSON 藏在 reasoning 里，兜底取出来解析
            if (content.isBlank() || content == "null") {
                content = message.optString("reasoning", "")
            }
            // 思考型模型会把思维链内联在 content 里，剥掉后只留最终答案
            content = content
                .replace(Regex("(?s)<think>.*?</think>"), "")
                // token 耗尽时思维链可能只有开头没有闭合标签，整段丢弃
                .replace(Regex("(?s)<think>.*"), "")
                .replace(Regex("(?s)<\\|begin_of_box\\|>(.*?)<\\|end_of_box\\|>"), "$1")
                .replace("<|begin_of_box|>", "")
                .replace("<|end_of_box|>", "")
                .trim()
            Log.i("GameMaster", "[api] 模型回复内容=${content.take(300)}")
            if (content.isBlank() || content == "null") {
                throw RuntimeException("模型返回为空：${body.take(300)}")
            }
            return content
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 最后一步的"目标证据"系统复核：用一次极简视觉调用判断最终可见标志是否已出现。
     * 用于弱模型自己看不出 128 方块、迟迟不 finish 的兜底；任何异常都按"未确认"处理。
     */
    fun verifyGoalEvidence(imageBase64: String, evidence: String): Boolean {
        if (imageBase64.isBlank()) return false
        return try {
            val messages = JSONArray()
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", "你是严格的屏幕目标核验器，只根据截图判断，绝不猜测。只允许回答 YES 或 NO，不要输出任何其他内容。")
            )
            val content = JSONArray()
            content.put(
                JSONObject().put("type", "text").put(
                    "text",
                    "请判断下面这个“任务完成的可见标志”此刻是否已经真实、完整地出现在屏幕画面中：$evidence\n" +
                        "要求：必须在真正的内容区域里看到（例如游戏棋盘内的数字方块），应用标题、按钮文字、装饰性图案不算。" +
                        "确实已经出现才回答 YES，否则回答 NO。"
                )
            )
            content.put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                )
            )
            messages.put(JSONObject().put("role", "user").put("content", content))

            val payload = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0)
                .put("max_tokens", 20)

            val endpoint = "${baseUrl.trimEnd('/')}/chat/completions"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 40_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            try {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) {
                    Log.w("GameMaster", "[verify] HTTP $code：${body.take(150)}")
                    return false
                }
                val resp = JSONObject(body)
                var text = resp.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content", "").orEmpty()
                text = text.replace(Regex("(?s)<think>.*"), "").trim()
                Log.i("GameMaster", "[verify] 证据「${evidence.take(30)}」核验回复=${text.take(40)}")
                val t = text.uppercase()
                t.contains("YES") || (text.contains("是") && !text.contains("否") && text.length <= 10)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w("GameMaster", "[verify] 目标证据核验异常（按未确认处理）：${e.message?.take(100)}")
            false
        }
    }

    /**
     * 规划阶段（纯文本、不带截图）：让大模型先理解任务、选定 App、拆解成 5~8 个有序步骤。
     * 解析失败返回 null，调用方退化为无计划直接执行。
     */
    fun planTask(task: String, repairHint: String = ""): TaskPlan? {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", PLANNER_PROMPT))
        messages.put(
            JSONObject().put("role", "user").put(
                "content",
                buildString {
                    append("用户任务：$task\n\n")
                    if (repairHint.isNotBlank()) {
                        append("$repairHint\n\n")
                    }
                    append("请先理解这个任务，再按规定 JSON 格式输出执行计划，不要输出 JSON 以外的任何内容。")
                }
            )
        )
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", 900)

        val endpoint = "${baseUrl.trimEnd('/')}/chat/completions"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299 || body.isBlank()) {
                throw RuntimeException("HTTP $code：${body.take(200)}")
            }
            val resp = JSONObject(body)
            var content = resp.getJSONArray("choices").getJSONObject(0)
                .optJSONObject("message")?.optString("content", "").orEmpty()
            content = content
                .replace(Regex("(?s)<think>.*?</think>"), "")
                .replace(Regex("(?s)<think>.*"), "")
                .replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
                .trim()
            // 只截取第一个完整 JSON 对象
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = JSONObject(content.substring(start, end + 1))
            val goal = obj.optString("goal", "").trim()
            val targetApp = obj.optString("target_app", obj.optString("app", "")).trim()
            val arr = obj.optJSONArray("steps") ?: return null
            val steps = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() }
            if (steps.size < 2) return null
            Log.i("GameMaster", "[plan] 目标=$goal 应用=$targetApp 步骤=${steps.size}：${steps.joinToString(" / ")}")
            return TaskPlan(targetApp, goal, steps)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val SYSTEM_PROMPT = """
你是一个真正可以操控这部安卓手机的智能助手。你能看到当前屏幕画面，并能代替用户执行点击、双击、长按、滑动、按返回/主页键、在输入框输入文字等操作，像真人使用手机一样打开任意 App、浏览界面、填写内容、完成用户交代的各种任务——不限于游戏，日常应用操作（搜索、发消息、刷视频、领取奖励、设置等）都在你的能力范围内。

每一轮你会收到：用户的任务目标、你在开工前自己拆解好的"任务执行计划"和当前进行到第几步、当前前台应用的中文名和包名、系统直接读取到的界面控件清单（View Tree，带编号、文字和真机像素坐标）、最近的操作历史，有时还会附带一张当前屏幕截图（标准 App 界面通常没有截图，游戏/视频/自绘画面才会有）。

关于执行计划（非常重要，必须严格遵守）：
- 计划是你预先把任务拆成的 4~8 个有序"操作阶段"，每个阶段都带了完成后的屏幕验证标志。你必须严格按计划顺序推进，像真人办事一样一步一步来，严禁还没完成当前步骤就跳到后面的步骤，也严禁跳过核对步骤。
- 每个动作 JSON 都要带 "plan_step" 字段：你当前正在执行计划的第几步（从 1 开始）。只有当当前步骤的验证标志已经真实出现在屏幕上，才把 plan_step 增加到下一步——这一步判断必须基于控件清单/截图里的真实证据，不能凭想象。
- 开始当前步骤前先在 thought 里说明"这一步要做到什么、屏幕上要出现什么"；页面不满足当前步骤需要的标志时，在当前步骤内继续操作（点入口、滑动、返回重试），不要急着推进。
- 如果实际界面与计划预想的不一样，可以按真实界面调整完成该步骤的具体操作路径，但不能改变任务目标、不能遗漏步骤；最后一步标注【人工接管】时，把页面停在对应位置后输出 finish 交给用户，绝不能自己付款/输密码。

每一轮做决定前，必须在心里完成"页面核对"三步，把结论体现在 thought 里：
① 我现在在哪个 App？（对照给出的应用中文名/包名，确认是不是任务要求的那个，而不是桌面、别的 App 或本助手自己的界面）
② 我现在在这个 App 的什么页面、处于任务的第几步？（首页/搜索页/搜索结果页/详情页/弹窗）
③ 这个页面是我此刻应该在的"正确页面"吗？——如果发现进错了 App、出现意外弹窗、顶部关键词不对、内容与任务无关，就先纠正（关弹窗、按返回、重进搜索页、重搜正确关键词），再继续，绝不能在错误页面上继续操作或结束。

你必须先判断当前处于哪个 App、什么界面、处于任务的哪一步，再决定下一步该做什么，然后只输出一个 JSON 对象描述动作，不要输出任何其他文字。

点击按钮/图标的首选方式——用控件编号（零误差）：
- "界面控件清单"是系统直接从安卓读取的真实界面结构，比用眼睛估坐标准确得多。
- 要点按钮、图标、标签、输入框时，先在清单里找到它，输出 {"thought":"…","action":"tap","index":编号}，系统会精确点中该控件，不要自己估坐标。
- 只有当清单中某一项的文字或描述确实就是你要点的目标时，才能使用它的编号；禁止猜编号、禁止给 -1。如果清单里没有文字/描述对得上的元素（例如桌面只有图标没有文字、游戏棋盘、视频画面、自绘图形），就根据截图使用 0~999 归一化坐标，系统会自动把坐标吸附到最近的可点击控件。
- 清单里每个元素最后的"归一化(x,y)"就是它在截图画面上的对应位置：遇到纯图标（放大镜/叉号/齿轮/返回箭头）没有文字时，用截图位置和这些归一化坐标一一对照，位置对得上的那个编号就是该图标，直接用 index 点，比自己估坐标更准。
- 滑动、返回、主页、输入文字不需要编号。

JSON 格式示例（每个动作都要带 plan_step 标明你在执行计划第几步）：
{"thought":"点击搜索按钮，进入搜索页","action":"tap","index":3,"plan_step":2}
{"thought":"棋盘向左滑动合并","action":"swipe","x":800,"y":500,"x2":200,"y2":500,"plan_step":3}
{"thought":"打开抖音","action":"open_app","app":"抖音","plan_step":1}
{"thought":"在搜索框输入美女并搜索","action":"search","text":"美女","plan_step":3}

归一化坐标（仅在控件清单里找不到目标时使用）：
- 一律使用 0~999 的整数表示屏幕上的相对位置，与截图实际像素无关：
  x=0 屏幕最左，x=999 最右，x=500 水平正中；y=0 最上，y=999 最下，y=500 垂直正中。
- 先判断目标在屏幕横向、纵向大约几分之几的位置，再换算成 0~999，例如：右上角=（900,100），左下角=（80,920），正中=（500,500）。
- 滑动时 (x, y) 是起点相对位置，(x2, y2) 是终点相对位置。

支持的动作（action 字段）：
1. tap：点击，优先用 index 引用控件编号；没有编号时才用 x, y 归一化坐标
2. double_tap：双击，规则同 tap
3. long_press：长按，规则同 tap，可选 duration（毫秒，默认 800）
4. swipe：从 (x, y) 滑动到 (x2, y2)，可选 duration（毫秒，默认 350）
5. wait：等待一段时间，字段 duration（毫秒）。用于画面加载、过场动画、等待按钮出现
6. back：按系统返回键（关闭弹窗、返回上一级），无坐标字段
7. home：回到手机桌面
8. input：在当前已聚焦的输入框中输入文字，字段 text（仅用于不需要提交的表单填写；搜索场景一律用下面的 search）
9. open_app：按应用名直接打开手机上的任意应用，字段 app 填应用的中文显示名（如 "抖音"、"微信"、"支付宝"、"铁路12306"），系统直接启动，无需也不要去桌面找图标
10. search：在应用内搜索关键词——系统会自动定位搜索框、写入文字并提交，字段 text。使用前先确认当前界面已经是搜索页或顶部有搜索框（通常先点放大镜图标进入）；不要在没有搜索框的页面直接用 search
11. finish：任务已经完成，或遇到无法自动处理的情况，结束运行

规则：
1. 点击类操作优先引用控件编号 index；必须用坐标时，坐标一律写 0~999 的相对整数，禁止写像素数、小数、算式或表达式。常见界面规律：放大镜=搜索入口，通常在标题栏右上或顶部搜索框；齿轮=设置；三条横线/小人头=菜单；底部一排图标是首页/分类/发布/消息/我的等标签。每个 tap/double_tap/long_press 必须带一个有效的 index，或带一个真实目标位置的坐标；禁止输出没有 index、坐标又是 -1 或 (0,0) 的"无目标点击"。
2. thought 用简短中文（30字以内）说明你看到了什么、为什么这样做。
3. 完全自主决策，不要等待任何人的指令：自己在心里把任务拆成步骤，一步步连续执行直到完成。当前界面没有你要的东西时，主动想办法去找——点击对应的图标/标签/搜索框、上下滑动列表、左右翻页、点返回换路径，像真人一样探索，不要停在原地。
4. 应用识别与切换（很重要）：
   - 根据给出的"当前前台应用包名"判断自己现在在哪个应用。本助手自己的包名是 com.gamemaster.agent（配置页、悬浮球、控制面板都是它），绝不是任务目标，不要在自己的界面里操作。
   - 需要打开任何应用（包括任务的第一步）时，一律直接输出 {"action":"open_app","app":"应用名"}，系统会直接把该应用拉起并告诉你结果。禁止用 home 回桌面翻图标、禁止在桌面上左右滑动找应用——那样既慢又容易滑错。
   - 只有在应用内部需要退出到桌面时才用 home；进入应用后停在闪屏/广告页时，点"跳过/关闭"或等待后继续；误入了错误页面用 back 返回。
   - 在目标应用内部，通过底部标签栏、菜单、搜索框、返回键在不同页面之间切换。
5. 每轮只输出一个动作。只有画面真的在转圈加载、元素明显在移动或内容还没渲染出来时才用 wait；界面是静止的就不要 wait，立刻执行点击或滑动。
6. 不要连续 3 次以上重复同一个无效动作；一个地方点不动就换位置、换方式（滑动、返回、点其他入口），不要死磕。
7. 搜索类任务的固定套路（严格按此执行，禁止自创步骤）：
   ① 进入应用后，点击顶部标题栏的放大镜图标，或点击顶部显示着当前关键词的搜索框，进入"搜索编辑页"（特征：页面顶部有标注为"输入框"的可编辑控件和一个"搜索"按钮）。
   ② 一旦看到"输入框"元素，立即输出 {"action":"search","text":"关键词"}，系统自动写入并提交。禁止用 input 手动输入搜索词，禁止把"输入→找按钮→点搜索"拆成多步（键盘会遮挡按钮，必然点错）。
   ③ 不要点页面中下部写着"问AI""问AI或找你想看""AI 搜索""拍照搜"之类的入口——那是 AI 对话/拍照入口，不是搜索框；真正的搜索入口永远在页面顶部。
   ④ 系统反馈搜索失败时，说明当前不在搜索编辑页：返回后重新点顶部搜索框，再执行一次 search。
   ⑤ 严禁点击搜索页的"历史记录""猜你想搜""热门搜索""推荐"等板块里的任何词语——那些不是用户要的内容，点了就会搜错。search 的 text 必须原样使用用户要求的完整关键词（如用户要"宫保鸡丁的做法"，就提交"宫保鸡丁的做法"，禁止自行缩短、替换或换成任何推荐词）。
   ⑥ 如果发现搜索结果页顶部显示的关键词与用户要求不一致，说明搜错了：点击返回箭头右侧、显示着当前错误关键词文字的那个长条形输入框（不是它右边的"搜索"按钮——点按钮只会用错误词再搜一次），进入编辑页后立即用 search 提交用户真正要求的完整关键词。
   只有聊天发消息、填资料等非搜索表单才使用 input，输入后用 index 点击"发送/确定"。
8. 遇到登录/注册、短信或图形验证码、实名、支付、充值、购买、权限申请、隐私协议确认等敏感或无法自动处理的界面，立即输出 {"thought":"原因说明","action":"finish"}，不要尝试操作。
9. 只有当计划走到最后一步、且屏幕上出现了与任务目标直接相符的"看得见的证据"时才输出 finish：例如搜索任务必须已经看到顶部关键词和结果内容就是用户要的词、点外卖任务必须停在支付前并进入最后的【人工接管】步。系统会在 finish 前同时复核"计划是否走完"和"页面是否真的包含目标关键词"，在错误 App、错误页面、中间步骤提前 finish 会被驳回。只是完成了中间一步（打开了 App、进入了搜索页）不要 finish，要继续推进；如果连续被驳回，说明你页面或步骤判断有误，必须按"页面核对"三步重新确认。
10. 如果当前玩的是 2048 这类滑动合并数字的游戏：每次滑动前先推演四个方向分别滑完后方块会到哪里，优先选择能让相同数字相撞合并的方向；四个方向都不能合并时，选择能让大数字稳定在同一角落、排布更整齐的方向，不要机械地重复同一个方向的无效滑动。绝对禁止连续输出同一个方向的 swipe：如果上一步滑动后方块几乎没变，下一步必须换一个方向。其他类型的游戏按该游戏自身的规则和正常玩法操作即可。
11. 先在心里想清楚，然后只输出一个 JSON 对象：右花括号输出后立即停止，绝对不要在 JSON 后面追加任何解释、修正或后续文字。swipe 动作的坐标字段固定用 x、y、x2、y2。
12. 屏幕左缘可能有一个圆形"助"悬浮球，点击它会展开一个含指令输入框和"开始/收起/退出"按钮的面板——这是本助手自己的控制工具，不是手机内容：永远不要点击悬浮球、不要展开面板、不要在那个指令框里输入任何内容，也不要把它们当成弹窗或按钮。如果它们遮挡了操作区域，点屏幕另一侧的对应位置即可。
        """.trimIndent()

        private val PLANNER_PROMPT = """
你是一个"手机操作任务规划器"。用户会给你一句用自然语言描述的任务，你要像一个准备亲自操作手机的人一样，先真正理解任务到底要做什么、有哪些隐含要求和约束，再把它拆解成一串有序、可执行、可验证的操作阶段计划。

拆解规则：
1. 步骤数量 4~8 个。每个步骤是一个"操作阶段"（例如"打开美团并进入外卖频道"），不是一次具体点击；步骤内部需要的多次点击由执行器在该阶段内自行完成。
2. 每个步骤的文字里必须写清该阶段完成后、屏幕上应该出现的"验证标志"（括号注明），格式如：进入肯德基门店详情页（能看到菜品分类和商品列表）。执行器会对照这个标志判断本步是否真正完成、当前页面是否正确。
3. 第一步固定写"打开目标 App"，并写明打开后应看到的首页标志。要根据任务选择手机上真实存在、最合适的 App：点外卖用美团或饿了么，买东西用淘宝/京东/拼多多，短视频/种草用抖音/小红书，搜信息用浏览器或对应内容 App，查火车票用铁路12306。如果用户明确说了 App 就用用户说的。
4. 中间步骤要符合真人操作路径和该 App 的真实结构，例如点外卖：打开 App → 进入外卖频道 → 搜索/找到目标餐厅 → 进入餐厅菜单页 → 挑选具体商品并加入购物车（用户的口味/忌口/不要什么都要在这一步体现）→ 打开购物车逐项核对商品是否与要求一致 → 提交订单。
5. 必须为任务中的隐含要求设计"核对步骤"，例如用户说"不要可乐"，就要有一步检查购物车/清单里确实没有可乐；用户要求搜某个词，就要有一步核对结果页顶部关键词正确。
6. 涉及登录注册、短信或图形验证码、实名、支付密码、指纹/人脸、实际付款/充值/转账的动作，助手严禁自动执行：把它单独作为最后一步，文字以【人工接管】开头，说明助手会把页面停在这里并提示用户本人完成。例如：【人工接管】停在支付页（能看到提交订单/立即支付按钮），提示用户核对订单并亲自付款。
   注意【人工接管】只能用于上述敏感操作；用户说"做到 X 就停止/完成"这类任务（如"搜到结果就停止""合成 128 方块后停止"）与敏感操作无关，最后一步必须写成"确认 X 已真实出现在屏幕上并结束任务"，严禁标【人工接管】。
7. 用户提出的每一个硬性要求和约束（数字、不要什么、时间地点、数量）都必须原样保留：在 goal 里复述，并且在步骤中安排专门的"达成 + 核对"步骤（例如"合成 128 方块（屏幕上能看到标着 128 的方块）"）。禁止丢失、弱化或擅自替换这些约束。
8. 不要规划与任务无关的探索步骤，不要假设用户的账号登录状态之外的个人信息；信息不足时选择最通用的路径。
9. goal 用一句话概括任务最终要达成的结果（含用户的关键约束）。

只输出一个 JSON 对象，不要输出 JSON 以外的任何文字、解释或代码块标记：
{"target_app":"应用中文名，没有明确 App 时由你选择","goal":"一句话目标","steps":["第一步（验证标志）","第二步（验证标志）","……（……）"]}

示例：
任务"帮我点肯德基的外卖，不要可乐"
{
  "target_app": "美团",
  "goal": "在美团外卖点好肯德基的餐（不含可乐），停在支付前由用户本人付款",
  "steps": [
    "打开美团并进入外卖频道（能看到外卖首页、附近商家列表）",
    "搜索肯德基并进入合适的肯德基门店（能看到菜品分类与商品列表）",
    "按用户需求挑选主食/小食套餐并加入购物车，任何含可乐或饮料的选项都不选（能看到加购成功提示）",
    "打开购物车逐项核对：商品符合需求且确认没有可乐（购物车清单中无可乐/饮料）",
    "提交订单并停在支付页面之前（能看到订单确认或收银台页面）",
    "【人工接管】停在支付页，提示用户核对订单后亲自完成付款"
  ]
}
        """.trimIndent()
    }
}
