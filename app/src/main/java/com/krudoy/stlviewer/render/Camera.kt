package com.krudoy.stlviewer.render

import android.opengl.Matrix
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Orbit camera around a model normalized to the unit sphere.
 *
 * Rotation is an accumulated trackball-style matrix (no gimbal lock): each drag
 * rotates the model about the screen's X and Y axes. Pan moves the model in the
 * view plane, and zoom moves the camera closer. Views can also be snapped to a
 * direction with a short animation (used by the orientation cube).
 *
 * All methods are synchronized because gestures run on the UI thread and
 * drawing runs on the GL thread.
 */
class Camera {
    private val rotation = FloatArray(16)
    private val tmp = FloatArray(16)
    private val delta = FloatArray(16)

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    // Animation state: rotation as quaternions (x, y, z, w), pan and zoom linearly.
    private var animStart = 0L
    private var animating = false
    private val fromQ = FloatArray(4)
    private val toQ = FloatArray(4)
    private var fromPanX = 0f; private var fromPanY = 0f; private var fromZoom = 1f
    private var toPanX = 0f; private var toPanY = 0f; private var toZoom = 1f

    var viewportWidth = 1; private set
    var viewportHeight = 1; private set

    init { reset(animate = false) }

    /** Back to the default 3/4 view. */
    @Synchronized
    fun reset(animate: Boolean = true) {
        val target = FloatArray(16)
        // STL files are usually Z-up. Turn Z to screen-up, then tilt into a 3/4 view.
        Matrix.setIdentityM(target, 0)
        Matrix.rotateM(target, 0, 25f, 1f, 0f, 0f)
        Matrix.rotateM(target, 0, -90f, 1f, 0f, 0f)
        Matrix.rotateM(target, 0, -35f, 0f, 0f, 1f)
        if (animate) {
            animateTo(target, 0f, 0f, 1f)
        } else {
            animating = false
            System.arraycopy(target, 0, rotation, 0, 16)
            zoom = 1f; panX = 0f; panY = 0f
        }
    }

    /**
     * Animate to look at the model from direction ([dx], [dy], [dz]) in model
     * space (Z-up), e.g. (0, -1, 0) for the front view or (1, -1, 1) for a corner.
     * Keeps the current zoom and re-centers the model.
     */
    @Synchronized
    fun snapToDirection(dx: Float, dy: Float, dz: Float) {
        val fl = sqrt(dx * dx + dy * dy + dz * dz)
        if (fl == 0f) return
        val f = floatArrayOf(dx / fl, dy / fl, dz / fl)
        // Screen-up is model +Z, except when looking straight down or up.
        val up = when {
            abs(f[0]) < 1e-4f && abs(f[1]) < 1e-4f -> if (f[2] > 0) floatArrayOf(0f, 1f, 0f) else floatArrayOf(0f, -1f, 0f)
            else -> floatArrayOf(0f, 0f, 1f)
        }
        val r = cross(up, f); normalize(r)
        val u = cross(f, r)
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        // Rows of the rotation are the camera basis expressed in model space.
        m[0] = r[0]; m[4] = r[1]; m[8] = r[2]
        m[1] = u[0]; m[5] = u[1]; m[9] = u[2]
        m[2] = f[0]; m[6] = f[1]; m[10] = f[2]
        animateTo(m, 0f, 0f, zoom)
    }

    private fun animateTo(target: FloatArray, px: Float, py: Float, z: Float) {
        tick()
        toQuat(rotation, fromQ)
        toQuat(target, toQ)
        fromPanX = panX; fromPanY = panY; fromZoom = zoom
        toPanX = px; toPanY = py; toZoom = z
        animStart = SystemClock.uptimeMillis()
        animating = true
    }

    /** Advances any running animation. Returns true while more frames are needed. */
    @Synchronized
    fun tick(): Boolean {
        if (!animating) return false
        val t = ((SystemClock.uptimeMillis() - animStart).toFloat() / ANIM_MS).coerceIn(0f, 1f)
        val e = t * t * (3f - 2f * t) // smoothstep
        val q = FloatArray(4)
        slerp(fromQ, toQ, e, q)
        fromQuat(q, rotation)
        panX = fromPanX + (toPanX - fromPanX) * e
        panY = fromPanY + (toPanY - fromPanY) * e
        zoom = fromZoom + (toZoom - fromZoom) * e
        if (t >= 1f) animating = false
        return animating
    }

    @Synchronized
    fun setViewport(width: Int, height: Int) {
        viewportWidth = max(1, width); viewportHeight = max(1, height)
    }

    /** Rotate by a finger drag of (dx, dy) pixels. */
    @Synchronized
    fun rotateBy(dx: Float, dy: Float) {
        animating = false
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
        animating = false
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
        animating = false
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

    /**
     * Spins the model by [degrees] about the view axis (counter-clockwise when
     * positive), around the screen point ([fx], [fy]) in view pixels so the spot
     * between the fingers stays put.
     */
    @Synchronized
    fun rollAt(degrees: Float, fx: Float, fy: Float) {
        animating = false
        if (degrees == 0f) return
        Matrix.setRotateM(delta, 0, degrees, 0f, 0f, 1f)
        Matrix.multiplyMM(tmp, 0, delta, 0, rotation, 0)
        System.arraycopy(tmp, 0, rotation, 0, 16)
        orthonormalize()
        // Rotate the pan offset around the focus point too.
        val upp = unitsPerPixel()
        val cx = (fx - viewportWidth / 2f) * upp
        val cy = -(fy - viewportHeight / 2f) * upp
        val a = Math.toRadians(degrees.toDouble())
        val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
        val px = panX - cx; val py = panY - cy
        panX = cx + c * px - s * py
        panY = cy + s * px + c * py
    }

    /** Animated spin about the view axis, around the screen center. */
    @Synchronized
    fun rollAnimated(degrees: Float) {
        tick()
        // Build on where a running animation is heading, so quick repeated taps
        // land on exact multiples instead of drifting.
        val base = FloatArray(16)
        val bx: Float; val by: Float; val bz: Float
        if (animating) {
            fromQuat(toQ, base); bx = toPanX; by = toPanY; bz = toZoom
        } else {
            System.arraycopy(rotation, 0, base, 0, 16); bx = panX; by = panY; bz = zoom
        }
        val target = FloatArray(16)
        Matrix.setRotateM(delta, 0, degrees, 0f, 0f, 1f)
        Matrix.multiplyMM(target, 0, delta, 0, base, 0)
        val a = Math.toRadians(degrees.toDouble())
        val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
        animateTo(target, c * bx - s * by, s * bx + c * by, bz)
    }

    /** Copies the current model rotation (no translation) into [out]. */
    @Synchronized
    fun rotationMatrix(out: FloatArray) {
        System.arraycopy(rotation, 0, out, 0, 16)
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

    companion object {
        const val FOV_Y = 40f
        /** Distance at which the unit sphere fits the view with a small margin. */
        val BASE_DISTANCE = (1.15 / Math.sin(Math.toRadians(FOV_Y / 2.0))).toFloat()
        const val MIN_ZOOM = 0.2f
        const val MAX_ZOOM = 100f
        const val ANIM_MS = 350f

        internal fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
        )

        internal fun normalize(v: FloatArray) {
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (l > 0f) { v[0] /= l; v[1] /= l; v[2] /= l }
        }

        /** Column-major rotation matrix to quaternion (x, y, z, w). */
        internal fun toQuat(m: FloatArray, q: FloatArray) {
            val m00 = m[0]; val m11 = m[5]; val m22 = m[10]
            val m01 = m[4]; val m02 = m[8]; val m10 = m[1]; val m12 = m[9]; val m20 = m[2]; val m21 = m[6]
            val trace = m00 + m11 + m22
            if (trace > 0f) {
                val s = sqrt(trace + 1f) * 2f
                q[3] = 0.25f * s; q[0] = (m21 - m12) / s; q[1] = (m02 - m20) / s; q[2] = (m10 - m01) / s
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                q[3] = (m21 - m12) / s; q[0] = 0.25f * s; q[1] = (m01 + m10) / s; q[2] = (m02 + m20) / s
            } else if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                q[3] = (m02 - m20) / s; q[0] = (m01 + m10) / s; q[1] = 0.25f * s; q[2] = (m12 + m21) / s
            } else {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                q[3] = (m10 - m01) / s; q[0] = (m02 + m20) / s; q[1] = (m12 + m21) / s; q[2] = 0.25f * s
            }
        }

        internal fun fromQuat(q: FloatArray, m: FloatArray) {
            val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
            Matrix.setIdentityM(m, 0)
            m[0] = 1 - 2 * (y * y + z * z); m[4] = 2 * (x * y - z * w); m[8] = 2 * (x * z + y * w)
            m[1] = 2 * (x * y + z * w); m[5] = 1 - 2 * (x * x + z * z); m[9] = 2 * (y * z - x * w)
            m[2] = 2 * (x * z - y * w); m[6] = 2 * (y * z + x * w); m[10] = 1 - 2 * (x * x + y * y)
        }

        internal fun slerp(a: FloatArray, b: FloatArray, t: Float, out: FloatArray) {
            var bx = b[0]; var by = b[1]; var bz = b[2]; var bw = b[3]
            var dot = a[0] * bx + a[1] * by + a[2] * bz + a[3] * bw
            if (dot < 0f) { dot = -dot; bx = -bx; by = -by; bz = -bz; bw = -bw } // shortest path
            val (wa, wb) = if (dot > 0.9995f) {
                (1f - t) to t
            } else {
                val theta = acos(dot.coerceAtMost(1f))
                val s = sin(theta)
                (sin((1f - t) * theta) / s) to (sin(t * theta) / s)
            }
            out[0] = wa * a[0] + wb * bx; out[1] = wa * a[1] + wb * by
            out[2] = wa * a[2] + wb * bz; out[3] = wa * a[3] + wb * bw
            val l = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3])
            for (i in 0 until 4) out[i] /= l
        }
    }
}
