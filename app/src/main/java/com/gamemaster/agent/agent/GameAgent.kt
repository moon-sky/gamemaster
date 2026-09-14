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

    /** 弱模型无视"换垂直方向"指令时，下一次 swipe 直接由系统改写为向下滑动 */
    private var forceVerticalNext = false

    /** 已处于计划最后一步的轮数（用于节流系统级目标证据复核） */
    private var lastStepRounds = 0

    /** 连续检测到 Game Over 失败终局的轮数：超 3 次模型仍未重开则系统代点 */
    private var deadEndRounds = 0

    /** 开工前的任务理解与分步计划（规划失败可为 null，退化为无计划执行） */
    @Volatile
    private var plan: TaskPlan? = null

    /** 当前已推进到的计划步骤（1 起）；只随模型在屏幕上确认后上报的 plan_step 前进 */
    private var currentStep = 1

    /** 计划目标 App 的包名（执行期跑偏拦截用；解析失败为 null 则不校验） */
    private var targetPackage: String? = null

    /** 连续处于"错误 App/桌面"的轮数：1 轮提醒，2 轮直接把目标 App 拉回前台 */
    private var driftRounds = 0

    /** 上次已播报的步骤，用于在悬浮窗显示"计划第 N/M 步" */
    private var lastAnnouncedStep = 0

    /** 本任务中真正提交过的搜索词；只在真正搜过之后才允许做"搜错词"纠偏 */
    private var lastSubmittedKeyword: String? = null

    /** 搜索步系统托管已尝试次数（最多代劳 2 次，失败交还给模型） */
    private var systemSearchAttempts = 0

    /** 系统托管搜索是否已成功提交（成功后不再代劳，后续步骤交给模型） */
    private var systemSearchSucceeded = false

    /** 计划中"执行搜索"那一步的序号（1 起）：模型没真正搜到词之前，不得越过这一步 */
    private var searchStepIndex = -1

    /** 连续按返回键的次数（搜索任务里靠返回键脱困只会越退越远，需要强提醒） */
    private var consecutiveBacks = 0

    /** 连续停留在"登录墙"页面的轮数：超过阈值判定任务无法自主完成，优雅停止并请求人工登录 */
    private var loginWallRounds = 0

    /** "搜索结果出来就停"类任务：系统提交搜索后自行核验结果，不依赖弱模型判断结束 */
    private var searchOnlyGoal = false
    private var resultEvidenceRounds = 0

    /** 帧签名：检测滑动后画面是否真的变化（游戏里连续朝无效方向空滑） */
    private var sigBeforeAction: String? = null
    private var lastActionWasSwipe = false
    private var lastModelSwipeDir: String? = null
    private var ineffectiveSwipes = 0
    private val steerTriedDirs = ArrayDeque<String>()

    /** 连续检测到锁屏的轮数（自动唤醒解锁；长时间解不开则优雅停止） */
    private var unlockAttempts = 0

    /** 连续检测到系统 ANR 框的轮数（重启 audioserver + 代点恢复；反复复发则优雅停止） */
    private var anrRounds = 0

    /** 模型输出连续无法解析的轮数（清历史/强制格式/系统代滑兜底，绝不因此停机） */
    private var parseFailStreak = 0

    /** 游戏进行中模型误发输入/返回/Home/开应用等非法操作的次数（驳回纠偏 + 系统代滑，不停机） */
    private var gameIllegalCount = 0

    // ───────────────────────── 通用 loop 健康监测 ─────────────────────────
    /** 每轮动作后的屏幕状态记录（包名 + 帧哈希 + 进度键 + 计划步），用于识别"语义打转" */
    private data class HealthState(val pkg: String, val sig: String, val progressKey: String, val step: Int)
    private val healthStates = ArrayDeque<HealthState>()

    /** 连续"没有实质进展"的轮数：计划步没推进、系统托管阶段没前进、也没出现新画面 */
    private var roundsWithoutProgress = 0
    private var lastProgressKey: String? = null

    /** 本任务已触发"基于画面重新规划"的次数（最多 2 次，避免无限重规划） */
    private var rethinkCount = 0

    /** 语义打转脱困档位：0 正常 → 1 返回 → 2 重新规划 → 3 换模型 → 4 重启 App */
    private var semanticEscalation = 0

    /** 重规划后的"宽限轮数"：新计划需要执行窗口，期间不触发语义打转脱困 */
    private var healthGraceRounds = 0

    /** "打开应用"任务前台包名连续命中目标的轮数（2 轮稳定即判定完成，避开闪屏瞬态） */
    private var openAppConfirmRounds = 0

    /** 当前使用的模型名（轮换时同步更新） */
    private var currentModelName: String = config.model.trim()

    /** 从任务描述中解析出的搜索关键词（如"搜索宫保鸡丁的做法，搜到就停止"→"宫保鸡丁的做法"） */
    private val expectedSearchKeyword: String? = run {
        extractSearchKeyword(config.task) ?: downloadTargetApp
    }

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

    /** 下载目标应用名（如"帮我下载小红书"→"小红书"），非下载任务为 null */
    private val downloadTargetApp: String? = extractDownloadTarget(config.task)

    /** 是否是"下载/安装某个 App"类任务 */
    private val isDownloadTask: Boolean = downloadTargetApp != null

    /** 是否是游戏/合成类任务（这类任务有空滑转向等专属保护，通用健康监测不介入） */
    private val isGameTask: Boolean =
        config.task.contains(Regex("游戏|滑动|方块|棋盘|2048|消消|合成"))

    /** 是否是纯"打开/启动某应用"任务——完成与否用前台包名做确定性判定，不靠弱视觉模型 */
    private val isOpenAppTask: Boolean =
        Regex("""^\s*(帮我|帮忙|请|麻烦|去)?\s*(打开|启动|开启|点开)\s*[「"'“”]?[\w·]{1,16}[」"'“”]?\s*(应用|app|APP|软件)?\s*(吧|啊|呀|哦)?\s*[。！!.…\s]*$""")
            .matches(config.task.trim())

    /** 从自然语言任务中提取"下载/安装 XXX"里的目标应用名 */
    private fun extractDownloadTarget(task: String): String? {
        val m = Regex(
            """(?:下载|安装|装一个|下个|装个)[一下]*[：:]?[「"'“”]?(.+?)[」"'“”]?(?:[，,。；;、\s]|应用|app|APP|软件|后|然后|并|再|$)"""
        ).find(task) ?: return null
        val name = m.groupValues[1].trim()
        if (name.length < 2 || name in setOf("一下", "应用", "软件", "app", "APP", "它", "这个")) return null
        return name
    }

    /** 应用名 → 包名映射（用于直接安装已下载的 APK） */
    private fun targetPackageName(appName: String): String? = when (appName) {
        "小红书" -> "com.xingin.xhs"
        "抖音" -> "com.ss.android.ugc.aweme"
        "快手" -> "com.smile.gifmaker"
        "微信" -> "com.tencent.mm"
        "微博" -> "com.sina.weibo"
        "王者荣耀" -> "com.tencent.tmgp.sgame"
        else -> null
    }

    /** 下载托管状态：0=未开始 1=已搜索待点应用 2=已进详情页待点下载 3=下载/安装中 4=完成 */
    private var downloadPhase = 0
    /** 等待 APK 下载的重试次数 */
    private var downloadWaitAttempts = 0
    /** 下载任务是否已尝试过打开应用商店（避免反复重启） */
    private var storeLaunched = false
    /** 系统是否精确点击了目标应用条目（信任详情页，不再校验标题） */
    private var trustedDetailEntry = false

    /**
     * 搜索词纠偏：当前界面顶部搜索框显示的词与任务关键词不一致时，
     * 生成一条强制提示，要求模型直接用 search 动作提交正确关键词。
     */
    private fun searchMismatchHint(elements: List<com.gamemaster.agent.service.UiElement>): String? {
        val kw = expectedSearchKeyword ?: return null
        // 任务刚开始、系统也还没尝试过搜索时不比对——
        // 首页的城市选择器（如"北京"）、轮播占位词等顶部文字会被误判成错误搜索词
        if (lastSubmittedKeyword == null && systemSearchAttempts == 0) return null
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

    /**
     * 从计划里定位"执行搜索"那一步：第一个同时包含"搜索"和关键词的步骤
     * （"进入搜索页面/确认搜索结果"这类导航或验证步不会被误算）；
     * 找不到时退化为第一个含"搜索"+"输入/提交/开始搜"的步骤。
     */
    private fun locateSearchStep(p: TaskPlan?): Int {
        if (p == null || expectedSearchKeyword == null) return -1
        val kw = expectedSearchKeyword
        p.steps.indexOfFirst { it.contains("搜索") && it.contains(kw) }.let { if (it >= 0) return it + 1 }
        val hit = p.steps.indexOfFirst {
            it.contains("搜索") && (it.contains("输入") || it.contains("提交") || it.contains("开始搜"))
        }
        return if (hit >= 0) hit + 1 else -1
    }

    /**
     * 搜索步系统托管：弱模型在美团/大众点评这类"自绘假搜索框"页面会长时间点不进输入框、
     * 反复点空白容器。只要满足：当前是计划搜索步 + 屏幕还没出现目标关键词 + 顶部确实有搜索入口，
     * 系统就直接代劳 点入口→输入→提交 整条链路，不再等模型开窍。
     * 返回 true 表示本轮已处理，调用方应跳过模型决策。
     */
    private suspend fun maybeSystemSearch(p: TaskPlan?): Boolean {
        val kw = expectedSearchKeyword ?: return false
        if (p == null || p.humanHandover) return false
        val ssi = searchStepIndex
        if (ssi < 0 || currentStep > ssi) return false
        if (systemSearchSucceeded || lastSubmittedKeyword == kw) return false
        if (systemSearchAttempts >= 2) return false
        // 必须在目标 App 内，绝不能把桌面/别的应用自带的搜索框当成目标搜索入口
        val tp = targetPackage
        if (tp != null && service.currentPackageName() != tp) return false
        // 注意：不能用"屏幕上出现关键词"判定已搜成——首页推荐位、活动卡片本来就可能含该词；
        // 只有真的提交过（系统托管或模型 search 动作）才算数。
        // canSubmitSearch 是只读探测：登录页/遮罩页等"没有搜索入口"的页面不消耗托管次数
        if (!service.canSubmitSearch()) return false

        systemSearchAttempts++
        service.postStatus("搜索步系统托管：直接提交关键词「$kw」…")
        android.util.Log.i("GameMaster", "[search-host] 第 $systemSearchAttempts 次托管搜索「$kw」")
        val ok = service.submitSearch(kw)
        return if (ok) {
            systemSearchSucceeded = true
            lastSubmittedKeyword = kw
            consecutiveBacks = 0
            if (isDownloadTask && downloadPhase == 0) downloadPhase = 1
            history.addLast(
                "系统：检测到你多次没能把关键词「$kw」输入搜索框，系统已代为点击搜索入口、输入并提交搜索。" +
                    "现在屏幕应是「$kw」的搜索结果页。请直接继续计划的下一步（在结果中找到目标并点进去），" +
                    "不要再点搜索框、热门搜索或推荐词；只有结果明显与「$kw」无关时，才再输出 search 用同一关键词重搜。"
            )
            delay(2600)
            true
        } else {
            history.addLast(
                "系统：尝试代为搜索「$kw」，但当前页面没有可用的搜索入口。请先点击页面顶部搜索框/放大镜进入搜索编辑页，" +
                    "然后只输出一个动作：{\"action\":\"search\",\"text\":\"$kw\"}，禁止点历史记录/热门搜索等推荐词。"
            )
            service.postStatus("系统托管未找到搜索入口，请模型先进入搜索页…")
            delay(800)
            false
        }
    }

    /**
     * 下载任务系统托管：弱模型在应用商店搜索结果页经常点错条目（如点到广告位"快手"），
     * 进了别的 App 的详情页又误以为搜错而反复重搜，最终卡死。
     *
     * 系统接管"验证+下载+完成"三段：
     *  - 进入详情页后核对标题必须含目标应用名，否则返回结果页（点错了）
     *  - 标题正确则直接点"下载"按钮
     *  - 下载/安装中持续等待，出现"打开/已安装"即判定完成
     * 返回 true 表示本轮已处理，跳过模型决策。
     */
    private suspend fun maybeSystemDownload(): Boolean {
        val target = downloadTargetApp ?: return false
        if (!isDownloadTask || downloadPhase >= 4) return false
        val tp = targetPackage
        if (tp == null) return false

        // 应用内协议/隐私弹窗（如应用宝"登录请知悉以下协议"）：挡住操作，自动点"同意并继续"
        if (service.pageContainsText("同意并继续") || service.pageContainsText("同意并继续使用")) {
            android.util.Log.i("GameMaster", "[download] 检测到应用协议弹窗，点击「同意并继续」")
            service.findAndTapByText(listOf("同意并继续", "同意", "我已阅读"))
            service.postStatus("处理应用协议弹窗…")
            delay(1500)
            return true
        }

        // Phase 0：还没进应用商店——直接用已解析的商店包名打开
        if (downloadPhase == 0 && !storeLaunched) {
            storeLaunched = true
            android.util.Log.i("GameMaster", "[download] 下载任务开局，系统直接打开应用商店 $tp")
            service.launchAppByPackage(tp)
            service.postStatus("正在打开应用商店…")
            delay(3500)
            return true
        }

        // 跑偏到桌面/其他应用：重新打开应用商店
        if (service.currentPackageName() != tp) {
            android.util.Log.i("GameMaster", "[download] 已不在应用商店，重新打开 $tp")
            service.launchAppByPackage(tp)
            service.postStatus("重新打开应用商店…")
            delay(3000)
            return true
        }

        // 在应用商店的"下载管理"详情页（有"一键安装"按钮）：自动返回，避免模型在错误页面死循环
        // 注意：首页右上角"待安装"按钮的 content-desc 也是"下载管理"，不能用"下载管理"判断
        if (service.pageContainsText("一键安装")) {
            android.util.Log.i("GameMaster", "[download] 当前在下载管理详情页，返回")
            service.globalBack()
            service.postStatus("返回应用商店首页…")
            delay(1500)
            return true
        }

        // 检测下载是否已完成：直接用 pm install 安装已下载的 APK（绕过应用宝 UI 的反自动化）
        if (systemSearchSucceeded && downloadPhase < 2) {
            val pkg = targetPackageName(target)
            if (pkg != null) {
                // 如果已安装，直接完成
                if (service.isPackageInstalled(pkg)) {
                    downloadPhase = 4
                    android.util.Log.i("GameMaster", "[download] 「$target」已安装完成")
                    service.setState(
                        com.gamemaster.agent.service.AgentState.FINISHED,
                        "「$target」已下载并安装完成"
                    )
                    return true
                }
                android.util.Log.i("GameMaster", "[download] 尝试直接安装 APK：$pkg")
                service.postStatus("安装「$target」…")
                val ok = service.installDownloadedApk(pkg)
                if (ok) {
                    if (service.isPackageInstalled(pkg)) {
                        downloadPhase = 4
                        android.util.Log.i("GameMaster", "[download] 「$target」已安装完成")
                        service.setState(
                            com.gamemaster.agent.service.AgentState.FINISHED,
                            "「$target」已下载并安装完成"
                        )
                        return true
                    }
                    downloadPhase = 3
                    history.addLast("系统：已通过系统命令直接安装「$target」，请等待安装完成。")
                    delay(3000)
                    return true
                }
                // APK 不存在：应用宝可能正在后台下载，等待后重试
                downloadWaitAttempts++
                if (downloadWaitAttempts <= 12) {
                    service.postStatus("「$target」下载中，等待…(${downloadWaitAttempts}/12)")
                    delay(5000)
                    return true
                }
            }
        }

        // Phase 1：在应用商店里，让模型搜索+点应用+点下载。
        // 注意：应用宝等商店界面大量自绘，无障碍树读不到应用名/下载按钮文字，
        // 系统无法精确点击或校验标题，必须依赖视觉模型。系统只负责兜底弹窗和完成检测。
        if (downloadPhase <= 1) {
            // 还没搜索过：系统直接代劳搜索（应用宝搜索框是自绘的，模型点不进去）
            if (!systemSearchSucceeded && lastSubmittedKeyword != target) {
                if (service.canSubmitSearch()) {
                    android.util.Log.i("GameMaster", "[download] 系统代劳搜索「$target」")
                    service.postStatus("搜索「$target」…")
                    val ok = service.submitSearch(target)
                    if (ok) {
                        systemSearchSucceeded = true
                        lastSubmittedKeyword = target
                        downloadPhase = 1
                        history.addLast("系统：已代为搜索「$target」并触发下载。" +
                            "如果现在在下载管理页面且看到「$target」的「安装」按钮，请点击它。" +
                            "如果弹出「要安装此应用吗？」系统会自动处理。")
                        delay(3000)
                        return true
                    }
                }
            }
            // 已搜索过：直接进入下载管理页面（应用宝搜索结果页有反自动化机制会退后台）
            if (systemSearchSucceeded && !service.pageContainsText("待安装")) {
                // 先确保在首页（点击底部"首页"tab）
                if (service.tapNodeByText("首页")) {
                    delay(1500)
                }
                // 点击右上角"待安装"按钮（content-desc="下载管理"）
                if (service.tapNodeByDesc("下载管理")) {
                    android.util.Log.i("GameMaster", "[download] 点击待安装进入下载管理")
                    service.postStatus("进入下载管理…")
                    delay(2000)
                    return true
                }
            }
            // 给模型明确的执行指引，避免瞎点
            if (history.none { it.contains("系统：下载任务执行指引") }) {
                history.addLast(
                    "系统：下载任务执行指引——你现在在应用商店里，请按以下步骤操作，每步完成后继续下一步：\n" +
                        "1. 点击顶部搜索框，输入「$target」并搜索；\n" +
                        "2. 在搜索结果里找到标题为「$target」的第一个应用（带「广告」标签的不要点），点击进入详情页；\n" +
                        "3. 点击详情页的「下载」按钮；\n" +
                        "4. 等待下载和安装完成。如果弹出「要安装此应用吗？」系统会自动处理，你不用管。\n" +
                        "不要反复搜索，不要点广告，完成一步就继续下一步。"
                )
            }
            // 不 return false 让模型执行——但先检查是否已经出现安装确认框或完成状态
        }

        // ---- 始终检查：安装确认框 / 完成状态 / 下载进度 ----
        // 系统安装确认框"要安装此应用吗？"：自动点"安装"继续
        if (service.pageContainsText("要安装此应用") || service.pageContainsText("安装此应用")) {
            downloadPhase = 3
            android.util.Log.i("GameMaster", "[download] 检测到系统安装确认框，点击「安装」")
            service.findAndTapByText(listOf("安装"))
            service.postStatus("确认安装…")
            delay(2500)
            return true
        }
        if (service.pageContainsText("打开") || service.pageContainsText("已安装") ||
            service.pageContainsText("启动") || service.pageContainsText("卸载")
        ) {
            downloadPhase = 4
            android.util.Log.i("GameMaster", "[download] 「$target」已安装完成")
            service.setState(
                com.gamemaster.agent.service.AgentState.FINISHED,
                "「$target」已下载并安装完成"
            )
            return true
        }
        // 仍在下载/安装中：有进度条/百分比/下载中等字样
        if (downloadPhase >= 2 && (service.pageContainsText("下载中") || service.pageContainsText("安装中") ||
            service.pageContainsText("等待中") || service.pageContainsText("%") ||
            service.pageContainsText("暂停"))
        ) {
            service.postStatus("「$target」正在下载/安装中，继续等待…")
            delay(3500)
            return true
        }

        // Phase 1 且无弹窗/完成信号：交给模型执行
        if (downloadPhase <= 1) return false

        return false
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
        val scaleToReal: Float,
        /** 本轮截图的均值哈希，用于判断动作后画面是否真的变化 */
        val sig: String
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

    /** 取最后一步括号里的"屏幕验证标志"作为系统复核证据；没有括号则用整步描述 */
    private fun lastStepEvidence(p: TaskPlan): String? {
        val last = p.steps.lastOrNull()?.trim().orEmpty()
        if (last.isBlank()) return null
        val marker = Regex("[（(]([^（）()]+)[）)]").findAll(last)
            .map { it.groupValues[1] }.lastOrNull()?.trim()
        return cleanEvidence(marker?.takeIf { it.length >= 2 } ?: last)
    }

    /**
     * 把计划步骤描述清洗成纯"可见事实"，避免动作词干扰视觉核验：
     * "确认128方块已合成并结束任务" → "128方块已合成"
     */
    private fun cleanEvidence(raw: String): String {
        var s = raw.trim()
        s = Regex("^(请先|请|先)?(确认|检查|验证|核对|确保|保证|观察|查看|看到|等待|找到|点击|打开|进入|完成)+")
            .replace(s, "")
        s = Regex(
            "(，|,|。|；|;)?(并|然后|之后|随后|即可|就)?(结束任务|结束游戏|结束本局|停止操作|停止游戏|完成任务|结束|停止)([。.！!]?)$"
        ).replace(s, "$4")
        s = s.trim().trim('，', ',', '。', '.', '的', ' ')
        // 合成类游戏（2048 等）：弱视觉模型认不出"128方块已合成"这种抽象状态，
        // 也认不稳"标着128的方块"，但能稳定认出"一个清楚写着数字128的方块"——
        // 只要证据含数字 + 方块/格子/合成等游戏词，统一转成具体可见描述
        val num = Regex("(\\d{1,5})").find(s)
        if (num != null && s.contains(Regex("方块|格子|合成|合并|tile", RegexOption.IGNORE_CASE))) {
            return "一个清楚写着数字${num.groupValues[1]}的方块"
        }
        return s.ifBlank { raw }
    }

    /** 等待指定包名回到前台（冷启动闪屏可能要 10 秒左右），超时返回 false */
    private suspend fun waitForeground(pkg: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (service.currentPackageName() == pkg) return true
            delay(700)
        }
        return service.currentPackageName() == pkg
    }

    /**
     * App 跑偏检测：当前前台不是目标 App（也不是系统弹窗/本助手）时纠偏。
     * @return true 表示系统已直接把目标 App 拉回前台，主循环应跳过本轮决策
     */
    private suspend fun driftGuard(): Boolean {
        val tp = targetPackage ?: return false
        val ta = plan?.targetApp ?: return false
        val fg = service.currentPackageName()
        val systemSafe = fg.isBlank() || fg == service.packageName ||
            fg == "android" || fg == "com.android.systemui" || fg == "com.android.permissioncontroller"
        if (systemSafe || fg == tp) {
            driftRounds = 0
            return false
        }
        driftRounds++
        val place = if (service.isHomeApp(fg)) "手机桌面" else "另一个应用（包名 $fg）"
        return if (driftRounds >= 2) {
            history.addLast("系统：你已经离开目标应用「$ta」跑到了$place。系统已直接重新打开「$ta」，请回到后继续未完成的计划步骤，不要再按 Home 或打开其他应用。")
            android.util.Log.i("GameMaster", "[drift-guard] 连续 $driftRounds 轮偏离目标（fg=$fg），直接拉回 $ta($tp)")
            service.postStatus("检测到跑到了$place，已自动拉回「$ta」…")
            service.launchApp(ta)
            // 部分 App（应用宝等）冷启动有 7~10 秒闪屏，没起来就连续拉回只会互相打断
            waitForeground(tp, 14000)
            driftRounds = 0
            true
        } else {
            history.addLast("系统：当前屏幕是$place，不是任务要求的「$ta」。请立即输出 open_app 打开「$ta」回到任务（不要点桌面图标、不要打开相机/设置等任何其他应用，也不要按 Home），然后继续未完成的计划步骤。")
            service.postStatus("提醒：当前不在目标 App「$ta」，要求立即返回…")
            android.util.Log.i("GameMaster", "[drift-guard] 第 $driftRounds 轮偏离目标（fg=$fg），已提醒模型返回 $ta")
            false
        }
    }

    // ─────────────── 通用 loop 健康监测：语义打转识别 + 自动脱困阶梯 ───────────────

    /** 当前"进度指纹"：计划步 + 系统托管阶段，任一变化都算实质进展（不含 finish 驳回计数——谎报完成不是进展） */
    private fun healthProgressKey(): String =
        "step=$currentStep|search=$systemSearchSucceeded|kw=${lastSubmittedKeyword ?: "-"}|dl=$downloadPhase"

    /**
     * 在每轮决策前调用：记录当前屏幕状态并判断是否"语义打转/长期无进展"。
     * 与坐标级 repeatCount 互补——它抓的是"换了位置但在做同一件无效的事 / 两个页面间横跳"。
     * @return 0=本轮正常放行给模型；1=系统已执行脱困动作，主循环应 continue；2=无法脱困，应 break 停机
     */
    private suspend fun healthCheckAndRecover(sigNow: String?, image: PreparedImage?): Int {
        val pkg = service.currentPackageName().orEmpty()
        val key = healthProgressKey()

        // 画面是否"新"：与最近记录的同 App 画面差异 ≥4% 视为新画面
        val novelScreen = sigNow == null || healthStates.none {
            it.pkg == pkg && sigDiffRatio(sigNow, it.sig) < 0.04
        }
        // 进度指纹前进 = 实质进展；只出现新画面（无目的浏览）不算实质进展，但说明没在同一帧打转
        val progressed = key != lastProgressKey
        if (progressed) {
            roundsWithoutProgress = 0
            semanticEscalation = 0
        } else if (!novelScreen) {
            roundsWithoutProgress++
        }
        lastProgressKey = key
        if (sigNow != null) {
            healthStates.addLast(HealthState(pkg, sigNow, key, currentStep))
            while (healthStates.size > 10) healthStates.removeFirst()
        }

        // 刚重规划完的宽限期：给新计划几步真正执行的窗口，旧画面记录不参与判定
        if (healthGraceRounds > 0) {
            healthGraceRounds--
            return 0
        }

        // 语义打转：最近窗口里同一画面（同包、帧差异<2%）已出现 ≥3 次，且进度指纹一直没动
        val window = healthStates.takeLast(9)
        val revisit = if (sigNow != null) window.count {
            it.pkg == pkg && it.progressKey == key && sigDiffRatio(sigNow, it.sig) < 0.02
        } else 0
        val semanticLoop = revisit >= 3
        val noProgress = roundsWithoutProgress >= 10

        if (!semanticLoop && !noProgress) return 0
        android.util.Log.i(
            "GameMaster",
            "[health] pkg=$pkg 语义打转=$semanticLoop(重访=$revisit) 无进展轮数=$roundsWithoutProgress 档位=$semanticEscalation 重规划次数=$rethinkCount"
        )

        // 兜底：已经重新规划过 2 次、脱困档位走满，仍然长期无进展——明确报错停机，不再空转烧 token
        if (rethinkCount >= 2 && (roundsWithoutProgress >= 20 || semanticEscalation >= 4)) {
            service.setState(
                com.gamemaster.agent.service.AgentState.ERROR,
                "连续 $roundsWithoutProgress 步没有实质进展，多次换策略仍无法完成任务，已自动停止，请手动接管。"
            )
            return 2
        }

        // 脱困阶梯升级。语义打转优先 BACK（关隐藏遮罩/退出卡住子页）；
        // 仅"无进展但画面在变"时跳过 BACK，直接重新规划。
        if (semanticEscalation == 0 && semanticLoop) {
            semanticEscalation = 1
            service.postStatus("检测到在同一界面反复操作，先按返回退出卡住的页面…")
            history.addLast(
                "系统：你已在当前界面附近反复操作但画面没有进展。系统已按一次返回退出可能的遮罩/子页面，" +
                    "请在返回后的新界面重新观察，换一条路径推进计划；若此路不通就回到任务主入口，禁止重复刚才的操作。"
            )
            android.util.Log.i("GameMaster", "[health] 脱困档位 1：BACK")
            service.globalBack()
            delay(1400)
            repeatCount = 0
            lastActionSig = null
            return 1
        }

        if (semanticEscalation <= 1 || (noProgress && semanticEscalation == 0)) {
            semanticEscalation = 2
            if (applyRethink(image, roundsWithoutProgress)) {
                return 1
            }
            // 截图/重规划失败：降档继续，不阻塞
            semanticEscalation = 1
        }

        if (semanticEscalation == 2 && fallbackModels.isNotEmpty()) {
            semanticEscalation = 3
            val next = fallbackModels.removeFirst()
            fallbackModels.addLast(currentModelName)
            client = VisionApiClient(config.baseUrl, config.apiKey, next)
            currentModelName = next
            service.postStatus("反复无进展，切换备用模型：$next")
            android.util.Log.i("GameMaster", "[health] 脱困档位 3：切换模型 $next")
            history.addLast("系统：已切换到另一个视觉模型 $next 重新观察屏幕，请用与之前不同的方式推进任务。")
            repeatCount = 0
            roundsWithoutProgress = 0
            delay(1000)
            return 1
        }

        if (semanticEscalation <= 3 && service.restartTopApp()) {
            semanticEscalation = 4
            service.postStatus("换策略无效，已重启当前应用以摆脱卡死状态…")
            android.util.Log.i("GameMaster", "[health] 脱困档位 4：重启前台应用")
            history.addLast("系统：当前应用疑似卡死，已被系统重启。请重新观察界面，从任务当前计划步继续，禁止输出 finish。")
            repeatCount = 0
            lastActionSig = null
            roundsWithoutProgress = 0
            targetPackage?.let { waitForeground(it, 14000) } ?: delay(5000)
            return 1
        }

        return 0
    }

    /** 基于当前真实画面重新规划任务；成功则替换计划并重置执行期状态 */
    private suspend fun applyRethink(image: PreparedImage?, stuckRounds: Int): Boolean {
        if (rethinkCount >= 2) return false
        var img = image
        if (img == null) {
            val bmp = service.captureScreenshot()
            if (bmp != null) {
                img = prepare(bmp)
                bmp.recycle()
            }
        }
        if (img == null) {
            android.util.Log.w("GameMaster", "[replan] 无法截屏，跳过画面重规划")
            return false
        }
        service.postStatus("原计划在当前界面走不通，正在根据屏幕重新规划…")
        val appHint = plan?.targetApp ?: service.currentPackageName().orEmpty()
        val reason = "连续 $stuckRounds 轮操作没有推进到下一步（停在第 $currentStep 步），并反复回到相似画面"
        val np = try {
            client.replanFromScreen(config.task, img.base64, appHint, reason)
        } catch (e: Exception) {
            android.util.Log.w("GameMaster", "[replan] 调用失败：${e.message?.take(100)}")
            null
        } ?: return false

        plan = np
        currentStep = 1
        lastAnnouncedStep = 0
        searchStepIndex = locateSearchStep(np)
        systemSearchAttempts = 0
        systemSearchSucceeded = false
        lastSubmittedKeyword = null
        resultEvidenceRounds = 0
        val lastStepText0 = np.steps.lastOrNull().orEmpty()
        searchOnlyGoal = !expectedSearchKeyword.isNullOrBlank() && (
            Regex("搜(索|一下)[^，。；]{0,14}(结果|出来)[^，。；]{0,10}(停|完成|结束|好)").containsMatchIn(config.task) ||
                (lastStepText0.contains("搜索结果") &&
                    Regex("出现|显示|看到|出来|找到").containsMatchIn(lastStepText0) &&
                    !Regex("点击|进入|打开|下载|安装|购买|付款").containsMatchIn(lastStepText0))
            )
        if (targetPackage == null) targetPackage = service.resolveAppPackage(np.targetApp)
        rethinkCount++
        repeatCount = 0
        lastActionSig = null
        roundsWithoutProgress = 0
        gameIllegalCount = 0
        // 新计划需要真正的执行窗口：清空旧画面记录、脱困档位归零、给 3 轮宽限不打转判定
        healthStates.clear()
        semanticEscalation = 0
        healthGraceRounds = 3
        history.clear()
        history.addLast(
            "系统：原计划在当前界面走不通，已根据你眼前的屏幕重新拆解为 ${np.steps.size} 步，新目标：${np.goal}。" +
                "请严格按新计划从第 1 步开始执行，不要重蹈之前卡住的操作。"
        )
        service.postStatus("已重新规划 ${np.steps.size} 步：${np.goal.take(40)}")
        android.util.Log.i(
            "GameMaster",
            "[replan] 第 $rethinkCount 次重规划：${np.steps.size} 步，目标 App=${np.targetApp}：" +
                np.steps.joinToString(" / ")
        )
        delay(900)
        return true
    }

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
        // 所有模型都可能遇到 429 限流：整轮失败时退避重试，绝不带着"无计划"裸跑
        for (attempt in 1..3) {
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
            if (best != null || attempt == 3) break
            val wait = 6000L * attempt
            service.postStatus("规划服务繁忙，${wait / 1000} 秒后自动重试（第 $attempt/3 轮）…")
            android.util.Log.i("GameMaster", "[plan] 整轮规划失败，${wait / 1000}s 后重试（第 $attempt 轮）")
            delay(wait)
        }

        val chosen = best
        if (chosen != null) {
            plan = chosen
            currentStep = 1
            // 定位"执行搜索"步：模型没真正搜出关键词之前，禁止谎报进度越过这一步
            searchStepIndex = locateSearchStep(chosen)
            systemSearchAttempts = 0
            systemSearchSucceeded = false
            lastSubmittedKeyword = null
            resultEvidenceRounds = 0
            // 识别"结果出来就停"型任务：用户原话或最后一步只要求看到搜索结果，
            // 不要求点进详情/下载等后续动作
            val lastStepText0 = chosen.steps.lastOrNull().orEmpty()
            searchOnlyGoal = !expectedSearchKeyword.isNullOrBlank() && (
                Regex("搜(索|一下)[^，。；]{0,14}(结果|出来)[^，。；]{0,10}(停|完成|结束|好)").containsMatchIn(config.task) ||
                    (lastStepText0.contains("搜索结果") &&
                        Regex("出现|显示|看到|出来|找到").containsMatchIn(lastStepText0) &&
                        !Regex("点击|进入|打开|下载|安装|购买|付款").containsMatchIn(lastStepText0))
                )
            // 解析目标 App 包名，用于执行期"跑偏拦截"：AI 只能打开这个 App
            // 下载任务特殊处理：targetApp 是要下载的应用（可能未安装），真正操作的"目标 App"
            // 是应用商店，所以优先解析商店包名；解析不到再 fallback 到模型给的 targetApp
            if (isDownloadTask) {
                val storeCandidates = listOf("应用宝", "应用商店", "华为应用市场", "小米应用商店")
                for (store in storeCandidates) {
                    val sp = service.resolveAppPackage(store)
                    if (sp != null) { targetPackage = sp; break }
                }
            }
            if (targetPackage == null) {
                targetPackage = service.resolveAppPackage(chosen.targetApp)
            }
            android.util.Log.i("GameMaster", "[plan] 目标 App 解析：${chosen.targetApp} → ${targetPackage ?: "未匹配到包名（将不做包名校验）"}")
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

        // 2048 类合成游戏：弱模型容易在左右横滑中原地踏步，开局注入固定角落策略
        if (config.task.contains("2048")) {
            history.addLast(
                "系统：2048 高分策略（必须严格执行）：① 选一个角（建议左下角）作为最大数字的固定窝，" +
                    "全程保持它在那里不动；② 绝大多数滑动只用「向左」「向下」两个方向，把相同数字不断撞向这个角；" +
                    "③ 只在能明确促成合并时才用「向上」，尽量不要「向右」（会把大数字带离角落）；" +
                    "④ 每次滑动前先找相邻或同一条线上的相同数字，让它们相撞合并；⑤ 只有真的 Game Over 时才点重开按钮；" +
                    "⑥ 棋盘是纯自绘界面，只允许输出 swipe 滑动；禁止输入文字/搜索、禁止返回或回桌面、禁止打开别的应用、不要用 index 点击。"
            )
            // 预防：本机定制 ROM 的 audioserver 偶发卡死，2048 首次移动播声音时
            // SoundPool 会阻塞主线程直接 ANR。任务开始前先重启一次音频服务（init 自动拉起）
            if (com.gamemaster.agent.screenshot.RootUtil.isRooted()) {
                com.gamemaster.agent.screenshot.RootUtil.exec("killall -9 audioserver 2>/dev/null; true")
                delay(800)
            }
        }

        service.postStatus("AI 助手已启动，正在观察屏幕…")
        var step = 0

        mainLoop@ while (coroutineContext.isActive) {
            step++

            // 0. 系统级"是否允许打开 XX"确认弹窗（HyperOS/MIUI 后台启动拦截）：
            //    先自动点"允许/始终允许/继续"，避免任务卡死在系统弹窗上
            if (service.dismissLaunchConfirmDialog()) {
                service.postStatus("检测到系统确认弹窗，已自动点「允许」…")
                delay(1500)
                continue
            }

            // 0.1 应用宝类"热门应用一键下载"首启遮罩：挡住整个首页且与任务无关，按返回关闭
            if (service.dismissBulkDownloadSheet()) {
                service.postStatus("检测到「一键下载」推荐遮罩，已自动关闭…")
                delay(1200)
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

            // 空滑检测：上一轮滑动后画面几乎没变化（帧哈希差异 < 2%），记为一次无效滑动
            val sigNow = image?.sig
            if (sigNow != null && sigBeforeAction != null) {
                val diff = sigDiffRatio(sigNow, sigBeforeAction!!)
                val frameChanged = diff >= 0.02
                if (lastActionWasSwipe) {
                    if (frameChanged) {
                        ineffectiveSwipes = 0
                        steerTriedDirs.clear()
                    } else {
                        ineffectiveSwipes++
                    }
                    android.util.Log.i("GameMaster", "[gesture] 上一动作帧变化=${(diff * 100).toInt()}% 连续空滑=$ineffectiveSwipes")
                }
                // 画面确实在变化说明动作有效：即使坐标签名相同（如同方向重复滑动），
                // 也绝不允许触发"死循环重启 App"——重启会直接清掉游戏进度
                if (frameChanged) {
                    repeatCount = 0
                    lastActionSig = null
                }
            }

            // "打开/启动应用"任务：前台包名连续 2 轮等于目标包名即确定性判定完成，
            // 必须排在健康监测之前——否则模型在已打开的页面上乱点会触发 BACK/重启，反而把 App 退掉
            if (isOpenAppTask && targetPackage != null) {
                if (service.currentPackageName() == targetPackage) {
                    openAppConfirmRounds++
                    if (openAppConfirmRounds >= 2) {
                        val appName = plan?.targetApp
                            ?: config.task.replace(Regex(".*?(打开|启动|开启|点开)"), "").trim()
                        service.setState(
                            com.gamemaster.agent.service.AgentState.FINISHED,
                            "「$appName」已打开并在前台显示，任务完成。"
                        )
                        android.util.Log.i("GameMaster", "[open-app] $targetPackage 连续 2 轮在前台，确定性判定完成")
                        break
                    }
                } else {
                    openAppConfirmRounds = 0
                }
            }

            // 通用 loop 健康监测（仅日常任务；游戏有空滑转向、下载有专属流水线，各有各的保护）：
            // 用"控件指纹优先、帧指纹兜底"识别语义打转/长期无进展，按 BACK→重规划→换模型→重启 阶梯脱困
            if (!isGameTask && !isDownloadTask) {
                val healthSig = viewTreeSignature(elements) ?: sigNow
                when (healthCheckAndRecover(healthSig, image)) {
                    1 -> continue
                    2 -> break
                }
            }

            // 游戏末步连续 2 次空滑：模型方向选死了，本轮不问模型，系统直接替它换一个
            // 没试过的方向滑动（最多尝试 3 个替代方向；四个方向全无效说明已终局，交给重开检测）
            val pl0 = plan
            if (sigNow != null && pl0 != null && ineffectiveSwipes >= 2 &&
                steerTriedDirs.size < 3 &&
                !pl0.humanHandover && currentStep >= pl0.steps.size &&
                lastStepEvidence(pl0) != null &&
                targetPackage != null && service.currentPackageName() == targetPackage
            ) {
                val cycle = listOf("上", "左", "下", "右")
                val dir = cycle.firstOrNull { it != lastModelSwipeDir && it !in steerTriedDirs }
                    ?: cycle.firstOrNull { it !in steerTriedDirs }
                if (dir != null) {
                    steerTriedDirs.addLast(dir)
                    val coords = when (dir) {
                        "上" -> floatArrayOf(500f, 720f, 500f, 260f)
                        "下" -> floatArrayOf(500f, 300f, 500f, 760f)
                        "左" -> floatArrayOf(720f, 700f, 200f, 700f)
                        else -> floatArrayOf(200f, 700f, 720f, 700f)
                    }
                    val steerAction = AgentAction(
                        "系统转向：连续向${lastModelSwipeDir ?: "原方向"}滑动棋盘毫无变化，自动改向$dir",
                        AgentAction.Type.SWIPE,
                        coords[0], coords[1], coords[2], coords[3], 220L
                    )
                    history.addLast(
                        "系统：检测到连续滑动后棋盘没有任何变化（朝${lastModelSwipeDir ?: "原方向"}的滑动当前无效），" +
                            "已自动替你向$dir 滑动一次。请重新观察棋盘，找相邻或同线的相同数字，" +
                            "优先朝能合并它们的方向滑，把大数字保持在角落。"
                    )
                    service.postStatus("连续空滑，系统自动改向$dir 滑动打破僵局…")
                    android.util.Log.i("GameMaster", "[anti-repeat] 连续 $ineffectiveSwipes 次空滑，系统代滑向$dir（已尝试：$steerTriedDirs）")
                    execute(steerAction, image!!.scaleToReal, image!!.scaledWidth, image!!.scaledHeight, elements)
                    sigBeforeAction = sigNow
                    lastActionWasSwipe = true
                    delay(900)
                    continue
                }
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
                            "[${it.index}]${it.text.ifBlank { it.desc }.ifBlank { it.viewId }}" +
                                (if (it.clickable) "✓" else "") +
                                (if (it.editable) "入" else "") +
                                (if (it.scrollable) "滑" else "")
                        }.take(400) else ""
            )

            // 搜索词纠偏：顶部搜索框里的词和任务关键词不一致时，强制要求模型用 search 重搜
            searchMismatchHint(elements)?.let { hint ->
                if (history.lastOrNull() != hint) {
                    history.addLast(hint)
                    service.postStatus("检测到搜索词错误，已要求重新搜索正确关键词…")
                }
            }

            // 锁屏检测：设备在任务中锁屏（USB 抖动/超时等）时截屏不报错但画面是锁屏，
            // 模型会在锁屏背后反复"打开应用"空转——主动唤醒解锁后再继续
            if (service.isLocked()) {
                unlockAttempts++
                if (unlockAttempts <= 5) {
                    service.postStatus("检测到锁屏，正在自动唤醒解锁…（$unlockAttempts/5）")
                    android.util.Log.i("GameMaster", "[unlock] 检测到锁屏，执行唤醒解锁（$unlockAttempts/5）")
                    service.wakeAndUnlock()
                    delay(3000)
                    continue
                }
                if (unlockAttempts == 30) {
                    service.setState(
                        com.gamemaster.agent.service.AgentState.ERROR,
                        "设备长时间停留在锁屏且无法自动解锁，任务已暂停。"
                    )
                    break
                }
                delay(2000)
                continue
            } else {
                unlockAttempts = 0
            }

            // 系统 ANR 框守护（本机定制 ROM 显示"比较累,休息一下~/恢复运行"）：
            // 2048 每次移动播音效，SoundPool 调 AudioFlinger 阻塞会导致游戏主线程 ANR。
            // 先 root 重启 audioserver 打断卡死的 binder（init 自动拉起），再点恢复，
            // 避免"恢复→下一滑又 ANR"的死循环；连续 5 次仍复发才优雅停止请求人工处理。
            if (service.findSystemErrorButton() != null) {
                anrRounds++
                if (anrRounds >= 5) {
                    service.setState(
                        com.gamemaster.agent.service.AgentState.ERROR,
                        "目标应用反复无响应（ANR），自动重启音频服务并恢复多次后仍复发，需人工检查。"
                    )
                    break
                }
                service.postStatus("检测到应用无响应(ANR)，正在重启音频服务并恢复应用…（$anrRounds/5）")
                android.util.Log.i("GameMaster", "[anr] 第 $anrRounds 次检测到系统 ANR 框，重启 audioserver 后点恢复")
                if (com.gamemaster.agent.screenshot.RootUtil.isRooted()) {
                    com.gamemaster.agent.screenshot.RootUtil.exec(
                        "killall -9 audioserver 2>/dev/null; killall -9 mediaserver 2>/dev/null; true"
                    )
                }
                delay(1500)
                service.dismissSystemErrorDialog()
                delay(4500)
                // 恢复框会重启应用进程，游戏进度清零：把计划进度与各类卡死计数一并归零，
                // 避免模型仍以为自己在"最后一步"、或旧空滑计数在新棋盘上触发误兜底
                currentStep = 1
                repeatCount = 0
                lastActionSig = null
                sigBeforeAction = null
                ineffectiveSwipes = 0
                steerTriedDirs.clear()
                continue
            } else {
                anrRounds = 0
            }

            // App 跑偏检测：当前前台既不是目标 App、也不是系统弹窗/本助手时进行纠偏；
            // 返回 true 表示本轮已直接拉回目标 App，跳过本轮决策
            if (driftGuard()) continue

            // 登录墙：目标 App 要求登录而任务尚未完成时，给模型 5 轮尝试"跳过/游客/返回"；
            // 仍登不进去就优雅停止并明确请求人工登录，绝不无限乱点或反复重启
            val fgPkg = service.currentPackageName()
            val wallHit = targetPackage != null && fgPkg == targetPackage &&
                currentStep < (p?.steps?.size ?: Int.MAX_VALUE) && service.isLoginWall()
            if (wallHit) {
                loginWallRounds++
                if (loginWallRounds >= 5) {
                    service.setState(
                        com.gamemaster.agent.service.AgentState.ERROR,
                        "目标 App「${p?.targetApp ?: targetPackage}」停留在登录页面，需要人工登录后才能继续，任务已自动暂停。"
                    )
                    android.util.Log.i("GameMaster", "[login-wall] 连续 $loginWallRounds 轮登录墙，优雅停止请求人工接管")
                    break
                }
                history.addLast(
                    "系统：当前是登录页面（检测到密码框/验证码登录）。如果页面有「跳过/稍后/游客模式」就点它绕过；" +
                        "没有的话不要反复点返回或乱点——登录需要人工账号密码，系统会在确认无法绕过后暂停任务并提示人工登录。"
                )
                service.postStatus("检测到登录页面，尝试绕过（$loginWallRounds/5）…")
                delay(600)
            } else {
                loginWallRounds = 0
            }

            // 搜索步系统托管：已回到目标 App、当前是搜索步但关键词始终没输进去时，
            // 系统直接代点搜索入口→输入→提交，避免弱模型在自绘假搜索框上无限乱点
            if (maybeSystemSearch(p)) continue

            // 下载任务系统托管：点错应用进入错误详情页时自动返回、标题正确时自动点下载、
            // 下载安装中自动等待完成，避免弱模型反复重搜或在详情页乱点
            if (maybeSystemDownload()) continue

            // "结果出来就停"型任务：系统已提交搜索后，连续 2 轮在结果列表区看到关键词+结果信号，
            // 直接验收完成——弱模型常把结果页误判成首页而迟迟不结束
            val kw0 = expectedSearchKeyword
            if (searchOnlyGoal && kw0 != null &&
                (systemSearchSucceeded || lastSubmittedKeyword == kw0)
            ) {
                if (service.searchResultEvidence(kw0)) {
                    resultEvidenceRounds++
                    android.util.Log.i("GameMaster", "[verify] 系统检测到搜索结果证据（$resultEvidenceRounds/2）")
                    if (resultEvidenceRounds >= 2) {
                        if (p != null) currentStep = p.steps.size
                        service.setState(
                            com.gamemaster.agent.service.AgentState.FINISHED,
                            "已在${p?.targetApp ?: "目标应用"}搜索出「$kw0」的结果，目标达成，自动结束。"
                        )
                        android.util.Log.i("GameMaster", "[verify] 搜索结果证据连续 2 轮成立，系统代裁 finish")
                        break
                    }
                } else {
                    resultEvidenceRounds = 0
                }
            }

            // 最后一步的系统级证据复核：弱模型可能已经达成目标却不输出 finish（如 128
            // 已经合出来还在继续滑动），每 3 轮用一次独立视觉调用代裁；
            // 同时检测"Game Over/失败终局"，像人一样重开新局继续朝目标努力，而不是空滑
            if (p != null && !p.humanHandover && currentStep >= p.steps.size) {
                lastStepRounds++
                val evidence = lastStepEvidence(p)
                if (evidence != null && image != null && lastStepRounds % 3 == 0) {
                    service.postStatus("正在复核最终目标是否达成…")
                    if (client.verifyGoalEvidence(image.base64, evidence)) {
                        history.addLast("系统：视觉复核已确认屏幕上出现最终目标证据（$evidence），任务完成，结束执行。")
                        android.util.Log.i("GameMaster", "[verify] 最终证据确认（$evidence），系统代裁 finish")
                        service.setState(
                            com.gamemaster.agent.service.AgentState.FINISHED,
                            "目标已达成：$evidence"
                        )
                        break
                    }
                    // 目标未达成但已进入失败终局：系统立刻代点右上角重开按钮开新局。
                    // 终局画面通常是自绘 View，没有控件树，弱模型给 index 根本点不中，
                    // 等它"学会"只会耗尽失败次数把任务停掉——所以直接系统动手
                    if (client.verifyDeadEnd(image.base64)) {
                        deadEndRounds++
                        recentSwipeDirs.clear()
                        ineffectiveSwipes = 0
                        steerTriedDirs.clear()
                        history.addLast(
                            "系统：游戏已经 Game Over（失败终局），但目标还没达成（$evidence），" +
                                "禁止再滑动——棋盘已经无法移动。系统正在代为点击右上角的重开按钮开始新一局；" +
                                "如果下一轮看到的仍是 Game Over 画面，请只输出一个动作：" +
                                "{\"action\":\"tap\",\"x\":908,\"y\":272}（右上角重开/循环箭头图标）。"
                        )
                        service.postStatus("检测到 Game Over，系统自动重开新局继续…")
                        android.util.Log.i("GameMaster", "[verify] 失败终局（第 $deadEndRounds 次），系统代点重开按钮")
                        val dm = service.realScreenSize()
                        service.tap(dm.first * 0.908f, dm.second * 0.272f)
                        delay(1500)
                        continue
                    } else {
                        deadEndRounds = 0
                    }
                }
            }

            // 4. 调用大模型（连续重复同一动作时附加破环警告）
            val loopNote = if (repeatCount >= 3) {
                "系统提醒：你已连续 $repeatCount 次执行几乎完全相同的动作，但画面没有任何变化，这是无效死循环！" +
                    "说明你点的位置不对或当前点法行不通。请重新仔细观察截图，换一个准确位置，或改用滑动、返回、点其他入口等完全不同的操作。"
            } else ""
            // 模型上一轮输出无法解析时，追加最强格式约束
            val formatNote = if (parseFailStreak > 0) {
                "系统紧急格式提醒：你上一条输出无法被解析。本轮只能输出一个合法 JSON 对象，且只包含一个动作；" +
                    "action 仅限 open_app/tap/double_tap/long_press/swipe/input_text/search/back/home/wait/finish；" +
                    "禁止在 thought 里用箭头或文字串联多个动作（如「滑动→滑动→滑动」），禁止输出 JSON 以外的任何字符。"
            } else ""
            val extraNote = listOf(loopNote, formatNote).filter { it.isNotEmpty() }.joinToString("\n")
            val output = try {
                client.decide(
                    task = config.task,
                    imageBase64 = image?.base64.orEmpty(),
                    imageWidth = image?.scaledWidth ?: 0,
                    imageHeight = image?.scaledHeight ?: 0,
                    history = history.toList(),
                    extraNote = extraNote,
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

            // 4. 解析动作（解析失败绝不允许停机：先纠格式重试，仍失败则系统按策略代操作）
            var action = AgentAction.parse(output)
            if (action == null || action.type == AgentAction.Type.UNKNOWN) {
                // 纯控件树模式下模型可能无从判断，强制接下来两轮带截图视觉兜底
                visualFallbackLeft = 2
                parseFailStreak++
                android.util.Log.w(
                    "GameMaster",
                    "[parse] 第 $parseFailStreak 次无法解析模型输出：${output.take(100).replace('\n', ' ')}"
                )
                // 第 2 次起模型常陷在自己写出的"多步动作链"历史里，清空历史让它重新开始
                if (parseFailStreak == 2) {
                    history.clear()
                    if (config.task.contains("2048")) {
                        history.addLast(
                            "系统：2048 角落策略：固定把最大数字窝在左下角，绝大多数滑动只用向左/向下，" +
                                "让相同数字不断撞向角落；只有明确促成合并时才向上，尽量不要向右。"
                        )
                    }
                    history.addLast(
                        "系统：你之前的输出格式错误（在一条回复里串联了多个动作）。" +
                            "从现在起每轮只输出一个合法 JSON、只描述一个 action。"
                    )
                }
                val gameLike = config.task.contains(Regex("游戏|滑动|方块|棋盘|2048|消消|合成"))
                if (gameLike && parseFailStreak >= 4 && image != null) {
                    // 模型持续宕机：系统按角落策略直接代滑，任务绝不能因模型抽风而停止
                    val cycle = listOf("左", "下", "左", "下", "上")
                    val dir = cycle[(parseFailStreak - 4) % cycle.size]
                    val coords = when (dir) {
                        "上" -> floatArrayOf(500f, 720f, 500f, 260f)
                        "下" -> floatArrayOf(500f, 300f, 500f, 760f)
                        "左" -> floatArrayOf(720f, 600f, 200f, 600f)
                        else -> floatArrayOf(200f, 600f, 720f, 600f)
                    }
                    val fallback = AgentAction(
                        "模型连续无法解析，系统按角落策略代滑向$dir",
                        AgentAction.Type.SWIPE,
                        coords[0], coords[1], coords[2], coords[3], 220L
                    )
                    history.addLast(
                        "系统：你连续 $parseFailStreak 次没有按格式输出，系统已按角落策略替你向$dir 滑动一次。" +
                            "下一轮必须恢复正常：只输出一个 JSON、一个 action。"
                    )
                    service.postStatus("AI 输出连续无法解析，系统自动向$dir 滑动保持任务推进…（$parseFailStreak）")
                    android.util.Log.i("GameMaster", "[parse] 连续 $parseFailStreak 次解析失败，系统代滑向$dir 兜底")
                    execute(fallback, image.scaleToReal, image.scaledWidth, image.scaledHeight, elements)
                    // 代滑若干次后再来一次彻底重置，给模型恢复机会
                    if (parseFailStreak >= 12) {
                        history.clear()
                        parseFailStreak = 0
                    }
                    delay(900)
                    continue
                }
                if (!gameLike && !isDownloadTask && parseFailStreak >= 10) {
                    service.setState(
                        com.gamemaster.agent.service.AgentState.ERROR,
                        "AI 连续 $parseFailStreak 次输出无法解析的内容，已停止。返回内容：${output.take(120)}"
                    )
                    break
                }
                if (isDownloadTask && parseFailStreak >= 6) {
                    // 下载任务模型持续抽风：清历史，下一轮由 maybeSystemDownload 全权接管
                    history.clear()
                    parseFailStreak = 0
                    android.util.Log.i("GameMaster", "[parse] 下载任务连续解析失败，清历史交系统托管")
                    service.postStatus("AI 输出异常，系统接管下载流程…")
                    delay(800)
                    continue
                }
                service.postStatus("第 $step 步：无法解析 AI 返回（$parseFailStreak 次），下一轮带严格格式提醒重试…")
                delay(2000)
                continue
            }
            parseFailStreak = 0
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

            // 计划进度推进：一次只允许前进一格，模型跳步（2→4）时夹到 currentStep+1，
            // 既防止它没验证屏幕就一口气走完计划，也避免它乱报超大步号导致进度永远卡死在倒数第二步
            if (p != null && action.planStep in 1..p.steps.size) {
                if (action.planStep > currentStep + 1) {
                    history.addLast(
                        "系统：你上报的 plan_step=${action.planStep} 跳步了（当前是第 $currentStep 步），" +
                            "系统只按顺序推进到第 ${currentStep + 1} 步。之后每轮只能报当前步+1，禁止再跳步。"
                    )
                    service.postStatus("计划 $currentStep/${p.steps.size}：模型试图跳步到 ${action.planStep}，已夹到第 ${currentStep + 1} 步")
                    android.util.Log.i("GameMaster", "[plan] 模型跳步 ${action.planStep}，夹到 ${currentStep + 1}")
                    action = action.copy(planStep = currentStep + 1)
                }
                if (action.planStep == currentStep + 1) {
                    // 硬闸门：想越过"执行搜索"步，必须真的提交过关键词或屏幕上已出现关键词，
                    // 防止模型点错（如点成语音识别）后谎报"搜索完成"进入结果确认步
                    val crossingSearch = searchStepIndex in 1..(currentStep + 1) &&
                        (currentStep + 1) > searchStepIndex
                    val kw = expectedSearchKeyword
                    // 只认"真的提交过搜索"：屏幕含词不算证据（首页推荐位本来就可能有该词）
                    val searchEvidenced = kw == null ||
                        lastSubmittedKeyword == kw || systemSearchSucceeded
                    if (crossingSearch && !searchEvidenced) {
                        history.addLast(
                            "系统：禁止推进！你还没有成功搜索关键词「$kw」——搜索框/结果页里都看不到它。" +
                                "请停留在当前步骤：点击顶部搜索框进入编辑页，只输出一个动作 " +
                                "{\"action\":\"search\",\"text\":\"$kw\"}；" +
                                "不要点语音按钮、历史记录、热门推荐，也不要按返回。"
                        )
                        service.postStatus("计划 $currentStep/${p.steps.size}：搜索尚未提交，已阻止模型谎报推进")
                        android.util.Log.i("GameMaster", "[plan] 阻止越过搜索步：关键词「$kw」尚无证据（当前=$currentStep，目标步=${action.planStep}）")
                    } else {
                        currentStep = action.planStep
                        val stepText = p.steps.getOrNull(currentStep - 1).orEmpty()
                        history.addLast(
                            "系统：你已进入计划第 $currentStep/${p.steps.size} 步——$stepText。" +
                                "请确认屏幕上已出现该步要求的验证标志后再操作；完成后才能继续推进。"
                        )
                    }
                }
                if (currentStep != lastAnnouncedStep) {
                    lastAnnouncedStep = currentStep
                    service.postStatus("计划 $currentStep/${p.steps.size}：${p.steps.getOrNull(currentStep - 1).orEmpty().take(40)}")
                }
            }

            // 无效动作闸门：点击类动作既没有有效 index 也没有坐标（glm-4v-flash 会刷 (-1,-1)），
            // 这种动作没有任何意义，不允许执行；先强反馈纠正，3 次换模型，7 次报错停止。
            // 另一种情况：当前是游戏/自绘画面（控件清单为空），模型却引用 index——
            // 清单里根本没有该编号，点了必然失败，按坐标点击纠偏处理，不计入"执行失败"硬止损
            val phantomIndex = elements.isEmpty() &&
                (action.type == AgentAction.Type.TAP ||
                    action.type == AgentAction.Type.DOUBLE_TAP ||
                    action.type == AgentAction.Type.LONG_PRESS) &&
                action.elementIndex >= 1
            val targetlessTap = (phantomIndex) ||
                ((action.type == AgentAction.Type.TAP ||
                    action.type == AgentAction.Type.DOUBLE_TAP ||
                    action.type == AgentAction.Type.LONG_PRESS) &&
                    action.elementIndex < 1 && (action.x < 0f || action.y < 0f))
            if (targetlessTap) {
                invalidActionCount++
                history.addLast(
                    if (phantomIndex)
                        "系统：当前画面没有任何控件清单（这是游戏/自绘界面），你引用的 index=${action.elementIndex} 不存在，该点击无效且不会执行。" +
                            "请直接看截图，给出 0~999 的归一化坐标点击；Game Over 时就点右上角重开图标 {\"action\":\"tap\",\"x\":908,\"y\":272}。"
                    else
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

            // 游戏进行中的非法操作闸门：已经在目标游戏里时，输入文字/搜索/返回/Home/打开别的应用
            // 都不可能推进游戏（弱模型偶尔对棋盘发"输入 start"）。不执行、不计失败、强纠偏，
            // 连续乱来则由系统按角落策略代滑，绝不停机
            val inTargetGame = config.task.contains(Regex("游戏|滑动|方块|棋盘|2048|消消|合成")) &&
                targetPackage != null && service.currentPackageName() == targetPackage
            if (inTargetGame && (
                    action.type == AgentAction.Type.INPUT ||
                    action.type == AgentAction.Type.SEARCH ||
                    action.type == AgentAction.Type.BACK ||
                    action.type == AgentAction.Type.HOME ||
                    action.type == AgentAction.Type.OPEN_APP
                )
            ) {
                gameIllegalCount++
                history.addLast(
                    "系统：现在已经在游戏中，${action.summary()} 是错误操作，本轮没有执行。" +
                        "游戏棋盘是自绘界面：没有输入框（禁止输入文字/搜索）、不要返回或回桌面、不要打开别的应用。" +
                        "只能输出 swipe 滑动方块（以向左/向下为主），直到画面出现目标数字方块后才允许 finish。"
                )
                android.util.Log.i(
                    "GameMaster",
                    "[game-guard] 游戏中非法操作被驳回（$gameIllegalCount 次）：${action.summary()}"
                )
                service.postStatus("AI 对棋盘发出无效操作（${action.summary().take(16)}），已驳回并要求继续滑动…")
                if (gameIllegalCount >= 2) {
                    val dirs = listOf("左", "下", "左", "下", "上")
                    val dir = dirs[(gameIllegalCount - 2) % dirs.size]
                    val coords = when (dir) {
                        "上" -> floatArrayOf(500f, 720f, 500f, 260f)
                        "下" -> floatArrayOf(500f, 300f, 500f, 760f)
                        "左" -> floatArrayOf(720f, 600f, 200f, 600f)
                        else -> floatArrayOf(200f, 600f, 720f, 600f)
                    }
                    val guardSwipe = AgentAction(
                        "游戏非法操作兜底，系统按角落策略代滑向$dir",
                        AgentAction.Type.SWIPE,
                        coords[0], coords[1], coords[2], coords[3], 220L
                    )
                    val dm = service.realScreenSize()
                    execute(guardSwipe, 1f, dm.first, dm.second, elements)
                    if (gameIllegalCount >= 10) {
                        history.clear()
                        gameIllegalCount = 0
                    }
                    delay(900)
                } else {
                    delay(700)
                }
                continue
            } else {
                gameIllegalCount = 0
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

            // 连续返回防护：搜索任务一次都没搜成时，按返回只会退出页面/退出 App，越退越远。
            // 第 3 次起强提醒，第 4 次直接拦截本次返回（下一轮搜索步托管会接管）。
            if (action.type == AgentAction.Type.BACK) {
                consecutiveBacks++
                val kw0 = expectedSearchKeyword
                val searchPending = kw0 != null && searchStepIndex >= 0 &&
                    currentStep <= searchStepIndex &&
                    lastSubmittedKeyword != kw0 && !systemSearchSucceeded
                if (consecutiveBacks >= 3 && searchPending) {
                    history.addLast(
                        "系统：你已连续按 $consecutiveBacks 次返回，但关键词「$kw0」一次都还没搜索成功，" +
                            "继续返回只会退出 App。禁止再按返回！请直接点击顶部搜索框，然后只输出一个动作：" +
                            "{\"action\":\"search\",\"text\":\"$kw0\"}。"
                    )
                    service.postStatus("检测到连续返回（$consecutiveBacks 次），已要求停止返回、直接搜索")
                    if (consecutiveBacks >= 4) {
                        delay(500)
                        continue
                    }
                }
            } else {
                consecutiveBacks = 0
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
            // 用 root 直接重启前台游戏进程，任务不中断。
            // 但滑动类重复绝不能重启——那只是模型方向选错（空滑转向会接管），重启会直接清空游戏进度
            if (repeatCount >= 8 && !lastActionWasSwipe && ineffectiveSwipes < 2) {
                if (service.restartTopApp()) {
                    history.addLast(
                        "$step. （系统备注：游戏刚才卡死已被系统自动重启，现在回到游戏了。请重新观察画面，继续正常游玩，禁止输出 finish。）"
                    )
                    service.postStatus("检测到卡死，已自动重启当前应用，继续任务…")
                    repeatCount = 0
                    lastActionSig = null
                    targetPackage?.let { waitForeground(it, 14000) } ?: delay(6000)
                    driftRounds = 0
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
                // 纯"打开/启动应用"任务：前台包名已等于目标包名就是铁证（比弱视觉核验可靠），直接放行
                val openAppDone = isOpenAppTask && targetPackage != null &&
                    service.currentPackageName() == targetPackage
                if (openAppDone) {
                    android.util.Log.i("GameMaster", "[verify] 打开应用任务：前台已是 $targetPackage，包名铁证通过")
                }
                // 视觉证据复核：游戏/合成类等"屏幕上能看到某物"的目标，在模型宣布 finish 的
                // 同一轮做一次独立视觉核验——没看到证据一律驳回，连续 6 次谎报则报错停下，绝不假完成。
                // 通用任务（非游戏/下载/搜索）没有具体证据串时，用计划目标 goal 兜底做同一次核验，
                // 让每一类任务在结束前都必须拿出"屏幕上的证据"。
                var evidenceGateActive = false
                if (rejectReason == null && !blockedSensitive && !handover && !openAppDone && p != null) {
                    // finish 是终判：即使本轮按原生界面省了截图，也要强制截一张给核验器"亲眼看"
                    var verifyImage = image
                    if (verifyImage == null) {
                        val bmp = service.captureScreenshot()
                        if (bmp != null) {
                            verifyImage = prepare(bmp)
                            bmp.recycle()
                        }
                    }
                    if (verifyImage != null) {
                        val explicitEvidence = lastStepEvidence(p)
                        val goalEvidence = if (explicitEvidence == null &&
                            !isGameTask && !isDownloadTask && kw == null
                        ) {
                            cleanEvidence(p.goal).takeIf { it.length >= 2 }
                        } else null
                        val evidence = explicitEvidence ?: goalEvidence
                        if (evidence != null) {
                            evidenceGateActive = true
                            val met = try {
                                client.verifyGoalEvidence(verifyImage.base64, evidence)
                            } catch (e: Exception) {
                                android.util.Log.w("GameMaster", "[verify] finish 当轮视觉复核调用失败：${e.message}")
                                null
                            }
                            if (met == false) {
                                rejectReason = "独立视觉复核没有在当前屏幕上看到最终目标证据「$evidence」，" +
                                    "任务还没有真正完成。请重新观察屏幕并继续操作" +
                                    "（游戏就继续找相同数字滑动合成，不要停在原方向空滑），" +
                                    "直到屏幕上真正出现该证据后才能 finish。"
                            }
                        }
                    }
                }
                if (rejectReason == null && !blockedSensitive && !handover && !evidenceGateActive && !openAppDone &&
                    kw != null && !service.screenContainsText(kw)
                ) {
                    rejectReason = "当前屏幕上完全没有出现「$kw」，说明这不是任务目标页面" +
                        "（可能进错了 App、停在错误的页面，或搜索结果是错的）。请核对当前应用和页面内容，" +
                        "直到屏幕上真正出现「$kw」相关内容后再 finish。"
                }

                if (rejectReason != null) {
                    wrongFinishCount++
                    history.addLast("系统：第 $wrongFinishCount 次「完成」判定被驳回——$rejectReason")
                    service.postStatus("第 $step 步：模型想提前结束，已驳回（$wrongFinishCount）：${rejectReason.take(50)}")
                    android.util.Log.i("GameMaster", "[verify] finish 被驳回（第 $wrongFinishCount 次，视觉闸门=$evidenceGateActive）")
                    if (evidenceGateActive) {
                        // 可视觉测量的目标：证据没出现就永远不放行；连续 6 次谎报说明模型彻底失判，报错人工接管
                        if (wrongFinishCount >= 6) {
                            service.setState(
                                com.gamemaster.agent.service.AgentState.ERROR,
                                "模型在目标证据出现前连续 $wrongFinishCount 次宣布完成，已自动停止，请手动接管。"
                            )
                            break
                        }
                        delay(800)
                        continue
                    }
                    if (wrongFinishCount < 3) {
                        delay(800)
                        continue
                    }
                    android.util.Log.i("GameMaster", "[agent] finish 复核连续 3 次未通过且无可用视觉证据，放行结束（原生界面兜底）")
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

            // 记录动作前帧签名，供下一轮空滑检测比对
            if (sigNow != null) sigBeforeAction = sigNow
            lastActionWasSwipe = action.type == AgentAction.Type.SWIPE
            swipeDir(action)?.let { lastModelSwipeDir = it }

            // 6. 执行（无截图轮次用真机屏幕分辨率作为归一化换算基准）
            val actionOk = if (image != null) {
                execute(action, image.scaleToReal, image.scaledWidth, image.scaledHeight, elements)
            } else {
                val dm = service.realScreenSize()
                execute(action, 1f, dm.first, dm.second, elements)
            }

            // 连续执行失败止损：与其在一个动作上无限乱点/乱输，不如报错停下让人介入；
            // 但游戏类和下载类任务绝不停机——模型对棋盘误发输入、或对详情页误发搜索时，
            // 系统代操作保持任务推进
            if (actionOk) {
                consecutiveActionFail = 0
            } else {
                consecutiveActionFail++
                if (consecutiveActionFail >= 4) {
                    if (isGameTask) {
                        history.addLast(
                            "系统：你连续 $consecutiveActionFail 次动作在游戏界面上无法执行（${action.summary()}）。" +
                                "2048 棋盘是纯自绘界面：没有任何输入框、没有可点的控件编号，唯一有效的操作是 swipe 滑动" +
                                "（只允许向左/向下为主），禁止 input_text、禁止引用 index、禁止 back/home/打开其他应用，" +
                                "也不要点右上角重开按钮（只有真的 Game Over 才由系统处理）。系统现在先替你滑一次。"
                        )
                        val dirs = listOf("左", "下", "左", "下", "上")
                        val dir = dirs[(consecutiveActionFail - 4) % dirs.size]
                        val coords = when (dir) {
                            "上" -> floatArrayOf(500f, 720f, 500f, 260f)
                            "下" -> floatArrayOf(500f, 300f, 500f, 760f)
                            "左" -> floatArrayOf(720f, 600f, 200f, 600f)
                            else -> floatArrayOf(200f, 600f, 720f, 600f)
                        }
                        val keepGoing = AgentAction(
                            "连续执行失败，系统按角落策略代滑向$dir",
                            AgentAction.Type.SWIPE,
                            coords[0], coords[1], coords[2], coords[3], 220L
                        )
                        val dm = service.realScreenSize()
                        execute(keepGoing, 1f, dm.first, dm.second, elements)
                        android.util.Log.i(
                            "GameMaster",
                            "[action-fail] 游戏任务连续 $consecutiveActionFail 次执行失败，系统代滑向$dir 继续（不停机）"
                        )
                        service.postStatus("AI 动作在棋盘上无效，系统自动向$dir 滑动保持游戏继续…")
                        // 给模型 4 轮纠偏窗口，仍持续失败则清历史重来，但永远不主动停机
                        if (consecutiveActionFail >= 16) {
                            history.clear()
                            parseFailStreak = 0
                            consecutiveActionFail = 0
                        }
                        delay(900)
                        continue
                    }
                    if (isDownloadTask) {
                        // 下载任务连续失败：多半是模型在详情页误发搜索/输入。系统接管，
                        // 清失败计数，下一轮由 maybeSystemDownload 决定返回结果页还是点下载
                        android.util.Log.i(
                            "GameMaster",
                            "[action-fail] 下载任务连续 $consecutiveActionFail 次执行失败，系统接管纠错（不停机）"
                        )
                        history.addLast(
                            "系统：你连续 $consecutiveActionFail 次操作无法执行（${action.summary()}）。" +
                                "下载任务里，应用详情页没有搜索框——不要 search、不要输入文字，" +
                                "只需要点击「下载」按钮然后等待安装完成。系统已接管，正在纠正。"
                        )
                        service.postStatus("AI 操作无效，系统接管下载流程…")
                        consecutiveActionFail = 0
                        delay(1000)
                        continue
                    }
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
            scaleToReal = w.toFloat() / scaledWidth,
            sig = frameSignature(bitmap)
        )
    }

    /** 均值哈希：把画面压成 16×28 灰度位串，用于两轮截图的变化比例比对 */
    private fun frameSignature(bm: Bitmap): String {
        val sw = 16; val sh = 28
        val small = Bitmap.createScaledBitmap(bm, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (small !== bm) small.recycle()
        var sum = 0L
        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val g = (30 * ((c shr 16) and 0xff) + 59 * ((c shr 8) and 0xff) + 11 * (c and 0xff)) / 100
            gray[i] = g; sum += g
        }
        val avg = sum / gray.size
        val sb = StringBuilder(gray.size)
        for (g in gray) sb.append(if (g >= avg) '1' else '0')
        return sb.toString()
    }

    private fun sigDiffRatio(a: String, b: String): Double {
        if (a.isEmpty() || a.length != b.length) return 1.0
        var d = 0
        for (i in a.indices) if (a[i] != b[i]) d++
        return d.toDouble() / a.length
    }

    /**
     * 原生界面的"页面指纹"：取前 60 个有文字/描述的控件做规范化哈希，生成与帧签名等长（448 位）
     * 的 0/1 串，让无截图的标准界面轮也能识别"反复回到同一个页面"。控件太少（<3）时返回 null。
     */
    private fun viewTreeSignature(elements: List<com.gamemaster.agent.service.UiElement>): String? {
        val labels = elements.asSequence()
            .map { it.text.trim().ifBlank { it.desc.trim() } }
            .filter { it.isNotBlank() }
            .take(60).toList()
        if (labels.size < 3) return null
        val canonical = labels.joinToString("|")
        val h = java.security.MessageDigest.getInstance("SHA-1")
            .digest(canonical.toByteArray(Charsets.UTF_8)) // 160 bit
        val sb = StringBuilder(448)
        for (i in 0 until 448) {
            val byte = h[(i / 8) % h.size]
            sb.append(if (((byte.toInt()) ushr (7 - (i % 8))) and 1 == 1) '1' else '0')
        }
        return sb.toString()
    }

    private fun swipeDir(a: AgentAction): String? {
        if (a.type != AgentAction.Type.SWIPE) return null
        val dx = a.x2 - a.x; val dy = a.y2 - a.y
        return if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
            if (dx >= 0) "右" else "左"
        } else {
            if (dy >= 0) "下" else "上"
        }
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

        /** 本次点击无法执行的具体原因（返回 null 时填充），用于给模型精确的纠错反馈 */
        var tapFailReason: String? = null

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
                    tapFailReason = "你给的编号 index=${action.elementIndex} 已超出最新控件清单范围（本轮只有 ${elements.size} 个控件）——" +
                        "页面刚刷新过，旧编号已失效，必须按本轮最新清单重新选一个编号，或改用截图上的 0~999 坐标"
                    return null
                }
                // 编号路径：按元素身份精确点击，不做坐标命中/吸附，避免点错旁边控件
                val target = service.clickElement(el)
                if (target != null) {
                    return if (target.nodeClicked) PAIR_NODE_CLICKED
                    else target.x.toFloat() to target.y.toFloat()
                }
                android.util.Log.i("GameMaster", "[gesture] index=${el.index} 无法精确命中，放弃本次点击")
                val name = el.text.ifBlank { el.desc }.ifBlank { "无文字控件" }
                tapFailReason = "编号 ${el.index}「$name」在当前界面读取失败（页面可能正在切换）。" +
                    "请确认当前页面后改用它在截图上的 0~999 坐标点击，或选清单中另一个带 ✓ 的可点击元素"
                return null
            }
            if (action.x < 0f || action.y < 0f) {
                android.util.Log.w("GameMaster", "[gesture] 动作既无有效 index 也无坐标，放弃本次点击")
                tapFailReason = "本次点击既没有控件编号 index 也没有坐标，无法执行。" +
                    "请用控件清单里的编号，或给出截图上的 0~999 坐标"
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
                    p == null -> false
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
                // 弱模型无视"必须换垂直方向"的文字纠偏、下一条仍是水平滑动时，
                // 系统直接把这次手势改写为向下滑动，用真实的棋盘变化打破横跳死循环
                val rawHorizontal = kotlin.math.abs(action.x2 - action.x) >=
                    kotlin.math.abs(action.y2 - action.y)
                val overridden = forceVerticalNext && rawHorizontal
                if (forceVerticalNext) forceVerticalNext = false
                val sx0 = if (overridden) 500f else action.x
                val sy0 = if (overridden) 250f else action.y
                val ex0 = if (overridden) 500f else action.x2
                val ey0 = if (overridden) 800f else action.y2
                if (overridden) {
                    history.addLast("系统：你没有按要求换方向，系统已替你执行了一次向下滑动，棋盘已经变化。请重新观察棋盘，优先寻找同一列可以纵向合并的相同数字继续操作。")
                    android.util.Log.i("GameMaster", "[anti-repeat] 模型仍水平滑动，系统强制执行向下滑动打破横跳")
                }
                var sx = sx0.coerceIn(lo, hi)
                var sy = sy0.coerceIn(lo, hi)
                var ex = ex0.coerceIn(lo, hi)
                var ey = ey0.coerceIn(lo, hi)
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
                            "接下来两步也优先做纵向合并，把大数字集中到同一列的角落。" +
                            "如果你下一条指令仍然是水平滑动，系统将直接忽略并替你执行一次向下滑动。"
                    } else {
                        "系统：你已连续 ${recentSwipeDirs.size} 次只向$dir 滑动，但棋盘没有产生合并——这个方向当前无效，属于机械重复。" +
                            "请仔细看清棋盘：找出同一行或同一列上相邻/只隔空格的相同数字，立即换一个能让它们相撞合并的方向（上/下/左/右中的另一个方向），" +
                            "并在后续几步持续围绕大数字所在的角落布局；不要再重复向$dir 滑动。"
                    }
                    history.addLast(hint)
                    android.util.Log.i("GameMaster", "[anti-repeat] 检测到连续 ${recentSwipeDirs.size} 次$dir 滑，已注入换方向纠偏（强制垂直=$stuckHorizontal）")
                    lastRepeatDir = dir
                    if (stuckHorizontal) forceVerticalNext = true
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
                // 跑偏拦截：有明确目标 App 时，AI 想打开的若是另一个已安装 App，直接驳回并改开目标 App
                var wanted = action.appName
                val tp = targetPackage
                val ta = plan?.targetApp
                if (tp != null && ta != null) {
                    val wantedPkg = service.resolveAppPackage(wanted)
                    if (wantedPkg != null && wantedPkg != tp) {
                        history.addLast(
                            "系统：计划要求全程使用「$ta」，你却要打开别的应用「$wanted」——已忽略该操作并直接为你打开「$ta」。" +
                                "不要离开目标 App；如果你在桌面，直接 open_app「$ta」即可，不要点桌面图标。"
                        )
                        android.util.Log.i("GameMaster", "[drift-guard] 拦截错误 open_app '$wanted'($wantedPkg)，改开 '$ta'($tp)")
                        wanted = ta
                    }
                }
                val label = service.launchApp(wanted)
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
                // 重复搜索拦截：已经成功提交过目标关键词、且当前不在搜索编辑页时，
                // 模型再发相同关键词的 search 属于无效重搜（弱模型常把详情页误判为搜错）。
                // 直接驳回，不计入执行失败，要求模型继续当前计划步。
                val alreadySearched = (systemSearchSucceeded || lastSubmittedKeyword == action.text.trim())
                val sameKw = !expectedSearchKeyword.isNullOrBlank() &&
                    action.text.trim() == expectedSearchKeyword
                if (alreadySearched && sameKw && !service.canSubmitSearch()) {
                    history.addLast(
                        "系统：关键词「${action.text}」之前已经成功搜索过了，当前也不在搜索编辑页，" +
                            "不需要再次搜索。请继续当前计划步骤——在搜索结果中找到目标并点击，" +
                            "不要反复输出 search 重搜同一个词。"
                    )
                    android.util.Log.i("GameMaster", "[search] 驳回重复搜索：「${action.text}」已搜过且不在搜索页")
                    service.postStatus("关键词已搜索过，无需重搜，请继续下一步操作")
                    true
                } else {
                    val ok = service.submitSearch(action.text)
                    if (ok) {
                        // 记录本任务已真实提交的搜索词，之后才允许做"顶部关键词不一致"纠偏
                        lastSubmittedKeyword = action.text.trim()
                        // 模型自己把正确关键词搜成了，托管逻辑同样不必再介入
                        if (action.text.trim() == expectedSearchKeyword) systemSearchSucceeded = true
                        if (isDownloadTask && downloadPhase == 0) downloadPhase = 1
                        // 里程碑确认：让模型下一轮明确知道"现在应该是 X 的结果页"，并据此核对页面
                        history.addLast(
                            "系统：已提交搜索「${action.text}」，现在应是「${action.text}」的搜索结果页。" +
                                "注意：只有搜索结果页顶部才会显示搜索关键词；如果你点进了某个应用的详情页，" +
                                "顶部自然不会再有关键词，这是正常的，不代表搜错了，不要因此重新搜索。" +
                                "请直接在结果列表里找到目标应用并点击进入详情页；${if (isDownloadTask) "进入详情页后点击下载按钮。" else ""}"
                        )
                    } else {
                        history.addLast("系统：搜索提交失败——请先点击页面上的搜索框/放大镜使其进入可输入状态，再重新执行 search；不要在没有搜索框的页面直接搜索")
                    }
                    ok
                }
            }

            else -> true
        }
        // 点击类动作失败：把"为什么点不动"的具体原因回灌给模型，
        // 不能笼统说"无目标点击"——模型明明给了编号时，错误反馈会让它无从修正
        if (!ok && tapFailReason != null &&
            (action.type == AgentAction.Type.TAP ||
                action.type == AgentAction.Type.DOUBLE_TAP ||
                action.type == AgentAction.Type.LONG_PRESS)
        ) {
            history.addLast("系统：上一步点击没有生效——$tapFailReason")
        }
        if (!ok) android.util.Log.w("GameMaster", "[gesture] 动作执行失败：${action.summary()}")
        return ok
    }

    /** 哨兵：节点已通过 ACTION_CLICK 消费，无需再发手势 */
    private val PAIR_NODE_CLICKED: Pair<Float, Float> = Float.NaN to Float.NaN
}
