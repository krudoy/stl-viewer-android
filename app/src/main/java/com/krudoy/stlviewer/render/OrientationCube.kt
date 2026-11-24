package com.krudoy.stlviewer.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * The navigation cube drawn in the corner of the viewer. It turns with the
 * model, and each of its 26 regions (6 faces, 12 edges, 8 corners) can be
 * tapped to look at the model from that direction.
 *
 * The cube lives in model space (Z-up): Front is -Y, Right is +X, Top is +Z.
 */
class OrientationCube(private val camera: Camera) {

    /** Cube rectangle in view pixels (top-left origin). Set by the view on layout. */
    @Volatile var left = 0f
    @Volatile var top = 0f
    @Volatile var size = 0f

    /** Hidden until a model is loaded. */
    @Volatile var visible = false

    /** Region under the finger as a direction with components in {-1, 0, 1}, or null. */
    @Volatile var highlight: IntArray? = null

    private val rot = FloatArray(16)
    private val proj = FloatArray(16)
    private val mvp = FloatArray(16)

    private var program = 0
    private var aPos = 0
    private var aUv = 0
    private var uMvp = 0
    private var uRot = 0
    private var uTex = 0
    private var uHi = 0
    private var uHasHi = 0
    private var texture = 0

    private val vertices: FloatBuffer = buildVertices()

    fun contains(x: Float, y: Float) = visible && size > 0f && x >= left && x <= left + size && y >= top && y <= top + size

    /**
     * Finds the cube region under view pixel ([x], [y]). Returns a direction with
     * components in {-1, 0, 1}, or null when the point misses the cube.
     */
    fun pick(x: Float, y: Float): IntArray? {
        if (!contains(x, y)) return null
        // Orthographic ray in cube view space.
        val ox = ((x - left) / size * 2f - 1f) * EXTENT
        val oy = (1f - (y - top) / size * 2f) * EXTENT
        camera.rotationMatrix(rot)
        // Inverse rotation is the transpose: v_cube[j] = sum_i R[i][j] * v_view[i].
        val o = FloatArray(3); val d = FloatArray(3)
        val vo = floatArrayOf(ox, oy, 10f); val vd = floatArrayOf(0f, 0f, -1f)
        for (j in 0 until 3) {
            o[j] = rot[j * 4] * vo[0] + rot[j * 4 + 1] * vo[1] + rot[j * 4 + 2] * vo[2]
            d[j] = rot[j * 4] * vd[0] + rot[j * 4 + 1] * vd[1] + rot[j * 4 + 2] * vd[2]
        }
        // Slab intersection with [-1, 1]^3.
        var tMin = Float.NEGATIVE_INFINITY; var tMax = Float.POSITIVE_INFINITY
        for (i in 0 until 3) {
            if (abs(d[i]) < 1e-6f) {
                if (o[i] < -1f || o[i] > 1f) return null
            } else {
                var t1 = (-1f - o[i]) / d[i]; var t2 = (1f - o[i]) / d[i]
                if (t1 > t2) { val s = t1; t1 = t2; t2 = s }
                if (t1 > tMin) tMin = t1
                if (t2 < tMax) tMax = t2
            }
        }
        if (tMin > tMax) return null
        val p = FloatArray(3) { o[it] + d[it] * tMin }
        return regionOf(p)
    }

    fun onSurfaceCreated() {
        program = ModelRenderer.buildProgram(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uRot = GLES20.glGetUniformLocation(program, "uRot")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uHi = GLES20.glGetUniformLocation(program, "uHi")
        uHasHi = GLES20.glGetUniformLocation(program, "uHasHi")
        texture = buildLabelTexture()
    }

    /** Draws the cube into its corner. Call after the model, with the full viewport set. */
    fun draw(viewWidth: Int, viewHeight: Int) {
        if (!visible || size <= 0f) return
        val px = left.toInt()
        val py = (viewHeight - top - size).toInt() // GL origin is bottom-left
        GLES20.glViewport(px, py, size.toInt(), size.toInt())
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        camera.rotationMatrix(rot)
        Matrix.orthoM(proj, 0, -EXTENT, EXTENT, -EXTENT, EXTENT, -10f, 10f)
        Matrix.multiplyMM(mvp, 0, proj, 0, rot, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uRot, 1, false, rot, 0)
        val hi = highlight
        GLES20.glUniform1f(uHasHi, if (hi != null) 1f else 0f)
        GLES20.glUniform3f(uHi, (hi?.get(0) ?: 0).toFloat(), (hi?.get(1) ?: 0).toFloat(), (hi?.get(2) ?: 0).toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(uTex, 0)

        vertices.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, vertices)
        vertices.position(3)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, STRIDE, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
    }

    private fun buildLabelTexture(): Int {
        val cell = 128
        val bmp = Bitmap.createBitmap(cell * FACES.size, cell, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fill = Paint().apply { color = Color.rgb(214, 222, 233) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(38, 46, 58)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        FACES.forEachIndexed { i, face ->
            val x0 = (i * cell).toFloat()
            canvas.drawRect(x0, 0f, x0 + cell, cell.toFloat(), fill)
            text.textSize = 30f
            // Shrink long labels to fit inside the face's central area.
            val w = text.measureText(face.label)
            if (w > cell * 0.8f) text.textSize *= cell * 0.8f / w
            val baseline = cell / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(face.label, x0 + cell / 2f, baseline, text)
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return ids[0]
    }

    private class Face(val label: String, val n: FloatArray, val r: FloatArray, val u: FloatArray)

    companion object {
        /** Half-size of the ortho view; the cube's corners reach sqrt(3) ≈ 1.73. */
        private const val EXTENT = 1.8f
        /** Fraction of the half-width near each edge that counts as an edge or corner. */
        const val BORDER = 0.28f
        private const val STRIDE = 5 * 4

        // r × u = n, so each face winds counter-clockwise seen from outside. Labels read
        // upright with Z up on the sides, and with the front edge at the bottom on top.
        private val FACES = listOf(
            Face("FRONT", floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f)),
            Face("BACK", floatArrayOf(0f, 1f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f)),
            Face("RIGHT", floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f)),
            Face("LEFT", floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, 1f)),
            Face("TOP", floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f)),
            Face("BOTTOM", floatArrayOf(0f, 0f, -1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, -1f, 0f)),
        )

        /** Maps a point on the cube surface to its region direction. */
        fun regionOf(p: FloatArray): IntArray {
            // The axis with the largest magnitude is the face we hit; it always counts.
            var face = 0
            for (i in 1 until 3) if (abs(p[i]) > abs(p[face])) face = i
            return IntArray(3) { i ->
                when {
                    i == face -> if (p[i] > 0) 1 else -1
                    abs(p[i]) > 1f - BORDER -> if (p[i] > 0) 1 else -1
                    else -> 0
                }
            }
        }

        private fun buildVertices(): FloatBuffer {
            val data = FloatArray(FACES.size * 6 * 5)
            var k = 0
            FACES.forEachIndexed { fi, f ->
                val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to -1f, 1f to 1f, -1f to 1f)
                for ((s, t) in corners) {
                    for (a in 0 until 3) data[k++] = f.n[a] + s * f.r[a] + t * f.u[a]
                    data[k++] = (fi + (s + 1f) / 2f) / FACES.size
                    data[k++] = (1f - t) / 2f
                }
            }
            return ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(data); position(0) }
        }

        private const val VS = """
            uniform mat4 uMvp;
            attribute vec3 aPos;
            attribute vec2 aUv;
            varying vec3 vPos;
            varying vec2 vUv;
            void main() {
                vPos = aPos;
                vUv = aUv;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        private val FS = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec3 uHi;
            uniform float uHasHi;
            uniform mat4 uRot;
            varying vec3 vPos;
            varying vec2 vUv;
            void main() {
                vec3 a = abs(vPos);
                // Region of this fragment, same rule as OrientationCube.regionOf.
                float m = max(a.x, max(a.y, a.z));
                vec3 faceAxis = step(m - 0.0001, a);
                vec3 region = sign(vPos) * max(faceAxis, step(1.0 - ${BORDER}, a));
                // Shade faces turned away from the viewer a little darker.
                vec3 n = sign(vPos) * faceAxis;
                float shade = 0.7 + 0.3 * max((uRot * vec4(n, 0.0)).z, 0.0);
                vec3 color = texture2D(uTex, vUv).rgb * shade;
                // Thin dark outline along the cube's edges.
                vec3 nearEdge = step(0.96, a) * (1.0 - faceAxis);
                if (max(nearEdge.x, max(nearEdge.y, nearEdge.z)) > 0.5) color *= 0.55;
                if (uHasHi > 0.5 && all(equal(region, uHi))) {
                    color = mix(color, vec3(0.30, 0.56, 0.95), 0.65);
                }
                gl_FragColor = vec4(color, 0.92);
            }
        """
    }
}
