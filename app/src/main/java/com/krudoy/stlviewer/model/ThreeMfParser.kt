package com.krudoy.stlviewer.model

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/**
 * Reads 3MF files (a ZIP package of XML model parts), including the
 * production extension that Bambu Studio, PrusaSlicer and Orca use to keep
 * each object in its own part (`p:path` on components).
 *
 * All build items are flattened into one triangle soup with their transforms
 * applied, and converted to millimeters.
 */
object ThreeMfParser {

    private const val MODEL_REL_TYPE = "http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"
    private const val DEFAULT_ROOT = "3D/3dmodel.model"
    private const val MAX_DEPTH = 16

    fun parse(file: File, onProgress: (Float) -> Unit = {}): Mesh {
        val zip = try {
            ZipFile(file)
        } catch (e: IOException) {
            throw StlParseException("Not a valid 3MF file", e)
        }
        zip.use { z ->
            val root = findRootModel(z)
            val ctx = Context(z)
            val rootModel = ctx.model(root)
            val out = MeshBuilder()
            val items = rootModel.build
            if (items.isEmpty()) {
                // No build section: show every object as-is.
                for (id in rootModel.objects.keys) ctx.emit(root, id, Transform.IDENTITY, out, 0)
            } else {
                items.forEachIndexed { i, item ->
                    ctx.emit(item.path ?: root, item.objectId, item.transform, out, 0)
                    onProgress((i + 1).toFloat() / items.size)
                }
            }
            if (out.triangleCount == 0) throw StlParseException("No triangles found in 3MF file")
            val scale = rootModel.unitScale
            return out.build(scale)
        }
    }

    private fun findRootModel(zip: ZipFile): String {
        val rels = zip.getEntry("_rels/.rels")
        if (rels != null) {
            var target: String? = null
            parseXml(zip.getInputStream(rels).readBytes(), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    if (target == null && local(qName) == "Relationship" && a.getValue("Type") == MODEL_REL_TYPE) {
                        target = a.getValue("Target")
                    }
                }
            })
            target?.let { t -> normalize(t).takeIf { zip.getEntry(it) != null }?.let { return it } }
        }
        if (zip.getEntry(DEFAULT_ROOT) != null) return DEFAULT_ROOT
        zip.entries().asSequence().firstOrNull { it.name.endsWith(".model", ignoreCase = true) }?.let { return it.name }
        throw StlParseException("No 3D model found in 3MF file")
    }

    private fun normalize(path: String) = path.trimStart('/')

    private fun local(qName: String) = qName.substringAfter(':')

    private fun parseXml(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        try {
            factory.newSAXParser().parse(bytes.inputStream(), handler)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw StlParseException("Malformed 3MF model: ${e.message}", e)
        }
    }

    /** Affine transform in 3MF's row-vector form: p' = p * M, 12 numbers. */
    class Transform(val m: FloatArray) {
        fun apply(x: Float, y: Float, z: Float, out: FloatArray, o: Int) {
            out[o] = x * m[0] + y * m[3] + z * m[6] + m[9]
            out[o + 1] = x * m[1] + y * m[4] + z * m[7] + m[10]
            out[o + 2] = x * m[2] + y * m[5] + z * m[8] + m[11]
        }

        /** this applied first, then [outer]. */
        fun then(outer: Transform): Transform {
            val a = m; val b = outer.m
            val r = FloatArray(12)
            for (row in 0 until 4) {
                for (col in 0 until 3) {
                    var v = if (row == 3) b[9 + col] else 0f
                    for (k in 0 until 3) v += a[row * 3 + k] * b[k * 3 + col]
                    r[row * 3 + col] = v
                }
            }
            return Transform(r)
        }

        /** True when the transform mirrors geometry, which flips triangle winding. */
        val mirrors: Boolean
            get() {
                val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
                    m[1] * (m[3] * m[8] - m[5] * m[6]) +
                    m[2] * (m[3] * m[7] - m[4] * m[6])
                return det < 0f
            }

        companion object {
            val IDENTITY = Transform(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f))

            fun parse(s: String?): Transform {
                if (s.isNullOrBlank()) return IDENTITY
                val parts = s.trim().split(Regex("\\s+"))
                if (parts.size != 12) throw StlParseException("Bad 3MF transform: $s")
                return Transform(FloatArray(12) { parts[it].toFloatOrNull() ?: throw StlParseException("Bad 3MF transform: $s") })
            }
        }
    }

    private class Component(val objectId: String, val path: String?, val transform: Transform)
    private class BuildItem(val objectId: String, val path: String?, val transform: Transform)

    private class ObjectDef(val id: String) {
        var vertices = FloatArray(0)
        var vertexCount = 0
        var triangles = IntArray(0)
        var triangleCount = 0
        val components = mutableListOf<Component>()
    }

    private class Model(val objects: Map<String, ObjectDef>, val build: List<BuildItem>, val unitScale: Float)

    private class Context(private val zip: ZipFile) {
        private val models = HashMap<String, Model>()

        fun model(path: String): Model = models.getOrPut(path) {
            val entry = zip.getEntry(path) ?: throw StlParseException("3MF part missing: $path")
            val handler = ModelHandler()
            zip.getInputStream(entry).use { input ->
                val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
                try {
                    factory.newSAXParser().parse(input, handler)
                } catch (e: StlParseException) {
                    throw e
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    throw StlParseException("Malformed 3MF model: ${e.message}", e)
                }
            }
            Model(handler.objects, handler.build, handler.unitScale)
        }

        fun emit(path: String, objectId: String, transform: Transform, out: MeshBuilder, depth: Int) {
            if (depth > MAX_DEPTH) throw StlParseException("3MF components nest too deeply")
            val obj = model(path).objects[objectId] ?: throw StlParseException("3MF object $objectId not found")
            if (obj.triangleCount > 0) out.add(obj.vertices, obj.triangles, obj.triangleCount, transform)
            for (c in obj.components) {
                emit(c.path ?: path, c.objectId, c.transform.then(transform), out, depth + 1)
            }
        }
    }

    private class ModelHandler : DefaultHandler() {
        val objects = HashMap<String, ObjectDef>()
        val build = mutableListOf<BuildItem>()
        var unitScale = 1f
        private var current: ObjectDef? = null

        override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
            when (local(qName)) {
                "model" -> unitScale = when (a.getValue("unit")?.lowercase()) {
                    "micron" -> 0.001f
                    "centimeter" -> 10f
                    "inch" -> 25.4f
                    "foot" -> 304.8f
                    "meter" -> 1000f
                    else -> 1f
                }
                "object" -> {
                    val id = a.getValue("id") ?: throw StlParseException("3MF object without id")
                    current = ObjectDef(id).also { objects[id] = it }
                }
                "vertex" -> current?.let { o ->
                    if (o.vertexCount * 3 + 3 > o.vertices.size) o.vertices = o.vertices.copyOf(maxOf(48, o.vertices.size * 2))
                    val i = o.vertexCount * 3
                    o.vertices[i] = num(a, "x"); o.vertices[i + 1] = num(a, "y"); o.vertices[i + 2] = num(a, "z")
                    o.vertexCount++
                }
                "triangle" -> current?.let { o ->
                    if (o.triangleCount * 3 + 3 > o.triangles.size) o.triangles = o.triangles.copyOf(maxOf(48, o.triangles.size * 2))
                    val i = o.triangleCount * 3
                    o.triangles[i] = idx(a, "v1", o); o.triangles[i + 1] = idx(a, "v2", o); o.triangles[i + 2] = idx(a, "v3", o)
                    o.triangleCount++
                }
                "component" -> current?.components?.add(
                    Component(
                        a.getValue("objectid") ?: throw StlParseException("3MF component without objectid"),
                        pathAttr(a),
                        Transform.parse(a.getValue("transform")),
                    ),
                )
                "item" -> build.add(
                    BuildItem(
                        a.getValue("objectid") ?: throw StlParseException("3MF build item without objectid"),
                        pathAttr(a),
                        Transform.parse(a.getValue("transform")),
                    ),
                )
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (local(qName) == "object") current = null
        }

        private fun pathAttr(a: Attributes): String? {
            for (i in 0 until a.length) {
                if (local(a.getQName(i)) == "path") return normalize(a.getValue(i))
            }
            return null
        }

        private fun num(a: Attributes, name: String): Float =
            a.getValue(name)?.toFloatOrNull() ?: throw StlParseException("Bad 3MF vertex attribute '$name'")

        private fun idx(a: Attributes, name: String, o: ObjectDef): Int {
            val v = a.getValue(name)?.toIntOrNull() ?: throw StlParseException("Bad 3MF triangle attribute '$name'")
            if (v < 0 || v >= o.vertexCount) throw StlParseException("3MF triangle refers to missing vertex $v")
            return v
        }
    }

    /** Collects transformed triangles into a growing triangle soup. */
    private class MeshBuilder {
        private var positions = FloatArray(9 * 1024)
        private var size = 0
        val triangleCount get() = size / 9

        fun add(vertices: FloatArray, triangles: IntArray, count: Int, t: Transform) {
            val needed = size + count * 9
            if (needed > positions.size) {
                if (needed < 0 || needed > Int.MAX_VALUE / 2) throw StlParseException("Model is too large")
                positions = positions.copyOf(maxOf(needed, positions.size * 2))
            }
            val flip = t.mirrors
            for (tri in 0 until count) {
                for (k in 0 until 3) {
                    val vi = triangles[tri * 3 + if (flip) 2 - k else k] * 3
                    t.apply(vertices[vi], vertices[vi + 1], vertices[vi + 2], positions, size)
                    size += 3
                }
            }
        }

        fun build(scale: Float): Mesh {
            val pos = positions.copyOf(size)
            if (scale != 1f) for (i in pos.indices) pos[i] *= scale
            val normals = FloatArray(size)
            var p = 0
            while (p < size) { StlParser.computeFaceNormal(pos, normals, p); p += 9 }
            return Mesh(pos, normals)
        }
    }
}
