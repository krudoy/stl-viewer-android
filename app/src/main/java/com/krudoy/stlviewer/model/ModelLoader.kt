package com.krudoy.stlviewer.model

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

enum class ModelFormat { STL, THREE_MF, STEP }

/** Picks the right parser by looking at the first bytes of the file. */
object ModelLoader {

    private const val STEP_MAGIC = "ISO-10303-21"

    fun sniff(head: ByteArray, len: Int): ModelFormat = when {
        isStep(head, len) -> ModelFormat.STEP
        // 3MF is a ZIP package: "PK\u0003\u0004".
        len >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2] == 3.toByte() && head[3] == 4.toByte() -> ModelFormat.THREE_MF
        else -> ModelFormat.STL
    }

    private fun isStep(head: ByteArray, len: Int): Boolean {
        // Skip a UTF-8 byte order mark and leading whitespace.
        var i = 0
        if (len >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
        while (i < len && head[i].toInt().toChar().isWhitespace()) i++
        if (len - i < STEP_MAGIC.length) return false
        return String(head, i, STEP_MAGIC.length, Charsets.US_ASCII) == STEP_MAGIC
    }

    /**
     * @param input the file's bytes; closed by the caller.
     * @param length total size if known, or -1.
     * @param scratch a file this loader may overwrite, for formats that need random access.
     */
    fun load(input: InputStream, length: Long, scratch: File, onProgress: (Float) -> Unit = {}): Mesh {
        val stream = BufferedInputStream(input, 1 shl 16)
        val head = ByteArray(32)
        stream.mark(head.size)
        var n = 0
        while (n < head.size) {
            val r = stream.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
        stream.reset()
        return when (sniff(head, n)) {
            ModelFormat.STL -> StlParser.parse(stream, length, onProgress)
            ModelFormat.THREE_MF -> {
                scratch.outputStream().use { stream.copyTo(it, 1 shl 16) }
                try {
                    ThreeMfParser.parse(scratch, onProgress)
                } finally {
                    scratch.delete()
                }
            }
            ModelFormat.STEP -> {
                if (!StepNative.available) throw StlParseException("STEP support is not included in this build")
                scratch.outputStream().use { stream.copyTo(it, 1 shl 16) }
                try {
                    StepNative.parse(scratch)
                } finally {
                    scratch.delete()
                }
            }
        }
    }
}
