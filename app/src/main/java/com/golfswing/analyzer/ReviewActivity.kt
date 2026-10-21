package com.golfswing.analyzer

import android.content.ContentValues
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.golfswing.analyzer.databinding.ActivityReviewBinding
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

/**
 * Revue du swing : lecture ralentie ou réelle, zoom à deux doigts, repérage de la balle par toucher
 * (recalé automatiquement sur le centre de la balle), suivi automatique, trajectoire et enregistrement.
 */
class ReviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO = "video"
        const val EXTRA_SLOWMO = "slowmo"
        const val EXTRA_FPS = "fps"
        const val EXTRA_FOCAL_RATIO = "focal_ratio"
        const val EXTRA_HFOV = "hfov"
        const val EXTRA_SEED_T = "seed_t"   // repérage automatique (session) : instant, position brute de la balle
        const val EXTRA_SEED_U = "seed_u"
        const val EXTRA_SEED_V = "seed_v"
        const val EXTRA_TRIM_START = "trim_start"   // fenêtre du swing (s) : seule cette plage est enregistrée
        const val EXTRA_TRIM_END = "trim_end"
        const val EXTRA_CLUB = "club"

        // Vitesses de lecture : 1.0 = vitesse réelle de la vidéo
        private val SPEEDS = floatArrayOf(0.1f, 0.25f, 0.5f, 1.0f)
        private const val ZOOM_MAX = 5f
    }

    private lateinit var b: ActivityReviewBinding
    private lateinit var path: String
    private var rawW = 1280
    private var rawH = 720
    private var focal = 0.0
    private var slowMo = false
    private var measuredFps = 0      // cadence réelle : nombre d'images / durée (0 = inconnu)
    private var speedIdx = 3
    private var trimmed = false          // true : un swing précis est ouvert, l'enregistrement se limite à sa fenêtre
    private var trimStart = 0.0
    private var trimEnd = Double.MAX_VALUE
    private var club = Clubs.UNSPECIFIED
    private var clipOffset = 0.0         // décalage (s) entre la vidéo source et le clip découpé
    private var ready = false            // true quand le clip (ou la vidéo) est prêt à être lu et analysé
    private var manualMode = false       // mode manuel : pas de détection, la courbe passe par les points posés à la main

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private val markers = mutableListOf<DoubleArray>()   // repères [temps, u, v, diamètre (NaN si inconnu)] ; le premier = tee
    private var result: SwingResult? = null
    private var awaitingValidation = false   // true : l'app attend un toucher sur la balle pour valider une image douteuse

    private lateinit var gestures: GestureDetector

    // Zoom à deux doigts : la vidéo et le tracé zooment ensemble (translation et échelle du cadre)
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private var anchorX = 0f            // point du contenu sous les doigts au début du pincement
    private var anchorY = 0f
    private var pinchSpan = 0f          // écart entre les doigts (repère du contenu)
    private var pinching = false
    private var gestureUsed = false     // un pincement a eu lieu dans ce geste : ce n'est pas un toucher

    private val ui = Handler(Looper.getMainLooper())
    private var userSeeking = false

    /** Met à jour la barre, le temps et l'icône lecture/pause ~30 fois par seconde. */
    private val clock = object : Runnable {
        override fun run() {
            player?.let { p ->
                try {
                    val pos = p.currentPosition
                    val dur = p.duration.coerceAtLeast(1)
                    b.trajectoryView.setTime(pos / 1000.0)
                    if (!userSeeking) b.seekBar.progress = (pos * 1000L / dur).toInt()
                    b.tvTime.text = String.format(Locale.FRANCE, "%.1f s / %.1f s", pos / 1000.0, dur / 1000.0)
                    b.btnPlay.setImageResource(if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                } catch (_: Exception) {}
            }
            ui.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(b.root)

        path = intent.getStringExtra(EXTRA_VIDEO) ?: run { finish(); return }
        slowMo = intent.getBooleanExtra(EXTRA_SLOWMO, false)
        club = intent.getStringExtra(EXTRA_CLUB) ?: club
        focal = intent.getDoubleExtra(EXTRA_FOCAL_RATIO, 0.0)
        speedIdx = if (slowMo) 1 else 3
        if (intent.hasExtra(EXTRA_TRIM_END)) {
            trimmed = true
            trimStart = intent.getDoubleExtra(EXTRA_TRIM_START, 0.0)
            trimEnd = intent.getDoubleExtra(EXTRA_TRIM_END, Double.MAX_VALUE)
        }

        // Toucher = repère de balle ; appui long = effacer les repères ; deux doigts = zoom
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { onBallTapped(e.x, e.y); return true }
            override fun onLongPress(e: MotionEvent) = clearMarkers()
        })
        b.trajectoryView.setOnTouchListener { _, e -> onTouch(e) }

        b.videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = initPlayer(st)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { releasePlayer(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        b.btnPlay.setOnClickListener { togglePlay() }
        b.btnClose.setOnClickListener { finish() }
        b.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                player?.let { p ->
                    try {
                        val dur = p.duration
                        if (dur > 0) p.seekTo((dur.toLong() * progress / 1000).toInt())
                    } catch (_: Exception) {}
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) { userSeeking = false }
        })
        b.btnSpeed.setOnClickListener {
            speedIdx = (speedIdx + 1) % SPEEDS.size
            updateSpeedLabel()
            updateFormatLabel()
            setSpeedParams()
        }
        b.btnAnalyze.setOnClickListener { runAnalysis() }
        b.btnManual.setOnClickListener { toggleManual() }
        b.btnSave.setOnClickListener { saveAll(withTrace = false) }
        b.btnSaveTraced.setOnClickListener { saveAll(withTrace = true) }
        b.btnAgain.setOnClickListener { finish() }

        if (trimmed) {
            // Swing choisi dans une longue session : on découpe d'abord la fenêtre utile, puis on la charge
            b.tvInfo.text = "Préparation du swing…"
            val src = path
            Thread { prepareClip(src) }.start()
        } else {
            setupClip()
        }
    }

    /** Découpe la fenêtre du swing dans la session (copie sans réencodage) et charge ce petit clip. */
    private fun prepareClip(src: String) {
        val clip = File(cacheDir, "swing_clip.mp4").absolutePath
        val offset = ClipCutter.cut(src, clip, trimStart, trimEnd, readRotation(src))
        runOnUiThread {
            if (offset == null) {
                b.tvInfo.text = "Découpe du swing impossible. Vérifiez l'espace de stockage puis réessayez."
                return@runOnUiThread
            }
            clipOffset = offset
            path = clip
            trimmed = false
            setupClip()
        }
    }

    /** Mise en place de la vidéo (complète ou découpée) : cadence, format, lecteur, repère automatique. */
    private fun setupClip() {
        measuredFps = Math.round(measureFps(path)).toInt()
        val (w, h) = readRawSize(path)
        rawW = w
        rawH = h
        // La vidéo est tournée de 90° pour l'affichage portrait : ratio = H / W
        b.aspectLayout.setAspect(rawH, rawW)
        b.trajectoryView.setRawSize(rawW, rawH)
        updateSpeedLabel()
        updateFormatLabel()
        if (measuredFps in 1..99) {
            b.tvInfo.text = "Cadence réelle : $measuredFps fps, trop lente pour suivre la balle. Filmez en pleine lumière."
        }
        ready = true
        if (b.videoView.isAvailable) initPlayer(b.videoView.surfaceTexture!!)

        // Repérage automatique (session) : la balle est déjà placée, l'analyse démarre seule
        val seedU = intent.getDoubleExtra(EXTRA_SEED_U, Double.NaN)
        if (!seedU.isNaN()) {
            val seedT = Math.max(0.0, intent.getDoubleExtra(EXTRA_SEED_T, 0.0) - clipOffset)
            markers.add(doubleArrayOf(seedT, seedU, intent.getDoubleExtra(EXTRA_SEED_V, 0.0), Double.NaN))
            b.trajectoryView.setMarkers(markers)
            b.tvInfo.text = getString(R.string.analysis_running)
            ui.postDelayed({ runAnalysis() }, 600)
        }
    }

    override fun onResume() {
        super.onResume()
        if (ready && b.videoView.isAvailable) initPlayer(b.videoView.surfaceTexture!!)
        ui.post(clock)
    }

    override fun onPause() {
        ui.removeCallbacks(clock)
        releasePlayer()
        super.onPause()
    }

    // ------------------------------------------------------------------
    // Zoom à deux doigts
    // ------------------------------------------------------------------

    /**
     * Les événements tactiles arrivent dans le repère du contenu (non zoomé) : les repères restent justes
     * quel que soit le zoom. Un pincement n'est jamais un toucher ni un appui long.
     */
    private fun onTouch(e: MotionEvent): Boolean {
        if (e.pointerCount >= 2) {
            if (!pinching) startPinch(e)
            if (e.actionMasked == MotionEvent.ACTION_MOVE) pinchMove(e)
            MotionEvent.obtain(e.downTime, e.eventTime, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                .also { gestures.onTouchEvent(it); it.recycle() }
            gestureUsed = true
        } else if (!gestureUsed) {
            gestures.onTouchEvent(e)
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            pinching = false
            gestureUsed = false
        }
        return true
    }

    private fun focusX(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getX(i)
        return s / e.pointerCount
    }

    private fun focusY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    private fun startPinch(e: MotionEvent) {
        pinching = true
        pinchSpan = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
        anchorX = focusX(e)
        anchorY = focusY(e)
    }

    private fun pinchMove(e: MotionEvent) {
        val span = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
        if (pinchSpan < 1f) { pinchSpan = span; return }
        val z = (zoom * span / pinchSpan).coerceIn(1f, ZOOM_MAX)
        pinchSpan = span
        // Les doigts, dans le cadre, gardent le point ancré sous eux (zoom centré sur les doigts + déplacement)
        val sx = tx + zoom * focusX(e)
        val sy = ty + zoom * focusY(e)
        applyZoom(z, sx, sy)
    }

    private fun applyZoom(z: Float, sx: Float, sy: Float) {
        val c = b.aspectLayout
        zoom = z
        val w = c.width.toFloat()
        val h = c.height.toFloat()
        tx = (sx - z * anchorX).coerceIn(w * (1f - z), 0f)
        ty = (sy - z * anchorY).coerceIn(h * (1f - z), 0f)
        c.pivotX = 0f
        c.pivotY = 0f
        c.scaleX = z
        c.scaleY = z
        c.translationX = tx
        c.translationY = ty
    }

    // ------------------------------------------------------------------
    // Lecture
    // ------------------------------------------------------------------

    private fun initPlayer(st: SurfaceTexture) {
        if (!ready || player != null) return
        val mp = MediaPlayer()
        try {
            mp.setDataSource(path)
            val s = Surface(st)
            surface = s
            mp.setSurface(s)
            mp.isLooping = true
            mp.setOnPreparedListener { p ->
                p.seekTo(0)
                setSpeedParams()
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            mp.release()
            Toast.makeText(this, "Lecture impossible : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        surface?.release()
        surface = null
    }

    private fun togglePlay() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) mp.pause() else { setSpeedParams(); mp.start() }
        } catch (_: Exception) {}
    }

    private fun setSpeedParams() {
        val mp = player ?: return
        val wasPlaying = mp.isPlaying
        try {
            mp.playbackParams = PlaybackParams().setSpeed(SPEEDS[speedIdx])
        } catch (_: Exception) {
            Toast.makeText(this, "Cette vitesse n'est pas supportée ici", Toast.LENGTH_SHORT).show()
        }
        if (!wasPlaying && mp.isPlaying) try { mp.pause() } catch (_: Exception) {}
    }

    /** Cadence mesurée dans le fichier ; le mode se déduit de cette cadence (et non d'un drapeau). */
    private fun updateFormatLabel() {
        val fps = if (measuredFps > 0) measuredFps else intent.getIntExtra(EXTRA_FPS, 60)
        val mode = if (fps >= 100) "haute vitesse" else "standard"
        val play = if (SPEEDS[speedIdx] == 1f) "lecture réelle" else "lecture ${SPEEDS[speedIdx]}×"
        b.tvFormat.text = "Capté à $fps fps (mesuré) · ${rawW}×${rawH} · mode $mode · $play"
    }

    /** Cadence réelle : nombre d'images du fichier divisé par sa durée (sans décodage). */
    private fun measureFps(p: String): Double {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(p)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i; break
                }
            }
            if (track < 0) return 0.0
            ex.selectTrack(track)
            var n = 0
            var first = -1L
            var last = 0L
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                if (first < 0) first = t
                last = t
                n++
                ex.advance()
            }
            if (n < 2 || last <= first) 0.0 else (n - 1) * 1_000_000.0 / (last - first)
        } finally {
            ex.release()
        }
    }

    private fun updateSpeedLabel() {
        b.btnSpeed.text = if (SPEEDS[speedIdx] == 1f) "1×" else "${SPEEDS[speedIdx]}×"
    }

    // ------------------------------------------------------------------
    // Repérage de la balle et analyse
    // ------------------------------------------------------------------

    /**
     * Pendant la lecture, un toucher lance ou met en pause.
     * À l'arrêt, il pose un repère : le repère est recalé sur le centre de la balle si elle est reconnue
     * près du toucher. Le premier repère est le tee, les suivants (balle en vol) guident le suivi.
     */
    private fun onBallTapped(x: Float, y: Float) {
        if (!ready) return   // clip encore en préparation
        if (player?.isPlaying == true) {
            togglePlay()
            return
        }
        val (u, v) = b.trajectoryView.viewToRaw(x, y)
        val t = (player?.currentPosition ?: 0) / 1000.0
        if (manualMode) {
            // Mode manuel : le point est pris tel quel, sans recalage automatique
            markers.add(doubleArrayOf(t, u, v, Double.NaN))
            markers.sortBy { it[0] }
            b.trajectoryView.setMarkers(markers)
            result = null
            b.trajectoryView.setResult(null)
            b.tvResults.visibility = View.GONE
            b.tvInfo.text = "${markers.size} point(s) posé(s). Continuez à toucher la balle, puis « Suivre la balle »."
            return
        }
        val m = doubleArrayOf(t, u, v, Double.NaN)
        markers.add(m)
        markers.sortBy { it[0] }
        b.trajectoryView.setMarkers(markers)
        b.tvInfo.text = "Repérage de la balle…"
        Thread {
            val blob = try { SwingAnalyzer.snapToBall(path, t, u, v, rawH) } catch (_: Exception) { null }
            runOnUiThread { onMarkerPlaced(m, blob) }
        }.start()
    }

    private fun onMarkerPlaced(m: DoubleArray, blob: SwingAnalyzer.Blob?) {
        if (markers.none { it === m }) return   // repère effacé entre-temps
        if (blob != null) {
            m[1] = blob.u
            m[2] = blob.v
            m[3] = blob.d
        }
        b.trajectoryView.setMarkers(markers)
        if (awaitingValidation) {
            // Image douteuse validée par l'utilisateur : on relance le suivi avec ce repère
            awaitingValidation = false
            runAnalysis()
            return
        }
        result = null
        b.trajectoryView.setResult(null)
        b.tvResults.visibility = View.GONE
        val isTee = markers.first() === m
        val status = if (blob != null)
            "balle reconnue (≈ ${"%.0f".format(blob.d)} px)"
        else
            "balle non reconnue près de ce point : repère gardé tel quel. Zoomez (deux doigts) et touchez le centre de la balle"
        b.tvInfo.text = if (isTee) "Tee : $status." else "Repère ${markers.size} : $status. Appuyez sur « Suivre la balle »."
    }

    /** Bascule le mode manuel : sans détection automatique, la trajectoire passe par les points posés à la main. */
    private fun toggleManual() {
        manualMode = !manualMode
        b.btnManual.text = if (manualMode) "Mode manuel : activé" else "Mode manuel : désactivé"
        result = null
        b.trajectoryView.setResult(null)
        b.tvResults.visibility = View.GONE
        b.tvInfo.text = if (manualMode)
            "Mode manuel : touchez la balle à chaque instant clé (tee, puis vol), puis « Suivre la balle »."
        else
            "Détection automatique activée. Touchez le tee pour repérer la balle."
    }

    private fun clearMarkers() {
        markers.clear()
        b.trajectoryView.setMarkers(markers)
        result = null
        b.trajectoryView.setResult(null)
        b.tvResults.visibility = View.GONE
        b.tvInfo.text = "Repères effacés. Touchez le tee pour recommencer."
    }

    private fun runAnalysis() {
        if (markers.isEmpty()) {
            Toast.makeText(this, R.string.hint_tap_ball, Toast.LENGTH_LONG).show()
            return
        }
        player?.let { try { it.pause() } catch (_: Exception) {} }
        if (manualMode) {
            // Mode manuel : aucune détection, la courbe passe par les points posés
            val res = SwingAnalyzer.fromMarkers(markers.map { it.copyOf() }, rawW, rawH, focal)
            if (res == null) {
                b.tvInfo.text = "Mode manuel : il faut au moins 3 points (tee + 2 positions de la balle en vol)."
            } else {
                onAnalysisDone(res, "", true)
            }
            return
        }
        b.btnAnalyze.isEnabled = false
        b.tvInfo.text = getString(R.string.analysis_running)
        val snapshot = markers.map { it.copyOf() }
        val f = focal
        val rw = rawW
        val rh = rawH

        Thread {
            var res = try {
                SwingAnalyzer.analyze(path, snapshot, f) { p ->
                    runOnUiThread { b.tvInfo.text = "Analyse… ${(p * 100).toInt()} %" }
                }
            } catch (_: Exception) { null }
            val diag = SwingAnalyzer.lastDiagnosis
            var byPoints = false
            if (res == null) {
                // Repli : trajectoire tracée à partir des seuls repères posés
                res = try { SwingAnalyzer.fromMarkers(snapshot, rw, rh, f) } catch (_: Exception) { null }
                byPoints = res != null
            }
            runOnUiThread { onAnalysisDone(res, diag, byPoints) }
        }.start()
    }

    private fun onAnalysisDone(res: SwingResult?, diag: String, byPoints: Boolean) {
        b.btnAnalyze.isEnabled = true
        if (res == null) {
            result = null
            b.trajectoryView.setResult(null)
            b.tvResults.visibility = View.GONE
            b.tvInfo.text = "${getString(R.string.analysis_failed)}\n$diag"
            return
        }
        result = res
        b.trajectoryView.setResult(res)
        b.tvResults.text = if (byPoints && res.carryM < 0) pointsSummary(res) else res.summary()
        b.tvResults.visibility = View.VISIBLE
        val doubt = res.doubtful.firstOrNull()
        if (doubt != null) {
            // Image douteuse : on s'arrête dessus et on demande de toucher la balle
            awaitingValidation = true
            b.tvInfo.text = "Balle incertaine sur cette image (${res.doubtful.size}). Touchez la balle pour la valider."
            player?.let { p ->
                try { p.pause(); p.seekTo((doubt * 1000).toLong(), MediaPlayer.SEEK_CLOSEST) } catch (_: Exception) {}
            }
            return
        }
        b.tvInfo.text = when {
            byPoints -> "Tracé à partir de vos repères (balle non suivie automatiquement). Ajoutez des repères sur la balle en vol pour l'affiner."
            res.quality == "faible" -> "Confiance faible : touchez la balle sur une image pour la valider, puis relancez."
            else -> "Trajectoire estimée. Lecture autour de l'impact."
        }

        player?.let { p ->
            try {
                p.seekTo(maxOf(0, ((res.impactT - 0.5) * 1000).toInt()))
                setSpeedParams()
                p.start()
            } catch (_: Exception) {}
        }
    }

    private fun pointsSummary(res: SwingResult): String =
        "Mode repères : trajectoire lissée sur ${res.nFit} points.\n" +
            "Pas d'estimation de vitesse ni de carry : elles demandent une balle suivie automatiquement."

    // ------------------------------------------------------------------
    // Enregistrement (MediaStore : Movies/GolfSwing et Download/GolfSwing)
    // ------------------------------------------------------------------

    /**
     * Enregistre la vidéo (avec ou sans tracé incrusté) et les données JSON de l'analyse.
     * Le tracé incrusté demande d'avoir lancé « Suivre la balle » ; l'export réencode la vidéo.
     */
    private fun saveAll(withTrace: Boolean) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val res = result
        if (withTrace && res == null) {
            Toast.makeText(this, "Lancez d'abord « Suivre la balle »", Toast.LENGTH_LONG).show()
            return
        }
        setSaveEnabled(false)
        val snapshot = markers.map { it.copyOf() }
        val rotation = readRotation(path)
        val fps = if (measuredFps > 0) measuredFps.toDouble() else 60.0
        val overlay = TracerOverlay()

        Thread {
            var videoPath: String? = path
            // Avec tracé, ou swing découpé : on réencode seulement la fenêtre du swing (fichier léger)
            if ((withTrace && res != null) || trimmed) {
                val traced = withTrace && res != null
                val out = File(cacheDir, if (traced) "golf_swing_traced.mp4" else "golf_swing_clip.mp4").absolutePath
                val ok = VideoExporter.export(
                    src = path,
                    dst = out,
                    rotation = rotation,
                    fps = fps,
                    overlayFrom = if (traced && res != null) res.impactT - 1.0 else Double.MAX_VALUE,
                    drawAt = { canvas: Canvas, t: Double ->
                        if (traced && res != null) {
                            overlay.draw(canvas, res, snapshot, t, 1f) { u, v -> PointF(u.toFloat(), v.toFloat()) }
                        }
                    },
                    onProgress = { p ->
                        val label = if (traced) "Export avec tracé" else "Découpe du swing"
                        runOnUiThread { b.tvInfo.text = "$label… ${(p * 100).toInt()} %" }
                    },
                    startSec = trimStart,
                    endSec = trimEnd
                )
                videoPath = if (ok) out else null
            }

            val vp = videoPath
            val tag = Clubs.slug(club)
            val name = if (withTrace) "golf_swing_${tag}_trace_$stamp" else "golf_swing_${tag}_$stamp"
            val videoOk = vp != null && saveFile(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "Movies/GolfSwing", "$name.mp4", "video/mp4"
            ) { out -> File(vp).inputStream().use { it.copyTo(out) } }

            val dataOk = if (res != null) {
                saveFile(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "Download/GolfSwing", "golf_swing_$stamp.json", "application/json"
                ) { out -> out.write(res.toJson().put("clip_offset_s", clipOffset).put("club", club).toString(2).toByteArray()) }
            } else true

            runOnUiThread {
                setSaveEnabled(true)
                Toast.makeText(
                    this,
                    if (videoOk && dataOk) R.string.saved_ok else R.string.save_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun setSaveEnabled(enabled: Boolean) {
        b.btnSave.isEnabled = enabled
        b.btnSaveTraced.isEnabled = enabled
    }

    /** Orientation de la vidéo (degrés), pour que l'export garde l'affichage portrait. */
    private fun readRotation(p: String): Int = try {
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(p)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        }
    } catch (_: Exception) {
        0
    }

    private fun saveFile(
        collection: Uri,
        relPath: String,
        name: String,
        mime: String,
        write: (OutputStream) -> Unit
    ): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return try {
            val uri = contentResolver.insert(collection, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { write(it) } ?: return false
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------

    private fun readRawSize(p: String): Pair<Int, Int> {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(p)
            for (i in 0 until ex.trackCount) {
                val f: MediaFormat = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    return Pair(f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT))
                }
            }
            Pair(1280, 720)
        } finally {
            ex.release()
        }
    }
}
