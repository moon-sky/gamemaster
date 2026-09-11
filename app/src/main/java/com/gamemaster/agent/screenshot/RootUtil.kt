package com.gamemaster.agent.screenshot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 已 root 设备（Android 10 且无 MediaProjection 授权界面的定制 ROM）兜底截屏：
 * 通过 su 执行系统 screencap，直接读取 PNG 字节流。
 */
object RootUtil {

    private const val TAG = "GameMaster"

    fun isRooted(): Boolean =
        File("/system/bin/su").exists() || File("/system/xbin/su").exists()

    /** 以 root 执行任意 shell 命令，返回是否成功退出 */
    fun exec(cmd: String): Boolean = try {
        val p = ProcessBuilder("su", "0", "sh", "-c", cmd)
            .redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
        val ok = p.waitFor(15, TimeUnit.SECONDS)
        p.destroy()
        ok
    } catch (e: Exception) {
        Log.e(TAG, "root exec failed: $cmd → ${e.message}")
        false
    }

    /** 以 root 执行命令并返回 stdout（失败返回 null） */
    fun execOut(cmd: String): String? = try {
        val p = ProcessBuilder("su", "0", "sh", "-c", cmd)
            .redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(15, TimeUnit.SECONDS)
        p.destroy()
        out
    } catch (e: Exception) {
        Log.e(TAG, "root execOut failed: $cmd → ${e.message}")
        null
    }

    /** 执行 `su 0 screencap -p`，返回屏幕位图；失败返回 null */
    fun screenshot(): Bitmap? {
        return try {
            val process = ProcessBuilder("su", "0", "screencap", "-p")
                .redirectErrorStream(false)
                .start()
            val bitmap = BitmapFactory.decodeStream(process.inputStream)
            process.waitFor(8, TimeUnit.SECONDS)
            process.destroy()
            if (bitmap != null) Log.i(TAG, "root screencap ok ${bitmap.width}x${bitmap.height}")
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "root screencap failed: ${e.message}")
            null
        }
    }

    // ---------------- 手势/按键注入（部分定制 ROM 上无障碍手势无效，用 root input 兜底） ----------------

    private fun execInput(vararg args: String): Boolean {
        return try {
            val p = ProcessBuilder("su", "0", "input", *args)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val finished = p.waitFor(10, TimeUnit.SECONDS)
            p.destroy()
            val ok = finished && p.exitValue() == 0
            if (ok) {
                Log.i(TAG, "root input ok: input ${args.joinToString(" ")}")
            } else {
                Log.w(TAG, "root input failed: input ${args.joinToString(" ")} | $out")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "root input error: ${e.message}")
            false
        }
    }

    fun tap(x: Int, y: Int): Boolean = execInput("tap", x.toString(), y.toString())

    fun doubleTap(x: Int, y: Int): Boolean {
        val first = tap(x, y)
        Thread.sleep(90)
        val second = tap(x, y)
        return first && second
    }

    /** input 没有长按命令，同点起停的长耗时滑动等效按住 */
    fun longPress(x: Int, y: Int, durationMs: Int): Boolean =
        execInput(
            "swipe", x.toString(), y.toString(), x.toString(), y.toString(),
            durationMs.coerceAtLeast(500).toString()
        )

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean =
        execInput(
            "swipe",
            x1.toString(), y1.toString(), x2.toString(), y2.toString(),
            durationMs.coerceAtLeast(100).toString()
        )

    /** 4=返回键，3=主页键 */
    fun keyEvent(keyCode: Int): Boolean = execInput("keyevent", keyCode.toString())

    fun inputText(text: String): Boolean =
        execInput("text", text.replace(" ", "%s").replace("\n", ""))
}
