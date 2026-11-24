package com.krudoy.stlviewer.model

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

enum class ModelFormat { STL, THREE_MF }

/** Picks the right parser by looking at the first bytes of the file. */
object ModelLoader {

    fun sniff(head: ByteArray, len: Int): ModelFormat = when {
        // 3MF is a ZIP package: "PK\u0003\u0004".
        len >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2] == 3.toByte() && head[3] == 4.toByte() -> ModelFormat.THREE_MF
        else -> ModelFormat.STL
    }

    /**
     * @param input the file's bytes; closed by the caller.
     * @param length total size if known, or -1.
     * @param scratch a file this loader may overwrite, for formats that need random access.
     */
    fun load(input: InputStream, length: Long, scratch: File, onProgress: (Float) -> Unit = {}): Mesh {
        val stream = BufferedInputStream(input, 1 shl 16)
        val head = ByteArray(16)
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
        }
    }
}
