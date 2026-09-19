package com.gamemaster.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.gamemaster.agent.MainActivity
import com.gamemaster.agent.agent.AgentConfig
import com.gamemaster.agent.agent.GameAgent
import com.gamemaster.agent.prefs.Prefs
import com.gamemaster.agent.backend.BackendSelector
import com.gamemaster.agent.backend.DeviceBackend
import com.gamemaster.agent.screenshot.ScreenshotManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * 无障碍服务：
 *  - 截屏（Android 11+ 系统能力，无需 MediaProjection 授权）
 *  - 模拟点击 / 双击 / 长按 / 滑动
 *  - 返回键 / 主页键 / 输入框填字
 *  - 托管 AI 决策循环（GameAgent）
 */
class GameAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: GameAccessibilityService? = null

        fun get(): GameAccessibilityService? = instance
        fun isRunning(): Boolean = instance != null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var agentJob: Job? = null

    @Volatile
    private var lastStatus: String = ""

    /** 最近一个前台窗口的包名（由无障碍事件更新，供 AI 判断自己在哪个应用） */
    @Volatile
    private var lastPackage: String = ""

    /** 悬浮窗注册后接收状态文本（已在主线程回调） */
    @Volatile
    var statusListener: ((String) -> Unit)? = null

    /** 悬浮窗注册后接收"运行状态 + 状态文本"（已在主线程回调） */
    @Volatile
    var stateListener: ((AgentState, String) -> Unit)? = null

    /** Agent 当前运行状态 */
    @Volatile
    var agentState: AgentState = AgentState.IDLE
        private set

    /** 更新运行状态并同步通知悬浮窗 */
    fun setState(state: AgentState, text: String) {
        agentState = state
        postStatus(text)
        Handler(Looper.getMainLooper()).post { stateListener?.invoke(state, text) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 注册无障碍后端到 BackendSelector（成为兜底后端）
        com.gamemaster.agent.backend.BackendSelector.setAccessibilityService(this)
        // 注册内置 Python 工具到 ToolRegistry（Chaquopy 已由 Application 启动）
        try {
            com.gamemaster.agent.tools.BuiltInTools.registerAll(this)
        } catch (e: Exception) {
            android.util.Log.w("GameMaster", "[tools] BuiltInTools 注册失败：${e.message}")
        }
        postStatus("无障碍服务已连接")
        // 进程曾被系统强杀时自动恢复任务（shouldRun 标记在 startAgent 时置位）
        if (Prefs.agentShouldRun(this) && !isAgentRunning()) {
            postStatus("检测到任务未正常结束，自动恢复运行…")
            startForegroundService(Intent(this, OverlayService::class.java))
            startAgent(Prefs.agentConfig(this))
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // 系统临时解绑/重建服务不算"用户手动停止"：保留 shouldRun 标志，重新绑定后自动续跑
        stopAgent(manual = false)
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopAgent(manual = false)
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 记录前台应用包名，供 AI 判断当前界面（本服务主要靠截屏理解画面）
        val self = packageName
        event?.packageName?.let { pkg ->
            val name = pkg.toString()
            if (name.isNotBlank() && name != "android" && name != "com.android.systemui" && name != self) {
                lastPackage = name
            }
        }
    }

    /** 当前前台应用包名：优先取活动窗口根节点，其次取最近事件记录 */
    fun currentPackageName(): String {
        // 优先用最上层的 TYPE_APPLICATION 窗口判断前台应用；rootInActiveWindow 在应用切后台
        // 或有悬浮窗时经常不准，导致下载托管反复误判"不在目标 App"
        // 排除自身包名：GameMaster 的透明/悬浮窗口经常盖在目标应用上面并抢走焦点
        val self = packageName
        try {
            val top = windows
                .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .filter { it.root?.packageName?.toString() != self }
                .maxByOrNull { it.layer }
            top?.root?.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Exception) { }
        rootInActiveWindow?.packageName?.toString()?.takeIf { it.isNotBlank() && it != self }?.let { return it }
        return lastPackage
    }

    /** 当前前台应用的中文显示名（如 小红书），供 AI 核对"是否进对了 App" */
    fun currentAppLabel(): String {
        val pkg = currentPackageName()
        if (pkg.isBlank() || pkg == packageName) return ""
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) { "" }
    }

    /**
     * 页面复核：当前界面所有控件的文字/描述中是否出现了关键词。
     * 用于 finish 前的硬校验——模型说"任务完成"时，系统亲自在 View Tree 上确认
     * 目标内容（如搜索关键词）真的出现在屏幕上，防止在错误页面误判完成。
     */
    suspend fun screenContainsText(keyword: String): Boolean = withContext(Dispatchers.Main) {
        val root = activeAppRoot() ?: return@withContext false
        val kw = keyword.trim()
        if (kw.isBlank()) return@withContext false
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        while (q.isNotEmpty() && n < 600) {
            val node = q.removeFirst()
            n++
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            if (t.contains(kw, ignoreCase = true) || d.contains(kw, ignoreCase = true)) {
                return@withContext true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        false
    }

    /**
     * 系统级搜索结果证据：关键词出现在顶部搜索栏以下（结果列表区），
     * 且同屏有至少一个"结果型"信号（下载/安装/联系人/商品等），
     * 用于"结果出来就停"类任务绕开弱模型的错误判断直接验收。
     */
    suspend fun searchResultEvidence(keyword: String): Boolean = withContext(Dispatchers.Main) {
        val root = activeAppRoot() ?: return@withContext false
        val kw = keyword.trim()
        if (kw.isBlank()) return@withContext false
        val markers = listOf(
            "下载", "安装", "次下载", "秒玩", "联系人", "聊天", "公众号", "小程序",
            "功能", "播放", "评论", "评分", "商品", "店铺", "网页", "关注", "万粉"
        )
        var kwInBody = false
        var marker = false
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        while (q.isNotEmpty() && n < 600) {
            val node = q.removeFirst(); n++
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            val all = "$t $d"
            val r = Rect(); node.getBoundsInScreen(r)
            if (r.top > 220 && (t.contains(kw) || d.contains(kw))) kwInBody = true
            if (markers.any { all.contains(it) }) marker = true
            if (kwInBody && marker) return@withContext true
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        false
    }

    /** 真机屏幕像素尺寸（无截图轮次做归一化换算用） */
    fun realScreenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    override fun onInterrupt() {
        stopAgent(manual = false)
    }

    // ---------------- 状态 ----------------

    fun postStatus(text: String) {
        lastStatus = text
        Log.i("GameMaster", "[status] $text")
        Handler(Looper.getMainLooper()).post { statusListener?.invoke(text) }
    }

    fun currentStatus(): String = lastStatus

    fun isAgentRunning(): Boolean = agentJob?.isActive == true

    // ---------------- 任务循环控制 ----------------

    fun startAgent(config: AgentConfig) {
        if (agentJob?.isActive == true) return
        if (config.apiKey.isBlank()) {
            postStatus("请先在 App 内填写 API Key")
            return
        }
        if (config.task.isBlank()) {
            postStatus("请先在 App 内填写任务目标")
            return
        }
        Prefs.setAgentShouldRun(this, true)
        setState(AgentState.RUNNING, "任务开始执行…")
        agentJob = scope.launch {
            try {
                GameAgent(config, this@GameAccessibilityService).run()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 正常停止
            } catch (e: Exception) {
                setState(AgentState.ERROR, "运行异常：${e.message}")
                Prefs.setAgentShouldRun(this@GameAccessibilityService, false)
            }
        }
    }

    /**
     * @param manual true=用户/界面主动停止（清除自动恢复标志）；
     *               false=系统解绑/销毁等非用户原因（保留标志，服务重连后自动续跑）
     */
    fun stopAgent(manual: Boolean = true) {
        val wasRunning = agentJob?.isActive == true
        if (manual) Prefs.setAgentShouldRun(this, false)
        agentJob?.cancel()
        agentJob = null
        if (manual && wasRunning) setState(AgentState.STOPPED, "已手动停止。")
    }

    /** 主循环结束时回调：清除自动恢复标记 */
    fun onAgentLoopEnded() {
        Prefs.setAgentShouldRun(this, false)
    }

    // ---------------- 截屏 ----------------

    /** 截取当前屏幕，返回软件位图；失败返回 null */
    suspend fun captureScreenshot(): Bitmap? {
        // Android 11+：无障碍服务自带截屏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return captureByAccessibility()
        }
        // Android 10：优先 MediaProjection，已 root 设备兜底用 su screencap
        return withContext(Dispatchers.IO) {
            var bmp = ScreenshotManager.capture()
            if (bmp == null) {
                delay(300)
                bmp = ScreenshotManager.capture()
            }
            if (bmp == null) {
                // 已 root / Shizuku 设备兜底用 shell 截屏
                bmp = BackendSelector.bestForShell()?.screenshot()
            }
            bmp
        }
    }

    private suspend fun captureByAccessibility(): Bitmap? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        var software: Bitmap? = null
                        try {
                            val hardware = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer,
                                result.colorSpace
                            )
                            if (hardware != null) {
                                software = hardware.copy(Bitmap.Config.ARGB_8888, false)
                                hardware.recycle()
                            }
                        } catch (_: Exception) {
                            software = null
                        } finally {
                            result.hardwareBuffer.close()
                        }
                        if (cont.isActive) cont.resume(software)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w("GameMaster", "[screenshot] 无障碍截屏失败 errorCode=$errorCode（1=内部错误/锁屏，2=无权限，3=不安全窗口）")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        }
    }

    /** 设备当前是否停在锁屏/Keyguard（截屏不会失败，但画面是锁屏，任务无法推进） */
    fun isLocked(): Boolean = try {
        val km = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        km.isKeyguardLocked || km.inKeyguardRestrictedInputMode()
    } catch (e: Exception) {
        false
    }

    /**
     * 截屏失败多半是屏幕熄灭或锁屏遮挡。唤醒屏幕并借 MainActivity 的
     * showWhenLocked/turnScreenOn 滑掉无密码锁屏，然后自动回桌面，任务不中断。
     */
    fun wakeAndUnlock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GameMaster:wake"
            )
            wl.acquire(5000)
        } catch (e: Exception) {
            Log.w("GameMaster", "[screenshot] 唤醒屏幕失败：${e.message}")
        }
        val i = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            .putExtra(MainActivity.EXTRA_WAKE_UNLOCK, true)
        try {
            startActivity(i)
            Log.i("GameMaster", "[screenshot] 已请求唤醒并解锁")
        } catch (e: Exception) {
            Log.w("GameMaster", "[screenshot] 拉起解锁页失败：${e.message}")
        }
    }

    // ---------------- 手势 ----------------

    private suspend fun dispatch(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gesture: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gesture: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) cont.resume(false)
        }

    private fun strokeAt(x: Float, y: Float, durationMs: Long, startMs: Long = 0L): GestureDescription.StrokeDescription {
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        return GestureDescription.StrokeDescription(path, startMs, durationMs.coerceAtLeast(1L))
    }

    /**
     * 手势注入：已 root / Shizuku 设备优先用 shell `input ...`（部分定制 ROM 上
     * AccessibilityService.dispatchGesture 对游戏无效），失败再回退无障碍手势。
     */
    private suspend fun viaBestBackend(
        blockShell: (DeviceBackend) -> Boolean,
        blockGesture: suspend () -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val backend = BackendSelector.bestForShell()
        if (backend != null) {
            val ok = blockShell(backend)
            if (ok) return@withContext true
            Log.w("GameMaster", "shell backend 未成功，回退无障碍手势")
        }
        blockGesture()
    }

    suspend fun tap(x: Float, y: Float): Boolean = viaBestBackend(
        blockShell = { it.tap(x.roundToInt(), y.roundToInt()) },
        blockGesture = {
            // 时长太短（<16ms）的手势在部分 App（如抖音）上会被忽略，用 40ms 更接近真人
            dispatch(GestureDescription.Builder().addStroke(strokeAt(x, y, 40L)).build())
        }
    )

    /** 双击 */
    suspend fun doubleTap(x: Float, y: Float): Boolean = viaBestBackend(
        blockShell = { it.doubleTap(x.roundToInt(), y.roundToInt()) },
        blockGesture = {
            val builder = GestureDescription.Builder()
                .addStroke(strokeAt(x, y, 8L, 0L))
                .addStroke(strokeAt(x + 1f, y, 8L, 110L))
            dispatch(builder.build())
        }
    )

    suspend fun longPress(x: Float, y: Float, durationMs: Long): Boolean = viaBestBackend(
        blockShell = { it.longPress(x.roundToInt(), y.roundToInt(), durationMs.toInt()) },
        blockGesture = {
            dispatch(GestureDescription.Builder().addStroke(strokeAt(x, y, durationMs)).build())
        }
    )

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.coerceAtLeast(1f), y1.coerceAtLeast(1f))
            lineTo(x2.coerceAtLeast(1f), y2.coerceAtLeast(1f))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(100L))
        return viaBestBackend(
            blockShell = {
                it.swipe(
                    x1.roundToInt(), y1.roundToInt(),
                    x2.roundToInt(), y2.roundToInt(),
                    durationMs.toInt()
                )
            },
            blockGesture = { dispatch(GestureDescription.Builder().addStroke(stroke).build()) }
        )
    }

    // ---------------- 按键与输入 ----------------

    fun globalBack() {
        // 无障碍全局返回优先：系统级 API，不留 input shell 痕迹（应用宝等反自动化
        // 会检测 `input keyevent` 命令并直接退后台，导致"越救越卡"）；shell keyevent 只作兜底
        if (performGlobalAction(GLOBAL_ACTION_BACK)) return
        BackendSelector.bestForShell()?.keyEvent(4)
    }

    fun globalHome() {
        if (performGlobalAction(GLOBAL_ACTION_HOME)) return
        BackendSelector.bestForShell()?.keyEvent(3)
    }

    /** 在当前聚焦的输入框中设置文字（只填字，不自动发送） */
    suspend fun inputText(text: String): Boolean = withContext(Dispatchers.Main) {
        if (text.isBlank()) return@withContext false
        val root = activeAppRoot() ?: return@withContext false
        val field = findVisibleEditable(root, topAreaOnly = false)
        if (field != null) {
            return@withContext setFieldText(field, text)
        }
        // 没有 EditText：可能是自绘输入框（如应用宝搜索框）。
        // 用剪贴板粘贴：复制到剪贴板 → 对当前焦点节点执行 ACTION_PASTE。
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("gm_input", text))
            val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root
            // ACTION_PASTE 常量值 16
            val pasted = focus.performAction(16) // AccessibilityNodeInfo.ACTION_PASTE
            if (pasted) {
                Log.i("GameMaster", "[input] 剪贴板粘贴成功: ${text.take(20)}")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.w("GameMaster", "[input] 剪贴板粘贴失败: ${e.message}")
        }
        Log.w("GameMaster", "[input] 当前界面没有可输入的文本框（需要先点击输入框聚焦）")
        false
    }

    /**
     * 非破坏性探测：当前界面是否具备"提交一次搜索"的入口。
     * 顶部有真实输入框，或顶部有可点的关键词框/"搜索"入口（点完能进入搜索编辑页），即视为可以。
     * 供 Agent 在搜索步做"系统托管搜索"前判断，避免在完全没有搜索入口的页面乱点。
     */
    suspend fun canSubmitSearch(): Boolean = withContext(Dispatchers.Main) {
        val root = activeAppRoot() ?: return@withContext false
        if (findVisibleEditable(root, topAreaOnly = true) != null) return@withContext true
        findKeywordBoxNode(root) != null || findTopSearchEntryNode(root) != null
    }

    suspend fun submitSearch(keyword: String): Boolean = withContext(Dispatchers.Main) {
        if (keyword.isBlank()) return@withContext false
        var root0 = activeAppRoot() ?: return@withContext false
        // 只要顶部标题栏区域的可见输入框；结果页底部"问点点/AI"之类的输入框绝不能当作搜索框
        var field = findVisibleEditable(root0, topAreaOnly = true)
        // 当前页没有顶部输入框（如应用宝首页，搜索框是自绘 TextView）：
        // 自动点顶部搜索入口进入搜索编辑页，再找一次
        if (field == null) {
            // 自绘搜索框：用 dispatchGesture 点击搜索框中心，确保进入编辑模式
            var entryNode = findTopSearchEntryNode(root0)
            // 应用宝刚启动时搜索框可能未加载，重试几次
            var retry = 0
            while (entryNode == null && retry < 3) {
                delay(1000)
                val retryRoot = activeAppRoot() ?: break
                entryNode = findTopSearchEntryNode(retryRoot)
                if (entryNode != null) root0 = retryRoot
                retry++
            }
            Log.i("GameMaster", "[search] findTopSearchEntryNode=${entryNode?.className} desc=${entryNode?.contentDescription}")
            if (entryNode != null) {
                val er = Rect()
                entryNode.getBoundsInScreen(er)
                val cx = er.left + er.width() / 2
                val cy = er.top + er.height() / 2
                Log.i("GameMaster", "[search] 搜索框 bounds=[$er] 中心=($cx,$cy)")
                // 用 dispatchGesture 直接点击（绕过 shell input tap，避免对自绘控件无效）
                val stroke = GestureDescription.StrokeDescription(
                    android.graphics.Path().apply { moveTo(cx.toFloat(), cy.toFloat()) },
                    0L, 80L
                )
                dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
                delay(1500)
                Log.i("GameMaster", "[search] 点击后 delay 完成，查找 EditText")
                val fresh = activeAppRoot()
                Log.i("GameMaster", "[search] fresh root=${fresh?.packageName}")
                if (fresh != null) {
                    root0 = fresh
                    field = findVisibleEditable(fresh, topAreaOnly = true)
                    Log.i("GameMaster", "[search] findVisibleEditable=$field")
                }
            } else {
                if (tapSearchWayIn(root0)) {
                    Log.i("GameMaster", "[search] 当前页无输入框，已进入搜索入口")
                    delay(1200)
                    val fresh = activeAppRoot()
                    if (fresh != null) {
                        root0 = fresh
                        field = findVisibleEditable(fresh, topAreaOnly = true)
                    }
                }
            }
        }
        if (field != null) {
            // 标准 EditText：直接 setFieldText + 提交
            if (!setFieldText(field, keyword)) return@withContext false
            submitSearchByEnter(field)
            delay(2000)
            Log.i("GameMaster", "[search] 提交后当前包名=${currentPackageName()}")
            // 不自动点击下载按钮（搜索结果页有广告，容易点错），让模型来点击
        } else {
            // 自绘搜索框（应用宝等）：找到搜索框节点本身，用 ACTION_SET_TEXT 设置文字
            Log.i("GameMaster", "[search] 无 EditText，尝试 ACTION_SET_TEXT 设置关键词")
            // 打印所有含"搜索"的节点，便于调试
            val r = Rect()
            var searchField: AccessibilityNodeInfo? = null
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root0)
            var n = 0
            while (q.isNotEmpty() && n < 500) {
                val node = q.removeFirst()
                n++
                val d = node.contentDescription?.toString()?.trim().orEmpty()
                val t = node.text?.toString()?.trim().orEmpty()
                if ((d.startsWith("搜索") || t.startsWith("搜索")) && d.contains("编辑框")) {
                    node.getBoundsInScreen(r)
                    if (r.top < 300) { searchField = node; break }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
            }
            // 兜底：找任何含"搜索"的可编辑节点
            if (searchField == null) {
                val q2 = ArrayDeque<AccessibilityNodeInfo>()
                q2.add(root0)
                var m = 0
                while (q2.isNotEmpty() && m < 500) {
                    val node = q2.removeFirst()
                    m++
                    val d = node.contentDescription?.toString()?.trim().orEmpty()
                    val t = node.text?.toString()?.trim().orEmpty()
                    if ((d.startsWith("搜索") || t.startsWith("搜索"))) {
                        node.getBoundsInScreen(r)
                        if (r.top < 300) { searchField = node; break }
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { q2.add(it) }
                }
            }
            val setOk = if (searchField != null) {
                val args = android.os.Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, keyword)
                searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } else false
            Log.i("GameMaster", "[search] ACTION_SET_TEXT setOk=$setOk fieldClass=${searchField?.className} fieldDesc=${searchField?.contentDescription}")
            if (!setOk) {
                // 兜底：剪贴板粘贴（Android 10+ 可能因非焦点应用失败）
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("gm_search", keyword))
                val focus = activeAppRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                focus?.performAction(16) // ACTION_PASTE
            }
            delay(600)
            // 提交：找"搜索"按钮点击，或发回车
            submitSearchByEnter(null)
        }
        true
    }

    private fun submitSearchByEnter(field: AccessibilityNodeInfo?): Boolean {
        var submitted = false
        if (field != null) {
            try {
                val imeEnter = AccessibilityNodeInfo::class.java
                    .getField("ACTION_IME_ENTER").getInt(null)
                submitted = field.performAction(imeEnter)
            } catch (_: Throwable) { }
            Log.i("GameMaster", "[search] ACTION_IME_ENTER submitted=$submitted")
        }
        // 不用 input keyevent（会导致应用宝退后台），改找搜索按钮点击
        if (!submitted) {
            val fresh = activeAppRoot()
            if (fresh != null) {
                val q = ArrayDeque<AccessibilityNodeInfo>()
                q.add(fresh)
                var n = 0
                outer@ while (q.isNotEmpty() && n < 400) {
                    val node = q.removeFirst()
                    n++
                    val t = node.text?.toString().orEmpty().trim()
                    val d = node.contentDescription?.toString().orEmpty().trim()
                    if (t == "搜索" || t.equals("Search", true) || d == "搜索" || d.startsWith("搜索")) {
                        Log.i("GameMaster", "[search] 找到搜索按钮 t=$t d=$d clickable=${node.isClickable}")
                        var cur: AccessibilityNodeInfo? = node
                        var hops = 0
                        while (cur != null && hops < 4) {
                            if (cur.isClickable &&
                                cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            ) {
                                submitted = true
                                break@outer
                            }
                            cur = cur.parent
                            hops++
                        }
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
                }
            }
            Log.i("GameMaster", "[search] 搜索按钮点击 submitted=$submitted")
        }
        return submitted
    }

    /**
     * 获取当前前台"应用"窗口的根节点。输入法弹出时 rootInActiveWindow 可能是键盘窗口，
     * 这里会退回到所有窗口中查找 TYPE_APPLICATION 的根。
     */
    private fun activeAppRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { root ->
            val pkg = root.packageName?.toString().orEmpty()
            // 输入法窗口的包名通常含 inputmethod/.ime/.keyboard
            val isIme = pkg.contains("inputmethod", true) ||
                pkg.contains(".ime", true) ||
                pkg.contains("keyboard", true)
            if (!isIme) return root
        }
        return try {
            windows.firstOrNull {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
            }?.root
        } catch (_: Exception) { null }
    }

    /**
     * 只找不点：顶部标题栏里"显示着当前关键词的可点击长框"（结果页点它回搜索编辑页）。
     * 美团/大众点评/小红书首页的自绘搜索框也走这个：框内有占位/历史词文本，
     * 父容器可点击且足够宽。取最宽的候选。
     */
    private fun findKeywordBoxNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        data class Cand(val node: AccessibilityNodeInfo, val score: Int, val top: Int)
        val boxCands = mutableListOf<Cand>()
        val blocked = setOf("搜索", "Search", "返回", "取消", "问点点", "问ai")
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        fun encloseBar(node: AccessibilityNodeInfo, hintScore: Int) {
            var cur: AccessibilityNodeInfo? = node
            var hops = 0
            while (cur != null && hops < 4) {
                val r = Rect()
                cur.getBoundsInScreen(r)
                // 顶部标题栏区域（y<520）的可点击长条：高 24~200（横屏下假框可能只有 30+px 高），
                // 宽>=180；排除整屏可点击根容器（高>=200 的那种）
                if (cur.isClickable && r.top in 0..520 && r.width() >= 180 && r.height() in 24..200) {
                    // 越靠上越像搜索框；desc 直接带"搜索"前缀的（如应用宝"搜索  红果免费短剧"）强加分
                    boxCands.add(Cand(cur, hintScore * 1000 - r.top, r.top))
                    break
                }
                cur = cur.parent
                hops++
            }
        }
        val rr = Rect()
        root.getBoundsInScreen(rr)
        val screenW = rr.width().coerceAtLeast(320)
        while (q.isNotEmpty() && n < 500) {
            val node = q.removeFirst()
            n++
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            // 信号 1：描述就是"搜索  xxx"（应用宝首页轮播假词框）
            if (d.startsWith("搜索") && d.length > 2 && d.length <= 40) {
                encloseBar(node, 2)
            }
            // 信号 2：顶部有 2~40 字提示词的可点击长条（美团/大众点评式假框）
            if (t.isNotBlank() && t !in blocked && d !in blocked && t.length in 2..40) {
                encloseBar(node, 1)
            }
            // 信号 3：纯几何特征——轮播提示词偶尔为空，此时靠形状也能认出：
            // 顶部标题栏里横向长条（宽>=180 且不贴屏幕两边），薄（24~120px），不可滚动
            if (node.isClickable && !node.isScrollable) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.top in 8..150 && r.height() in 24..120 &&
                    r.width() in 180..(screenW - 40) && r.left >= 40 && r.right <= screenW - 40
                ) {
                    boxCands.add(Cand(node, -5000 - r.top, r.top))
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        return boxCands.maxByOrNull { it.score }?.node
    }

    private fun tapSearchWayIn(root: AccessibilityNodeInfo): Boolean {
        findKeywordBoxNode(root)?.let {
            val clicked = it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i("GameMaster", "[search] 点击顶部关键词框回编辑页 ok=$clicked")
            if (clicked) return true
        }
        return tapTopSearchEntry(root)
    }

    /**
     * 只找不点：顶部文字/描述恰好为"搜索"的入口（小红书首页的 TextView、放大镜按钮）。
     * 避免误点"拍照搜索/AI搜索"。多个候选取屏幕最靠上的（标题栏入口）。
     */
    private fun findTopSearchEntryNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        data class Cand(val node: AccessibilityNodeInfo, val top: Int)
        val cands = mutableListOf<Cand>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        while (q.isNotEmpty() && n < 500) {
            val node = q.removeFirst()
            n++
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            // 匹配"搜索"按钮或搜索框（content-desc 如"搜索  QQ音乐"、text 如"搜索"）
            val isSearchEntry = t == "搜索" || t.equals("Search", true) || d == "搜索" ||
                d.startsWith("搜索") || t.startsWith("搜索")
            if (isSearchEntry) {
                var cur: AccessibilityNodeInfo? = node
                var hops = 0
                while (cur != null && hops < 4) {
                    val r = Rect()
                    cur.getBoundsInScreen(r)
                    if (cur.isClickable && r.width() > 5 && r.height() > 5) {
                        cands.add(Cand(cur, r.top))
                        break
                    }
                    cur = cur.parent
                    hops++
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        return cands.minByOrNull { it.top }?.node
    }

    private fun tapTopSearchEntry(root: AccessibilityNodeInfo): Boolean {
        val target = findTopSearchEntryNode(root) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK).also {
            val r = Rect(); target.getBoundsInScreen(r)
            Log.i("GameMaster", "[search] 点击顶部搜索入口 top=${r.top} ok=$it")
        }
    }

    /** 按 content-desc 精确匹配点击节点（用于点"下载管理/待安装"等按钮） */
    suspend fun tapNodeByDesc(desc: String): Boolean = withContext(Dispatchers.Main) {
        val root = activeAppRoot() ?: return@withContext false
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        while (q.isNotEmpty() && n < 500) {
            val node = q.removeFirst()
            n++
            if (node.contentDescription?.toString()?.trim() == desc) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withContext true
                var cur: AccessibilityNodeInfo? = node
                var hops = 0
                while (cur != null && hops < 5) {
                    if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withContext true
                    cur = cur.parent
                    hops++
                }
                return@withContext false
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        false
    }

    /** 按 text 精确匹配点击节点（用于点"首页"等 tab） */
    suspend fun tapNodeByText(text: String): Boolean = withContext(Dispatchers.Main) {
        val root = activeAppRoot() ?: return@withContext false
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        while (q.isNotEmpty() && n < 500) {
            val node = q.removeFirst()
            n++
            if (node.text?.toString()?.trim() == text) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withContext true
                var cur: AccessibilityNodeInfo? = node
                var hops = 0
                while (cur != null && hops < 5) {
                    if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withContext true
                    cur = cur.parent
                    hops++
                }
                return@withContext false
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        false
    }

    /** 检查应用是否已安装 */
    fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 直接用 pm install 安装应用宝已下载的 APK（绕过应用宝 UI 的反自动化机制）。
     * 在应用宝下载目录查找文件名包含目标包名的 APK，用 su 执行安装。
     */
    suspend fun installDownloadedApk(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val dir = "/sdcard/Android/data/com.tencent.android.qqdownloader/files/tassistant/apk"
        try {
            // 用 su 列出目录（普通应用无权限读 Android/data）
            // 此设备 su 语法为 su 0 command（不支持 -c）
            val lsProc = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", "ls $dir/*.apk 2>/dev/null"))
            val files = lsProc.inputStream.bufferedReader().readLines()
            lsProc.waitFor()
            val apk = files.firstOrNull { it.contains(packageName) }
            if (apk == null) {
                Log.i("GameMaster", "[download] 未找到 $packageName 的 APK，files=${files.size} list=${files.take(3)}")
                return@withContext false
            }
            Log.i("GameMaster", "[download] 找到 APK: $apk")
            // 用 su 执行 pm install -r
            val proc = Runtime.getRuntime().exec(arrayOf("su", "0", "pm", "install", "-r", apk))
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            Log.i("GameMaster", "[download] pm install exit=$exit output=$output")
            if (exit == 0 && output.contains("Success")) {
                delay(2000)
                return@withContext true
            }
            return@withContext false
        } catch (e: Exception) {
            Log.e("GameMaster", "[download] installDownloadedApk 异常: ${e.message}")
            false
        }
    }

    /**
     * 自动放行 HyperOS/MIUI 的"是否允许游戏助手打开 XX"后台启动确认弹窗。
     * 仅在系统/LBE 窗口内、且弹窗文案确实与"打开/启动应用"有关时，
     * 精确点击"允许/始终允许/允许本次使用"等白名单按钮，绝不碰"取消/拒绝"。
     */
    /**
     * 应用宝首启/冷启动时常弹出"热门应用一键下载（本页全选）"全屏遮罩，挡住首页搜索框，
     * 模型会把列表项误当搜索结果乱点。识别后点右上角 X，找不到 X 就按返回。
     */
    fun dismissBulkDownloadSheet(): Boolean {
        val root = try { activeAppRoot() } catch (_: Exception) { null } ?: return false
        val texts = mutableListOf<String>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        while (q.isNotEmpty() && n < 400) {
            val node = q.removeFirst(); n++
            node.text?.let { texts.add(it.toString()) }
            node.contentDescription?.let { texts.add(it.toString()) }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        val joined = texts.joinToString(" ")
        if (!joined.contains("一键下载") || !joined.contains("本页全选")) return false

        // 优先点右上角关闭小按钮
        val rr = Rect(); root.getBoundsInScreen(rr)
        q.clear(); q.add(root); n = 0
        while (q.isNotEmpty() && n < 400) {
            val node = q.removeFirst(); n++
            if (node.isClickable) {
                val r = Rect(); node.getBoundsInScreen(r)
                if (r.top in 120..380 && r.width() in 40..120 && r.height() in 40..120 &&
                    r.left >= rr.width() - 200
                ) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i("GameMaster", "[popup] 已关闭一键下载遮罩（右上角 X）")
                        return true
                    }
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        val back = performGlobalAction(GLOBAL_ACTION_BACK)
        Log.i("GameMaster", "[popup] 一键下载遮罩未找到 X，按返回 ok=$back")
        return back
    }

    /**
     * 登录墙识别：目标 App 要求登录（密码输入框/验证码登录等），继续点哪里都没用。
     * 命中条件：存在密码输入框；或同时出现"登录/验证码/密码"等两个以上强信号文案。
     */
    fun isLoginWall(): Boolean {
        val root = try { activeAppRoot() } catch (_: Exception) { null } ?: return false
        var hasPasswordField = false
        var signal = 0
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        while (q.isNotEmpty() && n < 400) {
            val node = q.removeFirst(); n++
            if (node.isPassword) hasPasswordField = true
            val t = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
            when {
                t.contains("短信验证码登录") || t.contains("验证码登录") ||
                    t.contains("密码登录") || t.contains("请输入密码") ||
                    t.contains("找回密码") || t.contains("登录密码") -> signal++
                t.trim() == "登录" || t.contains("请先登录") || t.contains("未登录") -> signal++
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        return hasPasswordField || signal >= 2
    }

    fun dismissLaunchConfirmDialog(): Boolean {
        val sysPkgs = setOf(
            "android", "com.lbe.security.miui",
            "com.miui.securitycenter", "com.miui.permcenter"
        )
        val allowWords = listOf(
            "允许本次使用", "始终允许", "允许本次", "允许", "继续", "打开"
        )
        val roots = try {
            windows.mapNotNull { it.root }
        } catch (_: Exception) { emptyList() }
        for (root in roots) {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg !in sysPkgs) continue

            // 收集整窗文案，确认这确实是"启动应用确认"弹窗
            val texts = mutableListOf<String>()
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root)
            var n = 0
            while (q.isNotEmpty() && n < 400) {
                val node = q.removeFirst()
                n++
                node.text?.let { texts.add(it.toString()) }
                node.contentDescription?.let { texts.add(it.toString()) }
                for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
            }
            val joined = texts.joinToString(" ")
            val isLaunchDialog = joined.contains("打开") || joined.contains("启动") ||
                (joined.contains("允许") && joined.contains("应用"))
            if (!isLaunchDialog) continue

            // 找白名单按钮（文字可能在 Button 的子 TextView 上，沿父链找可点击节点）
            for (word in allowWords) {
                var btn: AccessibilityNodeInfo? = null
                q.clear(); q.add(root); n = 0
                outer@ while (q.isNotEmpty() && n < 400) {
                    val node = q.removeFirst()
                    n++
                    val t = node.text?.toString()?.trim().orEmpty()
                    if (t == word) {
                        var cur: AccessibilityNodeInfo? = node
                        var hops = 0
                        while (cur != null && hops < 4) {
                            if (cur.isClickable) { btn = cur; break@outer }
                            cur = cur.parent
                            hops++
                        }
                        btn = node
                        break@outer
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
                }
                if (btn != null) {
                    val clicked = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i("GameMaster", "[launch-confirm] 系统启动确认弹窗，点击「$word」ok=$clicked 文案=$joined")
                    if (clicked) return true
                }
            }
        }
        return false
    }

    /**
     * 找一个"真正可用"的可编辑节点：
     *  - 优先当前已聚焦的输入框；
     *  - 否则遍历节点树；
     *  - 必须是真实可见、有合理尺寸的节点（避开 0 尺寸/隐藏/离屏的伪输入框）；
     *  - topAreaOnly=true 时只接受屏幕顶部标题栏区域的输入框（搜索场景，
     *    避开结果页底部"问点点/AI 对话"等输入框）。
     */
    private fun findVisibleEditable(root: AccessibilityNodeInfo, topAreaOnly: Boolean): AccessibilityNodeInfo? {
        fun usable(node: AccessibilityNodeInfo): Boolean {
            if (!node.isEditable) return false
            val visible = runCatching { node.isVisibleToUser }.getOrDefault(true)
            if (!visible) return false
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() < 120 || r.height() < 28) return false
            if (r.top < 0 || r.bottom > 3300) return false
            if (topAreaOnly && r.top > 700) return false
            return true
        }

        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
            if (usable(it)) {
                it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                return it
            }
        }
        var target: AccessibilityNodeInfo? = null
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        while (q.isNotEmpty() && n < 400) {
            val node = q.removeFirst()
            n++
            if (usable(node)) { target = node; break }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        target?.let {
            it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return target
    }

    /**
     * 判断输入框读回的文本是否可视为已写入目标文本。
     * 小红书等应用的搜索框会把前缀（如"搜索, "）拼进无障碍文本，
     * 读回为"搜索, 宫保鸡丁"，严格相等会把成功误判为失败，故放宽为：
     * 完全相等，或"以目标文本结尾且仅多一个短前缀"。
     */
    private fun textAccepted(readback: String?, wanted: String): Boolean {
        if (readback == null) return false
        if (readback == wanted) return true
        return readback.endsWith(wanted) && readback.length <= wanted.length + 16
    }

    /**
     * 向输入框写文字并校验结果：SET_TEXT 后读回节点文本核对，
     * 不一致（部分自绘输入框会返回 true 却不生效）则剪贴板粘贴兜底。
     */
    private fun setFieldText(field: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        field.refresh()
        val setOk = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i("GameMaster", "[input] ACTION_SET_TEXT setOk=$setOk")
        if (setOk) {
            // 等待文字刷新
            field.refresh()
            return true
        }
        // 兜底：剪贴板粘贴（Android 10+ 可能因非焦点应用失败）
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("input", text))
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        })
        val pasted = field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i("GameMaster", "[input] ACTION_PASTE pasted=$pasted")
        return pasted
    }

    /**
     * 检查当前应用页面所有无障碍节点的文本/描述中是否包含指定文字。
     * 用于下载任务：进入详情页后核对标题是否就是目标应用，没出现说明点错了条目。
     */
    suspend fun pageContainsText(text: String): Boolean = withContext(Dispatchers.Main) {
        if (text.isBlank()) return@withContext false
        val root = activeAppRoot() ?: return@withContext false
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        while (q.isNotEmpty() && n < 600) {
            val node = q.removeFirst()
            n++
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            if (t.contains(text) || d.contains(text)) return@withContext true
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        false
    }

    /**
     * 统计页面中文字精确匹配 text 的节点数量（用于区分详情页/列表页：
     * 应用宝详情页只有 1 个"下载"按钮，首页/结果页有多个）。
     */
    suspend fun countTextExact(text: String): Int = withContext(Dispatchers.Main) {
        if (text.isBlank()) return@withContext 0
        val root = activeAppRoot() ?: return@withContext 0
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        var count = 0
        while (q.isNotEmpty() && n < 600) {
            val node = q.removeFirst()
            n++
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (t == text || d == text) count++
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        count
    }

    /**
     * 只检查屏幕顶部区域（应用标题通常在 top<520px）是否包含指定文字。
     * 比 pageContainsText 更严格——避免详情页底部"相关推荐"里出现目标应用名时误判为已进对详情页。
     */
    suspend fun pageTopContainsText(text: String): Boolean = withContext(Dispatchers.Main) {
        if (text.isBlank()) return@withContext false
        val root = activeAppRoot() ?: return@withContext false
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        val r = Rect()
        while (q.isNotEmpty() && n < 600) {
            val node = q.removeFirst()
            n++
            node.getBoundsInScreen(r)
            if (r.top in 0..560) {
                val t = node.text?.toString().orEmpty()
                val d = node.contentDescription?.toString().orEmpty()
                if (t.contains(text) || d.contains(text)) return@withContext true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        false
    }

    /**
     * 在搜索结果列表区域（minTop 以下，避开顶部搜索框）找到文字精确匹配的应用条目并点击。
     * 用于下载任务：搜索出目标应用后，系统直接点进正确的详情页，避免弱模型点错广告位。
     * @return true=找到并点击
     */
    suspend fun tapResultItemByName(name: String, minTop: Int = 180): Boolean = withContext(Dispatchers.Main) {
        if (name.isBlank()) return@withContext false
        val root = activeAppRoot() ?: return@withContext false
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        val r = Rect()
        while (q.isNotEmpty() && n < 600) {
            val node = q.removeFirst()
            n++
            val t = node.text?.toString().orEmpty().trim()
            val d = node.contentDescription?.toString().orEmpty().trim()
            // 精确匹配应用名，避免点到"小红书千帆"等关联应用
            if (t == name || d == name) {
                node.getBoundsInScreen(r)
                if (r.top >= minTop && r.width() > 5 && r.height() > 5) {
                    // 沿父链找可点击节点
                    var clicked = false
                    var cur: AccessibilityNodeInfo? = node
                    var hops = 0
                    while (cur != null && hops < 6) {
                        if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            clicked = true; break
                        }
                        cur = cur.parent
                        hops++
                    }
                    if (!clicked) clicked = tap(r.exactCenterX(), r.exactCenterY())
                    Log.i("GameMaster", "[download] 点击搜索结果条目「$name」 center=(${r.centerX()},${r.centerY()}) ok=$clicked")
                    return@withContext clicked
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        false
    }

    /**
     * 死循环兜底：在无障碍节点树中按文字查找弹窗按钮（如"恢复运行/继续/确定"），
     * 找到后直接点击其屏幕中心。视觉模型对这类按钮定位不稳时使用。
     */
    suspend fun findAndTapByText(keywords: List<String>): Boolean {
        for (kw in keywords) {
            val root = activeAppRoot() ?: continue
            // 系统 ANR / 崩溃对话框（包名 android）绝不代点，避免误杀应用
            val rootPkg = root.packageName?.toString() ?: ""
            if (rootPkg == "android") {
                Log.i("GameMaster", "[unstuck] 当前是系统对话框($rootPkg)，跳过节点代点")
                return false
            }
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 300) {
                val node = queue.removeFirst()
                visited++
                val label = buildString {
                    append(node.text ?: "")
                    append(' ')
                    append(node.contentDescription ?: "")
                }
                if (label.contains(kw, ignoreCase = true)) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (r.width() > 5 && r.height() > 5) {
                        // 优先无障碍 ACTION_CLICK（直接触发 View 的 onClick，对冻结前的应用最可靠），
                        // 沿父链找可点击节点；都没有再退回坐标点击
                        var clicked = false
                        var cur: AccessibilityNodeInfo? = node
                        var hops = 0
                        while (cur != null && hops < 5) {
                            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                clicked = true
                                break
                            }
                            cur = cur.parent
                            hops++
                        }
                        val ok = clicked || tap(r.exactCenterX(), r.exactCenterY())
                        Log.i(
                            "GameMaster",
                            "[unstuck] 命中文字节点「$kw」 center=(${r.exactCenterX().toInt()}, ${r.exactCenterY().toInt()}) click=$clicked tap=${!clicked} ok=$ok"
                        )
                        return ok
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        }
        return false
    }

    /**
     * 系统 ANR / 应用错误恢复框（本定制 ROM 文案为"比较累,休息一下~ / 恢复运行"，
     * 标准节点 id=android:id/aerr_close）。它属于 system_server 的包名 "android"，
     * 与应用自身弹窗严格区分。找到按钮节点时返回，否则返回 null。
     */
    suspend fun findSystemErrorButton(): AccessibilityNodeInfo? = withContext(Dispatchers.Main) {
        val roots = ArrayList<AccessibilityNodeInfo?>()
        // 实测本 ROM 的 ANR 框是 active window，必须包含 activeAppRoot；
        // 某些机型上 active root 又是被冻住的应用，故再补 rootInActiveWindow 与全部窗口
        activeAppRoot()?.let { roots.add(it) }
        rootInActiveWindow?.let { roots.add(it) }
        try {
            for (w in windows) w.root?.let { roots.add(it) }
        } catch (e: Exception) {
            // 部分 ROM 取 windows 需要额外权限，忽略即可
        }
        for (root in roots) {
            root ?: continue
            val rootPkg = root.packageName?.toString().orEmpty()
            // 该框由 system_server 渲染，包名为 android；个别 ROM 上报空包名，靠文本兜底
            if (rootPkg.isNotEmpty() && rootPkg != "android") continue
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            var hasAnrText = false
            var textButton: AccessibilityNodeInfo? = null
            while (queue.isNotEmpty() && visited < 300) {
                val node = queue.removeFirst()
                visited++
                val id = node.viewIdResourceName ?: ""
                val t = node.text?.toString().orEmpty()
                if (t.contains("无响应") || t.contains("休息一下") || t.contains("ANR")) {
                    hasAnrText = true
                }
                // aerr_close/aerr_restart 是系统 ANR/崩溃框专用 id，见到即可直接点
                if (id == "android:id/aerr_close" || id == "android:id/aerr_restart") {
                    return@withContext node
                }
                // 部分 ROM 不向无障碍暴露 viewId（实测本机如此），用按钮文本兜底
                if ((t == "恢复运行" || t == "关闭应用" || t == "等待" || t == "确定") &&
                    (node.isClickable || node.parent?.isClickable == true)
                ) {
                    textButton = node
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            // 文本按钮必须与"休息一下/无响应"标题同框，避免误点应用自己的同名按钮
            if (hasAnrText && textButton != null) return@withContext textButton
        }
        null
    }

    /** 点掉系统 ANR 框（aerr_close 会重启卡死的应用进程）。true=已触发恢复。 */
    suspend fun dismissSystemErrorDialog(): Boolean {
        val node = findSystemErrorButton() ?: return false
        return withContext(Dispatchers.Main) {
            val r = Rect()
            node.refresh()
            node.getBoundsInScreen(r)
            var clicked = false
            if (node.isClickable) {
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (!clicked && r.width() > 5 && r.height() > 5) {
                clicked = tap(r.exactCenterX(), r.exactCenterY())
            }
            Log.i(
                "GameMaster",
                "[anr] 检测到系统 ANR/错误框，已点「恢复运行」节点 click=$clicked center=(${r.exactCenterX().toInt()}, ${r.exactCenterY().toInt()})"
            )
            clicked
        }
    }

    /**
     * 收集当前界面的可交互控件树（View Tree）：
     * 每个元素带文字/描述/ID/真实屏幕像素矩形，编号按从上到下、从左到右排列。
     * 视觉模型优先用编号精确点击，避免坐标点偏；游戏等自绘界面通常为空，回退截图识别。
     */
    suspend fun collectUiElements(): List<UiElement> = withContext(Dispatchers.Main) {
        // 键盘弹出时活动根可能是输入法窗口，改用应用窗口根，保证模型看到的仍是 App 界面
        val root = activeAppRoot() ?: return@withContext emptyList()
        val out = LinkedHashMap<String, UiElement>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var seq = 0
        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited++
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val clickable = node.isClickable || node.isLongClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable
            val interesting = clickable || editable || scrollable || text.isNotEmpty() || desc.isNotEmpty()

            val r = Rect()
            node.getBoundsInScreen(r)
            val onScreen = r.width() > 2 && r.height() > 2 && r.right > 0 && r.bottom > 0
            val ownPkg = node.packageName?.toString() == packageName
            if (interesting && onScreen && !ownPkg) {
                val key = when {
                    clickable -> "c|${r.left}|${r.top}|${r.right}|${r.bottom}"
                    editable -> "e|${r.left}|${r.top}|${r.right}|${r.bottom}"
                    else -> "t|$text|$desc|${r.left}|${r.top}"
                }
                val existing = out[key]
                if (existing == null) {
                    out[key] = UiElement(
                        index = -1,
                        text = text.take(24),
                        desc = desc.take(24),
                        viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty().take(24),
                        className = node.className?.toString()?.substringAfterLast('.').orEmpty(),
                        left = r.left, top = r.top, right = r.right, bottom = r.bottom,
                        clickable = clickable,
                        scrollable = scrollable,
                        editable = editable
                    )
                } else if (text.isNotEmpty() && existing.text.isEmpty()) {
                    out[key] = existing.copy(text = text.take(24))
                }
                seq++
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        // 排序 + 选择：
        //  ① 可交互元素（输入框/可点击/可滑动）是模型真正会点的目标，按从上到下、从左到右优先全部入选；
        //  ② 再用纯文本元素补满——文本是模型判断"当前页面对不对"的关键证据；
        //  ③ 同文字/描述去重（桌面图标容器和文字标签会各产生一个节点）
        val byPosition = compareBy<UiElement>({ it.top / 80 }, { it.left })
        val sorted = out.values.sortedWith(
            compareBy<UiElement>(
                { when { it.editable -> 0; it.clickable -> 1; it.scrollable -> 2; else -> 3 } },
                { it.top / 80 }, { it.left }
            )
        )
        val seenLabel = HashSet<String>()
        fun UiElement.isNewLabel(): Boolean {
            val label = text.ifBlank { desc }.trim()
            return label.isBlank() || seenLabel.add(label)
        }
        val interactive = sorted.filter { (it.clickable || it.editable || it.scrollable) && it.isNewLabel() }.take(45)
        val texts = sorted.filter {
            it !in interactive &&
                !(it.clickable || it.editable || it.scrollable) && it.isNewLabel()
        }
        val picked = (interactive + texts).sortedWith(byPosition).take(60)

        val (screenW, screenH) = realScreenSize()
        picked.mapIndexed { i, e -> e.copy(index = i + 1) }
            .onEach { it.normX = if (screenW > 0) it.centerX * 999 / screenW else -1; it.normY = if (screenH > 0) it.centerY * 999 / screenH else -1 }
    }

    /** 智能点击的解析结果：返回应点击的真实像素点；clickable 节点的 ACTION_CLICK 已尝试 */
    data class TapTarget(val x: Int, val y: Int, val nodeClicked: Boolean, val label: String)

    /**
     * 按控件清单中元素的"身份"精确点击（编号路径专用，杜绝坐标吸附点错邻接控件）：
     * 在当前最新 View Tree 上重新找到与快照 bounds/文字一致的同一个节点，
     * 只沿该节点自己的父链找可点击容器；找不到可点击容器则退回其中心手势坐标。
     */
    suspend fun clickElement(el: UiElement): TapTarget? = withContext(Dispatchers.Main) {
        val root = activeAppRoot() ?: return@withContext null

        fun labelOf(n: AccessibilityNodeInfo): String {
            val t = n.text?.toString().orEmpty()
            val d = n.contentDescription?.toString().orEmpty()
            return (t + " " + d).trim().take(20)
        }
        fun sameLabel(n: AccessibilityNodeInfo): Boolean {
            val t = n.text?.toString().orEmpty().trim()
            val d = n.contentDescription?.toString().orEmpty().trim()
            val want = el.text.ifBlank { el.desc }.trim()
            return want.isNotBlank() && (t == want || d == want)
        }

        var exact: AccessibilityNodeInfo? = null       // bounds 完全相同
        var labeled: AccessibilityNodeInfo? = null     // 同标签且中心最近
        var labeledDist = Int.MAX_VALUE
        // 列表轻微重排/轮播占位词变化（如"甲乙饼·现熬粥"→"甲乙饼粥店"）时的降级匹配：
        var byId: AccessibilityNodeInfo? = null        // 同 viewId 且位置接近
        var byIdDist = Int.MAX_VALUE
        var fuzzy: AccessibilityNodeInfo? = null       // 文字互相包含且位置接近
        var fuzzyDist = Int.MAX_VALUE
        val wantLabel = el.text.ifBlank { el.desc }.trim()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        while (q.isNotEmpty() && n < 600) {
            val node = q.removeFirst()
            n++
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.left == el.left && r.top == el.top && r.right == el.right && r.bottom == el.bottom) {
                exact = node
            }
            val dist = kotlin.math.abs(r.centerX() - el.centerX) +
                kotlin.math.abs(r.centerY() - el.centerY)
            if (sameLabel(node)) {
                if (dist < labeledDist) { labeledDist = dist; labeled = node }
            }
            // 降级①：同 viewId（收集时截断到 24 字符），中心距离 300px 以内
            if (el.viewId.isNotBlank()) {
                val nid = node.viewIdResourceName?.substringAfterLast('/').orEmpty().take(24)
                if (nid == el.viewId && dist in 1..300 && dist < byIdDist) {
                    byIdDist = dist; byId = node
                }
            }
            // 降级②：文字互相包含（轮播词/状态词轻微变化），中心距离 300px 以内
            if (fuzzy == null && wantLabel.length >= 2) {
                val t = node.text?.toString().orEmpty().trim()
                val d = node.contentDescription?.toString().orEmpty().trim()
                val near = t.length >= 2 && (t.contains(wantLabel) || wantLabel.contains(t)) ||
                    d.length >= 2 && (d.contains(wantLabel) || wantLabel.contains(d))
                if (near && dist in 1..300 && dist < fuzzyDist) {
                    fuzzyDist = dist; fuzzy = node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }

        val target = exact ?: labeled ?: byId ?: fuzzy
        if (target == null) {
            // 节点重定位全部失败（页面已跳转/列表项移动/动画中）：
            // 绝不退回"快照中心盲点击"——列表项滚动后旧坐标会点到完全无关的控件，
            // 宁可放弃本次动作并让上层提示模型重新观察屏幕
            Log.w("GameMaster", "[smarttap] index=${el.index}「${el.text.ifBlank { el.desc }}」重定位失败，放弃本次点击（不做盲点）")
            return@withContext null
        }

        // 只沿目标节点自己的父链（最多 4 跳）找可点击容器；
        // 跳过接近整屏的根布局（吞点击），优先最小的可点击祖先
        val (sw, sh) = realScreenSize()
        var cur: AccessibilityNodeInfo? = target
        var hops = 0
        var clickableHit: AccessibilityNodeInfo? = null
        while (cur != null && hops < 4) {
            if (cur!!.isClickable) {
                val r = Rect(); cur.getBoundsInScreen(r)
                val oversized = r.width() > sw * 0.92 && r.height() > sh * 0.85
                if (!oversized) { clickableHit = cur; break }
            }
            cur = cur.parent
            hops++
        }
        clickableHit?.let { hit ->
            val clicked = try {
                hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (_: Exception) { false }
            val r = Rect(); hit.getBoundsInScreen(r)
            Log.i("GameMaster", "[smarttap] index=${el.index} 精确命中「${labelOf(hit)}」click=$clicked")
            return@withContext TapTarget(r.centerX(), r.centerY(), clicked, labelOf(hit))
        }
        // 无可点击容器：返回元素中心，交给手势分发
        TapTarget(el.centerX, el.centerY, false, el.text.ifBlank { el.desc })
    }

    /**
     * 把一个真实像素坐标解析到最近的可点击控件：
     *  1) 命中测试：坐标落在某控件内 → 沿父链找可点击节点直接 ACTION_CLICK
     *  2) 就近吸附：找中心距离最近的可点击控件（阈值内），同样先 ACTION_CLICK
     * 都没有返回 null（调用方退回原始坐标手势点击）
     */
    suspend fun resolveTap(realX: Float, realY: Float): TapTarget? = withContext(Dispatchers.Main) {
        val root = activeAppRoot() ?: return@withContext null
        if (root.packageName?.toString() == "android") return@withContext null
        val xi = realX.toInt()
        val yi = realY.toInt()
        val (sw, sh) = realScreenSize()
        val screenArea = (sw * sh).toLong()

        fun Rect.isOversized(): Boolean =
            width() > sw * 0.92 && height() > sh * 0.85

        // 沿父链找可点击祖先；跳过接近整屏的"根布局"（点了也没反应，还会吞掉真实目标）
        fun tryClick(node: AccessibilityNodeInfo): Boolean {
            var cur: AccessibilityNodeInfo? = node
            var hops = 0
            while (cur != null && hops < 6) {
                val r = Rect(); cur!!.getBoundsInScreen(r)
                if (cur.isClickable && !r.isOversized()) {
                    if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
                cur = cur.parent
                hops++
            }
            return false
        }
        fun labelOf(n: AccessibilityNodeInfo): String {
            val t = n.text?.toString().orEmpty()
            val d = n.contentDescription?.toString().orEmpty()
            return (t + " " + d).trim().take(20)
        }

        // 一次遍历收集：所有包含点击点的节点 + 所有尺寸合理的可点击节点
        var smallestContaining: AccessibilityNodeInfo? = null
        var smallestArea = Long.MAX_VALUE
        val clickables = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        while (q.isNotEmpty() && n < 600) {
            val node = q.removeFirst()
            n++
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 2 && r.height() > 2) {
                val area = r.width().toLong() * r.height()
                if (xi in r.left..r.right && yi in r.top..r.bottom && area < smallestArea) {
                    smallestArea = area
                    smallestContaining = node
                }
                if (node.isClickable && area < screenArea * 0.4f &&
                    r.width() < sw * 0.95 && r.height() < sh * 0.9
                ) {
                    clickables.add(node to Rect(r))
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }

        // 1) 命中：包含该点的面积最小节点（最具体的目标），沿其自身父链点可点击容器
        smallestContaining?.let { hit ->
            val r = Rect(); hit.getBoundsInScreen(r)
            val clicked = tryClick(hit)
            if (clicked) {
                return@withContext TapTarget(r.centerX(), r.centerY(), true, labelOf(hit))
            }
        }

        // 2) 就近吸附：只在"控件 bounds 外扩一圈"的范围内才吸附，
        //    外扩量 = 控件短边的 35%（上限屏宽 8%），避免估偏一点就跳到隔壁控件
        val cap = sw * 0.08f
        var best: Pair<AccessibilityNodeInfo, Rect>? = null
        var bestDist = Float.MAX_VALUE
        for ((node, r) in clickables) {
            val pad = (minOf(r.width(), r.height()) * 0.35f).coerceAtMost(cap)
            val inside = xi >= r.left - pad && xi <= r.right + pad &&
                yi >= r.top - pad && yi <= r.bottom + pad
            if (!inside) continue
            val dist = kotlin.math.hypot(xi - r.exactCenterX(), yi - r.exactCenterY())
            if (dist < bestDist) { bestDist = dist; best = node to r }
        }
        best?.let { (node, r) ->
            val clicked = try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
            Log.i("GameMaster", "[smarttap] 坐标吸附 dist=${bestDist.toInt()} → ${labelOf(node)} (${r.centerX()},${r.centerY()}) click=$clicked")
            return@withContext TapTarget(r.centerX(), r.centerY(), clicked, labelOf(node))
        }
        null
    }

    /**
     * 应用卡死（ANR）兜底：重启当前前台应用。免 root：
     * 用 PackageManager 取启动 Intent，先杀后台进程再重新拉起。
     * 不会重启助手自己。
     */
    fun restartTopApp(): Boolean {
        val pkg = currentPackageName()
        if (pkg.isBlank() || pkg == packageName || pkg == "android") return false
        // 桌面启动器没有普通启动入口，重启无意义
        if (pkg.contains("launcher", ignoreCase = true) || pkg.contains("home", ignoreCase = true)) {
            Log.i("GameMaster", "[unstuck] 当前是桌面($pkg)，无需重启")
            return false
        }
        return try {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null) {
                Log.w("GameMaster", "[unstuck] 找不到 $pkg 的启动入口")
                return false
            }
            // 已 root / Shizuku 设备直接 force-stop 更彻底；都没有则退化为杀后台进程
            val backend = BackendSelector.bestForShell()
            if (backend != null) {
                backend.exec("am force-stop $pkg")
            } else {
                runCatching {
                    val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                    am.killBackgroundProcesses(pkg)
                }
            }
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            startActivity(launch)
            Log.i("GameMaster", "[unstuck] 已重新拉起应用 $pkg")
            true
        } catch (e: Exception) {
            Log.w("GameMaster", "[unstuck] 重启 $pkg 失败：${e.message}")
            false
        }
    }

    /** 判断某个包是否是桌面启动器（默认桌面包名或常见 launcher 命名） */
    fun isHomeApp(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg.contains("launcher", ignoreCase = true) ||
            pkg.contains("quickstep", ignoreCase = true) ||
            pkg.endsWith(".home", ignoreCase = true)
        ) return true
        return try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(home, 0)?.activityInfo?.packageName == pkg
        } catch (_: Exception) { false }
    }

    /**
     * 按应用名/包名解析已安装应用的包名（不启动）。
     * 匹配顺序：包名完全相同 → 应用名完全相同 → 应用名互相包含。
     */
    fun resolveAppPackage(target: String): String? {
        val t = target.trim()
        if (t.isBlank()) return null
        return try {
            val pm = packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val apps = pm.queryIntentActivities(main, 0)
            val match = apps.firstOrNull {
                    it.activityInfo.packageName.equals(t, ignoreCase = true)
                } ?: apps.firstOrNull {
                    it.loadLabel(pm).toString().trim().equals(t, ignoreCase = true)
                } ?: apps.firstOrNull {
                    val label = it.loadLabel(pm).toString().trim()
                    label.length >= 2 && (label.contains(t) || t.contains(label))
                }
            match?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
    }

    /** 直接按包名启动应用（下载任务已知商店包名时用，绕过应用名匹配） */
    suspend fun launchAppByPackage(pkg: String): Boolean = withContext(Dispatchers.IO) {
        if (pkg.isBlank()) return@withContext false
        try {
            // 先杀后台进程。getLaunchIntentForPackage 常返回 SplashActivity，
            // 在应用已在后台时启动后会立即退回桌面（如应用宝）。
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            try { am?.killBackgroundProcesses(pkg) } catch (_: Exception) { }
            // 再用 shell am force-stop 兜底（killBackgroundProcesses 杀不掉前台服务）
            try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "am force-stop $pkg")).waitFor() } catch (_: Exception) { }
            delay(600)

            // 优先用名字含 "Main" 的 Activity 启动（比 SplashActivity 更稳），
            // 找不到则回退到 getLaunchIntentForPackage。
            var launchCls: String? = null
            try {
                val info = packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES)
                launchCls = info.activities
                    ?.filter { it.exported }
                    ?.map { it.name }
                    ?.firstOrNull { it.contains("Main", ignoreCase = true) }
            } catch (_: Exception) { }
            if (launchCls == null) {
                launchCls = packageManager.getLaunchIntentForPackage(pkg)?.component?.className
            }
            if (launchCls != null) {
                val intent = Intent().apply {
                    setClassName(pkg, launchCls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                Log.i("GameMaster", "[open_app] startActivity → $pkg/$launchCls")
                true
            } else {
                Log.w("GameMaster", "[open_app] 未找到启动 Activity: $pkg")
                false
            }
        } catch (e: Exception) {
            Log.w("GameMaster", "[open_app] 启动 '$pkg' 失败：${e.message}")
            false
        }
    }

    /**
     * 按应用名/包名直接启动任意已安装应用（无需在桌面翻图标，最可靠的应用打开方式）。
     * 匹配顺序：包名完全相同 → 应用名完全相同 → 应用名互相包含。
     * @return 实际启动的应用显示名；找不到返回 null
     */
    fun launchApp(target: String): String? {
        val t = target.trim()
        if (t.isBlank()) return null
        return try {
            val pm = packageManager
            val pkg = resolveAppPackage(t) ?: return null
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return null
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            startActivity(launch)
            val label = pm.getApplicationLabel(
                pm.getApplicationInfo(pkg, 0)
            ).toString()
            Log.i("GameMaster", "[open_app] '$t' → $pkg ($label)")
            label
        } catch (e: Exception) {
            Log.w("GameMaster", "[open_app] 启动 '$t' 失败：${e.message}")
            null
        }
    }
}

/** Agent 运行状态（悬浮窗据此变色/显示） */
enum class AgentState {
    /** 未在运行（待机/配置就绪） */
    IDLE,

    /** 执行中 */
    RUNNING,

    /** 任务正常执行结束 */
    FINISHED,

    /** 执行出错（截屏连续失败、解析失败、运行异常等） */
    ERROR,

    /** 被用户手动停止 */
    STOPPED
}

/** 从无障碍 View Tree 提取的一个界面元素（坐标均为真机屏幕像素） */
data class UiElement(
    val index: Int,
    val text: String,
    val desc: String,
    val viewId: String,
    val className: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean
) {
    /** 中心点的 0~999 归一化坐标（与截图坐标系一致），方便模型把截图位置和编号一一对上 */
    var normX: Int = -1
    var normY: Int = -1

    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    /** 给模型看的一句话描述，如「可点击 文本"搜索" 中心(1275,259) 归一化(885,81)」 */
    fun describe(): String = buildString {
        val tags = buildList {
            if (editable) add("输入框")
            if (clickable) add("可点击")
            if (scrollable) add("可滑动")
        }.joinToString(" ")
        append(tags.ifBlank { "文本/图标" })
        if (text.isNotBlank()) append(" 文本\"$text\"")
        if (desc.isNotBlank()) append(" 描述\"$desc\"")
        if (text.isBlank() && desc.isBlank() && viewId.isNotBlank()) append(" id=$viewId")
        append(" 类型=$className")
        append(" 中心($centerX,$centerY)")
        if (normX >= 0 && normY >= 0) append(" 归一化($normX,$normY)")
    }
}
