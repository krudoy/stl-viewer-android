package com.krudoy.stlviewer.model

/**
 * A triangle soup ready for rendering: three vertices per triangle, with a
 * flat per-face normal repeated for each of its vertices.
 */
class Mesh(
    /** x,y,z per vertex; size = triangleCount * 9. */
    val positions: FloatArray,
    /** x,y,z per vertex; size = triangleCount * 9. */
    val normals: FloatArray,
    val name: String? = null,
) {
    init {
        require(positions.size % 9 == 0) { "positions must hold whole triangles" }
        require(normals.size == positions.size) { "normals must match positions" }
    }

    val triangleCount: Int get() = positions.size / 9
    val vertexCount: Int get() = positions.size / 3

    val bounds: Bounds by lazy { Bounds.of(positions) }
}

data class Bounds(
    val minX: Float, val minY: Float, val minZ: Float,
    val maxX: Float, val maxY: Float, val maxZ: Float,
) {
    val sizeX get() = maxX - minX
    val sizeY get() = maxY - minY
    val sizeZ get() = maxZ - minZ
    val centerX get() = (minX + maxX) / 2f
    val centerY get() = (minY + maxY) / 2f
    val centerZ get() = (minZ + maxZ) / 2f

    /** Radius of the sphere around the box center that encloses the box. */
    val radius: Float
        get() = (Math.sqrt((sizeX * sizeX + sizeY * sizeY + sizeZ * sizeZ).toDouble()) / 2.0).toFloat()

    companion object {
        fun of(positions: FloatArray): Bounds {
            if (positions.isEmpty()) return Bounds(0f, 0f, 0f, 0f, 0f, 0f)
            var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
            var i = 0
            while (i < positions.size) {
                val x = positions[i]; val y = positions[i + 1]; val z = positions[i + 2]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                i += 3
            }
            return Bounds(minX, minY, minZ, maxX, maxY, maxZ)
        }
    }
}
