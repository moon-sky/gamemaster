package com.gamemaster.agent.screenshot

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.graphics.Point

/**
 * 兼容 Android 10（API 29）的截屏方案：MediaProjection。
 * Android 11+ 直接用 AccessibilityService.takeScreenshot()，无需此类。
 *
 * 流程：MainActivity 请求屏幕录制授权 → 把 resultCode/data 交给 [start] →
 * 建立持续镜像的 VirtualDisplay + ImageReader → [capture] 随时取最新帧。
 */
object ScreenshotManager {

    private const val TAG = "GameMaster"

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var width: Int = 0
    @Volatile
    private var height: Int = 0

    val isReady: Boolean
        get() = projection != null && virtualDisplay != null && imageReader != null

    fun start(context: Context, resultCode: Int, data: Intent): Boolean {
        stop()
        return try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data) ?: return false

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val size = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(size)
            width = size.x
            height = size.y
            val density = context.resources.displayMetrics.densityDpi

            val thread = HandlerThread("gm-screenshot").also { it.start() }
            handlerThread = thread
            val bgHandler = Handler(thread.looper)
            handler = bgHandler

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            imageReader = reader

            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped")
                }
            }, bgHandler)

            virtualDisplay = proj.createVirtualDisplay(
                "gm_screen_mirror",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, bgHandler
            )
            projection = proj
            Log.i(TAG, "ScreenshotManager started ${width}x${height}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ScreenshotManager start failed", e)
            stop()
            false
        }
    }

    /** 取当前屏幕最新一帧；调用方负责 recycle */
    fun capture(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return null

        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bmpWidth = image.width + rowPadding / pixelStride

            val padded = Bitmap.createBitmap(bmpWidth, image.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)

            if (bmpWidth != image.width) {
                val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                padded.recycle()
                cropped
            } else {
                padded
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            null
        } finally {
            image.close()
        }
    }

    fun stop() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }
}
