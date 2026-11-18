package com.inhouse.reelgesture

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Foreground service that watches the front camera for a held hand pose
 * (☝ index finger up = next reel, ✌ two fingers = previous) and asks [ReelScrollService] to scroll.
 *
 * Battery/CPU budget:
 * - The camera is fully closed while Instagram is not on screen or the phone is locked.
 * - The hand model only runs when the cheap motion check sees movement (idle) or a hand
 *   was seen in the last couple of seconds (active), at a low frame rate.
 *
 * Every failure path degrades to a status message instead of crashing.
 */
class GestureCameraService : LifecycleService() {

    companion object {
        private const val TAG = "GestureCameraService"
        private const val CHANNEL_ID = "gesture_camera"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.inhouse.reelgesture.STOP"

        // While a hand is around, run the model on every frame it can keep up with.
        private const val ACTIVE_FRAME_INTERVAL_MS = 0L
        private const val IDLE_FRAME_INTERVAL_MS = 100L // 10 cheap motion checks/s otherwise
        // Run the model at least this often even with no motion, so it stays warm in memory
        // and the first ☝ after a long reel is recognised immediately.
        private const val KEEP_WARM_INTERVAL_MS = 1000L
        private const val HAND_ACTIVE_HOLD_MS = 2000L
        private const val STUCK_FRAME_TIMEOUT_MS = 1000L
        private const val CAMERA_OFF_DELAY_MS = 1500L

        private const val GRID_W = 24
        private const val GRID_H = 18
        private const val MOTION_THRESHOLD = 4 // mean abs green-channel change, 0..255

        @Volatile
        var isRunning = false
            private set

        /** Human-readable status for the UI (last error or state). */
        @Volatile
        var status: String = "Stopped"
            private set

        /** Live hand-tracking readout for the UI. */
        @Volatile
        var handState: String = ""
            private set

        /** Speed readout for the UI: model time per frame and last pose-to-scroll delay. */
        @Volatile
        var speedInfo: String = ""
            private set

        @Volatile
        private var current: GestureCameraService? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GestureCameraService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GestureCameraService::class.java))
        }

        /** Re-check whether the camera should be on (e.g. "Instagram only" toggled). */
        fun onSettingsChanged() {
            current?.scheduleCameraUpdate()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var analysisExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraBound = false
    private var receiverRegistered = false
    @Volatile private var landmarker: HandLandmarker? = null
    @Volatile private var frameInFlightSince = 0L
    @Volatile private var lastHandSeenMs = 0L
    @Volatile private var frameAspect = 0.75f
    private var lastCheckMs = 0L
    private var lastModelRunMs = 0L
    private var started = false
    private var failed = false

    // Reused per-frame buffers (analysis thread only) to avoid garbage every frame.
    private var gridPrev = IntArray(GRID_W * GRID_H)
    private var gridCur = IntArray(GRID_W * GRID_H)
    private var hasPrevGrid = false
    private var sourceBitmap: Bitmap? = null
    private var rotatedBitmap: Bitmap? = null
    private val rotateMatrix = Matrix()

    private val trigger = PoseTrigger { pose, frameTimeMs ->
        mainHandler.postAtFrontOfQueue { performScroll(pose, frameTimeMs) }
    }
    @Volatile private var modelMsAvg = 0f
    @Volatile private var lastScrollDelayMs = -1L

    private val applyCameraTask = Runnable { applyCameraState() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = scheduleCameraUpdate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY

        if (!goForeground("Watching for ☝ one finger")) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        isRunning = true
        failed = false
        current = this
        status = "Starting…"

        analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                runnable.run()
            }, "gesture-analysis")
        }
        if (!createLandmarker()) {
            stopSelf()
            return START_NOT_STICKY
        }
        registerScreenReceiver()
        ReelScrollService.onForegroundChanged = { scheduleCameraUpdate() }
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
            .setMinHandDetectionConfidence(0.4f)
            .setMinHandPresenceConfidence(0.4f)
            .setMinTrackingConfidence(0.4f)
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

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Screen receiver not registered", e)
        }
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
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
                cameraProvider = future.get()

                val builder = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                lowFpsRange()?.let {
                    Camera2Interop.Extender(builder)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                }
                val analysis = builder.build()
                analysisExecutor?.let { analysis.setAnalyzer(it, ::analyzeFrame) }
                imageAnalysis = analysis

                cameraProvider?.unbindAll()
                applyCameraState()
            } catch (e: Exception) {
                failAndStop("Could not open front camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** The lowest supported front-camera frame-rate range that still reaches 15 fps. */
    private fun lowFpsRange(): Range<Int>? = try {
        val manager = getSystemService(CameraManager::class.java)
        val frontId = manager?.cameraIdList?.firstOrNull {
            manager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
        frontId?.let { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.upper >= 15 }
                ?.minWithOrNull(compareBy<Range<Int>>({ it.upper }, { -it.lower }))
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read fps ranges", e)
        null
    }

    /** Camera is only needed while the screen is on, unlocked, and (optionally) on Instagram. */
    private fun wantCamera(): Boolean {
        if (!isRunning) return false
        if (getSystemService(PowerManager::class.java)?.isInteractive == false) return false
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return false
        if (!Prefs.instagramOnly(this)) return true
        val fg = ReelScrollService.foregroundPackage ?: return true
        return fg == Prefs.INSTAGRAM_PACKAGE
    }

    fun scheduleCameraUpdate() {
        mainHandler.removeCallbacks(applyCameraTask)
        // Turn on immediately; turn off after a short delay so quick app switches don't thrash the camera.
        mainHandler.postDelayed(applyCameraTask, if (wantCamera()) 0L else CAMERA_OFF_DELAY_MS)
    }

    private fun applyCameraState() {
        val provider = cameraProvider ?: return
        val analysis = imageAnalysis ?: return
        try {
            if (wantCamera()) {
                if (!cameraBound) {
                    val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                    camera.cameraInfo.cameraState.removeObservers(this)
                    camera.cameraInfo.cameraState.observe(this) { state -> onCameraState(state) }
                    cameraBound = true
                    updateNotification("Watching for ☝ one finger")
                }
            } else if (cameraBound) {
                provider.unbindAll()
                cameraBound = false
                status = "Paused — camera off until Instagram is open"
                handState = ""
                updateNotification("Paused — camera off")
            } else if (status == "Starting…") {
                status = "Paused — camera off until Instagram is open"
            }
        } catch (e: Exception) {
            failAndStop("Could not open front camera: ${e.message}")
        }
    }

    private fun onCameraState(state: CameraState) {
        if (!cameraBound) return
        val error = state.error
        status = when {
            error?.code == CameraState.ERROR_CAMERA_IN_USE ||
                error?.code == CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                // Another app has the camera. CameraX reconnects automatically when it is free.
                "Camera busy in another app — waiting…"
            error?.code == CameraState.ERROR_CAMERA_DISABLED ->
                "Camera disabled by device policy"
            error != null -> "Camera error ${error.code} — retrying…"
            state.type == CameraState.Type.OPEN -> "Running — show ☝ one finger to scroll"
            state.type == CameraState.Type.OPENING ||
                state.type == CameraState.Type.PENDING_OPEN -> "Opening camera…"
            else -> status
        }
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        try {
            val now = SystemClock.uptimeMillis()
            val handActive = now - lastHandSeenMs < HAND_ACTIVE_HOLD_MS
            if (now - lastCheckMs < if (handActive) ACTIVE_FRAME_INTERVAL_MS else IDLE_FRAME_INTERVAL_MS) return
            lastCheckMs = now

            // Cheap gate: with no hand around, only wake the model when something moves.
            val moved = motionDetected(proxy)
            if (!handActive && !moved && now - lastModelRunMs < KEEP_WARM_INTERVAL_MS) return

            val inFlight = frameInFlightSince
            if (inFlight != 0L && now - inFlight < STUCK_FRAME_TIMEOUT_MS) return // model still busy
            val model = landmarker ?: return

            val bitmap = uprightBitmap(proxy)
            frameAspect = bitmap.width.toFloat() / bitmap.height
            frameInFlightSince = now
            lastModelRunMs = now
            model.detectAsync(BitmapImageBuilder(bitmap).build(), now)
        } catch (e: Throwable) {
            Log.w(TAG, "Frame skipped", e)
            frameInFlightSince = 0L
        } finally {
            proxy.close()
        }
    }

    /** Compares a coarse grid of green-channel samples with the previous check. */
    private fun motionDetected(proxy: ImageProxy): Boolean {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = proxy.width
        val h = proxy.height
        var diff = 0L
        var i = 0
        for (gy in 0 until GRID_H) {
            val row = ((gy * 2 + 1) * h / (GRID_H * 2)) * rowStride
            for (gx in 0 until GRID_W) {
                val x = (gx * 2 + 1) * w / (GRID_W * 2)
                val value = buffer.get(row + x * pixelStride + 1).toInt() and 0xFF
                gridCur[i] = value
                diff += abs(value - gridPrev[i])
                i++
            }
        }
        val hadPrev = hasPrevGrid
        val swap = gridPrev
        gridPrev = gridCur
        gridCur = swap
        hasPrevGrid = true
        return !hadPrev || diff / (GRID_W * GRID_H) > MOTION_THRESHOLD
    }

    /** Upright RGBA bitmap of the frame, drawn into reused bitmaps. */
    private fun uprightBitmap(proxy: ImageProxy): Bitmap {
        val w = proxy.width
        val h = proxy.height
        val plane = proxy.planes[0]
        val source = if (plane.pixelStride == 4 && plane.rowStride == w * 4) {
            val bmp = sourceBitmap?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { sourceBitmap = it }
            plane.buffer.rewind()
            bmp.copyPixelsFromBuffer(plane.buffer)
            bmp
        } else {
            proxy.toBitmap()
        }

        val rotation = proxy.imageInfo.rotationDegrees
        if (rotation == 0) return source
        val (outW, outH) = if (rotation == 90 || rotation == 270) h to w else w to h
        val out = rotatedBitmap?.takeIf { it.width == outW && it.height == outH }
            ?: Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also { rotatedBitmap = it }
        rotateMatrix.reset()
        rotateMatrix.postRotate(rotation.toFloat(), w / 2f, h / 2f)
        rotateMatrix.postTranslate((outW - w) / 2f, (outH - h) / 2f)
        Canvas(out).drawBitmap(source, rotateMatrix, null)
        return out
    }

    private fun onHandResult(result: HandLandmarkerResult) {
        frameInFlightSince = 0L
        try {
            val time = result.timestampMs()
            val modelMs = (SystemClock.uptimeMillis() - time).toFloat()
            modelMsAvg = if (modelMsAvg == 0f) modelMs else modelMsAvg * 0.8f + modelMs * 0.2f
            updateSpeedInfo()
            val hand = result.landmarks().firstOrNull()
            if (hand == null || hand.size < 21) {
                trigger.update(time, null)
                handState = "No hand in view"
                return
            }
            lastHandSeenMs = SystemClock.uptimeMillis()
            val detection = classifyPose(hand)
            var pose = detection?.pose
            if (pose == PoseTrigger.Pose.TWO_FINGERS && !Prefs.allowPrevious(this)) pose = null
            // Find Instagram's reels pager ahead of time so the scroll itself is instant.
            if (pose != null && !trigger.waitingForRelease) {
                mainHandler.post { ReelScrollService.instance?.prepare() }
            }
            trigger.update(time, pose, detection?.strong == true)
            handState = when {
                pose == null -> "Hand seen — show ☝ one finger"
                trigger.waitingForRelease -> "Done — lower your hand to scroll again"
                pose == PoseTrigger.Pose.ONE_FINGER -> "☝ One finger — scrolling…"
                else -> "✌ Two fingers — going back…"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad hand result", e)
        }
    }

    private class Detection(val pose: PoseTrigger.Pose, val strong: Boolean)

    /**
     * ☝ index up with middle/ring/pinky folded, or ✌ index + middle up with ring/pinky folded.
     * The thumb is ignored. Fingers must point roughly upward. "Strong" means every finger
     * is clearly extended or clearly folded, which is safe to act on from a single frame.
     */
    private fun classifyPose(hand: List<NormalizedLandmark>): Detection? {
        val aspect = frameAspect
        fun distFromWrist(i: Int) =
            hypot((hand[i].x() - hand[0].x()) * aspect, hand[i].y() - hand[0].y())
        // tip-to-wrist vs knuckle-to-wrist: >1 when the finger is straight, <1 when curled.
        fun ratio(tip: Int, pip: Int) = distFromWrist(tip) / distFromWrist(pip).coerceAtLeast(1e-4f)
        fun pointsUp(tip: Int, mcp: Int) = hand[tip].y() < hand[mcp].y()

        val index = ratio(8, 6)
        val middle = ratio(12, 10)
        val ring = ratio(16, 14)
        val pinky = ratio(20, 18)
        if (index <= 1.2f || !pointsUp(8, 5) || ring >= 1.1f || pinky >= 1.1f) return null
        val othersClearlyFolded = ring < 0.95f && pinky < 0.95f
        return when {
            middle < 1.1f -> Detection(
                PoseTrigger.Pose.ONE_FINGER,
                strong = index > 1.35f && middle < 0.95f && othersClearlyFolded,
            )
            middle > 1.2f && pointsUp(12, 9) -> Detection(
                PoseTrigger.Pose.TWO_FINGERS,
                strong = index > 1.35f && middle > 1.35f && othersClearlyFolded,
            )
            else -> null
        }
    }

    private fun performScroll(pose: PoseTrigger.Pose, frameTimeMs: Long) {
        val scroller = ReelScrollService.instance
        if (scroller == null) {
            status = "Accessibility service is off — enable it in the app"
            return
        }
        val next = pose == PoseTrigger.Pose.ONE_FINGER
        if (scroller.scroll(next, Prefs.instagramOnly(this), Prefs.scrollBySwipe(this))) {
            lastScrollDelayMs = SystemClock.uptimeMillis() - frameTimeMs
            updateSpeedInfo()
            handState = if (next) "Scrolled to next reel" else "Scrolled to previous reel"
        }
    }

    private fun updateSpeedInfo() {
        val last = lastScrollDelayMs
        speedInfo = "Model ${modelMsAvg.toInt()} ms/frame" +
            if (last >= 0) " · last scroll ${last} ms after the frame" else ""
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
        if (current === this) current = null
        ReelScrollService.onForegroundChanged = null
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        mainHandler.removeCallbacksAndMessages(null)
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Unbind failed", e)
        }
        cameraBound = false
        cameraProvider = null
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
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
        handState = ""
        speedInfo = ""
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
            .setContentTitle("Reel Gesture")
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
