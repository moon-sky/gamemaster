package com.gamemaster.agent.backend

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gamemaster.agent.service.GameAccessibilityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 纯无障碍后端：最后兜底，仅靠 AccessibilityService.dispatchGesture 实现手势；
 * 不支持截屏 / shell 命令 / 按键（这些由 [BackendSelector.bestForShell] 选 Root/Shizuku 兜底）。
 *
 * 按键事件由 GameAccessibilityService 自己用 performGlobalAction 处理，不在此后端。
 */
class AccessibilityBackend(
    private val service: GameAccessibilityService
) : DeviceBackend {

    private val tag = "GameMaster"

    override val name: String = "无障碍"
    override val priority: Int = 1

    override fun isAvailable(): Boolean = true  // 服务一连接就恒为可用

    override fun screenshot(): android.graphics.Bitmap? = null  // 无 shell 能力
    override fun exec(cmd: String): Boolean = false
    override fun execOut(cmd: String): String? = null
    override fun keyEvent(keyCode: Int): Boolean = false  // 走 performGlobalAction，不在此处

    private fun stroke(x: Float, y: Float, durationMs: Long): GestureDescription.StrokeDescription {
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        return GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
    }

    private fun strokePath(
        x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long
    ): GestureDescription.StrokeDescription {
        val path = Path().apply {
            moveTo(x1.coerceAtLeast(1f), y1.coerceAtLeast(1f))
            lineTo(x2.coerceAtLeast(1f), y2.coerceAtLeast(1f))
        }
        return GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(100L))
    }

    /** 同步等待 dispatchGesture 回调：dispatchGesture 必须在主线程调用 */
    private fun dispatchSync(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        var result = false
        Handler(Looper.getMainLooper()).post {
            val dispatched = try {
                service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: GestureDescription?) {
                            result = true; latch.countDown()
                        }

                        override fun onCancelled(g: GestureDescription?) {
                            result = false; latch.countDown()
                        }
                    },
                    null
                )
            } catch (e: Exception) {
                Log.e(tag, "[backend.accessibility] dispatchGesture 异常：${e.message}")
                false
            }
            if (!dispatched) latch.countDown()  // 即时失败
        }
        return try {
            latch.await(3, TimeUnit.SECONDS) && result
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    override fun tap(x: Int, y: Int): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(stroke(x.toFloat(), y.toFloat(), 40L)).build()
        return dispatchSync(g)
    }

    override fun doubleTap(x: Int, y: Int): Boolean {
        // 双击：起止时间错开的两条 stroke
        val path1 = Path().apply { moveTo(x.coerceAtLeast(1).toFloat(), y.coerceAtLeast(1).toFloat()) }
        val path2 = Path().apply { moveTo((x + 1).coerceAtLeast(1).toFloat(), y.coerceAtLeast(1).toFloat()) }
        val s1 = GestureDescription.StrokeDescription(path1, 0L, 8L)
        val s2 = GestureDescription.StrokeDescription(path2, 110L, 8L)
        val g = GestureDescription.Builder().addStroke(s1).addStroke(s2).build()
        return dispatchSync(g)
    }

    override fun longPress(x: Int, y: Int, durationMs: Int): Boolean {
        val path = Path().apply { moveTo(x.coerceAtLeast(1).toFloat(), y.coerceAtLeast(1).toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.toLong())).build()
        return dispatchSync(g)
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(strokePath(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs.toLong()))
            .build()
        return dispatchSync(g)
    }

    override fun inputText(text: String): Boolean = false  // 文本输入走 AccessibilityNodeInfo，不在此处
}
