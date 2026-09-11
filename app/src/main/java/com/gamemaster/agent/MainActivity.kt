package com.gamemaster.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.gamemaster.agent.databinding.ActivityMainBinding
import com.gamemaster.agent.prefs.Prefs
import com.gamemaster.agent.screenshot.RootUtil
import com.gamemaster.agent.screenshot.ScreenshotManager
import com.gamemaster.agent.service.GameAccessibilityService
import com.gamemaster.agent.service.OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 运行中屏幕熄灭/锁屏时被服务拉起：直接亮屏、越过无密码锁屏，然后回桌面
        if (intent?.getBooleanExtra(EXTRA_WAKE_UNLOCK, false) == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                // 主动请求解除无密码（滑动）锁屏；有密码时此回调不会成功，不影响其他逻辑
                val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
                km.requestDismissKeyguard(this, null)
            }
            binding.root.postDelayed({
                // 回到桌面，让 AI 继续当前任务，不停留在配置页
                val home = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(home)
                finishAndRemoveTask()
            }, 1200)
            return
        }

        loadConfig()

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnOverlay.setOnClickListener {
            if (canDrawOverlays()) {
                Toast.makeText(this, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        binding.btnSave.setOnClickListener {
            saveConfig()
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        }

        binding.btnProjection.setOnClickListener {
            requestProjection()
        }

        binding.btnStart.setOnClickListener {
            if (!GameAccessibilityService.isRunning()) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            if (!canDrawOverlays()) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                && !ScreenshotManager.isReady
                && !RootUtil.isRooted()
            ) {
                Toast.makeText(this, "Android 10 需要先点“开启屏幕录制授权”", Toast.LENGTH_LONG).show()
                requestProjection()
                return@setOnClickListener
            }
            saveConfig()
            val cfg = Prefs.agentConfig(this)
            if (cfg.apiKey.isBlank()) {
                Toast.makeText(this, "请先填写 API Key", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (cfg.task.isBlank()) {
                Toast.makeText(this, "请先填写任务目标", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // 显示悬浮球/控制面板
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
            // 立即以最新配置开始一轮新任务（已在运行则先停止旧任务）
            val acc = GameAccessibilityService.get()
            if (acc == null) {
                Toast.makeText(this, "无障碍服务未连接，请重新开启后再试", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (acc.isAgentRunning()) acc.stopAgent()
            acc.startAgent(cfg)
            Toast.makeText(this, "任务已开始，请切换到目标游戏 / 应用", Toast.LENGTH_LONG).show()
            // 自动回到桌面：AI 从干净的桌面开始，自己识别并打开目标应用，
            // 不会停在本助手的配置页上产生误判
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                acc.globalHome()
            }, 1000)
        }

        requestNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_WAKE_UNLOCK, false)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            binding.root.postDelayed({
                val home = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(home)
            }, 1200)
        }
    }

    private fun requestProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(this, "当前系统使用无障碍截屏，无需此授权", Toast.LENGTH_SHORT).show()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val ok = ScreenshotManager.start(this, resultCode, data)
                Toast.makeText(
                    this,
                    if (ok) "屏幕录制授权成功" else "屏幕录制授权失败",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(this, "未授权屏幕录制，将尝试其他截屏方式", Toast.LENGTH_LONG).show()
            }
            refreshPermissionStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    private fun refreshPermissionStatus() {
        val accOn = GameAccessibilityService.isRunning()
        val overlayOn = canDrawOverlays()
        binding.tvPermStatus.text = buildString {
            appendLine("● 无障碍服务：" + if (accOn) "已开启" else "未开启")
            appendLine("● 悬浮窗权限：" + if (overlayOn) "已开启" else "未开启")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                append("● 截屏能力：" + when {
                    ScreenshotManager.isReady -> "屏幕录制授权已开启"
                    RootUtil.isRooted() -> "已通过 root 授权"
                    else -> "未开启"
                })
            }
        }
        // 按钮始终可点：缺少权限时点击会逐项弹提示引导开启
        binding.btnStart.isEnabled = true
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun loadConfig() {
        binding.etBaseUrl.setText(Prefs.getString(this, Prefs.KEY_BASE_URL, Prefs.DEFAULT_BASE_URL))
        binding.etApiKey.setText(Prefs.getString(this, Prefs.KEY_API_KEY))
        binding.etModel.setText(Prefs.getString(this, Prefs.KEY_MODEL, Prefs.DEFAULT_MODEL))
        binding.etTask.setText(Prefs.getString(this, Prefs.KEY_TASK))
    }

    private fun saveConfig() {
        Prefs.setString(this, Prefs.KEY_BASE_URL, binding.etBaseUrl.text.toString())
        Prefs.setString(this, Prefs.KEY_API_KEY, binding.etApiKey.text.toString())
        Prefs.setString(this, Prefs.KEY_MODEL, binding.etModel.text.toString())
        Prefs.setString(this, Prefs.KEY_TASK, binding.etTask.text.toString())
    }

    private fun requestNotificationPermission() {
        // POST_NOTIFICATIONS 是 Android 13(API 33) 权限，compileSdk 31 下用常量判断
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf("android.permission.POST_NOTIFICATIONS"),
                    1001
                )
            }
        }
    }

    companion object {
        private const val REQ_PROJECTION = 2001

        /** 由无障碍服务在锁屏时拉起：唤醒并滑掉无密码锁屏后自动回桌面 */
        const val EXTRA_WAKE_UNLOCK = "extra_wake_unlock"
    }
}
