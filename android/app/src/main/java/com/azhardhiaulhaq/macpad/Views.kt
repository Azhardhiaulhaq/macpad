package com.azhardhiaulhaq.macpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/**
 * Local pen hover marker drawn on top of the video so aiming does not wait for the stream.
 * It moves by translation only, so following the pen never re-records its drawing.
 */
internal class HoverDotView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }

    fun show(x: Float, y: Float) {
        translationX = x - RADIUS_DP * density
        translationY = y - RADIUS_DP * density
        if (visibility != VISIBLE) visibility = VISIBLE
    }

    fun hide() {
        if (visibility != GONE) visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width * 0.5f
        val cy = height * 0.5f
        canvas.drawCircle(cx, cy, RADIUS_DP * density, outline)
        canvas.drawCircle(cx, cy, (RADIUS_DP - 1f) * density, fill)
    }

    private companion object {
        /** 8dp dot plus a 1dp outline; matches the 10dp view size in the layout. */
        const val RADIUS_DP = 5f
    }
}

/** Vertical column that never grows wider than `android:maxWidth`. */
internal class MaxWidthLinearLayout(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val maxWidth: Int

    init {
        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxWidth))
        maxWidth = a.getDimensionPixelSize(0, Int.MAX_VALUE)
        a.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (size > maxWidth) MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }
}
