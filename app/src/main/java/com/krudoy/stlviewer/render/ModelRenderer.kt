package com.krudoy.stlviewer.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.krudoy.stlviewer.model.Mesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Draws one mesh with simple two-sided Blinn-Phong shading and a camera-mounted light. */
class ModelRenderer(private val camera: Camera) : GLSurfaceView.Renderer {

    @Volatile private var pendingMesh: Mesh? = null
    private var mesh: Mesh? = null
    @Volatile private var meshDirty = false

    private var program = 0
    private var bgProgram = 0
    private var aPosition = 0
    private var aNormal = 0
    private var uMvp = 0
    private var uModelView = 0
    private var uColor = 0
    private var bgPosition = 0

    private val vbo = IntArray(2)
    private var uploadedVertices = 0

    private val model = FloatArray(16)
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)

    private val bgQuad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    /** Called from any thread; the mesh is uploaded on the next frame. */
    fun setMesh(newMesh: Mesh?) {
        pendingMesh = newMesh
        meshDirty = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        program = buildProgram(MESH_VS, MESH_FS)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModelView = GLES20.glGetUniformLocation(program, "uModelView")
        uColor = GLES20.glGetUniformLocation(program, "uColor")

        bgProgram = buildProgram(BG_VS, BG_FS)
        bgPosition = GLES20.glGetAttribLocation(bgProgram, "aPosition")

        // A new context means old buffers are gone: re-upload whatever we were showing.
        GLES20.glGenBuffers(2, vbo, 0)
        uploadedVertices = 0
        if (pendingMesh == null) pendingMesh = mesh
        meshDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        camera.setViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (meshDirty) {
            meshDirty = false
            mesh = pendingMesh
            pendingMesh = null
            upload(mesh)
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        drawBackground()

        val m = mesh ?: return
        if (uploadedVertices == 0) return

        // Normalize: center the model and scale it to the unit sphere.
        val b = m.bounds
        val r = if (b.radius > 0f) b.radius else 1f
        Matrix.setIdentityM(model, 0)
        Matrix.scaleM(model, 0, 1f / r, 1f / r, 1f / r)
        Matrix.translateM(model, 0, -b.centerX, -b.centerY, -b.centerZ)

        camera.viewMatrix(view)
        camera.projectionMatrix(proj)
        Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, modelView, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModelView, 1, false, modelView, 0)
        GLES20.glUniform3f(uColor, 0.72f, 0.78f, 0.86f)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[1])
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, uploadedVertices)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProgram)
        GLES20.glEnableVertexAttribArray(bgPosition)
        GLES20.glVertexAttribPointer(bgPosition, 2, GLES20.GL_FLOAT, false, 0, bgQuad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(bgPosition)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun upload(m: Mesh?) {
        uploadedVertices = 0
        if (m == null) return
        try {
            uploadArray(vbo[0], m.positions)
            uploadArray(vbo[1], m.normals)
            uploadedVertices = m.vertexCount
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Not enough memory to upload ${m.triangleCount} triangles", e)
        }
    }

    private fun uploadArray(buffer: Int, data: FloatArray) {
        val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, fb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "Link failed: " + GLES20.glGetProgramInfoLog(p))
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "Compile failed: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private companion object {
        const val TAG = "ModelRenderer"

        const val MESH_VS = """
            uniform mat4 uMvp;
            uniform mat4 uModelView;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying vec3 vNormal;
            varying vec3 vViewPos;
            void main() {
                // Model-view is rotation + uniform scale, so it transforms normals correctly.
                vNormal = (uModelView * vec4(aNormal, 0.0)).xyz;
                vViewPos = (uModelView * vec4(aPosition, 1.0)).xyz;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """

        const val MESH_FS = """
            precision mediump float;
            uniform vec3 uColor;
            varying vec3 vNormal;
            varying vec3 vViewPos;
            void main() {
                vec3 n = normalize(vNormal);
                if (!gl_FrontFacing) n = -n;
                vec3 viewDir = normalize(-vViewPos);
                // Key light from the upper left of the camera, fill light from the camera.
                vec3 key = normalize(vec3(-0.4, 0.6, 1.0));
                float diffuse = max(dot(n, key), 0.0) * 0.75 + max(dot(n, viewDir), 0.0) * 0.25;
                vec3 h = normalize(key + viewDir);
                float spec = pow(max(dot(n, h), 0.0), 48.0) * 0.35;
                vec3 color = uColor * (0.18 + diffuse) + vec3(spec);
                gl_FragColor = vec4(color, 1.0);
            }
        """

        const val BG_VS = """
            attribute vec2 aPosition;
            varying float vT;
            void main() {
                vT = aPosition.y * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.999, 1.0);
            }
        """

        const val BG_FS = """
            precision mediump float;
            varying float vT;
            void main() {
                vec3 bottom = vec3(0.08, 0.09, 0.11);
                vec3 top = vec3(0.20, 0.23, 0.28);
                gl_FragColor = vec4(mix(bottom, top, vT), 1.0);
            }
        """
    }
}
