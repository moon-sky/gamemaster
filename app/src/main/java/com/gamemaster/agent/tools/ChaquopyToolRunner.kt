package com.gamemaster.agent.tools

import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chaquopy Python 工具执行器：把 Kotlin 的调用翻译为
 * `Python.getInstance().getModule(module).callAttr(func, *args)` 并返回字符串结果。
 *
 * 用位置参数传递而不是 kwargs：
 * - Python 工具签名稳定且参数量很少（≤3 个），由 [BuiltInTools] 各 handler 显式取参并按顺序传入；
 * - 避免 Chaquopy `callAttr(name, Map, varargs)` 与 `callAttr(name, varargs)` 在 Kotlin 下的重载歧义；
 * - 全部用 Any? 透传，让 Chaquopy 在 Java 层做 Python 类型转换（str/int/None）。
 *
 * 调用必须在 IO 线程：requests 走网络，IO 等待不应阻塞主线程。
 */
object ChaquopyToolRunner {

    private const val TAG = "GameMaster"

    /**
     * 执行 Python 模块的入口函数。
     *
     * @param module 例如 "tools.web_search"（对应 src/main/python/tools/web_search.py）
     * @param func   例如 "search"
     * @param args   位置参数，按 Python 函数签名顺序传入；空集合调用无参函数
     * @return Python 返回值的 toString()；模块/函数不存在或抛异常时返回 "ERROR: ..." 字符串
     */
    suspend fun run(module: String, func: String, vararg args: Any?): String =
        withContext(Dispatchers.IO) {
            try {
                if (!Python.isStarted()) {
                    Log.e(TAG, "[chaquopy] Python 未启动，跳过 $module.$func")
                    return@withContext "ERROR: Python 运行时未启动"
                }
                val py = Python.getInstance()
                val pyModule = py.getModule(module)
                    ?: run {
                        Log.e(TAG, "[chaquopy] 模块不存在：$module")
                        return@withContext "ERROR: 模块不存在 $module"
                    }
                val result: PyObject? = if (args.isEmpty()) {
                    pyModule.callAttr(func)
                } else {
                    // 转成 Array<Any?> 让 Chaquopy 走 callAttr(name, Object...) 重载
                    pyModule.callAttr(func, *args)
                }
                result?.toString() ?: ""
            } catch (e: PyException) {
                Log.e(TAG, "[chaquopy] $module.$func PyException：${e.message}")
                "ERROR: ${e.message}"
            } catch (e: Exception) {
                Log.e(TAG, "[chaquopy] $module.$func 异常：${e.message}")
                "ERROR: ${e.message}"
            }
        }
}
