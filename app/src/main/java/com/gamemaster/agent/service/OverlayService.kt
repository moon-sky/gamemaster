package com.gamemaster.agent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.gamemaster.agent.R
import com.gamemaster.agent.prefs.Prefs
import android.content.Context
import kotlin.math.abs

/**
 * 悬浮控制球 / 控制面板：
 *  - 小球可拖动，点击展开面板；颜色随运行状态变化
 *  - 面板显示状态（执行中/执行结束/执行出错/已停止）与 AI 最新想法
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var ballView: View? = null
    private var panelView: View? = null
    private var tvStatus: TextView? = null
    private var tvState: TextView? = null
    private var stateDot: View? = null
    private var btnToggle: Button? = null
    private var etCommand: EditText? = null

    /** 收起面板/启动任务前收起输入法 */
    private fun hideIme() {
        etCommand?.let {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private val statusListener: (String) -> Unit = { text ->
        tvStatus?.post { tvStatus?.text = text }
    }

    private val stateListener: (AgentState, String) -> Unit = { state, _ ->
        ballView?.post { applyState(state) }
        panelView?.post { applyState(state) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        GameAccessibilityService.get()?.let {
            it.statusListener = statusListener
            it.stateListener = stateListener
        }
        showBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        GameAccessibilityService.get()?.let {
            it.statusListener = null
            it.stateListener = null
        }
        ballView?.let { runCatching { wm.removeView(it) } }
        panelView?.let { runCatching { wm.removeView(it) } }
        ballView = null
        panelView = null
        super.onDestroy()
    }

    // ---------------- 悬浮球 ----------------

    private fun showBall() {
        if (ballView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_ball, null)
        // 悬浮球是本助手的工具，不是手机内容：整体从无障碍树隐藏，
        // 避免 AI 把控件当成可点击目标操作
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        // 窗口尺寸固定为正方形像素：部分 ROM 对 WRAP_CONTENT 的悬浮窗测量异常，
        // 会把正圆背景拉成椭圆
        val ballSize = dp(52)
        val params = WindowManager.LayoutParams(
            ballSize,
            ballSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // KEEP_SCREEN_ON：悬浮球可见期间保持屏幕常亮，避免运行中自动锁屏导致截屏失败
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(280)
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { wm.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        hideBall()
                        showPanel()
                    }
                    true
                }
                else -> false
            }
        }

        wm.addView(view, params)
        ballView = view
        applyState(GameAccessibilityService.get()?.agentState ?: AgentState.IDLE)
    }

    private fun hideBall() {
        ballView?.let { runCatching { wm.removeView(it) } }
        ballView = null
    }

    // ---------------- 控制面板 ----------------

    private fun showPanel() {
        if (panelView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        // 面板（含指令输入框）是本助手的工具：从无障碍树隐藏，杜绝 AI 给自己改任务
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        tvStatus = view.findViewById(R.id.tvOverlayStatus)
        tvState = view.findViewById(R.id.tvState)
        stateDot = view.findViewById(R.id.stateDot)
        btnToggle = view.findViewById(R.id.btnToggle)
        etCommand = view.findViewById(R.id.etCommand)
        // 预填当前任务，等同 App 主界面"告诉它要做什么"输入框
        etCommand?.setText(Prefs.getString(this, Prefs.KEY_TASK))

        view.findViewById<Button>(R.id.btnToggle).setOnClickListener {
            val acc = GameAccessibilityService.get()
            if (acc == null) {
                tvStatus?.text = "无障碍服务未连接，请先在 App 中开启无障碍服务"
                return@setOnClickListener
            }
            if (acc.isAgentRunning()) {
                // 运行中只允许停止；绝不保存输入框内容——
                // 防止 AI 误点悬浮面板时把它编造的文字写进任务（任务只能由人在停止状态下改写）
                acc.stopAgent()
            } else {
                // 面板里输入了新指令：先保存为任务目标（与 App 内输入框等价）
                val cmd = etCommand?.text?.toString()?.trim().orEmpty()
                if (cmd.isNotBlank()) {
                    Prefs.setString(this, Prefs.KEY_TASK, cmd)
                }
                val cfg = Prefs.agentConfig(this)
                when {
                    cfg.apiKey.isBlank() -> tvStatus?.text = "请先在 App 内填写 API Key"
                    cfg.task.isBlank() -> tvStatus?.text = "请先填写任务目标"
                    else -> {
                        acc.statusListener = statusListener
                        acc.stateListener = stateListener
                        acc.startAgent(cfg)
                        // 启动后自动收起面板，避免遮住游戏画面被 AI 当成弹窗乱点
                        hideIme()
                        hidePanel()
                        showBall()
                    }
                }
            }
            refreshToggle()
        }

        view.findViewById<Button>(R.id.btnCollapse).setOnClickListener {
            hideIme()
            hidePanel()
            showBall()
        }

        view.findViewById<Button>(R.id.btnExit).setOnClickListener {
            hideIme()
            GameAccessibilityService.get()?.stopAgent()
            stopSelf()
        }

        val params = WindowManager.LayoutParams(
            dp(292),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 面板需要可聚焦，指令输入框才能弹出输入法；小球窗口仍保持 NOT_FOCUSABLE
            0,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        wm.addView(view, params)
        panelView = view

        val acc = GameAccessibilityService.get()
        tvStatus?.text = acc?.currentStatus()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.overlay_waiting)
        applyState(acc?.agentState ?: AgentState.IDLE)
        refreshToggle()
    }

    private fun hidePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        tvStatus = null
        tvState = null
        stateDot = null
        btnToggle = null
        etCommand = null
    }

    /** 按状态更新面板状态行 + 悬浮球颜色 */
    private fun applyState(state: AgentState) {
        // 状态配色约定：白=未开始，黄=执行中，绿=执行结束，红=执行出错，灰=已停止
        val (label, color) = when (state) {
            AgentState.IDLE -> "未开始" to 0xFFFFFFFF.toInt()
            AgentState.RUNNING -> "执行中" to 0xFFFFB300.toInt()
            AgentState.FINISHED -> "执行结束" to 0xFF43A047.toInt()
            AgentState.ERROR -> "执行出错" to 0xFFE53935.toInt()
            AgentState.STOPPED -> "已停止" to 0xFF757575.toInt()
        }
        val tint = ColorStateList.valueOf(color)
        tvState?.text = label
        tvState?.setTextColor(color)
        stateDot?.backgroundTintList = tint
        // "未开始"为白色：换用带深色描边的白底并改深色字，保证浅色壁纸上也看得见
        if (state == AgentState.IDLE) {
            ballView?.setBackgroundResource(R.drawable.bg_ball_idle)
            (ballView as? android.widget.TextView)?.setTextColor(0xFF333333.toInt())
        } else {
            ballView?.setBackgroundResource(R.drawable.bg_ball)
            ballView?.backgroundTintList = tint
            (ballView as? android.widget.TextView)?.setTextColor(0xFFFFFFFF.toInt())
        }
        // 执行中：悬浮球缩小、半透明并贴到左上角状态栏区域，避免遮挡游戏/应用内容
        // （它会被截进截图，挡住棋盘格子会干扰 AI 判断；也可能吞掉落点附近的触摸）
        ballView?.let { v ->
            val lp = runCatching { v.layoutParams as? WindowManager.LayoutParams }.getOrNull()
            if (lp != null) {
                val running = state == AgentState.RUNNING
                val targetSize = if (running) dp(30) else dp(52)
                var changed = false
                if (lp.width != targetSize || lp.height != targetSize) {
                    lp.width = targetSize; lp.height = targetSize; changed = true
                }
                val tx = if (running) dp(6) else dp(16)
                val ty = if (running) dp(6) else dp(280)
                if (lp.x != tx || lp.y != ty) { lp.x = tx; lp.y = ty; changed = true }
                val ta = if (running) 0.55f else 1f
                if (v.alpha != ta) { v.alpha = ta; changed = true }
                if (changed) runCatching { wm.updateViewLayout(v, lp) }
            }
        }
        refreshToggle()
    }

    private fun refreshToggle() {
        val running = GameAccessibilityService.get()?.isAgentRunning() == true
        btnToggle?.text = if (running) getString(R.string.overlay_stop) else getString(R.string.overlay_start)
    }

    // ---------------- 通知 ----------------

    private fun startForegroundNotification() {
        val channelId = "gm_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.overlay_running))
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
