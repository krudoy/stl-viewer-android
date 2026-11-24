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
 * - tap the orientation cube: look from that face, edge or corner
 */
class ModelView(context: Context) : GLSurfaceView(context) {

    val camera = Camera()
    private val cube = OrientationCube(camera)
    private val renderer = ModelRenderer(camera, cube) { requestRender() }

    private val density = resources.displayMetrics.density
    private var cubeTopInset = 0
    private var cubeRightInset = 0

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
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val region = cube.pick(e.x, e.y) ?: return false
            camera.snapToDirection(region[0].toFloat(), region[1].toFloat(), region[2].toFloat())
            requestRender()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (cube.contains(e.x, e.y)) return false
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
        cube.visible = mesh != null
        camera.reset(animate = false)
        requestRender()
    }

    fun resetView() {
        camera.reset(animate = true)
        requestRender()
    }

    /** Space taken by overlays at the top and right, so the cube sits just below the top bar. */
    fun setCubeInsets(top: Int, right: Int) {
        cubeTopInset = top; cubeRightInset = right
        layoutCube(width)
        requestRender()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutCube(w)
    }

    private fun layoutCube(viewWidth: Int) {
        val size = CUBE_SIZE_DP * density
        val margin = 12 * density
        cube.size = size
        cube.left = viewWidth - cubeRightInset - margin - size
        cube.top = cubeTopInset + margin
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
                downX = e.x; downY = e.y
                setHighlight(cube.pick(e.x, e.y))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                setHighlight(null)
                mode = Mode.PAN
                lastX = centroidX(e, -1); lastY = centroidY(e, -1)
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.ROTATE -> if (e.pointerCount == 1) {
                    val moved = Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > touchSlop
                    // A press on the cube stays a tap until the finger really moves.
                    if (cubeHighlighted && moved) setHighlight(null)
                    if (!cubeHighlighted) {
                        camera.rotateBy(e.x - lastX, e.y - lastY)
                        requestRender()
                    }
                    lastX = e.x; lastY = e.y
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                setHighlight(null)
            }
        }
        return true
    }

    private var downX = 0f
    private var downY = 0f
    private var cubeHighlighted = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private fun setHighlight(region: IntArray?) {
        cube.highlight = region
        cubeHighlighted = region != null
        requestRender()
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

    private companion object {
        const val CUBE_SIZE_DP = 96
    }
}
