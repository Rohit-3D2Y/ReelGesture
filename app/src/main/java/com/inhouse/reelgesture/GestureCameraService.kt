package com.inhouse.reelgesture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that watches the front camera for a hand swipe and asks
 * [ReelScrollService] to scroll. Every failure path degrades to a status message
 * instead of crashing, and the camera is released as soon as the service stops.
 */
class GestureCameraService : LifecycleService() {

    companion object {
        private const val TAG = "GestureCameraService"
        private const val CHANNEL_ID = "gesture_camera"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.inhouse.reelgesture.STOP"
        private const val MIN_FRAME_INTERVAL_MS = 66L // ~15 fps cap keeps CPU/battery use low
        private const val STUCK_FRAME_TIMEOUT_MS = 1000L

        // Palm landmarks: wrist + base of each finger. More stable than a fingertip.
        private val PALM_LANDMARKS = intArrayOf(0, 5, 9, 13, 17)

        @Volatile
        var isRunning = false
            private set

        /** Human-readable status for the UI (last error or state). */
        @Volatile
        var status: String = "Stopped"
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GestureCameraService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GestureCameraService::class.java))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var analysisExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var landmarker: HandLandmarker? = null
    @Volatile private var frameInFlightSince = 0L
    private var lastFrameMs = 0L
    private var started = false
    private var failed = false

    private val detector = SwipeDetector(
        threshold = { Prefs.swipeThreshold(this) },
        allowDown = { Prefs.allowPrevious(this) },
        onSwipe = { direction -> mainHandler.post { performScroll(direction) } },
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY

        if (!goForeground("Watching for hand swipes")) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        isRunning = true
        failed = false
        status = "Starting…"

        analysisExecutor = Executors.newSingleThreadExecutor()
        if (!createLandmarker()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startCamera()
        // No auto-restart after the system kills us: on Android 14+ a camera service
        // cannot legally start from the background, and a restart loop would be noisy.
        return START_NOT_STICKY
    }

    private fun goForeground(text: String): Boolean = try {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
        true
    } catch (e: Exception) {
        // e.g. ForegroundServiceStartNotAllowedException or missing camera permission.
        Log.e(TAG, "Could not start foreground service", e)
        failed = true
        status = "Could not start: ${e.javaClass.simpleName}. Open the app and try again."
        false
    }

    private fun createLandmarker(): Boolean = try {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(Delegate.CPU)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> onHandResult(result) }
            .setErrorListener { e ->
                Log.w(TAG, "Hand landmarker error", e)
                frameInFlightSince = 0L
            }
            .build()
        landmarker = HandLandmarker.createFromOptions(this, options)
        true
    } catch (e: Throwable) {
        // Throwable: native library load problems surface as Errors, not Exceptions.
        Log.e(TAG, "Hand model failed to load", e)
        failed = true
        status = "Hand model failed to load: ${e.message}"
        false
    }

    private fun startCamera() {
        val future = try {
            ProcessCameraProvider.getInstance(this)
        } catch (e: Exception) {
            failAndStop("Camera unavailable: ${e.message}")
            return
        }
        future.addListener({
            if (!isRunning) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysisExecutor?.let { analysis.setAnalyzer(it, ::analyzeFrame) }

                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                camera.cameraInfo.cameraState.observe(this) { state -> onCameraState(state) }
            } catch (e: Exception) {
                failAndStop("Could not open front camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onCameraState(state: CameraState) {
        val error = state.error
        status = when {
            error?.code == CameraState.ERROR_CAMERA_IN_USE ||
                error?.code == CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                // Another app has the camera. CameraX reconnects automatically when it is free.
                "Camera busy in another app — waiting…"
            error?.code == CameraState.ERROR_CAMERA_DISABLED ->
                "Camera disabled by device policy"
            error != null -> "Camera error ${error.code} — retrying…"
            state.type == CameraState.Type.OPEN -> "Running — swipe your hand up for the next reel"
            state.type == CameraState.Type.OPENING ||
                state.type == CameraState.Type.PENDING_OPEN -> "Opening camera…"
            else -> status
        }
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameMs < MIN_FRAME_INTERVAL_MS) return
            if (!shouldWatchNow()) {
                detector.reset()
                return
            }
            val inFlight = frameInFlightSince
            if (inFlight != 0L && now - inFlight < STUCK_FRAME_TIMEOUT_MS) return // model still busy
            val model = landmarker ?: return
            lastFrameMs = now

            val bitmap = uprightBitmap(proxy)
            frameInFlightSince = now
            model.detectAsync(BitmapImageBuilder(bitmap).build(), now)
        } catch (e: Throwable) {
            Log.w(TAG, "Frame skipped", e)
            frameInFlightSince = 0L
        } finally {
            proxy.close()
        }
    }

    /** Skip all ML work (saves battery/CPU) unless Instagram is on screen in Instagram-only mode. */
    private fun shouldWatchNow(): Boolean {
        if (!Prefs.instagramOnly(this)) return true
        val fg = ReelScrollService.foregroundPackage ?: return true
        return fg == Prefs.INSTAGRAM_PACKAGE
    }

    private fun uprightBitmap(proxy: ImageProxy): Bitmap {
        val source = proxy.toBitmap()
        val rotation = proxy.imageInfo.rotationDegrees
        if (rotation == 0) return source
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
        if (rotated !== source) source.recycle()
        return rotated
    }

    private fun onHandResult(result: HandLandmarkerResult) {
        frameInFlightSince = 0L
        try {
            val hand = result.landmarks().firstOrNull()
            if (hand == null || hand.size < 21) {
                detector.reset()
                return
            }
            val y = PALM_LANDMARKS.map { hand[it].y() }.average().toFloat()
            detector.add(result.timestampMs(), y)
        } catch (e: Exception) {
            Log.w(TAG, "Bad hand result", e)
        }
    }

    private fun performScroll(direction: SwipeDetector.Direction) {
        val scroller = ReelScrollService.instance
        if (scroller == null) {
            status = "Accessibility service is off — enable it in the app"
            return
        }
        val next = direction == SwipeDetector.Direction.UP
        if (scroller.scroll(next, Prefs.instagramOnly(this))) {
            updateNotification(if (next) "Last: next reel" else "Last: previous reel")
        }
    }

    private fun failAndStop(message: String) {
        Log.e(TAG, message)
        failed = true
        status = message
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        started = false
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Unbind failed", e)
        }
        cameraProvider = null
        // Close the model on the analysis thread so it never races an in-progress frame.
        val model = landmarker
        landmarker = null
        val executor = analysisExecutor
        analysisExecutor = null
        if (executor != null) {
            try {
                executor.execute { runCatching { model?.close() } }
            } catch (e: Exception) {
                runCatching { model?.close() }
            }
            executor.shutdown()
        } else {
            runCatching { model?.close() }
        }
        mainHandler.removeCallbacksAndMessages(null)
        if (!failed) status = "Stopped"
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Gesture camera", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GestureCameraService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Reel Gesture is using the camera")
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "Notification update failed", e)
        }
    }
}
