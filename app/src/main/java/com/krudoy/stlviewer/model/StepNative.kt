package com.krudoy.stlviewer.model

import java.io.File

/** STEP import through the native Open CASCADE mesher (libstepmesh.so). */
object StepNative {

    private val loaded: Boolean = try {
        System.loadLibrary("stepmesh")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    /** True when this build includes Open CASCADE for the device's CPU. */
    val available: Boolean by lazy { loaded && nativeAvailable() }

    fun parse(file: File): Mesh {
        if (!available) throw StlParseException("STEP support is not included in this build")
        val arrays = nativeMesh(file.absolutePath)
        return Mesh(arrays[0], arrays[1])
    }

    @JvmStatic private external fun nativeAvailable(): Boolean
    @JvmStatic private external fun nativeMesh(path: String): Array<FloatArray>
}
