/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws a textured [Mesh3d] onto a software canvas, without a triangle scan-converter.
 *
 * [Matrix.setPolyToPoly] takes up to four point pairs and yields a full perspective homography, and
 * a planar face under perspective maps to its texture by exactly that — so four projected vertices
 * describe a face of any vertex count, and each face costs one clip plus one `drawBitmap`.
 *
 * The reference renderer runs unlit, so faces are drawn at full brightness with no shading pass.
 */
class SolidRasterizer {

    private val view = FloatArray(MAX_VERTS * 3)
    private val order = IntArray(MAX_FACES)
    private val depth = FloatArray(MAX_FACES)

    // A face clipped against the near plane gains at most one vertex.
    private val clipX = FloatArray(MAX_STRIDE + 1)
    private val clipY = FloatArray(MAX_STRIDE + 1)
    private val clipZ = FloatArray(MAX_STRIDE + 1)
    private val clipU = FloatArray(MAX_STRIDE + 1)
    private val clipV = FloatArray(MAX_STRIDE + 1)
    private val screenX = FloatArray(MAX_STRIDE + 1)
    private val screenY = FloatArray(MAX_STRIDE + 1)

    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val matrix = Matrix()
    private val path = Path()

    // No ANTI_ALIAS_FLAG: antialiased face edges against a hard clip leave visible seams between
    // neighbouring faces.
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * @param scale uniform scale applied after the rotation.
     * @param offsetY vertical shift in canvas fractions, applied after the rotation.
     * @param pushZ depth shift applied after the rotation, so the solid moves toward the camera.
     * @param mirrorU flips the texture horizontally, for the effects that refuse to show a mirrored
     *   back face.
     */
    private var effect: AvatarEffect? = null
    private var frame = 0

    /**
     * Names the frame about to be drawn, which is what lets [draw] recognise that another avatar has
     * already worked out where this shape's faces go.
     */
    fun begin(effect: AvatarEffect, frame: Int) {
        this.effect = effect
        this.frame = frame
    }

    fun draw(
            canvas: Canvas,
            mesh: Mesh3d,
            rotation: Rot3,
            texture: Bitmap,
            sizePx: Int,
            scale: Float = 1f,
            offsetY: Float = 0f,
            pushZ: Float = 0f,
            mirrorU: Boolean = false,
    ) {
        val named = effect
        val cached = named?.let { SolidGeometryCache.get(it, sizePx, frame) }
        val geometry = cached ?: project(mesh, rotation, sizePx, scale, offsetY, pushZ, mirrorU)
                ?.also { if (named != null) SolidGeometryCache.put(named, sizePx, frame, it) }
        paint(canvas, geometry, texture)
    }

    /**
     * Works out where every visible face lands, with no texture involved.
     *
     * Which is the point of it being separate: avatars sharing an effect, a size and a frame project
     * to exactly the same faces and differ only in the picture painted onto them, so a room list full
     * of one shape can do this once and fill it many times.
     */
    fun project(
            mesh: Mesh3d,
            rotation: Rot3,
            sizePx: Int,
            scale: Float = 1f,
            offsetY: Float = 0f,
            pushZ: Float = 0f,
            mirrorU: Boolean = false,
    ): SolidGeometry? {
        val vertexCount = mesh.verts.size / 3
        if (vertexCount > MAX_VERTS || mesh.faceCount > MAX_FACES || mesh.faceStride > MAX_STRIDE) return null

        var i = 0
        while (i < mesh.verts.size) {
            val x = mesh.verts[i]
            val y = mesh.verts[i + 1]
            val z = mesh.verts[i + 2]
            view[i] = (rotation.m00 * x + rotation.m01 * y + rotation.m02 * z) * scale
            view[i + 1] = (rotation.m10 * x + rotation.m11 * y + rotation.m12 * z) * scale + offsetY
            view[i + 2] = (rotation.m20 * x + rotation.m21 * y + rotation.m22 * z) * scale + pushZ
            i += 3
        }

        var visible = 0
        for (f in 0 until mesh.faceCount) {
            val base = f * mesh.faceStride
            var zSum = 0f
            var inFront = 0
            for (k in 0 until mesh.faceStride) {
                val z = view[mesh.faces[base + k] * 3 + 2]
                zSum += z
                if (z < NEAR_Z) inFront++
            }
            if (inFront == 0) continue
            order[visible] = f
            depth[visible] = zSum / mesh.faceStride
            visible++
        }
        sortFarthestFirst(visible)

        val half = sizePx / 2f
        val geometry = SolidGeometry(visible, MAX_STRIDE + 1)
        for (n in 0 until visible) {
            val f = order[n]
            val count = clipFace(mesh, f, mirrorU)
            if (count < 3) continue

            for (k in 0 until count) {
                val w = EYE_Z / (EYE_Z - clipZ[k])
                screenX[k] = half + clipX[k] * sizePx * w
                screenY[k] = half + clipY[k] * sizePx * w
            }
            val area = signedArea(count)
            if (area == 0f) continue
            // A closed solid hides its own back faces, so culling is the whole visibility solve and
            // halves the fill.
            if (mesh.closed && area < 0f) continue
            // Adjacent hard-clipped faces otherwise round apart and leave hairline gaps; drawing
            // back to front hides the overdraw.
            inflate(count)
            geometry.add(count, screenX, screenY, clipU, clipV)
        }
        return geometry
    }

    /** Paints [texture] onto faces already worked out by [project]. */
    fun paint(canvas: Canvas, geometry: SolidGeometry?, texture: Bitmap) {
        geometry ?: return
        val texW = texture.width.toFloat()
        val texH = texture.height.toFloat()
        for (face in 0 until geometry.faceCount) {
            val count = geometry.vertexCount(face)
            path.rewind()
            path.moveTo(geometry.screenX(face, 0), geometry.screenY(face, 0))
            for (k in 1 until count) path.lineTo(geometry.screenX(face, k), geometry.screenY(face, k))
            path.close()

            val points = min(4, count)
            for (k in 0 until points) {
                src[k * 2] = geometry.u(face, k) * texW
                src[k * 2 + 1] = geometry.v(face, k) * texH
                dst[k * 2] = geometry.screenX(face, k)
                dst[k * 2 + 1] = geometry.screenY(face, k)
            }
            if (!matrix.setPolyToPoly(src, 0, dst, 0, points)) continue

            val saved = canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(texture, matrix, paint)
            canvas.restoreToCount(saved)
        }
    }

    /**
     * Copies a face into the clip buffers, cutting it against the near plane on the way. Dropping a
     * face that crosses the plane instead makes anything lunging at the camera vanish for the frames
     * it matters most.
     */
    private fun clipFace(mesh: Mesh3d, face: Int, mirrorU: Boolean): Int {
        val stride = mesh.faceStride
        val base = face * stride
        var out = 0
        for (k in 0 until stride) {
            val current = mesh.faces[base + k] * 3
            val next = mesh.faces[base + (k + 1) % stride] * 3
            val cu = uAt(mesh, base, k, mirrorU)
            val cv = mesh.uv[(base + k) * 2 + 1]
            val nu = uAt(mesh, base, (k + 1) % stride, mirrorU)
            val nv = mesh.uv[(base + (k + 1) % stride) * 2 + 1]
            val cIn = view[current + 2] < NEAR_Z
            val nIn = view[next + 2] < NEAR_Z

            if (cIn) {
                clipX[out] = view[current]
                clipY[out] = view[current + 1]
                clipZ[out] = view[current + 2]
                clipU[out] = cu
                clipV[out] = cv
                out++
            }
            if (cIn != nIn && out <= MAX_STRIDE) {
                val t = (NEAR_Z - view[current + 2]) / (view[next + 2] - view[current + 2])
                clipX[out] = view[current] + (view[next] - view[current]) * t
                clipY[out] = view[current + 1] + (view[next + 1] - view[current + 1]) * t
                clipZ[out] = NEAR_Z
                clipU[out] = cu + (nu - cu) * t
                clipV[out] = cv + (nv - cv) * t
                out++
            }
            if (out > MAX_STRIDE) break
        }
        return out
    }

    private fun uAt(mesh: Mesh3d, base: Int, k: Int, mirrorU: Boolean): Float {
        val u = mesh.uv[(base + k) * 2]
        return if (mirrorU) 1f - u else u
    }

    private fun signedArea(count: Int): Float {
        var area = 0f
        for (k in 0 until count) {
            val next = (k + 1) % count
            area += screenX[k] * screenY[next] - screenX[next] * screenY[k]
        }
        return area / 2f
    }

    private fun inflate(count: Int) {
        var cx = 0f
        var cy = 0f
        for (k in 0 until count) {
            cx += screenX[k]
            cy += screenY[k]
        }
        cx /= count
        cy /= count
        for (k in 0 until count) {
            val dx = screenX[k] - cx
            val dy = screenY[k] - cy
            val len = sqrt(dx * dx + dy * dy)
            if (len < 0.001f) continue
            screenX[k] = cx + dx * (len + EDGE_INFLATE_PX) / len
            screenY[k] = cy + dy * (len + EDGE_INFLATE_PX) / len
        }
    }

    // Insertion sort: face counts are small, the order barely changes between frames, and it
    // allocates nothing (unlike sorting boxed keys).
    private fun sortFarthestFirst(count: Int) {
        for (i in 1 until count) {
            val f = order[i]
            val d = depth[i]
            var j = i - 1
            while (j >= 0 && depth[j] > d) {
                order[j + 1] = order[j]
                depth[j + 1] = depth[j]
                j--
            }
            order[j + 1] = f
            depth[j + 1] = d
        }
    }

    companion object {
        /** (size/2) / tan(30°), in units of the canvas size — p5's default camera distance. */
        const val EYE_Z = 0.8660254f

        private const val NEAR_Z = EYE_Z - 0.02f
        private const val EDGE_INFLATE_PX = 0.7f
        private const val MAX_VERTS = 4096
        private const val MAX_FACES = 2048
        private const val MAX_STRIDE = 5
    }
}

/**
 * Where a solid's visible faces landed on screen, and which part of a texture goes on each.
 *
 * Immutable once built, so the render threads can share one.
 */
class SolidGeometry(maxFaces: Int, private val stride: Int) {

    private val counts = IntArray(maxFaces)
    private val screen = FloatArray(maxFaces * stride * 2)
    private val texture = FloatArray(maxFaces * stride * 2)

    var faceCount = 0
        private set

    internal fun add(count: Int, xs: FloatArray, ys: FloatArray, us: FloatArray, vs: FloatArray) {
        val base = faceCount * stride * 2
        for (k in 0 until count) {
            screen[base + k * 2] = xs[k]
            screen[base + k * 2 + 1] = ys[k]
            texture[base + k * 2] = us[k]
            texture[base + k * 2 + 1] = vs[k]
        }
        counts[faceCount] = count
        faceCount++
    }

    fun vertexCount(face: Int) = counts[face]
    fun screenX(face: Int, vertex: Int) = screen[(face * stride + vertex) * 2]
    fun screenY(face: Int, vertex: Int) = screen[(face * stride + vertex) * 2 + 1]
    fun u(face: Int, vertex: Int) = texture[(face * stride + vertex) * 2]
    fun v(face: Int, vertex: Int) = texture[(face * stride + vertex) * 2 + 1]

    /** Roughly what it occupies, for the cache that keeps these. */
    fun sizeInBytes() = (screen.size + texture.size) * 4 + counts.size * 4
}
