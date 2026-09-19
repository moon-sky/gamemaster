package com.gamemaster.agent.backend

import android.content.Intent
import android.util.Log
import rikka.shizuku.ShizukuProvider

/**
 * Shizuku IPC Provider：app 进程与 Shizuku 服务之间的 binder 桥。
 *
 * Manifest 中必须声明此 provider，否则 [Shizuku.pingBinder] 永远返回 false。
 * 首次访问 provider 时 Shizuku SDK 会自动建立 binder 连接，无需手动调用。
 *
 * 同时承担权限请求回调入口：[onRequestPermissionResult] 收到用户的授权结果，
 * 通过 [BackendSelector] 更新当前后端状态。
 */
class ShizukuProvider : ShizukuProvider() {

    companion object {
        const val REQUEST_PERMISSION_RESULT = 0xC0DE

        private const val TAG = "GameMaster"
    }

    init {
        // Shizuku binder 状态变更监听：用户在 Shizuku App 里停止 / 启动服务时触发
        try {
            rikka.shizuku.Shizuku.addBinderReceivedListener {
                Log.i(TAG, "[shizuku] binder 已建立（connected=true）")
            }
            rikka.shizuku.Shizuku.addBinderDeadListener {
                Log.w(TAG, "[shizuku] binder 已断开（用户停止 Shizuku 服务？）")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[shizuku] 监听 binder 失败：${e.message}")
        }
    }

    /** 权限对话框结果：MainActivity 透传 Shizuku 的回调到这里 */
    fun onRequestPermissionResult(resultCode: Int, data: Intent?) {
        if (resultCode == REQUEST_PERMISSION_RESULT) {
            // 即时更新 BackendSelector 里的 ShizukuBackend 可用状态
            try {
                val granted = rikka.shizuku.Shizuku.checkSelfPermission() ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                Log.i(TAG, "[shizuku] 权限请求结果：granted=$granted")
                // ShizukuBackend.isAvailable() 下次查询会自动反映最新状态；
                // 若 BackendSelector 里尚未注册 ShizukuBackend，MainActivity 已在调
                // requestBindIfNeeded() 时完成注册，无需在此重复 bind。
            } catch (e: Exception) {
                Log.e(TAG, "[shizuku] 检查权限失败：${e.message}")
            }
        }
    }
}
