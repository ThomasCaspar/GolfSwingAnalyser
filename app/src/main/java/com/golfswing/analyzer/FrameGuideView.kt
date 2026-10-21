package com.golfswing.analyzer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Cadre virtuel fixe, par-dessus l'aperçu (tir par tir) : la balle doit être posée dedans.
 * Vert quand une balle est reconnue dans le cadre, rouge pointillé sinon.
 * Les fractions X0..Y1 sont aussi celles utilisées par la détection dans l'image d'analyse.
 */
class FrameGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val X0 = 0.30f
        const val X1 = 0.70f
        const val Y0 = 0.52f
        const val Y1 = 0.78f
        private const val GREEN = "#00E676"
        private const val RED = "#FF3D00"
    }

    private val density = resources.displayMetrics.density
    private var found = false
    private val rect = RectF()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14 * density
        setShadowLayer(6 * density, 0f, 0f, Color.BLACK)
    }

    fun setFound(value: Boolean) {
        if (found == value) return
        found = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect.set(width * X0, height * Y0, width * X1, height * Y1)
        stroke.color = Color.parseColor(if (found) GREEN else RED)
        stroke.pathEffect = if (found) null else DashPathEffect(floatArrayOf(14 * density, 10 * density), 0f)
        canvas.drawRoundRect(rect, 12 * density, 12 * density, stroke)
        val text = if (found) "Balle dans le cadre" else "Placez la balle dans le cadre"
        canvas.drawText(text, rect.left, rect.top - 8 * density, label)
    }
}
