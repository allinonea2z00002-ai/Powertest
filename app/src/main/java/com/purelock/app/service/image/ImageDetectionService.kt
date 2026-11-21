package com.purelock.app.service.image

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.data.db.SecurityEventType
import com.purelock.app.ui.block.ImageCoverActivity
import com.purelock.app.util.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Experimental on-device image detection (built LAST, per spec).
 *
 * Captures the screen with MediaProjection, samples one frame every few seconds and classifies
 * it with the on-device TensorFlow Lite model. Frames are converted to a bitmap, classified and
 * recycled immediately — no image is ever stored or sent anywhere.
 *
 * When a frame scores above the sensitivity threshold, the cover screen is raised (a
 * high-priority notification with a full-screen intent) and the hit is logged. The UI must say
 * this can be wrong (false positives and misses) and uses more battery.
 *
 * Without a model file (`assets/nsfw_model.tflite`, not bundled yet) the service stops itself
 * and tells the user; it never pretends to protect.
 */
class ImageDetectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var classifier: ImageClassifier? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var sampleThread: HandlerThread? = null
    private var sampleHandler: Handler? = null
    private var sensitivity: Int = 2
    private var lastHitAt = 0L

    private val sampler = object : Runnable {
        override fun run() {
            sampleOnce()
            sampleHandler?.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        classifier = ImageClassifier(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (classifier?.available != true) {
            // No model: never pretend to protect. Tell the user and stop.
            Graph.notifier.alert(
                Notifier.ID_IMAGE_MODEL_MISSING,
                getString(R.string.image_detection_title),
                getString(R.string.image_detection_model_missing)
            )
            stopSelf()
            return START_NOT_STICKY
        }
        sensitivity = intent?.getIntExtra(EXTRA_SENSITIVITY, 2) ?: 2
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Foreground first: on Android 14+ MediaProjection requires the media-projection
        // foreground service type to be active before the projection is created.
        startForegroundNotification()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        this.projection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, sampleHandler)

        val metrics = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
        val width = (metrics.widthPixels / CAPTURE_DOWNSCALE).coerceAtLeast(1)
        val height = (metrics.heightPixels / CAPTURE_DOWNSCALE).coerceAtLeast(1)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "PureLockImageDetection",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
        val thread = HandlerThread("ImageDetectionSampler").apply { start() }
        sampleThread = thread
        val handler = Handler(thread.looper)
        sampleHandler = handler
        handler.postDelayed(sampler, SAMPLE_INTERVAL_MS)
        ImageDetectionController.heartbeat(this)
        return START_STICKY
    }

    private fun sampleOnce() {
        ImageDetectionController.heartbeat(this)
        val reader = imageReader ?: return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val bitmap = image.toBitmap() ?: return
            try {
                val probability = classifier?.classify(bitmap) ?: return
                if (probability >= thresholdFor(sensitivity)) onHit(probability)
            } finally {
                bitmap.recycle()
            }
        } finally {
            image.close()
        }
    }

    private fun onHit(probability: Float) {
        val now = System.currentTimeMillis()
        if (now - lastHitAt < HIT_THROTTLE_MS) return
        lastHitAt = now
        scope.launch {
            runCatching {
                Graph.eventLog.log(
                    SecurityEventType.IMAGE_DETECTION_BLOCK,
                    "probability=${"%.2f".format(probability)}"
                )
            }
        }
        // Raise the cover screen: a high-priority notification with a full-screen intent.
        // (Starting an activity directly from a background service is not allowed.)
        val coverIntent = Intent(this, ImageCoverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val fullScreen = PendingIntent.getActivity(
            this, 0, coverIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, Notifier.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_nav_shield)
            .setContentTitle(getString(R.string.image_detection_cover_title))
            .setContentText(getString(R.string.image_detection_cover_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreen, true)
            .setAutoCancel(true)
            .build()
        runCatching {
            val manager = NotificationManagerCompat.from(this)
            if (manager.areNotificationsEnabled()) {
                manager.notify(Notifier.ID_IMAGE_DETECTION, notification)
            }
        }
    }

    private fun startForegroundNotification() {
        Graph.notifier.createChannels()
        val notification: Notification = NotificationCompat.Builder(this, Notifier.CH_PROTECTION)
            .setSmallIcon(R.drawable.ic_nav_shield)
            .setContentTitle(getString(R.string.image_detection_title))
            .setContentText(getString(R.string.image_detection_notification_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        sampleHandler?.removeCallbacks(sampler)
        sampleThread?.quitSafely()
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        classifier?.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SENSITIVITY = "sensitivity"

        private const val NOTIFICATION_ID = 2002
        /** Frames are classified at most this often — sampling is the battery cost. */
        private const val SAMPLE_INTERVAL_MS = 3_000L
        /** At most one cover screen per 30 seconds, however many frames hit. */
        private const val HIT_THROTTLE_MS = 30_000L
        /** Captured frames are downscaled by this factor before classification. */
        private const val CAPTURE_DOWNSCALE = 2

        /** Adult-probability threshold per sensitivity level (1 low .. 3 high). */
        fun thresholdFor(sensitivity: Int): Float = when (sensitivity) {
            1 -> 0.90f
            3 -> 0.65f
            else -> 0.80f
        }

        fun start(context: Context, resultCode: Int, data: Intent, sensitivity: Int) {
            val intent = Intent(context, ImageDetectionService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_SENSITIVITY, sensitivity)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ImageDetectionService::class.java))
        }
    }
}

/** Converts an RGBA_8888 [Image] to a [Bitmap], honouring row padding. Returns null on failure. */
private fun Image.toBitmap(): Bitmap? {
    if (format != PixelFormat.RGBA_8888) return null
    return runCatching {
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, width, height).also { bitmap.recycle() }
    }.getOrNull()
}
