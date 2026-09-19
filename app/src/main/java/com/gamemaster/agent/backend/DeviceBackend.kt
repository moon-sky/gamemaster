package com.gamemaster.agent.backend

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.gamemaster.agent.service.GameAccessibilityService

/**
 * 设备执行后端抽象：把截屏、手势注入、shell 命令统一成同一组接口。
 * 不同后端（Root / Shizuku / 纯无障碍）按优先级自动选择，调用方无感。
 *
 * 设计原则：
 * - screenshot/tap/swipe 等基本能力所有后端都该实现；shell 能力（exec/execOut）
 *   只有 Root / Shizuku 后端提供，无障碍后端返回 false/null。
 * - 任意方法失败返回 false / null，由 [BackendSelector] 自动切换到下一个后端。
 */
interface DeviceBackend {
    /** 后端显示名（用于 UI 状态显示） */
    val name: String

    /** 优先级：10=Root、8=Shizuku、1=无障碍。Selector 取最高可用者 */
    val priority: Int

    /** 当前是否可用（如 RootBackend 检测 su 是否存在，ShizukuBackend 检测 binder 是否绑定） */
    fun isAvailable(): Boolean

    /** 截屏，失败返回 null */
    fun screenshot(): Bitmap?

    /** 单击坐标 */
    fun tap(x: Int, y: Int): Boolean

    /** 双击坐标 */
    fun doubleTap(x: Int, y: Int): Boolean

    /** 长按坐标，durationMs ≥ 500 */
    fun longPress(x: Int, y: Int, durationMs: Int): Boolean

    /** 滑动，durationMs ≥ 100 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean

    /** 按键：4=返回，3=主页 等 */
    fun keyEvent(keyCode: Int): Boolean

    /** 输入文本（已编码，无空格、无换行） */
    fun inputText(text: String): Boolean

    /** 执行任意 shell 命令；不支持的后端返回 false */
    fun exec(cmd: String): Boolean

    /** 执行 shell 命令并返回 stdout；不支持返回 null */
    fun execOut(cmd: String): String?
}

/**
 * 后端选择器：负责注册、初始化、按优先级选最优可用后端。
 *
 * 调用方约定：
 * - 需要截屏/手势：用 [best]（一定返回某个后端，最差是 AccessibilityBackend）
 * - 需要 shell 能力（pm install / screencap 等命令行）：用 [bestForShell]（可能返回 null，
 *   调用方自行降级处理）
 */
object BackendSelector {

    private const val TAG = "GameMaster"

    /** 注册的后端列表（按 priority 升序；选 best 时从尾部取） */
    private val backends: MutableList<DeviceBackend> = mutableListOf()

    /** 无障碍后端弱引用：GameAccessibilityService onServiceConnected 时 set */
    @Volatile private var accessibilityBackend: AccessibilityBackend? = null

    /** Shizuku 后端引用：MainActivity 用户点"连接"后 set */
    @Volatile private var shizukuBackend: ShizukuBackend? = null

    /** Root 后端是静态对象，直接复用 */
    private val rootBackend: RootBackend = RootBackend

    init {
        // Root 永远注册（isAvailable() 会判断 su 是否存在）
        backends.add(rootBackend)
    }

    /** 由 GameAccessibilityService.onServiceConnected 调用，注入无障碍后端 */
    fun setAccessibilityService(service: GameAccessibilityService) {
        val backend = AccessibilityBackend(service)
        accessibilityBackend = backend
        // 替换列表中已有的 AccessibilityBackend（如果有）
        synchronized(backends) {
            backends.removeAll { it is AccessibilityBackend }
            backends.add(backend)
        }
        Log.i(TAG, "[backend] AccessibilityBackend 已注册")
    }

    /** 由 MainActivity 调用，注入 Shizuku 后端 */
    fun setShizukuBackend(backend: ShizukuBackend) {
        shizukuBackend = backend
        synchronized(backends) {
            backends.removeAll { it is ShizukuBackend }
            backends.add(backend)
        }
        Log.i(TAG, "[backend] ShizukuBackend 已注册")
    }

    /** 列出所有当前可用的后端，按优先级降序 */
    fun allAvailable(): List<DeviceBackend> = synchronized(backends) {
        backends.filter { it.isAvailable() }.sortedByDescending { it.priority }
    }

    /** 选优先级最高的可用后端；最差返回 AccessibilityBackend（无 shell 能力） */
    fun best(): DeviceBackend? = allAvailable().firstOrNull()

    /** 仅在 Root/Shizuku 中选最优；都没有返回 null（调用方降级处理） */
    fun bestForShell(): DeviceBackend? = allAvailable().firstOrNull { it.priority >= 7 }

    /** 当前选中的后端名（UI 状态显示用） */
    fun currentName(): String = best()?.name ?: "无"

    /** Shizuku 是否已绑定（MainActivity 状态显示用） */
    fun shizukuBound(): Boolean = shizukuBackend?.isAvailable() == true

    /** Root 是否可用（MainActivity 状态显示用） */
    fun rootAvailable(): Boolean = rootBackend.isAvailable()

    /**
     * 触发 Shizuku 权限请求：用户点"连接 Shizuku"按钮时调。
     * Shizuku 已安装并运行时返回 true；权限授予由 [ShizukuBackend] 监听回调异步生效。
     */
    fun bindShizuku(ctx: Context): Boolean {
        val backend = shizukuBackend ?: ShizukuBackend().also {
            setShizukuBackend(it)
        }
        return backend.requestBindIfNeeded(ctx)
    }
}
