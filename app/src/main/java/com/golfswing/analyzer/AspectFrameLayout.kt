package com.golfswing.analyzer

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** Conteneur qui force un ratio largeur/hauteur (celui de la vidéo affichée). */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private var aspect = 0f

    fun setAspect(width: Int, height: Int) {
        aspect = if (width > 0 && height > 0) width.toFloat() / height else 0f
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = View.MeasureSpec.getSize(widthMeasureSpec)
        val maxH = View.MeasureSpec.getSize(heightMeasureSpec)
        if (aspect <= 0f || maxW == 0 || maxH == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        var w = maxW
        var h = (maxW / aspect).toInt()
        if (h > maxH) {
            h = maxH
            w = (maxH * aspect).toInt()
        }
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
    }
}
