package com.krudoy.stlviewer.model

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class StlParseException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reads binary and ASCII STL files.
 *
 * Detection: a file is binary when its length is exactly 84 + 50 * n, where n is
 * the triangle count stored at offset 80. Many binary files start with the word
 * "solid" in their header, so the header text alone is not a reliable signal.
 * When the length is unknown we fall back to sniffing the first bytes.
 *
 * Normals stored in the file are ignored and recomputed from the vertex winding,
 * because exporters often write zeros or garbage there.
 */
object StlParser {

    private const val HEADER_SIZE = 80
    private const val TRIANGLE_SIZE = 50

    /**
     * @param length total byte length of the stream if known, or -1.
     * @param onProgress called with a fraction in 0..1 while parsing.
     */
    fun parse(input: InputStream, length: Long = -1, onProgress: (Float) -> Unit = {}): Mesh {
        val stream = if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16)
        stream.mark(HEADER_SIZE + 4 + 512)
        val head = ByteArray(HEADER_SIZE + 4 + 512)
        val headLen = readFully(stream, head, 0, head.size)
        stream.reset()

        val ascii = looksLikeAscii(head, headLen)
        if (headLen >= HEADER_SIZE + 4) {
            val count = ByteBuffer.wrap(head, HEADER_SIZE, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            val expected = HEADER_SIZE + 4 + count * TRIANGLE_SIZE
            // Exact size match wins. Otherwise anything that is not ASCII is treated as
            // binary, which also tolerates exporters that append trailing bytes.
            if ((length >= 0 && expected == length) || !ascii) {
                if (length >= 0 && length < expected) {
                    throw StlParseException("File is truncated: header says $count triangles")
                }
                return parseBinary(stream, count, onProgress)
            }
        }
        if (ascii) return parseAscii(stream, length, onProgress)
        throw StlParseException("Not a valid STL file")
    }

    private fun looksLikeAscii(head: ByteArray, len: Int): Boolean {
        var i = 0
        while (i < len && head[i].toInt().toChar().isWhitespace()) i++
        if (len - i < 5) return false
        val word = String(head, i, 5, Charsets.US_ASCII)
        if (!word.equals("solid", ignoreCase = true)) return false
        // A real ASCII file has "facet" soon after the header line and no binary bytes.
        val text = String(head, 0, len, Charsets.ISO_8859_1)
        val hasFacet = text.contains("facet", ignoreCase = true) || text.contains("endsolid", ignoreCase = true)
        val hasBinary = (0 until len).any { val b = head[it].toInt() and 0xff; b == 0 || b > 0x7e && b < 0xa0 }
        return hasFacet && !hasBinary
    }

    private fun parseBinary(stream: InputStream, count: Long, onProgress: (Float) -> Unit): Mesh {
        if (count > Int.MAX_VALUE / 9) throw StlParseException("Too many triangles: $count")
        val n = count.toInt()
        if (n == 0) throw StlParseException("No triangles found")
        skipFully(stream, (HEADER_SIZE + 4).toLong())

        val positions = FloatArray(n * 9)
        val normals = FloatArray(n * 9)
        val chunkTriangles = 4096
        val buf = ByteArray(chunkTriangles * TRIANGLE_SIZE)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

        var done = 0
        while (done < n) {
            val batch = minOf(chunkTriangles, n - done)
            val bytes = batch * TRIANGLE_SIZE
            if (readFully(stream, buf, 0, bytes) < bytes) {
                throw StlParseException("File is truncated: expected $n triangles, got ${done}")
            }
            for (t in 0 until batch) {
                val base = t * TRIANGLE_SIZE + 12 // skip stored normal
                val p = (done + t) * 9
                for (k in 0 until 9) positions[p + k] = bb.getFloat(base + k * 4)
                computeFaceNormal(positions, normals, p)
            }
            done += batch
            onProgress(done.toFloat() / n)
        }
        return Mesh(positions, normals)
    }

    private fun parseAscii(stream: InputStream, length: Long, onProgress: (Float) -> Unit): Mesh {
        val tokens = Tokenizer(stream)
        var positions = FloatArray(9 * 1024)
        var size = 0
        var name: String? = null
        var vertexInFacet = 0

        var tok = tokens.next() ?: throw StlParseException("Empty file")
        if (!tok.equals("solid", ignoreCase = true)) throw StlParseException("Expected 'solid'")
        name = tokens.restOfLine()?.trim()?.ifEmpty { null }

        while (true) {
            tok = tokens.next() ?: break
            when {
                tok.equals("vertex", ignoreCase = true) -> {
                    if (size + 3 > positions.size) positions = positions.copyOf(positions.size * 2)
                    for (k in 0 until 3) {
                        val v = tokens.next() ?: throw StlParseException("Unexpected end of file in vertex")
                        positions[size++] = v.toFloatOrNull()
                            ?: throw StlParseException("Bad number '$v' near line ${tokens.line}")
                    }
                    vertexInFacet++
                }
                tok.equals("endfacet", ignoreCase = true) -> {
                    if (vertexInFacet != 3) {
                        throw StlParseException("Facet with $vertexInFacet vertices near line ${tokens.line}")
                    }
                    vertexInFacet = 0
                    if (length > 0 && (size / 9) % 2048 == 0) onProgress(tokens.bytesRead.toFloat() / length)
                }
                tok.equals("endsolid", ignoreCase = true) -> {
                    // Some files hold several solids; keep reading.
                    tokens.restOfLine()
                }
                tok.equals("solid", ignoreCase = true) -> tokens.restOfLine()
                // facet, normal (+3 numbers), outer, loop, endloop: nothing to do.
                else -> Unit
            }
        }
        if (size == 0) throw StlParseException("No triangles found")
        val finalPositions = positions.copyOf(size)
        val normals = FloatArray(size)
        var p = 0
        while (p < size) {
            computeFaceNormal(finalPositions, normals, p)
            p += 9
        }
        onProgress(1f)
        return Mesh(finalPositions, normals, name)
    }

    /** Writes the normal of the triangle at [p] (index into positions) to all three of its vertices. */
    private fun computeFaceNormal(pos: FloatArray, nrm: FloatArray, p: Int) {
        val ax = pos[p + 3] - pos[p]; val ay = pos[p + 4] - pos[p + 1]; val az = pos[p + 5] - pos[p + 2]
        val bx = pos[p + 6] - pos[p]; val by = pos[p + 7] - pos[p + 1]; val bz = pos[p + 8] - pos[p + 2]
        var nx = ay * bz - az * by
        var ny = az * bx - ax * bz
        var nz = ax * by - ay * bx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 0f && len.isFinite()) { nx /= len; ny /= len; nz /= len } else { nx = 0f; ny = 0f; nz = 1f }
        for (v in 0 until 3) {
            nrm[p + v * 3] = nx; nrm[p + v * 3 + 1] = ny; nrm[p + v * 3 + 2] = nz
        }
    }

    private fun readFully(s: InputStream, b: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val r = s.read(b, off + total, len - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        val tmp = ByteArray(256)
        while (left > 0) {
            val r = s.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
            if (r < 0) throw StlParseException("Unexpected end of file")
            left -= r
        }
    }

    /** Whitespace tokenizer over a byte stream, avoiding per-line String allocation. */
    private class Tokenizer(private val s: InputStream) {
        private val buf = ByteArray(1 shl 16)
        private var pos = 0
        private var lim = 0
        var bytesRead = 0L; private set
        var line = 1; private set
        private val sb = StringBuilder(32)

        private fun peek(): Int {
            if (pos >= lim) {
                lim = s.read(buf, 0, buf.size)
                pos = 0
                if (lim <= 0) { lim = 0; return -1 }
                bytesRead += lim
            }
            return buf[pos].toInt() and 0xff
        }

        fun next(): String? {
            var c = peek()
            while (c != -1 && c <= 0x20) { if (c == '\n'.code) line++; pos++; c = peek() }
            if (c == -1) return null
            sb.setLength(0)
            while (c != -1 && c > 0x20) { sb.append(c.toChar()); pos++; c = peek() }
            return sb.toString()
        }

        fun restOfLine(): String? {
            sb.setLength(0)
            var c = peek()
            while (c != -1 && c != '\n'.code) { sb.append(c.toChar()); pos++; c = peek() }
            if (c == '\n'.code) { pos++; line++ }
            return sb.toString()
        }
    }
}
