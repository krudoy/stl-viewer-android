package com.krudoy.stlviewer.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThreeMfParserTest {

    private val ns = """xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02""""
    private val nsP = """xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06""""

    private val rels = """<?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
        </Relationships>"""

    private val triangleMesh = """
        <mesh>
          <vertices>
            <vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/>
          </vertices>
          <triangles><triangle v1="0" v2="1" v3="2"/></triangles>
        </mesh>"""

    private fun zip(vararg entries: Pair<String, String>): File {
        val f = File.createTempFile("test", ".3mf")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
            }
        }
        return f
    }

    @Test fun parsesSimpleModelWithBuildTransform() {
        val model = """<?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" $ns>
              <resources><object id="1" type="model">$triangleMesh</object></resources>
              <build><item objectid="1" transform="1 0 0 0 1 0 0 0 1 10 20 30"/></build>
            </model>"""
        val mesh = ThreeMfParser.parse(zip("_rels/.rels" to rels, "3D/3dmodel.model" to model))
        assertEquals(1, mesh.triangleCount)
        assertArrayEquals(floatArrayOf(10f, 20f, 30f, 11f, 20f, 30f, 10f, 21f, 30f), mesh.positions, 1e-6f)
        assertEquals(1f, mesh.normals[2], 1e-6f)
    }

    @Test fun followsProductionComponentsInOtherParts() {
        // Bambu Studio style: root object is only components pointing into another part.
        val root = """<?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" $ns $nsP>
              <resources>
                <object id="2" type="model">
                  <components>
                    <component p:path="/3D/Objects/object_1.model" objectid="1" transform="2 0 0 0 2 0 0 0 2 0 0 0"/>
                    <component p:path="/3D/Objects/object_1.model" objectid="1" transform="1 0 0 0 1 0 0 0 1 5 0 0"/>
                  </components>
                </object>
              </resources>
              <build><item objectid="2" transform="1 0 0 0 1 0 0 0 1 0 0 1"/></build>
            </model>"""
        val part = """<?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" $ns>
              <resources><object id="1" type="model">$triangleMesh</object></resources>
            </model>"""
        val mesh = ThreeMfParser.parse(
            zip("_rels/.rels" to rels, "3D/3dmodel.model" to root, "3D/Objects/object_1.model" to part),
        )
        assertEquals(2, mesh.triangleCount)
        // First copy: scaled by 2, then lifted by 1 in Z.
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 2f, 0f, 1f, 0f, 2f, 1f), mesh.positions.copyOfRange(0, 9), 1e-6f)
        // Second copy: moved 5 in X, then lifted.
        assertArrayEquals(floatArrayOf(5f, 0f, 1f, 6f, 0f, 1f, 5f, 1f, 1f), mesh.positions.copyOfRange(9, 18), 1e-6f)
    }

    @Test fun convertsUnitsToMillimeters() {
        val model = """<model unit="inch" $ns>
              <resources><object id="1">$triangleMesh</object></resources>
              <build><item objectid="1"/></build>
            </model>"""
        val mesh = ThreeMfParser.parse(zip("3D/3dmodel.model" to model))
        assertEquals(25.4f, mesh.bounds.maxX, 1e-4f)
    }

    @Test fun mirroredTransformKeepsOutwardWinding() {
        val model = """<model $ns>
              <resources><object id="1">$triangleMesh</object></resources>
              <build><item objectid="1" transform="-1 0 0 0 1 0 0 0 1 0 0 0"/></build>
            </model>"""
        val mesh = ThreeMfParser.parse(zip("3D/3dmodel.model" to model))
        // Mirroring in X would flip the normal to -Z; winding is reversed to keep +Z.
        assertEquals(1f, mesh.normals[2], 1e-6f)
    }

    @Test fun badVertexIndexFails() {
        val model = """<model $ns><resources><object id="1"><mesh>
              <vertices><vertex x="0" y="0" z="0"/></vertices>
              <triangles><triangle v1="0" v2="1" v3="2"/></triangles>
            </mesh></object></resources><build><item objectid="1"/></build></model>"""
        assertThrows(StlParseException::class.java) { ThreeMfParser.parse(zip("3D/3dmodel.model" to model)) }
    }

    @Test fun notAZipFails() {
        val f = File.createTempFile("bad", ".3mf").apply { writeText("hello"); deleteOnExit() }
        assertThrows(StlParseException::class.java) { ThreeMfParser.parse(f) }
    }

    @Test fun loaderDetectsZipAndStl() {
        val model = """<model $ns><resources><object id="1">$triangleMesh</object></resources>
            <build><item objectid="1"/></build></model>"""
        val bytes = zip("3D/3dmodel.model" to model).readBytes()
        val scratch = File.createTempFile("scratch", ".tmp").apply { deleteOnExit() }
        val mesh = ModelLoader.load(ByteArrayInputStream(bytes), bytes.size.toLong(), scratch)
        assertEquals(1, mesh.triangleCount)

        val stl = "solid x\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid x\n"
            .toByteArray()
        assertEquals(1, ModelLoader.load(ByteArrayInputStream(stl), stl.size.toLong(), scratch).triangleCount)
    }
}
