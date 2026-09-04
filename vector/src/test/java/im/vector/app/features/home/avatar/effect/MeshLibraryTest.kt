/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The solids are generated rather than transcribed, so their winding and topology are asserted here:
 * a face wound the wrong way is culled exactly when it should be drawn, which looks like the shape
 * turning itself inside out.
 */
class MeshLibraryTest {

    private val closedSolids = mapOf(
            "cube" to MeshLibrary.cube(0.25f),
            "tetrahedron" to MeshLibrary.tetrahedron(0.45f),
            "dodecahedron" to MeshLibrary.dodecahedron(0.40f),
            "sphere" to MeshLibrary.sphere(0.33f, 8, 6),
            "torus" to MeshLibrary.torus(0.2f, 0.1f, 12, 8),
    )

    @Test
    fun `every closed solid winds its faces outwards`() {
        closedSolids.forEach { (name, mesh) ->
            for (face in 0 until mesh.faceCount) {
                val normal = normalOf(mesh, face)
                // A revolved mesh's pole rows collapse to a point, so those faces have no normal to
                // wind; the rasterizer drops them on area instead.
                if (normal.all { it == 0f }) continue
                val centroid = centroidOf(mesh, face)
                // A torus is not star-shaped about its centre — on the inside of the ring the
                // outward normal points back towards the axis — so "away from the origin" only
                // holds for the others; there it has to be away from the tube's own centre line.
                val reference = if (name == "torus") tubeCentre(centroid, 0.2f) else floatArrayOf(0f, 0f, 0f)
                val outward = (0..2).map { (centroid[it] - reference[it]) * normal[it] }.sum()

                withClue("$name face $face") { (outward > 0f).shouldBeTrue() }
            }
        }
    }

    @Test
    fun `every face is flat`() {
        closedSolids.forEach { (name, mesh) ->
            if (mesh.faceStride == 3) return@forEach
            for (face in 0 until mesh.faceCount) {
                val normal = normalOf(mesh, face)
                val base = face * mesh.faceStride
                val origin = vertex(mesh, mesh.faces[base])
                for (k in 3 until mesh.faceStride) {
                    val v = vertex(mesh, mesh.faces[base + k])
                    val offPlane = (0..2).sumOf { ((v[it] - origin[it]) * normal[it]).toDouble() }

                    withClue("$name face $face vertex $k") { (abs(offPlane) < 1e-3).shouldBeTrue() }
                }
            }
        }
    }

    @Test
    fun `the platonic solids have the faces and vertices they should`() {
        MeshLibrary.tetrahedron(0.45f).let {
            it.faceCount shouldBeEqualTo 4
            it.verts.size / 3 shouldBeEqualTo 4
        }
        MeshLibrary.dodecahedron(0.40f).let {
            it.faceCount shouldBeEqualTo 12
            it.verts.size / 3 shouldBeEqualTo 20
            it.faceStride shouldBeEqualTo 5
            // Three pentagons meet at each of the twenty corners.
            it.faces.toList().groupingBy { index -> index }.eachCount().values.forEach { uses ->
                uses shouldBeEqualTo 3
            }
        }
    }

    /** The point on the torus' major circle nearest [point], which its surface wraps around. */
    private fun tubeCentre(point: FloatArray, major: Float): FloatArray {
        val radial = sqrt(point[0] * point[0] + point[1] * point[1])
        if (radial == 0f) return floatArrayOf(major, 0f, 0f)
        return floatArrayOf(point[0] / radial * major, point[1] / radial * major, 0f)
    }

    private fun vertex(mesh: Mesh3d, index: Int) =
            floatArrayOf(mesh.verts[index * 3], mesh.verts[index * 3 + 1], mesh.verts[index * 3 + 2])

    private fun centroidOf(mesh: Mesh3d, face: Int): FloatArray {
        val base = face * mesh.faceStride
        val out = FloatArray(3)
        for (k in 0 until mesh.faceStride) {
            val v = vertex(mesh, mesh.faces[base + k])
            for (axis in 0..2) out[axis] += v[axis] / mesh.faceStride
        }
        return out
    }

    private fun normalOf(mesh: Mesh3d, face: Int): FloatArray {
        val base = face * mesh.faceStride
        val a = vertex(mesh, mesh.faces[base])
        val b = vertex(mesh, mesh.faces[base + 1])
        val c = vertex(mesh, mesh.faces[base + 2])
        val u = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        val v = floatArrayOf(c[0] - a[0], c[1] - a[1], c[2] - a[2])
        val n = floatArrayOf(
                u[1] * v[2] - u[2] * v[1],
                u[2] * v[0] - u[0] * v[2],
                u[0] * v[1] - u[1] * v[0],
        )
        val length = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
        return if (length == 0f) n else floatArrayOf(n[0] / length, n[1] / length, n[2] / length)
    }

    private fun <T> withClue(clue: Any, block: () -> T): T = try {
        block()
    } catch (error: AssertionError) {
        throw AssertionError("$clue: ${error.message}", error)
    }
}
