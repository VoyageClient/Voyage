/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A textured solid in normalised world units, where 1.0 is the canvas edge — the same units the
 * reference renderer works in once its `size` is divided out.
 *
 * @param faceStride vertices per face; every face in a mesh has the same count.
 * @param uv one (u, v) pair per face vertex, so a face can carry the whole image rather than a
 *   share of one atlas.
 * @param closed a closed convex solid hides its own back faces, so they can be culled instead of
 *   sorted and overdrawn.
 */
class Mesh3d(
        val verts: FloatArray,
        val faces: IntArray,
        val faceStride: Int,
        val uv: FloatArray,
        val closed: Boolean,
) {
    val faceCount = faces.size / faceStride
}

object MeshLibrary {

    /** The plane every flat-card effect spins: p5 scales the image to [AvatarEffect.FIT_FRACTION]. */
    fun card(): Mesh3d {
        val size = AvatarEffect.FIT_FRACTION
        val h = size / 2f
        return Mesh3d(
                verts = floatArrayOf(-h, -h, 0f, h, -h, 0f, h, h, 0f, -h, h, 0f),
                faces = intArrayOf(0, 1, 2, 3),
                faceStride = 4,
                uv = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
                closed = false,
        )
    }

    /**
     * The card given depth. The reference stacks copies of the plane every 0.1 world units, which
     * reads as a solid slab; one box of the same depth is the same picture for a fraction of the
     * fill.
     */
    fun slab(depth: Float): Mesh3d {
        val h = AvatarEffect.FIT_FRACTION / 2f
        val d = depth / 2f
        return box(h, h, d, textureSides = false)
    }

    fun cube(half: Float) = box(half, half, half, textureSides = true)

    /** A regular tetrahedron, its four faces each carrying the whole image. */
    fun tetrahedron(circumradius: Float): Mesh3d {
        val k = circumradius / sqrt(3f)
        val v = floatArrayOf(
                k, k, k,
                k, -k, -k,
                -k, k, -k,
                -k, -k, k,
        )
        // A tetrahedron's faces sit opposite its vertices.
        val centres = FloatArray(v.size) { -v[it] }
        return Mesh3d(v, facesAround(v, centres, 3), 3, triangleUv(4), closed = true)
    }

    /** A regular dodecahedron: twelve pentagons, each showing the image inscribed in it. */
    fun dodecahedron(circumradius: Float): Mesh3d {
        val phi = (1f + sqrt(5f)) / 2f
        // The standard vertex set has circumradius sqrt(3).
        val s = circumradius / sqrt(3f)
        val a = s
        val b = s / phi
        val c = s * phi
        val v = floatArrayOf(
                a, a, a, a, a, -a, a, -a, a, a, -a, -a,
                -a, a, a, -a, a, -a, -a, -a, a, -a, -a, -a,
                0f, b, c, 0f, b, -c, 0f, -b, c, 0f, -b, -c,
                b, c, 0f, b, -c, 0f, -b, c, 0f, -b, -c, 0f,
                c, 0f, b, -c, 0f, b, c, 0f, -b, -c, 0f, -b,
        )
        // The twelve face normals, which are (0, ±φ, ±1) and its cyclic permutations against the
        // vertex set above. Not (0, ±1, ±φ): those directions point at vertices, so the five nearest
        // to each are not a face and the solid comes out a tangle.
        val centres = floatArrayOf(
                0f, phi, 1f, 0f, phi, -1f, 0f, -phi, 1f, 0f, -phi, -1f,
                phi, 1f, 0f, phi, -1f, 0f, -phi, 1f, 0f, -phi, -1f, 0f,
                1f, 0f, phi, -1f, 0f, phi, 1f, 0f, -phi, -1f, 0f, -phi,
        )
        return Mesh3d(v, facesAround(v, centres, 5), 5, pentagonUv(12), closed = true)
    }

    /** A square pyramid with four textured sides, as the reference draws it (no base). */
    fun pyramid(scale: Float): Mesh3d {
        // Reference vertices in units of the canvas, then its scale() and translate().
        val ty = -1f / 1.75f
        fun x(v: Float) = (v - 0.5f) * scale
        fun y(v: Float) = (v + ty) * scale
        fun z(v: Float) = (v - 0.5f) * scale
        val v = floatArrayOf(
                x(0f), y(1f), z(0f),
                x(1f), y(1f), z(0f),
                x(1f), y(1f), z(1f),
                x(0f), y(1f), z(1f),
                x(0.5f), y(0f), z(0.5f),
        )
        val faces = intArrayOf(
                0, 4, 1,
                1, 4, 2,
                0, 4, 3,
                3, 4, 2,
        )
        // Apex is the top-middle of the image, base corners its bottom two, matching the reference UVs.
        val uv = FloatArray(faces.size * 2)
        for (f in 0 until 4) {
            val o = f * 6
            uv[o] = 0f; uv[o + 1] = 1f
            uv[o + 2] = 0.5f; uv[o + 3] = 0f
            uv[o + 4] = 1f; uv[o + 5] = 1f
        }
        // Four sides and no base, so it is not closed: culling its back faces would erase the whole
        // solid at the angles where only they face the camera.
        return Mesh3d(v, faces, 3, uv, closed = false)
    }

    fun sphere(radius: Float, segmentsX: Int, segmentsY: Int) = revolve(segmentsX, segmentsY) { u, v ->
        val theta = u * 2f * PI.toFloat()
        val phi = v * PI.toFloat()
        floatArrayOf(
                radius * sin(phi) * sin(theta),
                -radius * cos(phi),
                radius * sin(phi) * cos(theta),
        )
    }

    /** Lies in the XY plane with its hole facing the camera, as the reference's torus does. */
    fun torus(major: Float, minor: Float, segmentsX: Int, segmentsY: Int) = revolve(segmentsX, segmentsY) { u, v ->
        val theta = u * 2f * PI.toFloat()
        val phi = v * 2f * PI.toFloat()
        val r = major + minor * cos(phi)
        floatArrayOf(r * cos(theta), r * sin(theta), minor * sin(phi))
    }

    private inline fun revolve(segmentsX: Int, segmentsY: Int, point: (Float, Float) -> FloatArray): Mesh3d {
        val cols = segmentsX
        val rows = segmentsY
        val verts = FloatArray((cols + 1) * (rows + 1) * 3)
        var i = 0
        for (r in 0..rows) {
            for (c in 0..cols) {
                val p = point(c / cols.toFloat(), r / rows.toFloat())
                verts[i++] = p[0]
                verts[i++] = p[1]
                verts[i++] = p[2]
            }
        }
        val faces = IntArray(cols * rows * 4)
        val uv = FloatArray(cols * rows * 8)
        var f = 0
        var t = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val tl = r * (cols + 1) + c
                faces[f++] = tl
                faces[f++] = tl + 1
                faces[f++] = tl + cols + 2
                faces[f++] = tl + cols + 1
                val u0 = c / cols.toFloat()
                val u1 = (c + 1) / cols.toFloat()
                val v0 = r / rows.toFloat()
                val v1 = (r + 1) / rows.toFloat()
                uv[t++] = u0; uv[t++] = v0
                uv[t++] = u1; uv[t++] = v0
                uv[t++] = u1; uv[t++] = v1
                uv[t++] = u0; uv[t++] = v1
            }
        }
        return Mesh3d(verts, faces, 4, uv, closed = true)
    }

    private fun triangleUv(count: Int): FloatArray {
        val uv = FloatArray(count * 6)
        for (f in 0 until count) {
            val o = f * 6
            uv[o] = 0f; uv[o + 1] = 1f
            uv[o + 2] = 0.5f; uv[o + 3] = 0f
            uv[o + 4] = 1f; uv[o + 5] = 1f
        }
        return uv
    }

    private fun pentagonUv(count: Int): FloatArray {
        val uv = FloatArray(count * 10)
        for (f in 0 until count) {
            for (k in 0 until 5) {
                val angle = (-PI / 2.0 + k * 2.0 * PI / 5.0).toFloat()
                uv[f * 10 + k * 2] = 0.5f + 0.5f * cos(angle)
                uv[f * 10 + k * 2 + 1] = 0.5f + 0.5f * sin(angle)
            }
        }
        return uv
    }

    private fun box(hx: Float, hy: Float, hz: Float, textureSides: Boolean): Mesh3d {
        val v = floatArrayOf(
                -hx, -hy, hz, hx, -hy, hz, hx, hy, hz, -hx, hy, hz,
                -hx, -hy, -hz, hx, -hy, -hz, hx, hy, -hz, -hx, hy, -hz,
        )
        val faces = intArrayOf(
                0, 1, 2, 3,
                5, 4, 7, 6,
                1, 5, 6, 2,
                4, 0, 3, 7,
                4, 5, 1, 0,
                3, 2, 6, 7,
        )
        val uv = FloatArray(faces.size * 2)
        for (f in 0 until 6) {
            val o = f * 8
            // A slab's four rims are edges of the image, not another copy of it.
            val squeeze = !textureSides && f >= 2
            val u0 = if (squeeze) 0.48f else 0f
            val u1 = if (squeeze) 0.52f else 1f
            uv[o] = u0; uv[o + 1] = 0f
            uv[o + 2] = u1; uv[o + 3] = 0f
            uv[o + 4] = u1; uv[o + 5] = 1f
            uv[o + 6] = u0; uv[o + 7] = 1f
        }
        return Mesh3d(v, faces, 4, uv, closed = true)
    }

    /**
     * Builds each face from the [perFace] vertices nearest its centre direction, wound
     * anticlockwise as seen from outside. Deriving the topology beats transcribing it: the standard
     * vertex tables are easy to get subtly wrong and impossible to eyeball once wrong.
     */
    private fun facesAround(verts: FloatArray, centres: FloatArray, perFace: Int): IntArray {
        val faceCount = centres.size / 3
        val out = IntArray(faceCount * perFace)
        val vertexCount = verts.size / 3
        for (f in 0 until faceCount) {
            var cx = centres[f * 3]
            var cy = centres[f * 3 + 1]
            var cz = centres[f * 3 + 2]
            val cLen = sqrt(cx * cx + cy * cy + cz * cz)
            cx /= cLen; cy /= cLen; cz /= cLen

            val picked = (0 until vertexCount)
                    .sortedBy { -(verts[it * 3] * cx + verts[it * 3 + 1] * cy + verts[it * 3 + 2] * cz) }
                    .take(perFace)

            // An in-plane basis, so the vertices can be ordered by their angle about the centre.
            val seed = picked[0]
            var ux = verts[seed * 3]
            var uy = verts[seed * 3 + 1]
            var uz = verts[seed * 3 + 2]
            val dot = ux * cx + uy * cy + uz * cz
            ux -= cx * dot; uy -= cy * dot; uz -= cz * dot
            val uLen = sqrt(ux * ux + uy * uy + uz * uz)
            ux /= uLen; uy /= uLen; uz /= uLen
            val wx = cy * uz - cz * uy
            val wy = cz * ux - cx * uz
            val wz = cx * uy - cy * ux

            val ordered = picked.sortedBy {
                val x = verts[it * 3]
                val y = verts[it * 3 + 1]
                val z = verts[it * 3 + 2]
                kotlin.math.atan2(x * wx + y * wy + z * wz, x * ux + y * uy + z * uz)
            }
            for (k in 0 until perFace) out[f * perFace + k] = ordered[k]
        }
        return out
    }
}
