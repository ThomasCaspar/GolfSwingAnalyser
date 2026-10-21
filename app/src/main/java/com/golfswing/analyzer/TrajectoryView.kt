package com.golfswing.analyzer

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

/**
 * Overlay affiché par-dessus la vidéo. Le dessin est partagé avec l'export (TracerOverlay).
 *
 * Conversion : la vidéo est tournée de 90° pour l'affichage portrait.
 *   affichage_x = (1 - v / H_brut) * largeur_vue
 *   affichage_y = (u / W_brut)     * hauteur_vue
 */
class TrajectoryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var rawW = 1
    private var rawH = 1
    private var markers: List<DoubleArray> = emptyList()
    private var result: SwingResult? = null
    private var tNow = 0.0
    private val overlay = TracerOverlay()

    fun setRawSize(w: Int, h: Int) {
        rawW = w
        rawH = h
        invalidate()
    }

    /** Repères [temps, u, v] en pixels bruts. */
    fun setMarkers(list: List<DoubleArray>) {
        markers = list.map { it.copyOf() }
        invalidate()
    }

    fun setResult(r: SwingResult?) {
        result = r
        invalidate()
    }

    /** Temps de lecture courant (s) : le tracé se dessine jusqu'à ce point, puis reste affiché. */
    fun setTime(t: Double) {
        tNow = t
        invalidate()
    }

    /** Convertit un toucher (coordonnées de la vue) en pixels bruts. */
    fun viewToRaw(x: Float, y: Float): Pair<Double, Double> {
        val du = (x / width).coerceIn(0f, 1f).toDouble()
        val dv = (y / height).coerceIn(0f, 1f).toDouble()
        return Pair(dv * rawW, (1.0 - du) * rawH)
    }

    private fun toView(u: Double, v: Double): PointF {
        val du = 1.0 - v / rawH
        val dv = u / rawW
        return PointF((du * width).toFloat(), (dv * height).toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rawH <= 0) return
        // Pixels du canevas par pixel brut (la largeur affichée correspond à la hauteur brute)
        val k = width.toFloat() / rawH
        overlay.draw(canvas, result, markers, tNow, k) { u, v -> toView(u, v) }
    }
}
