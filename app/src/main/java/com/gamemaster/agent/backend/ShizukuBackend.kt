package com.gamemaster.agent.backend

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shizuku 后端：通过 Shizuku 服务的 [IShizukuService.newProcess] 执行 shell 命令，
 * 让非 Root 设备也能拿到 shell 权限执行截屏、`input` 手势、`pm install` 等。
 *
 * 注：`newProcess` 在 Shizuku 公开 API 里是私有的（service 字段），
 * 这里用反射取 IBinder 后包装成 IShizukuService 接口调用。
 *
 * 调用前置条件：
 * 1. Shizuku 已安装并启动（用户在 Shizuku App 内点"启动"）
 * 2. 已通过 [ShizukuProvider] 绑定 binder
 * 3. 已通过 [requestBindIfNeeded] 触发权限对话框并获用户授权
 */
class ShizukuBackend : DeviceBackend {

    private val tag = "GameMaster"
    private val shizukuPkg = "moe.shizuku.privileged-api"

    override val name: String = "Shizuku"
    override val priority: Int = 8

    /**
     * Shizuku 是否可用：binder 已绑定 + 权限已授。
     * 都满足时 [runShell] 才能正常拿到 IShizukuService。
     */
    override fun isAvailable(): Boolean = try {
        pingBinderInternal() && checkSelfPermission()
    } catch (_: Throwable) {
        false
    }

    /**
     * 触发 Shizuku binder 绑定 + 权限请求。
     * - Shizuku 未安装返回 false
     * - 已安装但权限未授，调 [Shizuku.requestPermission] 异步弹框
     */
    fun requestBindIfNeeded(ctx: Context): Boolean {
        if (!isShizukuInstalled(ctx)) {
            Log.w(tag, "[shizuku] 未安装 Shizuku")
            return false
        }
        return try {
            if (!pingBinderInternal()) {
                Log.w(tag, "[shizuku] binder 未建立，请确认 Shizuku App 已启动")
                return false
            }
            if (!checkSelfPermission()) {
                Shizuku.requestPermission(ShizukuProvider.REQUEST_PERMISSION_RESULT)
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(tag, "[shizuku] requestBindIfNeeded 失败：${e.message}")
            false
        }
    }

    private fun isShizukuInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(shizukuPkg, 0) != null
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Shizuku.pingBinder 是公开静态方法，binder 是否已绑定。
     * 用反射包一层应对 API 兼容。
     */
    private fun pingBinderInternal(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    private fun checkSelfPermission(): Boolean = try {
        if (Shizuku.isPreV11()) true
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /**
     * 通过反射取 rikka.shizuku.Shizuku 类的私有 `binder` 字段，
     * 包装成 IShizukuService 后调 newProcess。
     */
    private fun getService(): IShizukuService? {
        return try {
            val field = Shizuku::class.java.getDeclaredField("binder")
            field.isAccessible = true
            val binder = field.get(null) as? IBinder ?: return null
            IShizukuService.Stub.asInterface(binder)
        } catch (e: Exception) {
            Log.e(tag, "[shizuku] 反射取 service 失败：${e.message}")
            null
        }
    }

    // ---------------- shell / 截屏 / 手势：结构与 RootBackend 对称 ----------------

    override fun exec(cmd: String): Boolean = runShell(cmd, captureStdout = false) != null

    override fun execOut(cmd: String): String? = runShell(cmd, captureStdout = true)

    override fun screenshot(): Bitmap? {
        return try {
            val service = getService() ?: return null
            val process = service.newProcess(arrayOf("screencap", "-p"), null, null)
            val pfd = process.inputStream ?: run { process.destroy(); return null }
            val bitmap = FileInputStream(pfd.fileDescriptor).use { BitmapFactory.decodeStream(it) }
            // 截屏命令会自然退出，等一下确保进程清理
            runCatching {
                val t = Thread { runCatching { process.waitFor() } }
                t.isDaemon = true
                t.start()
                t.join(8000)
                t.interrupt()
            }
            process.destroy()
            if (bitmap != null) Log.i(tag, "shizuku screencap ok ${bitmap.width}x${bitmap.height}")
            bitmap
        } catch (e: Exception) {
            Log.e(tag, "shizuku screencap failed: ${e.message}")
            null
        }
    }

    private fun runShell(cmd: String, captureStdout: Boolean): String? {
        return try {
            val service = getService() ?: return null
            val process = service.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val outRef = AtomicReference<String>("")
            val errRef = AtomicReference<String>("")
            val outThread = Thread {
                val pfd = process.inputStream ?: return@Thread
                outRef.set(runCatching { FileInputStream(pfd.fileDescriptor).bufferedReader().readText() }.getOrDefault(""))
            }
            val errThread = Thread {
                val pfd = process.errorStream ?: return@Thread
                errRef.set(runCatching { FileInputStream(pfd.fileDescriptor).bufferedReader().readText() }.getOrDefault(""))
            }
            outThread.isDaemon = true; errThread.isDaemon = true
            outThread.start(); errThread.start()

            // 等进程结束（最多 15s）
            var exitCode = -1
            val waitThread = Thread { exitCode = runCatching { process.waitFor() }.getOrDefault(-1) }
            waitThread.isDaemon = true
            waitThread.start()
            waitThread.join(15_000)
            waitThread.interrupt()
            outThread.join(2_000); errThread.join(2_000)
            process.destroy()

            if (exitCode != 0) {
                Log.w(tag, "shizuku exec failed (exit=$exitCode): $cmd | stderr=${errRef.get().take(200)}")
                null
            } else if (!captureStdout) {
                ""
            } else {
                outRef.get()
            }
        } catch (e: Exception) {
            Log.e(tag, "shizuku exec error: $cmd → ${e.message}")
            null
        }
    }

    private fun execInput(vararg args: String): Boolean {
        val cmd = "input ${args.joinToString(" ")}"
        val out = runShell(cmd, captureStdout = true)
        val ok = out != null
        if (ok) Log.i(tag, "shizuku input ok: $cmd")
        return ok
    }

    override fun tap(x: Int, y: Int): Boolean = execInput("tap", x.toString(), y.toString())

    override fun doubleTap(x: Int, y: Int): Boolean {
        val first = tap(x, y)
        Thread.sleep(90)
        val second = tap(x, y)
        return first && second
    }

    override fun longPress(x: Int, y: Int, durationMs: Int): Boolean =
        execInput(
            "swipe", x.toString(), y.toString(), x.toString(), y.toString(),
            durationMs.coerceAtLeast(500).toString()
        )

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean =
        execInput(
            "swipe",
            x1.toString(), y1.toString(), x2.toString(), y2.toString(),
            durationMs.coerceAtLeast(100).toString()
        )

    override fun keyEvent(keyCode: Int): Boolean = execInput("keyevent", keyCode.toString())

    override fun inputText(text: String): Boolean =
        execInput("text", text.replace(" ", "%s").replace("\n", ""))
}
