package com.krudoy.stlviewer.render

import android.opengl.Matrix
import kotlin.math.max
import kotlin.math.tan

/**
 * Orbit camera around a model normalized to the unit sphere.
 *
 * Rotation is an accumulated trackball-style matrix (no gimbal lock): each drag
 * rotates the model about the screen's X and Y axes. Pan moves the model in the
 * view plane, and zoom moves the camera closer. All methods are synchronized
 * because gestures run on the UI thread and drawing runs on the GL thread.
 */
class Camera {
    private val rotation = FloatArray(16)
    private val tmp = FloatArray(16)
    private val delta = FloatArray(16)

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    var viewportWidth = 1; private set
    var viewportHeight = 1; private set

    init { reset() }

    @Synchronized
    fun reset() {
        zoom = 1f; panX = 0f; panY = 0f
        // STL files are usually Z-up. Turn Z to screen-up, then tilt into a 3/4 view.
        Matrix.setIdentityM(rotation, 0)
        Matrix.rotateM(rotation, 0, 25f, 1f, 0f, 0f)
        Matrix.rotateM(rotation, 0, -90f, 1f, 0f, 0f)
        Matrix.rotateM(rotation, 0, -35f, 0f, 0f, 1f)
    }

    @Synchronized
    fun setViewport(width: Int, height: Int) {
        viewportWidth = max(1, width); viewportHeight = max(1, height)
    }

    /** Rotate by a finger drag of (dx, dy) pixels. */
    @Synchronized
    fun rotateBy(dx: Float, dy: Float) {
        val degreesPerPixel = 180f / minOf(viewportWidth, viewportHeight)
        val ax = dy * degreesPerPixel
        val ay = dx * degreesPerPixel
        Matrix.setIdentityM(delta, 0)
        if (ay != 0f) Matrix.rotateM(delta, 0, ay, 0f, 1f, 0f)
        if (ax != 0f) Matrix.rotateM(delta, 0, ax, 1f, 0f, 0f)
        Matrix.multiplyMM(tmp, 0, delta, 0, rotation, 0)
        System.arraycopy(tmp, 0, rotation, 0, 16)
        orthonormalize()
    }

    /** Move the model so that it follows a two-finger drag of (dx, dy) pixels. */
    @Synchronized
    fun panBy(dx: Float, dy: Float) {
        val unitsPerPixel = unitsPerPixel()
        panX += dx * unitsPerPixel
        panY -= dy * unitsPerPixel
    }

    /**
     * Zoom by [factor] (>1 zooms in), keeping the point under ([fx], [fy]) in
     * view pixels fixed on screen.
     */
    @Synchronized
    fun zoomAt(factor: Float, fx: Float, fy: Float) {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val f = newZoom / zoom
        if (f == 1f) return
        val upp = unitsPerPixel()
        // Offset of the focus from the screen center, in view-plane units.
        val sx = (fx - viewportWidth / 2f) * upp
        val sy = -(fy - viewportHeight / 2f) * upp
        // Keep (q + pan) / distance constant for the point q under the finger.
        val qx = sx - panX; val qy = sy - panY
        panX = (qx + panX) / f - qx
        panY = (qy + panY) / f - qy
        zoom = newZoom
    }

    /** Fills [out] with the view matrix (without model normalization). */
    @Synchronized
    fun viewMatrix(out: FloatArray) {
        Matrix.setIdentityM(tmp, 0)
        Matrix.translateM(tmp, 0, panX, panY, -distance())
        Matrix.multiplyMM(out, 0, tmp, 0, rotation, 0)
    }

    @Synchronized
    fun projectionMatrix(out: FloatArray) {
        val d = distance()
        val near = max(d - 1.5f, d * 0.02f)
        val far = d + 1.5f
        Matrix.perspectiveM(out, 0, FOV_Y, viewportWidth.toFloat() / viewportHeight, near, far)
    }

    private fun distance(): Float = BASE_DISTANCE / zoom

    private fun unitsPerPixel(): Float =
        2f * distance() * tan(Math.toRadians(FOV_Y / 2.0)).toFloat() / viewportHeight

    /** Keeps accumulated float error from skewing the rotation over many drags. */
    private fun orthonormalize() {
        // Columns 0,1,2 are the basis vectors (column-major layout).
        val x = floatArrayOf(rotation[0], rotation[1], rotation[2])
        val y = floatArrayOf(rotation[4], rotation[5], rotation[6])
        normalize(x)
        val z = cross(x, y); normalize(z)
        val y2 = cross(z, x)
        rotation[0] = x[0]; rotation[1] = x[1]; rotation[2] = x[2]
        rotation[4] = y2[0]; rotation[5] = y2[1]; rotation[6] = y2[2]
        rotation[8] = z[0]; rotation[9] = z[1]; rotation[10] = z[2]
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )

    private fun normalize(v: FloatArray) {
        val l = Matrix.length(v[0], v[1], v[2])
        if (l > 0f) { v[0] /= l; v[1] /= l; v[2] /= l }
    }

    companion object {
        const val FOV_Y = 40f
        /** Distance at which the unit sphere fits the view with a small margin. */
        val BASE_DISTANCE = (1.15 / Math.sin(Math.toRadians(FOV_Y / 2.0))).toFloat()
        const val MIN_ZOOM = 0.2f
        const val MAX_ZOOM = 100f
    }
}
