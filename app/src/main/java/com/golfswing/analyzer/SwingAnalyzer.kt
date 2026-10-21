package com.golfswing.analyzer

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/** Position de la balle dans l'image brute (pixels du décodeur), à un instant de la vidéo. */
data class BallSample(val tVid: Double, val u: Double, val v: Double, val d: Double)

data class SwingResult(
    val samples: List<BallSample>,     // trajectoire réellement détectée
    val predicted: List<BallSample>,   // trajectoire estimée par la physique, projetée sur l'image
    val rawW: Int,
    val rawH: Int,
    val impactT: Double,               // instant du départ de la balle (s, dans la vidéo)
    val ballSpeed: Double,             // m/s
    val launchDeg: Double,             // angle de lancement vertical (°)
    val azimuthDeg: Double,            // direction de départ (° ; + = droite de la caméra)
    val curveM: Double,                // déviation latérale due à l'effet (m ; + = vers la droite), 0 si inconnue
    val shape: String,                 // forme estimée : draw, fade, push, pull, slice, hook...
    val carryM: Double,                // carry estimé (m), -1 si non fiable
    val apexM: Double,                 // hauteur max estimée (m), -1 si non fiable
    val hangS: Double,                 // durée de vol estimée (s), -1 si non fiable
    val clubSpeed: Double,             // m/s, estimée = vitesse balle / smash factor, -1 si non fiable
    val confidence: Int,               // fiabilité de la prévision, 0 à 100
    val quality: String,               // "bonne", "moyenne" ou "faible"
    val nFit: Int,                     // nombre de points détectés
    val doubtful: List<Double> = emptyList() // instants (s) où la balle est douteuse : à valider
) {
    val confidenceLabel: String
        get() = when {
            confidence >= 70 -> "élevée"
            confidence >= 45 -> "moyenne"
            else -> "faible"
        }

    /** Lignes affichées sur la vidéo (aperçu et export). */
    fun overlayLines(): List<String> = listOf(
        if (carryM > 0) "Carry ≈ %.0f m · %s".format(carryM, shape) else "Trajectoire non fiable",
        "Fiabilité de la prévision : $confidence % ($confidenceLabel)"
    )

    fun summary(): String = buildString {
        if (carryM < 0) {
            appendLine("Trajectoire non fiable : validez la balle sur les images signalées.")
        } else {
            appendLine("Vitesse balle : %.0f km/h (%.0f mph)".format(ballSpeed * 3.6, ballSpeed * 2.23694))
            appendLine("Vitesse club (est.) : %.0f km/h (%.0f mph)".format(clubSpeed * 3.6, clubSpeed * 2.23694))
            appendLine("Lancement : %.1f°   Direction : %+.1f°".format(launchDeg, azimuthDeg))
            appendLine("Carry estimé : %.0f m   Hauteur max : %.0f m   Vol : %.1f s".format(carryM, apexM, hangS))
            appendLine("Forme estimée : $shape (sans effet de rotation mesuré)")
        }
        appendLine("Fiabilité de la prévision : $confidence % ($confidenceLabel)")
        appendLine("Points détectés : $nFit")
        append("Images douteuses : ${doubtful.size}")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("raw_width", rawW)
        put("raw_height", rawH)
        put("impact_time_s", impactT)
        put("ball_speed_ms", ballSpeed)
        put("club_speed_est_ms", clubSpeed)
        put("launch_angle_deg", launchDeg)
        put("azimuth_deg", azimuthDeg)
        put("curve_m", curveM)
        put("shape", shape)
        put("carry_estimate_m", carryM)
        put("apex_estimate_m", apexM)
        put("hang_time_s", hangS)
        put("confidence_pct", confidence)
        put("quality", quality)
        put("doubtful_times_s", JSONArray(doubtful))
        put("note", "Estimations indicatives : modèle simplifié (traînée, effet latéral constant, sans rotation mesurée), caméra supposée horizontale.")
        put("samples", JSONArray().apply {
            samples.forEach { s -> put(JSONObject().put("t", s.tVid).put("u", s.u).put("v", s.v).put("d", s.d)) }
        })
        put("predicted", JSONArray().apply {
            predicted.forEach { s -> put(JSONObject().put("t", s.tVid).put("u", s.u).put("v", s.v)) }
        })
    }
}

/**
 * Décode la vidéo (MediaCodec -> ImageReader), détecte la balle et estime vitesse, lancement, carry.
 *
 * Principe :
 * 1. Le premier repère est le tee. Avant l'impact, on surveille seulement ce tee.
 * 2. Le départ du vol est confirmé quand une tache « balle » a quitté le tee sur 2 images consécutives.
 * 3. La balle est reconnue par sa couleur (blanc, jaune de practice, orange) et sa forme ronde,
 *    puis suivie d'image en image. Les images où le suivi hésite ou se perd sont signalées.
 * 4. La trajectoire prévisionnelle est calculée par la physique, à partir des premières images.
 */
object SwingAnalyzer {

    private const val BALL_DIAMETER_M = 0.04267
    private const val GRAVITY = 9.81

    /** Cause du dernier échec d'analyse, lisible par l'utilisateur (vide si réussi). */
    @Volatile var lastDiagnosis = ""
    private const val SMASH_FACTOR = 1.50
    private const val DRAG_K = 0.00935   // 1/m : 0,5·rho·Cd·A/m (balle 45,9 g, Cd ≈ 0,25)

    /**
     * @param markers repères [temps (s), u, v] en pixels bruts. Le plus ancien = tee.
     * @param focalRatio focale / largeur capteur (CameraCaps.focalPxRatio).
     */
    fun analyze(
        path: String,
        markers: List<DoubleArray>,
        focalRatio: Double,
        onProgress: (Float) -> Unit = {}
    ): SwingResult? {
        lastDiagnosis = ""
        if (markers.isEmpty()) return null
        val sorted = markers.sortedBy { it[0] }
        val seed = sorted[0]

        val ex = MediaExtractor()
        ex.setDataSource(path)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                track = i; fmt = f; break
            }
        }
        if (track < 0 || fmt == null) { ex.release(); return null }
        ex.selectTrack(track)
        if (seed[0] > 0.0) ex.seekTo((seed[0] * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
        val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 1L

        val thread = HandlerThread("SwingAnalyzer").also { it.start() }
        val handler = Handler(thread.looper)
        val tracker = BallTracker(w, h, seed, sorted.drop(1), focalRatio)
        val reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 4)
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
            try { tracker.feed(img) } finally { img.close() }
        }, handler)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, reader.surface, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var inEos = false
        var outEos = false
        while (!outEos && !tracker.done) {
            if (!inEos) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inEos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                codec.releaseOutputBuffer(outIdx, info.size > 0)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                onProgress(min(1f, info.presentationTimeUs.toFloat() / durUs))
            }
        }

        // Laisser arriver les dernières images dans l'ImageReader
        var last = -1
        var stable = 0
        var waited = 0
        while (stable < 3 && waited < 40) {
            Thread.sleep(100); waited++
            if (tracker.frames == last) stable++ else { stable = 0; last = tracker.frames }
        }

        codec.stop(); codec.release()
        ex.release()
        reader.close()
        thread.quitSafely()

        val res = tracker.buildResult()
        if (res == null) lastDiagnosis = tracker.diagnosis()
        return res
    }

    // ------------------------------------------------------------------
    // Détection et suivi de la balle
    // ------------------------------------------------------------------

    /** Tache reconnue comme balle. d en pixels bruts ; score de 0 à 1 (forme, taille). */
    class Blob(val u: Double, val v: Double, val d: Double, val score: Double)
    private class FlightFrame(val t: Double, val blobs: List<Blob>)
    private class Track(val samples: List<BallSample>, val doubt: List<Double>)

    /**
     * Une balle est reconnue par sa couleur : blanche (faible saturation, lumineuse),
     * ou jaune / orange (fortement saturée, U bas, V haut). La peau et le ciel bleu sont écartés.
     */
    private fun isBallColour(y: Int, cu: Int, cv: Int): Boolean {
        if (abs(cu) <= 22 && abs(cv) <= 22 && y >= 140) return true   // blanc
        if (cu <= -40 && cv >= 15 && y >= 110) return true             // jaune de practice, orange
        return false
    }

    private class BallTracker(
        private val w: Int,
        private val h: Int,
        private val seed: DoubleArray,
        private val markersAfter: List<DoubleArray>,
        private val focalRatio: Double
    ) {
        @Volatile var done = false
        @Volatile var frames = 0
        private var initialized = false
        private var initTries = 0
        private var failed = false
        private var phaseFlight = false
        private var movedRun = 0
        private var movedStartT = 0.0
        private var restU = 0.0
        private var restV = 0.0
        private var d0 = 0.0
        private var tImpact = -1.0
        private val flight = ArrayList<FlightFrame>()
        private val teeRoi = max(40, (0.1 * max(w, h)).toInt())

        fun feed(img: Image) {
            frames++
            if (failed) { done = true; return }
            val t = img.timestamp / 1e9
            if (t + 1e-4 < seed[0]) return

            if (!initialized) {
                val r = roi(seed[1], seed[2])
                val b = blobsIn(img, r[0], r[1], r[2], r[3], 1, 2.0, 1e9)
                    .filter { it.score >= 0.3 }
                    .minByOrNull { hypot(it.u - seed[1], it.v - seed[2]) }
                if (b == null) {
                    if (++initTries > 15) failed = true
                    return
                }
                restU = b.u; restV = b.v; d0 = max(3.0, b.d)
                initialized = true
                return
            }

            if (!phaseFlight) {
                val r = roi(restU, restV)
                val b = blobsIn(img, r[0], r[1], r[2], r[3], 1, 2.0, 1e9)
                    .minByOrNull { hypot(it.u - restU, it.v - restV) }
                val moved = b != null && hypot(b.u - restU, b.v - restV) > max(0.6 * d0, 2.5)
                if (!moved) {
                    // Tee visible et immobile, ou masqué par le club : on n'avance pas
                    movedRun = 0
                    return
                }
                if (movedRun == 0) movedStartT = t
                movedRun++
                if (movedRun < 2) return          // confirmation sur 2 images
                phaseFlight = true
                tImpact = movedStartT
            }

            if (t - tImpact > 1.6) { done = true; return }
            flight.add(FlightFrame(t, blobsIn(img, 0, 0, w - 1, h - 1, 2, 0.4 * d0, 2.5 * d0)))
        }

        /** Explication de l'échec, pour guider l'utilisateur vers le bon repère. */
        fun diagnosis(): String = when {
            failed || !initialized ->
                "La balle n'a pas été trouvée au repère du tee. Placez le repère sur le centre de la balle, sur une image où elle est immobile."
            tImpact < 0.0 ->
                "Aucun départ de la balle détecté après le tee : la balle ne bouge pas, ou le repère de tee est placé après l'impact."
            flight.size < 4 ->
                "La balle n'a été vue que ${flight.size} fois après son départ : vérifiez la lumière et la couleur de la balle."
            else ->
                "Le suivi a perdu la balle après son départ (forme ou couleur non reconnue). Ajoutez des repères sur la balle en vol."
        }

        fun buildResult(): SwingResult? {
            if (!initialized || failed || tImpact < 0.0 || flight.size < 4) return null
            val waypoints = markersAfter.filter { it[0] > tImpact }
            val startRadius = max(40.0, 10.0 * d0)

            var best: Track? = null
            var bestScore = -1e9
            for (k0 in flight.indices) {
                val f0 = flight[k0]
                if (f0.t - tImpact > 0.03) break
                for (b in f0.blobs) {
                    if (hypot(b.u - restU, b.v - restV) > startRadius) continue
                    val tr = follow(k0, b)
                    if (tr.samples.size < 4) continue
                    val sc = score(tr.samples, waypoints) - 3.0 * tr.doubt.size
                    if (sc > bestScore) { bestScore = sc; best = tr }
                }
            }
            val tr = best ?: return null
            if (tr.samples.size < 5) return null
            return physics(tr.samples).copy(doubtful = tr.doubt.distinct().sorted().take(12))
        }

        /**
         * Suit une tache d'une image à l'autre, en prédisant sa position à vitesse constante.
         * Note les instants douteux : image sans balle reconnue, ou balle reconnue avec peu de certitude.
         */
        private fun follow(k0: Int, start: Blob): Track {
            val out = ArrayList<BallSample>()
            val doubt = ArrayList<Double>()
            val t0 = flight[k0].t
            out.add(BallSample(t0, start.u, start.v, start.d))
            var pu = start.u
            var pv = start.v
            var vu = 0.0
            var vv = 0.0
            var lastT = t0
            var miss = 0
            var first = true
            for (k in k0 + 1 until flight.size) {
                val f = flight[k]
                if (f.t - t0 > 1.2) break
                val dt = f.t - lastT
                if (dt <= 0.0) continue
                val pu2 = pu + vu * dt
                val pv2 = pv + vv * dt
                val gate = if (first) max(40.0, 10.0 * d0) else max(2.5 * d0, 0.35 * hypot(vu, vv) * dt + 2.0 * d0)
                var best: Blob? = null
                var bd = gate
                for (c in f.blobs) {
                    val dd = hypot(c.u - pu2, c.v - pv2)
                    if (dd < bd) { bd = dd; best = c }
                }
                val hit = best
                if (hit != null) {
                    val nvu = (hit.u - pu) / dt
                    val nvv = (hit.v - pv) / dt
                    if (first) { vu = nvu; vv = nvv; first = false }
                    else { vu = 0.5 * vu + 0.5 * nvu; vv = 0.5 * vv + 0.5 * nvv }
                    pu = hit.u; pv = hit.v
                    out.add(BallSample(f.t, hit.u, hit.v, hit.d))
                    if (hit.score < 0.5) doubt.add(f.t)
                    miss = 0
                } else {
                    miss++
                    if (f.t - t0 <= 0.8) doubt.add(f.t)
                    if (miss > 3) break
                    pu = pu2; pv = pv2
                }
                lastT = f.t
            }
            return Track(out, doubt)
        }

        /** Note une trajectoire : longueur, vitesse stable, passage par les repères. */
        private fun score(tr: List<BallSample>, waypoints: List<DoubleArray>): Double {
            val n = tr.size
            if (n < 4) return -1e9
            val sp = ArrayList<Double>()
            for (i in 1 until min(n, 14)) {
                val dt = tr[i].tVid - tr[i - 1].tVid
                if (dt > 0.0) sp.add(hypot(tr[i].u - tr[i - 1].u, tr[i].v - tr[i - 1].v) / dt)
            }
            // Le club ralentit vite après l'impact ; la balle garde presque sa vitesse
            val ratio = if (sp.size >= 6) sp.takeLast(3).average() / max(1e-6, sp.take(3).average()) else 0.0
            var s = n.toDouble() * (if (ratio >= 0.75) 1.0 else 0.4)
            for (wp in waypoints) {
                var bestD = Double.MAX_VALUE
                for (p in tr) {
                    if (abs(p.tVid - wp[0]) < 0.02) bestD = min(bestD, hypot(p.u - wp[1], p.v - wp[2]))
                }
                s += if (bestD < max(8.0, 2.5 * d0)) 50.0 else -200.0
            }
            return s
        }

        fun physics(tr: List<BallSample>): SwingResult {
            val f = focalRatio * w
            val cx = w / 2.0
            val cy = h / 2.0
            val n = tr.size
            val t0 = tr[0].tVid

            // Positions 3D (m) de chaque point détecté, relatives à la caméra
            val tt = DoubleArray(n) { tr[it].tVid - t0 }
            val zs = DoubleArray(n) { f * BALL_DIAMETER_M / tr[it].d }
            val xs = DoubleArray(n) { (tr[it].u - cx) * zs[it] / f }
            val ys = DoubleArray(n) { (cy - tr[it].v) * zs[it] / f }
            val x0 = xs[0]
            val y0 = ys[0]
            val z0 = zs[0]

            // Vitesse initiale : ajustement sur les 0,25 s de vol
            val m = max(min(n, 4), tt.count { it <= 0.25 })
            val tf = tt.copyOf(m)
            val xf = DoubleArray(m) { xs[it] - x0 }
            val zf = DoubleArray(m) { zs[it] - z0 }
            val yf = DoubleArray(m) { ys[it] - y0 + 0.5 * GRAVITY * tf[it] * tf[it] }
            val cX = polyfit2(tf, xf)
            val cY = polyfit2(tf, yf)
            val cZ = polyfit2(tf, zf)
            val vx = cX[1]
            val vy = cY[1]
            val vz = cZ[1]

            // Résidu de l'ajustement en pixels : mesure de la cohérence des points
            var sq = 0.0
            for (i in 0 until m) {
                val ti = tf[i]
                val zF = z0 + cZ[0] + cZ[1] * ti + cZ[2] * ti * ti
                val xF = x0 + cX[0] + cX[1] * ti + cX[2] * ti * ti
                val yF = y0 + cY[0] + cY[1] * ti + cY[2] * ti * ti - 0.5 * GRAVITY * ti * ti
                val uF = cx + xF * f / zF
                val vF = cy - yF * f / zF
                val du = uF - tr[i].u
                val dv = vF - tr[i].v
                sq += du * du + dv * dv
            }
            val rmsPx = sqrt(sq / m)

            // Effet latéral observé : accélération latérale moyenne sur les 0,8 s détectés
            val mL = tt.count { it <= 0.8 }
            var ax = 0.0
            if (mL >= 6 && tt[mL - 1] >= 0.25) {
                val cL = polyfit2(tt.copyOf(mL), DoubleArray(mL) { xs[it] - x0 })
                ax = (2.0 * cL[2]).coerceIn(-12.0, 12.0)
            }

            val speed = sqrt(vx * vx + vy * vy + vz * vz)
            val vh = hypot(vx, vz)
            val launch = Math.toDegrees(atan2(vy, vh))
            val azimuth = Math.toDegrees(atan2(vx, vz))
            // Contrôle de cohérence : vitesse et lancement plausibles pour une balle de golf
            val valid = speed in 15.0..95.0 && launch in 1.0..55.0

            val sim = simulate(vx, vy, vz, ax)
            val last = sim[sim.size - 1]
            val carry = if (valid) hypot(last[1], last[3]) else -1.0
            val apex = if (valid) sim.maxOf { it[2] } else -1.0
            val hang = if (valid) last[0] else -1.0
            // Déviation latérale au point d'atterrissage, par rapport à la ligne de départ
            val curve = last[1] - last[3] * tan(Math.toRadians(azimuth))
            val predicted: List<BallSample> = if (!valid) emptyList() else sim.mapNotNull { p ->
                val zA = z0 + p[3]
                if (zA <= 0.5) null
                else {
                    val xA = x0 + p[1]
                    val yA = y0 + p[2]
                    BallSample(t0 + p[0], cx + xA * f / zA, cy - yA * f / zA, f * BALL_DIAMETER_M / zA)
                }
            }

            // Fiabilité : nombre de points, durée observée, cohérence de l'ajustement
            val span = tt[n - 1]
            val sCount = min(40.0, n * 2.5)
            val sSpan = min(30.0, span / 0.6 * 30.0)
            val sRes = when {
                rmsPx <= 1.5 -> 30.0
                rmsPx <= 4.0 -> 15.0
                else -> 0.0
            }
            val confidence = (sCount + sSpan + sRes).toInt().coerceIn(0, 100)
            val quality = when {
                !valid -> "faible"
                confidence >= 70 -> "bonne"
                confidence >= 45 -> "moyenne"
                else -> "faible"
            }

            return SwingResult(
                samples = tr,
                predicted = predicted,
                rawW = w,
                rawH = h,
                impactT = t0,
                ballSpeed = speed,
                launchDeg = launch,
                azimuthDeg = azimuth,
                curveM = if (valid) curve else 0.0,
                shape = if (valid) classify(azimuth, curve) else "",
                carryM = carry,
                apexM = apex,
                hangS = hang,
                clubSpeed = if (valid) speed / SMASH_FACTOR else -1.0,
                confidence = confidence,
                quality = quality,
                nFit = n
            )
        }

        private fun roi(cu: Double, cv: Double): IntArray {
            val r = teeRoi
            return intArrayOf(
                (cu - r).toInt().coerceIn(0, w - 1),
                (cv - r).toInt().coerceIn(0, h - 1),
                (cu + r).toInt().coerceIn(0, w - 1),
                (cv + r).toInt().coerceIn(0, h - 1)
            )
        }

        /**
         * Taches « balle » d'une zone : pixels de couleur de balle (blanc, jaune, orange),
         * regroupés en composantes connexes. Chaque composante est notée sur sa forme ronde
         * (remplissage, rapport largeur/hauteur) et gardée si son diamètre est dans [dLo, dHi] (px bruts).
         */
        fun blobsIn(
            img: Image, x0: Int, y0: Int, x1: Int, y1: Int, step: Int, dLo: Double, dHi: Double
        ): List<Blob> {
            val gw = (x1 - x0) / step + 1
            val gh = (y1 - y0) / step + 1
            if (gw < 3 || gh < 3) return emptyList()

            val yp = img.planes[0]
            val up = img.planes[1]
            val vp = img.planes[2]
            val yb = yp.buffer
            val ub = up.buffer
            val vb = vp.buffer

            val mask = BooleanArray(gw * gh)
            for (gy in 0 until gh) {
                val py = y0 + gy * step
                for (gx in 0 until gw) {
                    val px = x0 + gx * step
                    val yy = yb.get(py * yp.rowStride + px * yp.pixelStride).toInt() and 0xFF
                    val cu = (ub.get((py / 2) * up.rowStride + (px / 2) * up.pixelStride).toInt() and 0xFF) - 128
                    val cv = (vb.get((py / 2) * vp.rowStride + (px / 2) * vp.pixelStride).toInt() and 0xFF) - 128
                    mask[gy * gw + gx] = isBallColour(yy, cu, cv)
                }
            }

            // Taille maximale d'une balle, en cases de la grille (évite de parcourir les grandes zones blanches)
            val maxCells = if (dHi < 1e8) (PI / 4.0 * (dHi / step) * (dHi / step) * 1.5).toInt() + 4 else gw * gh
            val seen = BooleanArray(gw * gh)
            val queue = IntArray(gw * gh)
            val out = ArrayList<Blob>()

            for (start in 0 until gw * gh) {
                if (!mask[start] || seen[start]) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                seen[start] = true
                var n = 0
                var sx = 0.0
                var sy = 0.0
                var minX = Int.MAX_VALUE
                var maxX = -1
                var minY = Int.MAX_VALUE
                var maxY = -1
                while (head < tail) {
                    val p = queue[head++]
                    val px = p % gw
                    val py = p / gw
                    n++
                    sx += px
                    sy += py
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py
                    if (n > maxCells) break
                    if (px > 0 && mask[p - 1] && !seen[p - 1]) { seen[p - 1] = true; queue[tail++] = p - 1 }
                    if (px < gw - 1 && mask[p + 1] && !seen[p + 1]) { seen[p + 1] = true; queue[tail++] = p + 1 }
                    if (py > 0 && mask[p - gw] && !seen[p - gw]) { seen[p - gw] = true; queue[tail++] = p - gw }
                    if (py < gh - 1 && mask[p + gw] && !seen[p + gw]) { seen[p + gw] = true; queue[tail++] = p + gw }
                }
                if (n > maxCells || n < 3) continue

                val bw = (maxX - minX + 1).toDouble()
                val bh = (maxY - minY + 1).toDouble()
                val d = 2.0 * sqrt(n * step.toDouble() * step / PI)
                if (d < dLo || d > dHi) continue
                // Une balle est pleine et ronde : remplissage proche de π/4, rapport largeur/hauteur voisin de 1
                val fill = n / (bw * bh)
                val fillQ = (1.0 - abs(fill - 0.785) / 0.4).coerceIn(0.0, 1.0)
                val aspect = max(bw, bh) / min(bw, bh)
                val aspQ = (1.0 - max(0.0, aspect - 1.3) / 0.5).coerceIn(0.0, 1.0)
                val score = fillQ * aspQ
                if (score < 0.2) continue
                out.add(Blob(x0 + sx / n * step, y0 + sy / n * step, d, score))
                if (out.size >= 60) break
            }
            return out
        }
    }

    /**
     * Repérage précis au toucher : cherche la balle près du point touché, sur l'image la plus proche de t.
     * Retourne null si aucune tache « balle » ne se trouve à proximité.
     */
    fun snapToBall(path: String, t: Double, u: Double, v: Double, rawH: Int): Blob? {
        val ex = MediaExtractor()
        val thread = HandlerThread("Snap").also { it.start() }
        val handler = Handler(thread.looper)
        var codec: MediaCodec? = null
        var reader: ImageReader? = null
        var target: Image? = null
        try {
            ex.setDataSource(path)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i
                    fmt = f
                    break
                }
            }
            val format = fmt ?: return null
            ex.selectTrack(track)
            ex.seekTo((max(0.0, t - 0.1) * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)

            val queue = LinkedBlockingQueue<Image>()
            val rd = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 6)
            reader = rd
            rd.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { queue.add(it) } }, handler)

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, rd.surface, null, 0)
            dec.start()

            val info = MediaCodec.BufferInfo()
            var inEos = false
            var guard = 0
            while (target == null && guard++ < 3000) {
                if (!inEos) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) dec.releaseOutputBuffer(oi, info.size > 0)
                var frame = queue.poll(5, TimeUnit.MILLISECONDS)
                while (frame != null) {
                    if (target == null && frame.timestamp / 1e9 >= t - 0.004) target = frame else frame.close()
                    frame = queue.poll()
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            val img = target ?: return null
            // Zone de recherche : 5 % de la hauteur autour du toucher
            val r = 0.05 * rawH
            val x0 = (u - r).toInt().coerceIn(0, w - 1)
            val y0 = (v - r).toInt().coerceIn(0, h - 1)
            val x1 = (u + r).toInt().coerceIn(0, w - 1)
            val y1 = (v + r).toInt().coerceIn(0, h - 1)
            return findBallBlobs(img, x0, y0, x1, y1, 1, 2.0, 0.06 * rawH)
                .filter { it.score >= 0.2 }
                .minByOrNull { hypot(it.u - u, it.v - v) }
        } catch (_: Exception) {
            return null
        } finally {
            target?.close()
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            ex.release()
            thread.quitSafely()
        }
    }

    /**
     * Trajectoire tracée à partir des seuls repères posés (repli quand la détection automatique échoue).
     * Le premier repère est le tee ; les suivants (balle en vol) donnent une courbe lissée.
     * Les vitesses ne sont fiables que si la taille de la balle a été relevée au toucher.
     */
    fun fromMarkers(markers: List<DoubleArray>, rawW: Int, rawH: Int, focalRatio: Double): SwingResult? {
        val sorted = markers.sortedBy { it[0] }
        if (sorted.size < 5) return null
        val tee = sorted[0]
        val flight = sorted.drop(1)
        val known = flight.map { dOf(it) }.filter { !it.isNaN() && it > 0.0 }.sorted()
        val dDefault = if (known.isEmpty()) 0.016 * rawH else known[known.size / 2]
        val samples = flight.map { m ->
            val d = dOf(m)
            BallSample(m[0], m[1], m[2], if (d.isNaN() || d <= 0.0) dDefault else d)
        }
        val t0 = samples[0].tVid
        val ts = DoubleArray(samples.size) { samples[it].tVid - t0 }
        if (ts[ts.size - 1] < 0.05) return null   // repères simultanés : pas de courbe possible
        val cu = polyfit2(ts, DoubleArray(samples.size) { samples[it].u })
        val cv = polyfit2(ts, DoubleArray(samples.size) { samples[it].v })
        val dMean = samples.map { it.d }.average()
        val tEnd = ts[ts.size - 1]
        val smooth = ArrayList<BallSample>()
        var t = 0.0
        while (t <= tEnd + 1e-9) {
            val u = cu[0] + cu[1] * t + cu[2] * t * t
            val v = cv[0] + cv[1] * t + cv[2] * t * t
            smooth.add(BallSample(t0 + t, u, v, dMean))
            t += 0.01
        }
        val n = samples.size
        val span = ts[n - 1]
        val base = BallTracker(rawW, rawH, doubleArrayOf(tee[0], tee[1], tee[2]), emptyList(), focalRatio)
            .physics(samples)
        val common = base.copy(
            predicted = smooth,
            confidence = min(70, 20 + 8 * n),
            quality = if (n >= 6) "moyenne" else "faible",
            doubtful = emptyList(),
            nFit = n
        )
        // Physique seulement si au moins 4 points couvrent 0,3 s ; sinon, la courbe seule
        if (n >= 4 && span >= 0.3) return common
        return common.copy(
            ballSpeed = -1.0, launchDeg = 0.0, azimuthDeg = 0.0, curveM = 0.0, shape = "",
            carryM = -1.0, apexM = -1.0, hangS = -1.0, clubSpeed = -1.0
        )
    }

    private fun dOf(m: DoubleArray): Double = if (m.size > 3) m[3] else Double.NaN

    /** Taches « balle » d'une zone d'image (utilisé par le repérage automatique des sessions). */
    fun findBallBlobs(
        img: Image, x0: Int, y0: Int, x1: Int, y1: Int, step: Int, dLo: Double, dHi: Double
    ): List<Blob> = BallTracker(img.width, img.height, doubleArrayOf(0.0, 0.0, 0.0), emptyList(), 0.0)
        .blobsIn(img, x0, y0, x1, y1, step, dLo, dHi)

    /** Forme estimée à partir de la direction de départ et de la déviation finale. */
    private fun classify(azimuthDeg: Double, curve: Double): String {
        val s = if (azimuthDeg > 1.0) 1 else if (azimuthDeg < -1.0) -1 else 0   // +1 départ à droite
        val c = if (curve > 2.0) 1 else if (curve < -2.0) -1 else 0            // +1 courbe à droite
        val big = abs(curve) > 8.0
        return when {
            s == 0 && c == 0 -> "tout droit"
            s == 0 && c > 0 -> if (big) "slice" else "fade"
            s == 0 && c < 0 -> if (big) "hook" else "draw"
            s > 0 && c == 0 -> "push"
            s < 0 && c == 0 -> "pull"
            s > 0 && c > 0 -> if (big) "push-slice" else "push-fade"
            s > 0 && c < 0 -> if (big) "push-hook" else "push-draw"
            s < 0 && c < 0 -> if (big) "pull-hook" else "pull-draw"
            else -> if (big) "pull-slice" else "pull-fade"
        }
    }

    /**
     * Simulation 3D avec traînée et accélération latérale constante (effet observé).
     * Chaque point : [t, x (latéral), y (hauteur), z (profondeur)], relatif au point de départ.
     */
    private fun simulate(vx: Double, vy: Double, vz: Double, ax: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val dt = 0.002
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var ux = vx
        var uy = vy
        var uz = vz
        var t = 0.0
        var step = 0
        out.add(doubleArrayOf(0.0, 0.0, 0.0, 0.0))
        while (t < 8.0) {
            val sp = sqrt(ux * ux + uy * uy + uz * uz)
            ux += (ax - DRAG_K * sp * ux) * dt
            uy += (-GRAVITY - DRAG_K * sp * uy) * dt
            uz += -DRAG_K * sp * uz * dt
            x += ux * dt
            y += uy * dt
            z += uz * dt
            t += dt
            step++
            if (step % 5 == 0) out.add(doubleArrayOf(t, x, y, z))
            if (y < 0.0 && t > 0.05) {
                out.add(doubleArrayOf(t, x, y, z))
                break
            }
        }
        return out
    }

    /** Ajustement quadratique y = a + b t + c t² (moindres carrés). Retourne [a, b, c]. */
    private fun polyfit2(t: DoubleArray, y: DoubleArray): DoubleArray {
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var r0 = 0.0; var r1 = 0.0; var r2 = 0.0
        for (i in t.indices) {
            val ti = t[i]; val yi = y[i]; val t2 = ti * ti
            s0 += 1.0; s1 += ti; s2 += t2; s3 += t2 * ti; s4 += t2 * t2
            r0 += yi; r1 += ti * yi; r2 += t2 * yi
        }
        val m = arrayOf(
            doubleArrayOf(s0, s1, s2),
            doubleArrayOf(s1, s2, s3),
            doubleArrayOf(s2, s3, s4)
        )
        val rhs = doubleArrayOf(r0, r1, r2)
        val det = det3(m)
        if (abs(det) < 1e-18) return doubleArrayOf(0.0, 0.0, 0.0)
        return DoubleArray(3) { col ->
            val mc = Array(3) { r -> DoubleArray(3) { c -> if (c == col) rhs[r] else m[r][c] } }
            det3(mc) / det
        }
    }

    private fun det3(m: Array<DoubleArray>): Double =
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
        m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
        m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
}
