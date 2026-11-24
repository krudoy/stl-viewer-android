package com.krudoy.stlviewer.render

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class OrientationCubeTest {

    private fun region(x: Float, y: Float, z: Float) = OrientationCube.regionOf(floatArrayOf(x, y, z))

    @Test fun faceCentersMapToFaces() {
        assertArrayEquals(intArrayOf(0, -1, 0), region(0f, -1f, 0f)) // front
        assertArrayEquals(intArrayOf(0, 0, 1), region(0.1f, -0.2f, 1f)) // top
        assertArrayEquals(intArrayOf(1, 0, 0), region(1f, 0.5f, -0.5f)) // right
    }

    @Test fun nearOneBorderIsAnEdge() {
        // On the front face, close to the top border: the front-top edge.
        assertArrayEquals(intArrayOf(0, -1, 1), region(0f, -1f, 0.9f))
        // On the top face, close to the right border: the top-right edge.
        assertArrayEquals(intArrayOf(1, 0, 1), region(0.95f, 0f, 1f))
    }

    @Test fun nearTwoBordersIsACorner() {
        assertArrayEquals(intArrayOf(1, -1, 1), region(0.9f, -1f, 0.9f))
        assertArrayEquals(intArrayOf(-1, 1, -1), region(-1f, 0.85f, -0.85f))
    }
}
