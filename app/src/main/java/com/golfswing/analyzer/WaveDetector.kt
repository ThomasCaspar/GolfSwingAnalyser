package com.golfswing.analyzer

import android.media.Image
import kotlin.math.abs

/**
 * Détection du signe de la main, avec des filtres anti-faux déclenchements :
 * - amplitude minimale pour chaque aller simple (les tremblements sont ignorés) ;
 * - 3 allers simples valides dans une fenêtre de 2,2 s ;
 * - surface de mouvement limitée (ni secousse, ni déplacement global de la scène) ;
 * - période d'armement après la pose du téléphone ;
 * - délai de sécurité après chaque déclenchement.
 * Un déclenchement déclenche en plus un décompte annulable (voir MainActivity).
 */
class WaveDetector(
    private val onWave: () -> Unit,
    private val onProgress: (count: Int, needed: Int) -> Unit = { _, _ -> }
) {
    enum class Level(val label: String, val minAmp: Float, val minFrac: Float, val maxFrac: Float) {
        LOW("Faible", 0.26f, 0.010f, 0.20f),
        NORMAL("Normale", 0.18f, 0.005f, 0.25f),
        HIGH("Élevée", 0.12f, 0.003f, 0.30f)
    }

    var level = Level.NORMAL
    var enabled = true

    private val needed = 3
    private val windowMs = 2200L
    private val noMotionResetMs = 900L
    private val cooldownMs = 4000L

    private var prev: ByteArray? = null
    private var armedUntil = 0L
    private var cooldownUntil = 0L
    private var lastMotion = 0L
    private var dir = 0
    private var segStart = -1f
    private var extreme = -1f
    private val swings = ArrayDeque<Long>()
    private var lastCount = -1

    /** Ignore les mouvements pendant [ms] (le golfeur pose le téléphone). */
    fun armFor(ms: Long) {
        armedUntil = System.currentTimeMillis() + ms
    }

    fun reset() {
        prev = null
        resetSegments()
    }

    private fun resetSegments() {
        dir = 0
        segStart = -1f
        extreme = -1f
        swings.clear()
        notifyProgress(0)
    }

    private fun notifyProgress(count: Int) {
        if (count != lastCount) {
            lastCount = count
            onProgress(count, needed)
        }
    }

    /** À appeler pour chaque image YUV (plan Y uniquement). */
    fun process(image: Image) {
        if (!enabled) return
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val step = 2
        val sw = image.width / step
        val sh = image.height / step

        val cur = ByteArray(sw * sh)
        var i = 0
        for (y in 0 until sh) {
            val rowBase = y * step * rowStride
            for (x in 0 until sw) {
                cur[i++] = buf.get(rowBase + x * step * pixelStride)
            }
        }

        val p = prev
        prev = cur
        if (p == null || p.size != cur.size) return

        val now = System.currentTimeMillis()
        if (now < armedUntil || now < cooldownUntil) return

        var count = 0
        var sumX = 0L
        for (k in cur.indices) {
            val d = abs((cur[k].toInt() and 0xFF) - (p[k].toInt() and 0xFF))
            if (d > 28) {
                count++
                sumX += (k % sw)
            }
        }
        val frac = count.toFloat() / cur.size

        if (frac > 0.45f) {                   // secousse ou changement de lumière
            resetSegments()
            return
        }
        if (frac < level.minFrac || frac > level.maxFrac) {
            if (now - lastMotion > noMotionResetMs) resetSegments()
            return
        }
        lastMotion = now

        val cx = sumX.toFloat() / count / sw  // centre du mouvement (0..1)
        if (segStart < 0f) {
            segStart = cx; extreme = cx
            return
        }
        when {
            dir == 0 -> if (abs(cx - segStart) > 0.02f) {
                dir = if (cx > segStart) 1 else -1
                extreme = cx
            }
            dir > 0 -> if (cx > extreme) extreme = cx
                       else if (extreme - cx > 0.02f) turn(now, -1, cx)
            else -> if (cx < extreme) extreme = cx
                    else if (cx - extreme > 0.02f) turn(now, 1, cx)
        }

        while (swings.isNotEmpty() && now - swings.first() > windowMs) swings.removeFirst()
        notifyProgress(swings.size)

        if (swings.size >= needed) {
            cooldownUntil = now + cooldownMs
            resetSegments()
            onWave()
        }
    }

    /** Fin d'un aller simple : compté seulement si son amplitude est suffisante. */
    private fun turn(now: Long, newDir: Int, cx: Float) {
        if (abs(extreme - segStart) >= level.minAmp) swings.addLast(now)
        segStart = extreme
        extreme = cx
        dir = newDir
    }
}
