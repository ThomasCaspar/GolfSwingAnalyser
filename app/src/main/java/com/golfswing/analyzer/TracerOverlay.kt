package com.golfswing.analyzer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Dessin du tracé « shot tracer » : utilisé à l'écran (TrajectoryView) et lors de l'export vidéo.
 *
 * - Trajectoire observée : trait orange lumineux, tracé au fil du temps, qui reste affiché après le vol.
 * - Trajectoire prévisionnelle : en pointillés après le dernier point détecté ; son opacité suit la fiabilité.
 * - Points détectés en blanc, tête de balle pendant le vol, anneau à l'atterrissage.
 * - Repères posés avant l'analyse, en rouge.
 *
 * Toutes les épaisseurs sont exprimées en pixels bruts et multipliées par [k] (pixels du canevas par pixel brut).
 */
class TracerOverlay {

    private val orange = Color.parseColor("#FF9100")

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = orange
        alpha = 110
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE0B2")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = orange
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val detected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val headFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val headRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = orange
        style = Paint.Style.STROKE
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0101410")
        style = Paint.Style.FILL
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val observed = Path()
    private val predicted = Path()
    private val rect = RectF()

    /**
     * @param r résultat de l'analyse, ou null (avant analyse : seuls les repères sont dessinés)
     * @param markers repères [temps, u, v] en pixels bruts
     * @param t instant courant (s) de la vidéo
     * @param k pixels du canevas par pixel brut
     * @param map conversion pixels bruts -> coordonnées du canevas
     */
    fun draw(
        canvas: Canvas,
        r: SwingResult?,
        markers: List<DoubleArray>,
        t: Double,
        k: Float,
        map: (Double, Double) -> PointF
    ) {
        if (r == null) {
            marker.strokeWidth = 4f * k
            for (m in markers) {
                val p = map(m[1], m[2])
                canvas.drawCircle(p.x, p.y, 20f * k, marker)
            }
            return
        }
        if (t < r.impactT - 0.3) return

        glow.strokeWidth = 18f * k
        core.strokeWidth = 5f * k
        dashed.strokeWidth = 5f * k
        dashed.pathEffect = DashPathEffect(floatArrayOf(18f * k, 12f * k), 0f)
        dashed.alpha = (60 + r.confidence * 1.9).toInt().coerceIn(60, 230)

        val tEnd = r.samples.lastOrNull()?.tVid ?: r.impactT
        observed.reset()
        predicted.reset()
        var moveO = true
        var moveP = true
        var head: PointF? = null
        for (p in r.predicted) {
            if (p.tVid > t) break
            val q = map(p.u, p.v)
            if (p.tVid <= tEnd) {
                if (moveO) { observed.moveTo(q.x, q.y); moveO = false } else observed.lineTo(q.x, q.y)
            } else {
                if (moveP) { predicted.moveTo(q.x, q.y); moveP = false } else predicted.lineTo(q.x, q.y)
            }
            head = q
        }
        if (!moveO) {
            canvas.drawPath(observed, glow)
            canvas.drawPath(observed, core)
        }
        if (!moveP) canvas.drawPath(predicted, dashed)

        for (s in r.samples) {
            if (s.tVid > t) break
            val q = map(s.u, s.v)
            canvas.drawCircle(q.x, q.y, 4f * k, detected)
        }

        val landingT = r.predicted.lastOrNull()?.tVid ?: tEnd
        head?.let {
            if (t < landingT) {
                canvas.drawCircle(it.x, it.y, 11f * k, headFill)
                headRing.strokeWidth = 5f * k
                canvas.drawCircle(it.x, it.y, 17f * k, headRing)
            } else {
                // Atterrissage prévu : anneau blanc fixe
                headRing.strokeWidth = 3f * k
                headRing.color = Color.WHITE
                canvas.drawCircle(it.x, it.y, 14f * k, headRing)
                headRing.color = orange
            }
        }

        drawLabel(canvas, r, t, k)
    }

    private fun drawLabel(canvas: Canvas, r: SwingResult, t: Double, k: Float) {
        if (t < r.impactT) return
        val lines = r.overlayLines()
        labelText.textSize = 30f * k
        val pad = 14f * k
        val lineH = 38f * k
        var maxW = 0f
        for (l in lines) maxW = maxOf(maxW, labelText.measureText(l))
        val left = 24f * k
        val top = 24f * k
        rect.set(left, top, left + maxW + 2 * pad, top + lines.size * lineH + pad)
        canvas.drawRoundRect(rect, 16f * k, 16f * k, labelBg)
        var y = top + pad + 26f * k
        for (l in lines) {
            canvas.drawText(l, left + pad, y, labelText)
            y += lineH
        }
    }
}
