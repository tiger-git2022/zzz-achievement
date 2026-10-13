package com.local.stardbzzz.scan

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.local.stardbzzz.ZZZAchievementApp
import kotlinx.coroutines.delay

/**
 * Foreground service that owns the MediaProjection for the scan session
 * (Android requires a mediaProjection-type foreground service while capturing).
 */
class ScreenCaptureService : Service() {
    private var capture: ProjectionScreenCapture? = null
    private val coordinator get() = (application as ZZZAchievementApp).container.scanCoordinator

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be in the foreground before getMediaProjection() on Android 14+.
        startInForeground()

        val resultCode = intent?.getIntExtra(ExtraResultCode, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, ExtraResultData, Intent::class.java) }
        val projection = data?.let {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, it)
        }
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        capture?.release()
        capture = ProjectionScreenCapture(this, projection) { stopSelf() }.also(coordinator::attachCapture)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        capture?.let { coordinator.detachCapture(it) }
        capture?.release()
        capture = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "成就扫描", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("成就扫描已就绪")
            .setContentText("在游戏成就页点击悬浮窗的「开始」")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NotificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    companion object {
        private const val ChannelId = "scan"
        private const val NotificationId = 2001
        private const val ExtraResultCode = "result_code"
        private const val ExtraResultData = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(ExtraResultCode, resultCode)
                .putExtra(ExtraResultData, data)
            ContextCompat.startForegroundService(context, intent)
        }

        /** stopService() is allowed from the background, unlike startService(). */
        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}

/** Mirrors the default display into an ImageReader and hands out the latest frame as a Bitmap. */
class ProjectionScreenCapture(
    context: Context,
    private val projection: MediaProjection,
    onStopped: () -> Unit,
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val densityDpi = context.resources.displayMetrics.densityDpi
    private val thread = HandlerThread("screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var size = Point()
    private var latestImage: Image? = null // guarded by lock
    private var lastFrame: Bitmap? = null

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() = onStopped()
    }

    init {
        // Android 14+ requires a registered callback before createVirtualDisplay().
        projection.registerCallback(callback, handler)
    }

    /** The real display size in the current rotation; gestures use the same coordinate space. */
    fun displaySize(): Point = currentDisplaySize(displayManager)

    suspend fun capture(): Bitmap {
        ensureDisplay()
        repeat(FrameWaitAttempts) {
            val image = synchronized(lock) { latestImage.also { latestImage = null } }
            if (image != null) {
                lastFrame = try {
                    image.toBitmap()
                } finally {
                    image.close()
                }
            }
            // No new frame means the screen hasn't changed since the last one.
            lastFrame?.let { return it }
            delay(FrameWaitMillis)
        }
        error("截屏失败：没有收到画面")
    }

    fun release() {
        projection.unregisterCallback(callback)
        virtualDisplay?.release()
        virtualDisplay = null
        synchronized(lock) {
            latestImage?.close()
            latestImage = null
        }
        reader?.close()
        reader = null
        projection.stop()
        thread.quitSafely()
    }

    @Synchronized
    private fun ensureDisplay() {
        val real = displaySize()
        if (virtualDisplay != null && real == size) return

        // The game is landscape; resize the mirror whenever the rotation changes so pixels map 1:1 to the screen.
        val newReader = ImageReader.newInstance(real.x, real.y, PixelFormat.RGBA_8888, 3)
        newReader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            synchronized(lock) {
                latestImage?.close()
                latestImage = image
            }
        }, handler)

        val display = virtualDisplay
        if (display == null) {
            virtualDisplay = projection.createVirtualDisplay(
                "zzz-achievement-scan",
                real.x,
                real.y,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface,
                null,
                handler,
            )
        } else {
            // A MediaProjection may create only one VirtualDisplay on Android 14+, so reuse it.
            display.resize(real.x, real.y, densityDpi)
            display.surface = newReader.surface
        }
        synchronized(lock) {
            latestImage?.close()
            latestImage = null
        }
        reader?.close()
        reader = newReader
        lastFrame = null
        size = real
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPixels == width) return padded
        return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }

    private companion object {
        const val FrameWaitAttempts = 40
        const val FrameWaitMillis = 50L
    }
}

@Suppress("DEPRECATION")
fun currentDisplaySize(displayManager: DisplayManager): Point =
    Point().also { displayManager.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(it) }
