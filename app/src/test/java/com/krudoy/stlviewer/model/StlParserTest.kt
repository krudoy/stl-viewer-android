package com.krudoy.stlviewer.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StlParserTest {

    private val tri1 = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
    private val tri2 = floatArrayOf(0f, 0f, 2f, 0f, 3f, 2f, 4f, 0f, 2f)

    private fun binary(header: String, vararg tris: FloatArray, trailing: Int = 0, declared: Int = tris.size): ByteArray {
        val bb = ByteBuffer.allocate(84 + 50 * tris.size + trailing).order(ByteOrder.LITTLE_ENDIAN)
        val h = header.toByteArray(Charsets.US_ASCII).copyOf(80)
        bb.put(h)
        bb.putInt(declared)
        for (t in tris) {
            repeat(3) { bb.putFloat(0f) } // stored normal, ignored
            for (v in t) bb.putFloat(v)
            bb.putShort(0)
        }
        return bb.array()
    }

    private fun ascii(vararg tris: FloatArray, name: String = "part") = buildString {
        append("solid $name\n")
        for (t in tris) {
            append("  facet normal 0 0 0\n    outer loop\n")
            for (i in 0 until 3) append("      vertex ${t[i * 3]} ${t[i * 3 + 1]} ${t[i * 3 + 2]}\n")
            append("    endloop\n  endfacet\n")
        }
        append("endsolid $name\n")
    }.toByteArray()

    private fun parse(bytes: ByteArray, withLength: Boolean = true) =
        StlParser.parse(ByteArrayInputStream(bytes), if (withLength) bytes.size.toLong() else -1)

    @Test fun parsesBinary() {
        val mesh = parse(binary("exported by something", tri1, tri2))
        assertEquals(2, mesh.triangleCount)
        assertArrayEquals(tri1 + tri2, mesh.positions, 0f)
    }

    @Test fun binaryWithSolidHeaderIsNotMistakenForAscii() {
        val mesh = parse(binary("solid model exported by CAD", tri1, tri2))
        assertEquals(2, mesh.triangleCount)
        assertArrayEquals(tri1 + tri2, mesh.positions, 0f)
    }

    @Test fun binaryWithSolidHeaderAndUnknownLength() {
        val mesh = parse(binary("solid model", tri1), withLength = false)
        assertEquals(1, mesh.triangleCount)
    }

    @Test fun binaryWithTrailingBytes() {
        val mesh = parse(binary("x", tri1, trailing = 7))
        assertEquals(1, mesh.triangleCount)
    }

    @Test fun truncatedBinaryFails() {
        val bytes = binary("x", tri1, tri2, declared = 3)
        assertThrows(StlParseException::class.java) { parse(bytes) }
        assertThrows(StlParseException::class.java) { parse(bytes, withLength = false) }
    }

    @Test fun emptyBinaryFails() {
        assertThrows(StlParseException::class.java) { parse(binary("x")) }
    }

    @Test fun parsesAscii() {
        val mesh = parse(ascii(tri1, tri2, name = "bracket"))
        assertEquals(2, mesh.triangleCount)
        assertArrayEquals(tri1 + tri2, mesh.positions, 0f)
        assertEquals("bracket", mesh.name)
    }

    @Test fun parsesAsciiWithoutLengthAndOddFormatting() {
        val text = "SOLID\r\nFACET NORMAL 0 0 1\r\nOUTER LOOP\r\nVERTEX 0 0 0\r\nVERTEX 1e0 0 0\r\n" +
            "VERTEX 0 1.0E+00 0\r\nENDLOOP\r\nENDFACET\r\nENDSOLID\r\n"
        val mesh = parse(text.toByteArray(), withLength = false)
        assertEquals(1, mesh.triangleCount)
        assertArrayEquals(tri1, mesh.positions, 0f)
        assertNull(mesh.name)
    }

    @Test fun asciiWithManyTrianglesGrowsBuffer() {
        val tris = Array(5000) { i -> FloatArray(9) { k -> (i * 9 + k).toFloat() } }
        val mesh = parse(ascii(*tris))
        assertEquals(5000, mesh.triangleCount)
        assertEquals(44999f, mesh.positions.last(), 0f)
    }

    @Test fun badAsciiFails() {
        val text = "solid x\nfacet normal 0 0 0\nouter loop\nvertex 0 0 abc\n"
        assertThrows(StlParseException::class.java) { parse(text.toByteArray()) }
    }

    @Test fun garbageFails() {
        assertThrows(StlParseException::class.java) { parse("hello".toByteArray()) }
    }

    @Test fun normalsAreRecomputedFromWinding() {
        val mesh = parse(binary("x", tri1))
        // Counter-clockwise in the XY plane faces +Z.
        for (v in 0 until 3) {
            assertEquals(0f, mesh.normals[v * 3], 1e-6f)
            assertEquals(0f, mesh.normals[v * 3 + 1], 1e-6f)
            assertEquals(1f, mesh.normals[v * 3 + 2], 1e-6f)
        }
    }

    @Test fun boundsCoverAllVertices() {
        val b = parse(binary("x", tri1, tri2)).bounds
        assertEquals(0f, b.minX, 0f); assertEquals(4f, b.maxX, 0f)
        assertEquals(0f, b.minY, 0f); assertEquals(3f, b.maxY, 0f)
        assertEquals(0f, b.minZ, 0f); assertEquals(2f, b.maxZ, 0f)
    }
}
