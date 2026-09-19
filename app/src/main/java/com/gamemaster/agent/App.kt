package com.gamemaster.agent

import android.app.Application
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * 应用入口：在进程启动时初始化 Chaquopy Python 运行时。
 *
 * Chaquopy 16 不再通过 ContentProvider 自动启动 Python，必须由应用主动调用
 * Python.start(AndroidPlatform(ctx))。否则 ToolRegistry 里的 Python 工具
 * （web_search/web_read/apk_install/file_ops）会全部返回 "Python 运行时未启动"。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
                Log.i("GameMaster", "[chaquopy] Python 已启动")
            }
        } catch (e: Exception) {
            Log.e("GameMaster", "[chaquopy] Python 启动失败: ${e.message}", e)
        }
    }
}
