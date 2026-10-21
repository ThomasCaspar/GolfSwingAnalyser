package com.golfswing.analyzer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.File
import kotlin.math.abs
import kotlin.math.atan

/** Capacités détectées sur la caméra arrière. */
data class CameraCaps(
    val cameraId: String,
    val sensorOrientation: Int,
    val previewSize: Size,          // buffer de prévisualisation (orientation capteur)
    val analysisSize: Size,         // buffer YUV pour la détection du geste
    val previewFpsRange: Range<Int>,// 60 fps si disponible
    val slowMoSupported: Boolean,   // true = session haute vitesse disponible
    val recordSize: Size,           // taille d'enregistrement (haute vitesse ou standard)
    val recordFps: Int,             // fps réel de capture (240 / 120 / 60)
    val recordFpsRange: Range<Int>,
    val focalPxRatio: Double,       // f (px) = focalPxRatio * largeur de l'image
    val hfovDeg: Double,
    val flashAvailable: Boolean     // lampe utilisable en mode torche
) {
    val summary: String
        get() = buildString {
            append("Preview ${previewFpsRange.upper} fps · ")
            append(if (slowMoSupported) "Slow-mo ${recordFps} fps (${recordSize.width}x${recordSize.height})"
                   else "Pas de slow-mo matériel : ${recordFps} fps")
        }
}

object CameraDetector {

    private const val TAG = "GolfCam"

    /**
     * Copie des capacités avec une cadence d'enregistrement choisie (60, 120 ou 240 fps).
     * Retourne null si l'appareil ne propose pas cette cadence en haute vitesse.
     */
    fun withRecordFps(context: Context, base: CameraCaps, targetFps: Int): CameraCaps? {
        if (targetFps <= 60) {
            return base.copy(
                slowMoSupported = false,
                recordSize = base.previewSize,
                recordFps = base.previewFpsRange.upper,
                recordFpsRange = base.previewFpsRange
            )
        }
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val map = mgr.getCameraCharacteristics(base.cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        var picked: Pair<Size, Range<Int>>? = null
        for (size in map.highSpeedVideoSizes) {
            // Session longue : 1280 px au plus pour garder des fichiers raisonnables
            if (size.width > 1280) continue
            for (r in map.getHighSpeedVideoFpsRangesFor(size)) {
                if (r.lower != targetFps || r.upper != targetFps) continue
                val cur = picked
                val area = size.width.toLong() * size.height
                if (cur == null || area > cur.first.width.toLong() * cur.first.height) {
                    picked = Pair(size, r)
                }
            }
        }
        val pick = picked ?: return null
        return base.copy(
            slowMoSupported = true,
            recordSize = pick.first,
            recordFps = targetFps,
            recordFpsRange = pick.second
        )
    }

    fun detect(context: Context): CameraCaps? {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: return null
        val ch = mgr.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        // Prévisualisation : <= 1280 px de large, le plus proche du 16:9
        val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= 1280 }
            .minByOrNull { abs(it.width.toFloat() / it.height - 16f / 9f) * 1000f - it.width }
            ?: Size(1280, 720)

        // Analyse du geste : petit buffer YUV
        val analysisSize = map.getOutputSizes(ImageFormat.YUV_420_888)
            .filter { it.width in 320..640 }
            .minByOrNull { it.width } ?: Size(320, 240)

        // 60 fps pour la prévisualisation
        val aeRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf()
        val previewRange = aeRanges.filter { it.upper == 60 }.maxByOrNull { it.lower }
            ?: aeRanges.maxByOrNull { it.upper }
            ?: Range(30, 30)

        // Haute vitesse : on privilégie 240 fps, sinon 120 fps, en limitant à 1920 px
        var hsSize: Size? = null
        var hsRange: Range<Int>? = null
        var hsFps = 0
        for (size in map.highSpeedVideoSizes) {
            val ranges = map.getHighSpeedVideoFpsRangesFor(size)
            Log.i(TAG, "Haute vitesse $size : ${ranges.toList()}")
            if (size.width > 1920) continue
            for (r in ranges) {
                // Plage fixe obligatoire (lower == upper) : sinon le capteur peut tomber à 20-30 fps
                if (r.lower != r.upper) continue
                val fps = minOf(r.upper, 240)
                if (fps < 120) continue
                val area = size.width.toLong() * size.height
                val bestArea = hsSize?.let { it.width.toLong() * it.height } ?: 0L
                if (fps > hsFps || (fps == hsFps && area > bestArea)) {
                    hsFps = fps; hsSize = size; hsRange = r
                }
            }
        }

        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.toDouble() ?: 4.0
        val sensorW = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width?.toDouble() ?: 6.0
        val hfov = Math.toDegrees(2 * atan(sensorW / (2 * focal)))
        val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

        return if (hsSize != null && hsRange != null) {
            CameraCaps(
                cameraId = id, sensorOrientation = sensorOrientation,
                previewSize = previewSize, analysisSize = analysisSize,
                previewFpsRange = previewRange, slowMoSupported = true,
                recordSize = hsSize, recordFps = hsFps, recordFpsRange = hsRange,
                focalPxRatio = focal / sensorW, hfovDeg = hfov, flashAvailable = flash
            )
        } else {
            // Repli : enregistrement standard à 60 fps (ralenti logiciel)
            CameraCaps(
                cameraId = id, sensorOrientation = sensorOrientation,
                previewSize = previewSize, analysisSize = analysisSize,
                previewFpsRange = previewRange, slowMoSupported = false,
                recordSize = previewSize, recordFps = previewRange.upper,
                recordFpsRange = previewRange,
                focalPxRatio = focal / sensorW, hfovDeg = hfov, flashAvailable = flash
            )
        }
    }
}

/**
 * Gère la caméra : preview 60 fps + analyse (geste), puis enregistrement
 * en haute vitesse (ConstrainedHighSpeedCaptureSession) ou standard en repli.
 */
class CameraController(private val context: Context, val caps: CameraCaps) {

    private val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var analysisReader: ImageReader? = null

    // Requête répétée courante, reconstruite pour allumer / éteindre la lampe
    private var repeatFn: ((Boolean) -> Unit)? = null
    private var flashOn = false

    // Mode réellement utilisé pour le dernier enregistrement (lu par l'écran principal)
    var usedHighSpeed = false
    var usedFps = 0

    /** Allume ou éteint la lampe (torche) sans recréer la session. */
    fun setFlash(on: Boolean) {
        flashOn = on
        val h = handler ?: return
        h.post { try { repeatFn?.invoke(on) } catch (_: Exception) {} }
    }

    private fun applyFlash(b: CaptureRequest.Builder, on: Boolean) {
        if (!caps.flashAvailable) return
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        b.set(
            CaptureRequest.FLASH_MODE,
            if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
    }

    fun start() {
        val t = HandlerThread("CameraThread").also { it.start() }
        thread = t
        handler = Handler(t.looper)
    }

    fun stop() {
        closeSession()
        device?.close(); device = null
        thread?.quitSafely(); thread = null; handler = null
    }

    @SuppressLint("MissingPermission")
    fun open(onReady: () -> Unit, onError: (String) -> Unit) {
        val h = handler ?: return
        mgr.openCamera(caps.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { device = camera; onReady() }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close(); device = null; onError("Erreur caméra (code $error)")
            }
        }, h)
    }

    /** Prévisualisation 60 fps + flux YUV vers [listener] (détection du geste). */
    fun startPreview(texture: SurfaceTexture, listener: ImageReader.OnImageAvailableListener, onError: (String) -> Unit) {
        val dev = device ?: return
        val h = handler ?: return
        closeSession()
        texture.setDefaultBufferSize(caps.previewSize.width, caps.previewSize.height)
        val previewSurface = Surface(texture)
        val reader = ImageReader.newInstance(
            caps.analysisSize.width, caps.analysisSize.height, ImageFormat.YUV_420_888, 3
        )
        reader.setOnImageAvailableListener(listener, h)
        analysisReader = reader

        dev.createCaptureSession(listOf(previewSurface, reader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                try {
                    repeatFn = { flash ->
                        val r = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, caps.previewFpsRange)
                            applyFlash(this, flash)
                        }.build()
                        s.setRepeatingRequest(r, null, h)
                    }
                    repeatFn?.invoke(flashOn)
                } catch (e: Exception) {
                    onError("Preview : ${e.message}")
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) = onError("Configuration preview échouée")
        }, h)
    }

    /**
     * Démarre l'enregistrement dans [output].
     * Essaie d'abord la session haute vitesse SANS prévisualisation (seule la surface de l'encodeur
     * est branchée), puis bascule en 60 fps standard si l'appareil refuse la configuration.
     */
    fun startRecording(
        texture: SurfaceTexture,
        output: File,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
        allowHighSpeed: Boolean = true
    ) {
        if (caps.slowMoSupported && allowHighSpeed) {
            beginRecording(texture, output, highSpeed = true, onStarted = onStarted) { reason ->
                Log.w("GolfCam", "Haute vitesse refusée ($reason) : repli en 60 fps")
                beginRecording(texture, output, highSpeed = false, onStarted = onStarted, onFail = onError)
            }
        } else {
            beginRecording(texture, output, highSpeed = false, onStarted = onStarted, onFail = onError)
        }
    }

    private fun beginRecording(
        texture: SurfaceTexture,
        output: File,
        highSpeed: Boolean,
        onStarted: () -> Unit,
        onFail: (String) -> Unit
    ) {
        val dev = device ?: return onFail("Caméra non ouverte")
        val h = handler ?: return onFail("Thread caméra absent")
        closeSession()
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null

        val size = if (highSpeed) caps.recordSize else caps.previewSize
        val fps = if (highSpeed) caps.recordFps else caps.previewFpsRange.upper
        val range = if (highSpeed) caps.recordFpsRange else caps.previewFpsRange

        val rec = try {
            buildRecorder(output, size, fps)
        } catch (e: Exception) {
            return onFail("MediaRecorder : ${e.message}")
        }
        recorder = rec
        val surfaces = if (highSpeed) {
            listOf(rec.surface)                        // haute vitesse : pas d'aperçu
        } else {
            texture.setDefaultBufferSize(size.width, size.height)
            listOf(Surface(texture), rec.surface)      // standard : aperçu + encodeur
        }

        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                try {
                    repeatFn = { flash ->
                        val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            surfaces.forEach { addTarget(it) }
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                            applyFlash(this, flash)
                        }.build()
                        if (highSpeed) {
                            val hs = s as CameraConstrainedHighSpeedCaptureSession
                            hs.setRepeatingBurst(hs.createHighSpeedRequestList(builder), null, h)
                        } else {
                            s.setRepeatingRequest(builder, null, h)
                        }
                    }
                    repeatFn?.invoke(flashOn)
                    rec.start()
                    usedHighSpeed = highSpeed
                    usedFps = fps
                    onStarted()
                } catch (e: Exception) {
                    onFail("Enregistrement : ${e.message}")
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                onFail("Session refusée (haute vitesse = $highSpeed)")
            }
        }

        try {
            if (highSpeed) dev.createConstrainedHighSpeedCaptureSession(surfaces, cb, h)
            else dev.createCaptureSession(surfaces, cb, h)
        } catch (e: Exception) {
            onFail("Création de session : ${e.message}")
        }
    }

    /** Arrête l'enregistrement. Retourne false si le fichier est probablement invalide. */
    fun stopRecording(): Boolean {
        var ok = true
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { recorder?.stop() } catch (_: Exception) { ok = false }
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        closeSession()
        return ok
    }

    private fun buildRecorder(out: File, size: Size, fps: Int): MediaRecorder {
        val bitrate = when {
            fps >= 240 -> 40_000_000
            fps >= 120 -> 25_000_000
            else -> 16_000_000
        }
        @Suppress("DEPRECATION")
        return MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(out.absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(size.width, size.height)
            setVideoFrameRate(fps)
            setVideoEncodingBitRate(bitrate)
            // Caméra arrière, téléphone en portrait : rotation pour une lecture droite
            setOrientationHint(caps.sensorOrientation)
            prepare()
        }
    }

    private fun closeSession() {
        repeatFn = null
        try { session?.close() } catch (_: Exception) {}
        session = null
        analysisReader?.close()
        analysisReader = null
    }
}
