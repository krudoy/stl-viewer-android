package com.krudoy.stlviewer.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.krudoy.stlviewer.model.Mesh

/**
 * GL view with touch navigation:
 * - one finger drag: rotate
 * - two finger drag: pan
 * - pinch: zoom toward the fingers
 * - double tap: reset the view
 */
class ModelView(context: Context) : GLSurfaceView(context) {

    val camera = Camera()
    private val renderer = ModelRenderer(camera)

    private enum class Mode { NONE, ROTATE, PAN }
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                camera.zoomAt(d.scaleFactor, d.focusX, d.focusY)
                requestRender()
                return true
            }
        }).apply { isQuickScaleEnabled = false }

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetView()
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setMesh(mesh: Mesh?) {
        renderer.setMesh(mesh)
        camera.reset()
        requestRender()
    }

    fun resetView() {
        camera.reset()
        requestRender()
    }

    /** Scroll-wheel zoom for emulators, Chromebooks and mice. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                camera.zoomAt(if (v > 0) 1.1f else 1f / 1.1f, event.x, event.y)
                requestRender()
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        tapDetector.onTouchEvent(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.ROTATE
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                mode = Mode.PAN
                lastX = centroidX(e, -1); lastY = centroidY(e, -1)
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.ROTATE -> if (e.pointerCount == 1) {
                    camera.rotateBy(e.x - lastX, e.y - lastY)
                    lastX = e.x; lastY = e.y
                    requestRender()
                }
                Mode.PAN -> if (e.pointerCount >= 2) {
                    val cx = centroidX(e, -1); val cy = centroidY(e, -1)
                    camera.panBy(cx - lastX, cy - lastY)
                    lastX = cx; lastY = cy
                    requestRender()
                }
                Mode.NONE -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount - 1 >= 2) {
                    // Still panning with the remaining fingers; re-anchor without the lifted one.
                    lastX = centroidX(e, e.actionIndex); lastY = centroidY(e, e.actionIndex)
                } else {
                    // Back to one finger: stop until it lifts, so the model does not jump.
                    mode = Mode.NONE
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = Mode.NONE
        }
        return true
    }

    private fun centroidX(e: MotionEvent, skip: Int): Float {
        var sum = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getX(i); n++ }
        return if (n > 0) sum / n else e.x
    }

    private fun centroidY(e: MotionEvent, skip: Int): Float {
        var sum = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getY(i); n++ }
        return if (n > 0) sum / n else e.y
    }
}
