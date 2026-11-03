package com.azhardhiaulhaq.macpad

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns window-space MotionEvents into POINTER and SCROLL messages. Pen: hover, strokes with
 * pressure and tilt, side button as right button. Fingers: tap, drag, two-finger scroll and
 * two-finger tap (right click). Fingers are ignored while the pen is near (palm rejection).
 * Main thread only.
 */
internal class InputMapper(context: Context, private val dot: HoverDotView) {
    var session: Session? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // Video rect in window coordinates.
    private var left = 0f
    private var top = 0f
    private var width = 1f
    private var height = 1f

    // Pen.
    private var penId = -1
    private var penButtons = 0
    private var penHovering = false
    private var lastPenTime = Long.MIN_VALUE / 2

    // Fingers.
    private var finger = F_IDLE
    private var id1 = -1
    private var id2 = -1
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var twoStartTime = 0L
    private var centroidX = 0f
    private var centroidY = 0f
    private var remainderX = 0f
    private var remainderY = 0f

    fun setVideoRect(l: Float, t: Float, w: Float, h: Float) {
        left = l
        top = t
        width = if (w > 0f) w else 1f
        height = if (h > 0f) h else 1f
    }

    /** Clears gesture state without sending anything (session closed or stream left). */
    fun reset() {
        penId = -1
        penHovering = false
        finger = F_IDLE
        dot.hide()
    }

    private fun nx(x: Float): Int = (((x - left) / width).coerceIn(0f, 1f) * 65535f).roundToInt()
    private fun ny(y: Float): Int = (((y - top) / height).coerceIn(0f, 1f) * 65535f).roundToInt()

    private fun isPen(e: MotionEvent, index: Int): Boolean {
        val tool = e.getToolType(index)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    // ---- Pen hover ----

    fun onGenericMotion(e: MotionEvent): Boolean {
        if (e.pointerCount == 0 || !isPen(e, 0)) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                lastPenTime = e.eventTime
                if (penId != -1) return true
                if (!penHovering) {
                    endFingerGesture()
                    penHovering = true
                    sendPen(ACTION_HOVER_ENTER, e, 0, -1)
                }
                for (h in 0 until e.historySize) sendPen(ACTION_HOVER_MOVE, e, 0, h)
                sendPen(ACTION_HOVER_MOVE, e, 0, -1)
                dot.show(e.x, e.y)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                lastPenTime = e.eventTime
                if (penHovering) {
                    penHovering = false
                    sendPen(ACTION_HOVER_EXIT, e, 0, -1)
                }
                dot.hide()
            }
            else -> return false
        }
        return true
    }

    // ---- Touch ----

    fun onTouch(e: MotionEvent) {
        val action = e.actionMasked
        val index = e.actionIndex
        var penEvent = false
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (isPen(e, index)) {
                penEvent = true
                if (penId == -1) {
                    endFingerGesture()
                    if (penHovering) {
                        penHovering = false
                        sendPen(ACTION_HOVER_EXIT, e, index, -1)
                    }
                    dot.hide()
                    penId = e.getPointerId(index)
                    penButtons = if (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) 1 else 0
                    sendPen(ACTION_DOWN, e, index, -1)
                }
            }
            MotionEvent.ACTION_MOVE -> if (penId != -1) {
                penEvent = true
                val i = e.findPointerIndex(penId)
                if (i >= 0) {
                    for (h in 0 until e.historySize) sendPen(ACTION_MOVE, e, i, h)
                    sendPen(ACTION_MOVE, e, i, -1)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (penId != -1 && e.getPointerId(index) == penId) {
                    penEvent = true
                    sendPen(ACTION_UP, e, index, -1)
                    penId = -1
                }
            MotionEvent.ACTION_CANCEL -> if (penId != -1) {
                penEvent = true
                val i = e.findPointerIndex(penId)
                sendPen(ACTION_UP, e, if (i >= 0) i else 0, -1)
                penId = -1
            }
        }
        if (penEvent || penId != -1) {
            lastPenTime = e.eventTime
            return
        }
        onFinger(e, action, index)
    }

    private fun penNear(time: Long) = penId != -1 || penHovering || time - lastPenTime < PALM_WINDOW_MS

    private fun sendPen(action: Int, e: MotionEvent, i: Int, h: Int) {
        val s = session ?: return
        val x: Float
        val y: Float
        val pressure: Float
        val tilt: Float
        val orientation: Float
        if (h < 0) {
            x = e.getX(i)
            y = e.getY(i)
            pressure = e.getPressure(i)
            tilt = e.getAxisValue(MotionEvent.AXIS_TILT, i)
            orientation = e.getAxisValue(MotionEvent.AXIS_ORIENTATION, i)
        } else {
            x = e.getHistoricalX(i, h)
            y = e.getHistoricalY(i, h)
            pressure = e.getHistoricalPressure(i, h)
            tilt = e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h)
            orientation = e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, i, h)
        }
        val touching = action == ACTION_DOWN || action == ACTION_MOVE
        val p = if (touching) (pressure.coerceIn(0f, 1f) * 65535f).roundToInt() else 0
        val lean = sin(tilt)
        val tiltX = (lean * sin(orientation) * 32767f).roundToInt().coerceIn(-32767, 32767)
        val tiltY = (-lean * cos(orientation) * 32767f).roundToInt().coerceIn(-32767, 32767)
        val buttons = if (action >= ACTION_DOWN) penButtons else 0
        s.pointer(action, TOOL_PEN, buttons, nx(x), ny(y), p, tiltX, tiltY)
    }

    // ---- Fingers ----

    private fun onFinger(e: MotionEvent, action: Int, index: Int) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                finger = if (penNear(e.eventTime)) F_IGNORE else F_PENDING
                id1 = e.getPointerId(0)
                startX = e.x
                startY = e.y
                lastX = startX
                lastY = startY
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (finger == F_PENDING) {
                if (penNear(e.eventTime)) {
                    finger = F_IGNORE
                    return
                }
                id2 = e.getPointerId(index)
                twoStartTime = e.eventTime
                if (!centroid(e)) return
                startX = centroidX
                startY = centroidY
                finger = F_TWO
            }
            MotionEvent.ACTION_MOVE -> {
                if (finger != F_IDLE && finger != F_IGNORE && penNear(e.eventTime)) {
                    endFingerGesture()
                    return
                }
                when (finger) {
                    F_PENDING -> {
                        val i = e.findPointerIndex(id1)
                        if (i < 0 || hypot(e.getX(i) - startX, e.getY(i) - startY) <= slop) return
                        fingerPointer(ACTION_DOWN, startX, startY, 0)
                        finger = F_DRAG
                        dragMove(e, i)
                    }
                    F_DRAG -> {
                        val i = e.findPointerIndex(id1)
                        if (i >= 0) dragMove(e, i)
                    }
                    F_TWO -> {
                        if (!centroid(e) || hypot(centroidX - startX, centroidY - startY) <= slop) return
                        session?.scroll(SCROLL_BEGAN, nx(centroidX), ny(centroidY), 0, 0)
                        finger = F_SCROLL
                        lastX = startX
                        lastY = startY
                        remainderX = 0f
                        remainderY = 0f
                        scrollTo(centroidX, centroidY)
                    }
                    F_SCROLL -> if (centroid(e)) scrollTo(centroidX, centroidY)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(index)
                when (finger) {
                    F_DRAG -> if (id == id1) {
                        fingerPointer(ACTION_UP, e.getX(index), e.getY(index), 0)
                        finger = F_IGNORE
                    }
                    F_TWO -> if (id == id1 || id == id2) finger = F_TWO_LIFTING
                    F_SCROLL -> if (id == id1 || id == id2) {
                        session?.scroll(SCROLL_ENDED, nx(lastX), ny(lastY), 0, 0)
                        finger = F_IGNORE
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                when (finger) {
                    F_PENDING -> {
                        fingerPointer(ACTION_DOWN, startX, startY, 0)
                        fingerPointer(ACTION_UP, startX, startY, 0)
                    }
                    F_DRAG -> fingerPointer(ACTION_UP, e.x, e.y, 0)
                    F_TWO, F_TWO_LIFTING -> if (e.eventTime - twoStartTime <= TWO_FINGER_TAP_MS) {
                        fingerPointer(ACTION_DOWN, startX, startY, 1)
                        fingerPointer(ACTION_UP, startX, startY, 1)
                    }
                    F_SCROLL -> session?.scroll(SCROLL_ENDED, nx(lastX), ny(lastY), 0, 0)
                }
                finger = F_IDLE
            }
            MotionEvent.ACTION_CANCEL -> {
                endFingerGesture()
                finger = F_IDLE
            }
        }
    }

    /** Ends an active drag or scroll cleanly; the remaining touches are ignored until all lift. */
    private fun endFingerGesture() {
        when (finger) {
            F_DRAG -> fingerPointer(ACTION_UP, lastX, lastY, 0)
            F_SCROLL -> session?.scroll(SCROLL_ENDED, nx(lastX), ny(lastY), 0, 0)
        }
        if (finger != F_IDLE) finger = F_IGNORE
    }

    private fun dragMove(e: MotionEvent, i: Int) {
        for (h in 0 until e.historySize) fingerPointer(ACTION_MOVE, e.getHistoricalX(i, h), e.getHistoricalY(i, h), 0)
        lastX = e.getX(i)
        lastY = e.getY(i)
        fingerPointer(ACTION_MOVE, lastX, lastY, 0)
    }

    private fun fingerPointer(action: Int, x: Float, y: Float, buttons: Int) {
        val pressure = if (action == ACTION_UP) 0 else 65535
        session?.pointer(action, TOOL_FINGER, buttons, nx(x), ny(y), pressure, 0, 0)
    }

    /** Sets [centroidX]/[centroidY] from the two tracked fingers; false if one is missing. */
    private fun centroid(e: MotionEvent): Boolean {
        val a = e.findPointerIndex(id1)
        val b = e.findPointerIndex(id2)
        if (a < 0 || b < 0) return false
        centroidX = (e.getX(a) + e.getX(b)) * 0.5f
        centroidY = (e.getY(a) + e.getY(b)) * 0.5f
        return true
    }

    /** Sends the centroid movement since the last SCROLL in whole panel pixels, keeping fractions. */
    private fun scrollTo(x: Float, y: Float) {
        val fx = x - lastX + remainderX
        val fy = y - lastY + remainderY
        val dx = fx.toInt().coerceIn(-32767, 32767)
        val dy = fy.toInt().coerceIn(-32767, 32767)
        remainderX = fx - dx
        remainderY = fy - dy
        lastX = x
        lastY = y
        if (dx != 0 || dy != 0) session?.scroll(SCROLL_CHANGED, nx(x), ny(y), dx, dy)
    }

    private companion object {
        const val ACTION_HOVER_ENTER = 0
        const val ACTION_HOVER_MOVE = 1
        const val ACTION_HOVER_EXIT = 2
        const val ACTION_DOWN = 3
        const val ACTION_MOVE = 4
        const val ACTION_UP = 5
        const val TOOL_FINGER = 0
        const val TOOL_PEN = 1
        const val SCROLL_BEGAN = 1
        const val SCROLL_CHANGED = 2
        const val SCROLL_ENDED = 3

        const val PALM_WINDOW_MS = 500L
        const val TWO_FINGER_TAP_MS = 300L

        const val F_IDLE = 0
        const val F_PENDING = 1
        const val F_DRAG = 2
        const val F_TWO = 3
        const val F_TWO_LIFTING = 4
        const val F_SCROLL = 5
        const val F_IGNORE = 6
    }
}
