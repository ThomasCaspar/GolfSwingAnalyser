package com.golfswing.analyzer

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.golfswing.analyzer.databinding.ActivitySessionBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enregistrement continu d'une session complète.
 * Qualité choisie par l'utilisateur : 60, 120 ou 240 fps (haute vitesse, aperçu coupé pendant l'enregistrement).
 * À l'arrêt, la vidéo est copiée dans Movies/GolfSwing, puis l'utilisateur choisit d'analyser la session.
 */
class SessionActivity : AppCompatActivity() {

    private companion object {
        const val PREFS = "settings"
        const val KEY_FPS = "session_fps"
        val FPS_CHOICES = intArrayOf(60, 120, 240)
        // Estimation de la taille, en Mo par minute, pour 720p
        val MB_PER_MIN = mapOf(60 to 120, 120 to 190, 240 to 300)
    }

    private lateinit var b: ActivitySessionBinding
    private var caps: CameraCaps? = null
    private var controller: CameraController? = null
    private var recording = false
    private var recordStart = 0L
    private var file: File? = null
    private var fpsChoice = 120
    private val ui = Handler(Looper.getMainLooper())
    private var club = Clubs.UNSPECIFIED     // demandé une fois au début de la session
    private var clubChosen = false
    private lateinit var framingGestures: GestureDetector

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCameraIfReady() else b.tvSessionStatus.text = getString(R.string.no_camera_permission)
    }

    /** Pas d'analyse de mouvement pendant la session : on libère simplement les images. */
    private val noopListener = ImageReader.OnImageAvailableListener { reader ->
        reader.acquireLatestImage()?.close()
    }

    private val timerTick = object : Runnable {
        override fun run() {
            val s = (System.currentTimeMillis() - recordStart) / 1000
            b.tvSessionTimer.text = String.format(Locale.FRANCE, "● REC  %02d:%02d", s / 60, s % 60)
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        fpsChoice = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_FPS, 120)
        caps = CameraDetector.detect(this)
        b.tvSessionStatus.text = if (caps == null) "Aucune caméra arrière détectée." else getString(R.string.session_ready)
        updateQualityLabel()

        b.btnSessionToggle.setOnClickListener { if (recording) stopSession(restart = true) else requestStart() }
        // Appui = verrouille le cadrage sur la zone touchée ; appui long = libère le cadrage
        framingGestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { lockFraming(e.x, e.y); return true }
            override fun onLongPress(e: MotionEvent) {
                controller?.unlock()
                b.tvSessionStatus.text = "Cadrage libre"
            }
        })
        b.textureView.setOnTouchListener { _, e -> framingGestures.onTouchEvent(e); true }
        b.btnSessionQuality.setOnClickListener { cycleQuality() }

        b.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startCameraIfReady()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    override fun onResume() {
        super.onResume()
        startCameraIfReady()
    }

    override fun onPause() {
        // Quitter l'écran arrête proprement la session en cours
        if (recording) stopSession(restart = false)
        ui.removeCallbacksAndMessages(null)
        controller?.stop()
        controller = null
        super.onPause()
    }

    // ------------------------------------------------------------------
    // Qualité d'enregistrement
    // ------------------------------------------------------------------

    private fun cycleQuality() {
        if (recording) return
        val i = FPS_CHOICES.indexOf(fpsChoice)
        fpsChoice = FPS_CHOICES[(i + 1) % FPS_CHOICES.size]
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_FPS, fpsChoice).apply()
        updateQualityLabel()
        // La configuration caméra change : on rouvre la caméra avec la nouvelle cadence
        controller?.stop()
        controller = null
        startCameraIfReady()
    }

    private fun updateQualityLabel() {
        val mb = MB_PER_MIN[fpsChoice] ?: 0
        b.btnSessionQuality.text = "Qualité : $fpsChoice fps · ≈ $mb Mo/min · toucher pour changer"
    }

    /** Capacités pour la cadence choisie ; repli sur 60 fps si l'appareil ne la propose pas. */
    private fun recordCaps(): CameraCaps? {
        val base = caps ?: return null
        CameraDetector.withRecordFps(this, base, fpsChoice)?.let { return it }
        Toast.makeText(this, "$fpsChoice fps indisponible sur cet appareil : 60 fps", Toast.LENGTH_LONG).show()
        return CameraDetector.withRecordFps(this, base, 60)
    }

    // ------------------------------------------------------------------
    // Caméra
    // ------------------------------------------------------------------

    private fun startCameraIfReady() {
        val c = recordCaps() ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (!b.textureView.isAvailable || controller != null) return

        val ctl = CameraController(this, c)
        controller = ctl
        ctl.start()
        ctl.open(
            onReady = {
                runOnUiThread {
                    val st = b.textureView.surfaceTexture ?: return@runOnUiThread
                    ctl.startPreview(st, noopListener) { msg -> runOnUiThread { b.tvSessionStatus.text = msg } }
                }
            },
            onError = { msg -> runOnUiThread { b.tvSessionStatus.text = msg } }
        )
    }

    /** Le club est demandé une seule fois par session, au premier démarrage. */
    private fun requestStart() {
        if (clubChosen) {
            startSession()
            return
        }
        Clubs.choose(this, club, onPick = {
            club = it
            clubChosen = true
            startSession()
        })
    }

    private fun lockFraming(x: Float, y: Float) {
        val c = controller ?: return
        if (b.textureView.width == 0 || b.textureView.height == 0) return
        c.lockAt(x / b.textureView.width, y / b.textureView.height)
        b.tvSessionStatus.text = "Cadrage verrouillé sur la zone touchée (appui long pour libérer)"
    }

    private fun startSession() {
        val ctl = controller ?: return
        val st = b.textureView.surfaceTexture ?: return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(filesDir, "session_${Clubs.slug(club)}_$stamp.mp4")
        file = f

        // En haute vitesse, la session n'a pas d'aperçu : on le signale à l'utilisateur
        val highSpeed = ctl.caps.slowMoSupported
        ctl.startRecording(
            texture = st,
            output = f,
            onStarted = {
                runOnUiThread {
                    recording = true
                    recordStart = System.currentTimeMillis()
                    ui.post(timerTick)
                    b.btnSessionToggle.text = getString(R.string.session_stop)
                    b.btnSessionToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF3D00"))
                    b.btnSessionQuality.isEnabled = false
                    b.tvSessionStatus.text = if (highSpeed)
                        "${getString(R.string.session_recording)} Aperçu coupé pour garder la cadence de $fpsChoice fps."
                    else
                        getString(R.string.session_recording)
                }
            },
            onError = { msg -> runOnUiThread { b.tvSessionStatus.text = msg } },
            allowHighSpeed = true
        )
    }

    /** Arrête l'enregistrement, copie la vidéo dans Movies/GolfSwing et propose l'analyse. */
    private fun stopSession(restart: Boolean) {
        if (!recording) return
        recording = false
        ui.removeCallbacks(timerTick)

        val ctl = controller
        val ok = ctl?.stopRecording() ?: false
        ctl?.stop()
        controller = null

        b.btnSessionToggle.text = getString(R.string.session_start)
        b.btnSessionToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
        b.btnSessionQuality.isEnabled = true

        val f = file
        if (!ok || f == null || !f.exists() || f.length() == 0L) {
            b.tvSessionStatus.text = "Enregistrement invalide, réessayez."
            if (restart) startCameraIfReady()
            return
        }

        b.tvSessionStatus.text = getString(R.string.session_saving)
        Thread {
            val saved = saveToGallery(f)
            runOnUiThread {
                if (restart) {
                    b.tvSessionStatus.text = if (saved) "Session enregistrée dans Movies/GolfSwing." else getString(R.string.save_failed)
                    startCameraIfReady()
                    askAnalyze(f)
                } else {
                    Toast.makeText(this, if (saved) "Session enregistrée" else getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun askAnalyze(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Session enregistrée")
            .setMessage("Voulez-vous compter les swings et tracer chaque balle ?")
            .setPositiveButton("Analyser") { _, _ ->
                startActivity(Intent(this, SessionAnalysisActivity::class.java).apply {
                    putExtra(SessionAnalysisActivity.EXTRA_VIDEO, f.absolutePath)
                    putExtra(SessionAnalysisActivity.EXTRA_FOCAL, caps?.focalPxRatio ?: 0.75)
                    putExtra(SessionAnalysisActivity.EXTRA_CLUB, club)
                })
                finish()
            }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    /** Copie la vidéo dans Movies/GolfSwing (visible dans la galerie). */
    private fun saveToGallery(f: File): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/GolfSwing")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return try {
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } } ?: return false
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            false
        }
    }
}
