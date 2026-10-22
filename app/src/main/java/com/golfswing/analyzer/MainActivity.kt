package com.golfswing.analyzer

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.ImageReader
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.golfswing.analyzer.databinding.ActivityMainBinding
import java.io.File
import java.util.Locale

/**
 * Flux : Prévisualisation -> (signe de la main OU bouton / télécommande)
 *        -> décompte 3-2-1 annulable -> enregistrement 18 s (flash clignotant) -> revue.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val RECORD_MS = 18_000L
        const val COUNTDOWN_S = 3
        private const val BLINK_ON_MS = 350L
        private const val BLINK_OFF_MS = 650L
        private const val PREFS = "settings"
        private const val KEY_CLUB = "last_club"

        /** Touches envoyées par les télécommandes Bluetooth (déclencheur d'appareil photo) et le volume. */
        private val TRIGGER_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_FOCUS,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE
        )
    }

    private enum class Phase { PREVIEW, COUNTDOWN, RECORDING }

    private lateinit var b: ActivityMainBinding
    private var caps: CameraCaps? = null
    private var controller: CameraController? = null
    private var phase = Phase.PREVIEW
    private var countdownLeft = COUNTDOWN_S
    private var recordStart = 0L
    private var pendingFile: File? = null
    private var blinkOn = false
    private var toneGen: ToneGenerator? = null
    private var handAnim: ObjectAnimator? = null
    private val ui = Handler(Looper.getMainLooper())

    private var club = Clubs.UNSPECIFIED            // club du prochain tir, réglé sur l'écran principal
    private var choosing = false                    // la liste des clubs est ouverte
    @Volatile private var ballInFrame = false       // balle reconnue dans le cadre virtuel
    private var frameCount = 0
    private lateinit var framingGestures: GestureDetector

    private val wave = WaveDetector(
        onWave = { runOnUiThread { if (phase == Phase.PREVIEW && ballInFrame) requestCountdown() } },
        onProgress = { count, needed -> runOnUiThread { updateMeter(count, needed) } }
    )

    private val analysisListener = ImageReader.OnImageAvailableListener { reader ->
        val img = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            wave.process(img)
            // Balle dans le cadre ? Une image sur trois suffit, la zone est petite
            frameCount++
            if (frameCount % 3 == 0) checkBallInFrame(img)
        } finally {
            img.close()
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCameraIfReady() else b.tvStatus.text = getString(R.string.no_camera_permission)
    }

    private val countdownStep = Runnable { doCountdownStep() }

    private val timerTick = object : Runnable {
        override fun run() {
            val left = (RECORD_MS - (System.currentTimeMillis() - recordStart)).coerceAtLeast(0L)
            b.tvTimer.text = String.format(Locale.FRANCE, "● REC  %.1f s", left / 1000.0)
            if (left <= 0L) stopRecording() else ui.postDelayed(this, 100)
        }
    }

    /** Le flash clignote pendant l'enregistrement pour signaler au golfeur que la caméra tourne. */
    private val blinkTick = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            setLight(blinkOn)
            ui.postDelayed(this, if (blinkOn) BLINK_ON_MS else BLINK_OFF_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        toneGen = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) } catch (_: Exception) { null }

        caps = CameraDetector.detect(this)
        val c = caps
        if (c == null) {
            b.tvStatus.text = "Aucune caméra arrière détectée."
        } else {
            // Capacités détectées, affichées sous le conseil (ex. « Slow-mo 240 fps (1280x720) »)
            b.tvTip.text = "${getString(R.string.tip_manual)}\n${c.summary}"
            if (!c.slowMoSupported) Toast.makeText(this, R.string.no_slowmo, Toast.LENGTH_LONG).show()
        }

        b.btnRecord.setOnClickListener { onManualTrigger() }
        club = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CLUB, Clubs.UNSPECIFIED) ?: Clubs.UNSPECIFIED
        updateClubButton()
        b.btnClub.setOnClickListener { chooseClub() }
        // Toucher l'écran : pendant le décompte = annulation, sinon verrouille le cadrage sur la zone touchée
        framingGestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { onFramingTap(e.x, e.y); return true }
            override fun onLongPress(e: MotionEvent) { onFramingLongPress() }
        })
        b.textureView.setOnTouchListener { _, e -> framingGestures.onTouchEvent(e); true }

        b.btnSensitivity.setOnClickListener {
            val values = WaveDetector.Level.values()
            wave.level = values[(wave.level.ordinal + 1) % values.size]
            b.btnSensitivity.text = getString(R.string.sensitivity, wave.level.label)
        }
        b.btnSensitivity.text = getString(R.string.sensitivity, wave.level.label)

        b.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startCameraIfReady()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    override fun onResume() {
        super.onResume()
        phase = Phase.PREVIEW
        wave.enabled = true
        wave.reset()
        wave.armFor(2000)   // laisse le temps de poser le téléphone sans déclencher
        renderPhase()
        startCameraIfReady()
    }

    override fun onPause() {
        when (phase) {
            Phase.RECORDING -> stopRecording()
            Phase.COUNTDOWN -> cancelCountdown()
            Phase.PREVIEW -> Unit
        }
        ui.removeCallbacksAndMessages(null)
        stopHandAnim()
        controller?.stop()
        controller = null
        super.onPause()
    }

    override fun onDestroy() {
        toneGen?.release()
        toneGen = null
        super.onDestroy()
    }

    /** Télécommande Bluetooth, bouton volume, bouton caméra : déclenchement manuel. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in TRIGGER_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onManualTrigger()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onManualTrigger() {
        when (phase) {
            Phase.PREVIEW -> requestCountdown()
            Phase.COUNTDOWN -> cancelCountdown()
            Phase.RECORDING -> stopRecording()
        }
    }

    // ------------------------------------------------------------------
    // Caméra
    // ------------------------------------------------------------------

    private fun startCameraIfReady() {
        val c = caps ?: return
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
                    ctl.startPreview(st, analysisListener) { msg -> runOnUiThread { b.tvStatus.text = msg } }
                }
            },
            onError = { msg -> runOnUiThread { b.tvStatus.text = msg } }
        )
    }

    // ------------------------------------------------------------------
    // Décompte (retardateur)
    // ------------------------------------------------------------------

    /** Choix du club sur l'écran principal, avant de se placer ; retenu pour les tirs suivants. */
    private fun chooseClub() {
        if (phase != Phase.PREVIEW || choosing) return
        choosing = true
        wave.enabled = false
        Clubs.choose(
            this, club,
            onPick = {
                choosing = false
                club = it
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_CLUB, club).apply()
                updateClubButton()
                wave.enabled = true
                wave.reset()
                wave.armFor(1000)
            },
            onCancel = {
                choosing = false
                wave.enabled = true
                wave.reset()
                wave.armFor(1000)
            }
        )
    }

    private fun updateClubButton() {
        b.btnClub.text = "Club : $club"
    }

    /** Déclenchement d'un tir : décompte direct, le club a déjà été choisi. */
    private fun requestCountdown() {
        if (controller == null || choosing) return
        startCountdown()
    }

    /** Balle reconnue dans le cadre virtuel : le cadre passe au vert. */
    private fun checkBallInFrame(img: android.media.Image) {
        val w = img.width
        val h = img.height
        val blobs = SwingAnalyzer.findBallBlobs(
            img,
            (FrameGuideView.X0 * w).toInt(), (FrameGuideView.Y0 * h).toInt(),
            (FrameGuideView.X1 * w).toInt(), (FrameGuideView.Y1 * h).toInt(),
            2, 0.006 * w, 0.05 * w
        )
        val found = blobs.any { it.score >= 0.5 }
        if (found != ballInFrame) {
            ballInFrame = found
            runOnUiThread { b.frameGuide.setFound(found) }
        }
    }

    /** Appui sur l'écran : verrouille le cadrage sur la zone touchée. */
    private fun onFramingTap(x: Float, y: Float) {
        if (phase == Phase.COUNTDOWN) {
            cancelCountdown()
            return
        }
        if (phase != Phase.PREVIEW || b.textureView.width == 0) return
        controller?.lockAt(x / b.textureView.width, y / b.textureView.height)
        b.tvStatus.text = "Cadrage verrouillé (appui long pour libérer)"
    }

    private fun onFramingLongPress() {
        if (phase != Phase.PREVIEW) return
        controller?.unlock()
        b.tvStatus.text = getString(R.string.status_waiting)
    }

    private fun startCountdown() {
        if (controller == null) return
        phase = Phase.COUNTDOWN
        wave.enabled = false
        countdownLeft = COUNTDOWN_S
        renderPhase()
        doCountdownStep()
    }

    private fun doCountdownStep() {
        if (phase != Phase.COUNTDOWN) return
        if (countdownLeft > 0) {
            b.tvCountdown.text = countdownLeft.toString()
            beep(ToneGenerator.TONE_PROP_BEEP, 150)
            flashPulse(250)
            countdownLeft--
            ui.postDelayed(countdownStep, 1000)
        } else {
            startRecording()
        }
    }

    private fun cancelCountdown() {
        ui.removeCallbacks(countdownStep)
        phase = Phase.PREVIEW
        setLight(false)
        wave.enabled = true
        wave.reset()
        wave.armFor(1500)
        renderPhase()
    }

    // ------------------------------------------------------------------
    // Enregistrement
    // ------------------------------------------------------------------

    private fun startRecording() {
        val ctl = controller ?: return
        val st = b.textureView.surfaceTexture ?: return
        phase = Phase.RECORDING
        beep(ToneGenerator.TONE_PROP_BEEP2, 450)

        val file = File(cacheDir, "swing_${System.currentTimeMillis()}.mp4")
        pendingFile = file
        renderPhase()

        ctl.startRecording(
            texture = st,
            output = file,
            onStarted = {
                runOnUiThread {
                    recordStart = System.currentTimeMillis()
                    ui.post(timerTick)
                    blinkOn = false
                    ui.post(blinkTick)
                }
            },
            onError = { msg -> runOnUiThread { abortRecording(msg) } }
        )
    }

    private fun stopRecording() {
        if (phase != Phase.RECORDING) return
        ui.removeCallbacks(timerTick)
        ui.removeCallbacks(blinkTick)
        setLight(false)
        val ctl = controller ?: return
        val file = pendingFile
        val ok = ctl.stopRecording()
        // Mode réellement utilisé (peut différer de l'annonce si la haute vitesse a été refusée)
        val usedHs = ctl.usedHighSpeed
        val usedFps = ctl.usedFps
        phase = Phase.PREVIEW
        ctl.stop()
        controller = null

        if (ok && file != null && file.exists() && file.length() > 0) {
            val c = caps
            startActivity(Intent(this, ReviewActivity::class.java).apply {
                putExtra(ReviewActivity.EXTRA_VIDEO, file.absolutePath)
                putExtra(ReviewActivity.EXTRA_SLOWMO, usedHs)
                putExtra(ReviewActivity.EXTRA_FPS, if (usedFps > 0) usedFps else (c?.previewFpsRange?.upper ?: 60))
                putExtra(ReviewActivity.EXTRA_FOCAL_RATIO, c?.focalPxRatio ?: 0.0)
                putExtra(ReviewActivity.EXTRA_HFOV, c?.hfovDeg ?: 60.0)
                putExtra(ReviewActivity.EXTRA_CLUB, club)
            })
        } else {
            abortRecording("Enregistrement invalide, réessayez.")
        }
    }

    private fun abortRecording(msg: String) {
        ui.removeCallbacks(timerTick)
        ui.removeCallbacks(blinkTick)
        ui.removeCallbacks(countdownStep)
        setLight(false)
        controller?.stop()
        controller = null
        phase = Phase.PREVIEW
        wave.enabled = true
        wave.reset()
        wave.armFor(1500)
        renderPhase()
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        startCameraIfReady()
    }

    // ------------------------------------------------------------------
    // Lumière (flash en mode torche, ou écran blanc en repli)
    // ------------------------------------------------------------------

    private fun setLight(on: Boolean) {
        val hasFlash = caps?.flashAvailable == true
        if (hasFlash) controller?.setFlash(on)
        b.flashOverlay.alpha = if (on && !hasFlash) 0.8f else 0f
    }

    private fun flashPulse(ms: Long) {
        setLight(true)
        ui.postDelayed({ if (phase == Phase.COUNTDOWN) setLight(false) }, ms)
    }

    private fun beep(tone: Int, ms: Int) {
        try { toneGen?.startTone(tone, ms) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------
    // Interface
    // ------------------------------------------------------------------

    private fun renderPhase() {
        when (phase) {
            Phase.PREVIEW -> {
                b.tvStatus.text = getString(R.string.status_waiting)
                b.tvGuide.text = getString(R.string.guide_steps)
                b.tvGuide.visibility = View.VISIBLE
                b.tvTimer.visibility = View.GONE
                b.tvCountdown.visibility = View.GONE
                b.statusDot.setBackgroundResource(R.drawable.bg_dot_green)
                b.btnRecord.text = getString(R.string.btn_record)
                setRecordColor("#00E676", "#001A0B")
                b.btnSensitivity.isEnabled = true
                b.btnClub.isEnabled = true
                b.tvTip.visibility = View.VISIBLE
                updateMeter(0, 3)
                startHandAnim()
            }
            Phase.COUNTDOWN -> {
                b.tvStatus.text = getString(R.string.status_countdown)
                b.tvGuide.text = getString(R.string.guide_countdown)
                b.tvCountdown.visibility = View.VISIBLE
                b.tvTimer.visibility = View.GONE
                b.statusDot.setBackgroundResource(R.drawable.bg_dot_green)
                b.btnRecord.text = getString(R.string.btn_cancel)
                setRecordColor("#FFFFFF", "#000000")
                b.btnSensitivity.isEnabled = false
                b.btnClub.isEnabled = false
                stopHandAnim()
            }
            Phase.RECORDING -> {
                b.tvStatus.text = getString(R.string.status_recording)
                b.tvGuide.text = getString(R.string.guide_recording)
                b.tvCountdown.visibility = View.GONE
                b.tvTimer.visibility = View.VISIBLE
                b.statusDot.setBackgroundResource(R.drawable.bg_dot_red)
                b.btnRecord.text = getString(R.string.btn_stop)
                setRecordColor("#FF3D00", "#FFFFFF")
                b.btnSensitivity.isEnabled = false
                b.btnClub.isEnabled = false
                stopHandAnim()
            }
        }
    }

    private fun setRecordColor(bg: String, fg: String) {
        b.btnRecord.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor(bg))
        b.btnRecord.setTextColor(android.graphics.Color.parseColor(fg))
    }

    /** Compteur de gestes : 3 points qui se remplissent au fil des allers-retours. */
    private fun updateMeter(count: Int, needed: Int) {
        if (phase != Phase.PREVIEW) return
        listOf(b.dot1, b.dot2, b.dot3).forEachIndexed { i, v ->
            v.setBackgroundResource(if (i < count) R.drawable.bg_dot_green else R.drawable.bg_dot_off)
        }
        b.tvStatus.text = if (count > 0) getString(R.string.status_detected, count, needed)
                          else getString(R.string.status_waiting)
    }

    private fun startHandAnim() {
        val anim = handAnim ?: ObjectAnimator.ofFloat(b.tvHand, "translationX", -40f, 40f).also {
            it.duration = 700
            it.repeatCount = ObjectAnimator.INFINITE
            it.repeatMode = ObjectAnimator.REVERSE
            it.interpolator = LinearInterpolator()
            handAnim = it
        }
        b.tvHand.visibility = View.VISIBLE
        if (!anim.isStarted) anim.start()
    }

    private fun stopHandAnim() {
        handAnim?.cancel()
        b.tvHand.visibility = View.INVISIBLE
        b.tvHand.translationX = 0f
    }
}
