package com.golfswing.analyzer

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import java.util.Collections
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Parcourt une vidéo de session en une seule passe :
 * 1. mesure le mouvement image par image : un swing est un épisode de mouvement intense ;
 * 2. cherche, juste avant chaque épisode, la balle posée : une tache ronde qui reste immobile.
 *
 * Résultat indicatif : le comptage peut rater un swing ou en fusionner deux, et une balle
 * non trouvée est signalée pour être touchée à la main dans la revue.
 */
object SessionScanner {

    class Swing(
        val runStart: Double,   // début du mouvement (s)
        val runEnd: Double,     // fin du mouvement (s)
        val seedT: Double,      // instant du repère de balle (s), avant le mouvement
        val seedU: Double,      // position de la balle (pixels bruts)
        val seedV: Double,
        val seedFound: Boolean
    )

    class Scan(val duration: Double, val swings: List<Swing>)

    private class Frame(
        val t: Double,
        val energy: Double,
        val hasBlobs: Boolean,
        val blobs: List<SwingAnalyzer.Blob>
    )

    private class Seed(val t: Double, val u: Double, val v: Double)

    private const val GRID = 8          // pas d'échantillonnage pour le mouvement
    private const val BLOB_EVERY_S = 0.05   // recherche de la balle toutes les 50 ms, quelle que soit la cadence
    private const val BLOB_STEP = 4

    /** Retourne null si la vidéo est illisible. onProgress reçoit une valeur de 0 à 1. */
    fun scan(path: String, onProgress: (Float) -> Unit): Scan? {
        val frames: MutableList<Frame> = Collections.synchronizedList(ArrayList<Frame>())
        val ex = MediaExtractor()
        val thread = HandlerThread("SessionScan").also { it.start() }
        var codec: MediaCodec? = null
        var reader: ImageReader? = null
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
            if (track < 0) return null
            ex.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            val durUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 1L
            val gw = w / GRID
            val gh = h / GRID
            // Diamètre de balle plausible dans une image complète : de 0,7 % à 3,5 % de la hauteur
            val blobMin = 0.007 * h
            val blobMax = 0.035 * h

            val rd = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 4)
            reader = rd
            var prev: IntArray? = null
            var lastBlobT = -1.0
            rd.setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    val t = img.timestamp / 1e9
                    val cur = sampleLuma(img, gw, gh)
                    val p = prev
                    val energy = if (p == null) 0.0 else meanAbsDiff(cur, p)
                    prev = cur
                    val withBlobs = t - lastBlobT >= BLOB_EVERY_S
                    if (withBlobs) lastBlobT = t
                    val blobs = if (withBlobs) {
                        SwingAnalyzer.findBallBlobs(img, 0, 0, w - 1, h - 1, BLOB_STEP, blobMin, blobMax)
                            .filter { it.score >= 0.5 }
                    } else {
                        emptyList()
                    }
                    frames.add(Frame(t, energy, withBlobs, blobs))
                } finally {
                    img.close()
                }
            }, Handler(thread.looper))

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, rd.surface, null, 0)
            dec.start()

            val info = MediaCodec.BufferInfo()
            var inEos = false
            var outEos = false
            while (!outEos) {
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
                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    dec.releaseOutputBuffer(outIdx, info.size > 0)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                    onProgress(min(1f, info.presentationTimeUs.toFloat() / durUs))
                }
            }

            // Attendre que les dernières images arrivent dans l'ImageReader
            var last = -1
            var stable = 0
            var waited = 0
            while (stable < 3 && waited < 40) {
                Thread.sleep(100)
                waited++
                val now = frames.size
                if (now == last) stable++ else { stable = 0; last = now }
            }
            val snapshot = synchronized(frames) { frames.toList() }
            return segment(snapshot, durUs / 1e6)
        } catch (_: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            ex.release()
            thread.quitSafely()
        }
    }

    /** Luminance échantillonnée sur une grille grossière, pour mesurer le mouvement. */
    private fun sampleLuma(img: Image, gw: Int, gh: Int): IntArray {
        val yp = img.planes[0]
        val yb = yp.buffer
        val rs = yp.rowStride
        val ps = yp.pixelStride
        val out = IntArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                out[gy * gw + gx] = yb.get(gy * GRID * rs + gx * GRID * ps).toInt() and 0xFF
            }
        }
        return out
    }

    private fun meanAbsDiff(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) s += abs(a[i] - b[i])
        return s.toDouble() / a.size
    }

    /** Découpe la vidéo en épisodes de mouvement et associe à chacun un repère de balle. */
    private fun segment(frames: List<Frame>, duration: Double): Scan? {
        val n = frames.size
        if (n < 30) return null
        val median = frames.map { it.energy }.sorted()[n / 2]
        val thr = max(median * 3.0, median + 2.5)

        // Épisodes où le mouvement dépasse le seuil
        val runs = ArrayList<IntArray>()
        var i = 0
        while (i < n) {
            if (frames[i].energy > thr) {
                var j = i
                while (j + 1 < n && frames[j + 1].energy > thr) j++
                runs.add(intArrayOf(i, j))
                i = j + 1
            } else {
                i++
            }
        }

        // Fusion des épisodes trop proches : un même swing contient des pauses
        val merged = ArrayList<IntArray>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && frames[r[0]].t - frames[last[1]].t < 1.2) {
                last[1] = r[1]
            } else {
                merged.add(intArrayOf(r[0], r[1]))
            }
        }

        val swings = ArrayList<Swing>()
        for (r in merged) {
            val tStart = frames[r[0]].t
            val tEnd = frames[r[1]].t
            if (tEnd - tStart < 0.6) continue
            val seed = findSeed(frames, tStart)
            swings.add(
                Swing(
                    runStart = tStart,
                    runEnd = tEnd,
                    seedT = seed?.t ?: (tStart - 0.3),
                    seedU = seed?.u ?: 0.0,
                    seedV = seed?.v ?: 0.0,
                    seedFound = seed != null
                )
            )
        }
        return Scan(duration, swings)
    }

    /**
     * Balle posée : la tache qui revient le plus souvent, au même endroit, pendant la pause
     * qui précède le mouvement. Un objet qui bouge (manche, golfeur) ne revient pas au même endroit.
     */
    private fun findSeed(frames: List<Frame>, tStart: Double): Seed? {
        val window = frames.filter { it.hasBlobs && it.t >= tStart - 1.2 && it.t <= tStart - 0.1 }
        if (window.isEmpty()) return null
        var best: Seed? = null
        var bestCount = 0
        var bestScore = 0.0
        for (f in window) {
            for (c in f.blobs) {
                var count = 0
                var lastT = f.t
                val tol = 0.6 * c.d + 3.0
                for (g in window) {
                    for (b in g.blobs) {
                        if (abs(b.u - c.u) < tol && abs(b.v - c.v) < tol) {
                            count++
                            if (g.t > lastT) lastT = g.t
                            break
                        }
                    }
                }
                if (count > bestCount || (count == bestCount && c.score > bestScore)) {
                    bestCount = count
                    bestScore = c.score
                    best = Seed(lastT, c.u, c.v)
                }
            }
        }
        return if (bestCount >= 3) best else null
    }
}
