package app.pteronpdf.pdf

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Pure geometry for markups: what to draw, what was hit, and how handles edit a markup. Shared by overlay + engine. */
object Geom {
    private const val MIN_BOX = 10f
    private const val CURVE_STEPS = 28
    private const val CIRCLE_STEPS = 72

    fun arrowBarb(width: Float) = (width * 5f).coerceIn(9f, 30f)

    /** Polylines that make up the stroked shape. The overlay paints exactly this, and the PDF stores exactly this. */
    fun strokes(m: Markup): List<List<PointF>> = when (m) {
        is Markup.Ink -> listOf(m.pts)
        is Markup.Shape -> when (m.kind) {
            ShapeKind.Line -> listOf(listOf(m.p0, m.p1))
            ShapeKind.Arrow -> {
                val ang = atan2(m.p1.y - m.p0.y, m.p1.x - m.p0.x)
                val len = arrowBarb(m.width)
                fun barb(d: Float) = listOf(m.p1, PointF(m.p1.x - len * cos(ang + d), m.p1.y - len * sin(ang + d)))
                listOf(listOf(m.p0, m.p1), barb(0.5f), barb(-0.5f))
            }
            ShapeKind.Rect -> {
                val r = rectOf(m.p0, m.p1)
                listOf(listOf(PointF(r.left, r.top), PointF(r.right, r.top), PointF(r.right, r.bottom), PointF(r.left, r.bottom), PointF(r.left, r.top)))
            }
            ShapeKind.Circle -> {
                val r = rectOf(m.p0, m.p1)
                val cx = r.centerX(); val cy = r.centerY(); val rx = r.width() / 2; val ry = r.height() / 2
                listOf((0..CIRCLE_STEPS).map { i ->
                    val a = (2 * PI * i / CIRCLE_STEPS).toFloat()
                    PointF(cx + rx * cos(a), cy + ry * sin(a))
                })
            }
        }
        is Markup.Curve -> listOf((0..CURVE_STEPS).map { i ->
            val t = i / CURVE_STEPS.toFloat(); val u = 1 - t
            PointF(u * u * m.p0.x + 2 * u * t * m.c.x + t * t * m.p1.x, u * u * m.p0.y + 2 * u * t * m.c.y + t * t * m.p1.y)
        })
        is Markup.Text -> emptyList()
    }

    fun widthOf(m: Markup): Float? = when (m) {
        is Markup.Ink -> m.width; is Markup.Shape -> m.width; is Markup.Curve -> m.width; else -> null
    }

    fun withWidth(m: Markup, w: Float): Markup = when (m) {
        is Markup.Ink -> m.copy(width = w); is Markup.Shape -> m.copy(width = w); is Markup.Curve -> m.copy(width = w); else -> m
    }

    fun rectOf(a: PointF, b: PointF) = RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

    fun bounds(m: Markup): RectF = when (m) {
        is Markup.Text -> RectF(m.rect)
        else -> {
            val pts = strokes(m).flatten()
            RectF(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        }
    }

    // ───────── hit testing ─────────
    private fun distToSeg(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-6f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    fun hit(m: Markup, p: PointF, tol: Float): Boolean = when (m) {
        is Markup.Text -> RectF(m.rect).apply { inset(-tol, -tol) }.contains(p.x, p.y)
        else -> {
            val reach = tol + (widthOf(m) ?: 0f) / 2
            strokes(m).any { line -> line.zipWithNext().any { (a, b) -> distToSeg(p, a, b) <= reach } }
        }
    }

    // ───────── handles ─────────
    fun corners(r: RectF) = listOf(PointF(r.left, r.top), PointF(r.right, r.top), PointF(r.right, r.bottom), PointF(r.left, r.bottom))

    private fun curveMid(m: Markup.Curve) = PointF(
        0.25f * m.p0.x + 0.5f * m.c.x + 0.25f * m.p1.x, 0.25f * m.p0.y + 0.5f * m.c.y + 0.25f * m.p1.y)

    /** Draggable handle positions. Corner handles are ordered TL, TR, BR, BL. */
    fun handles(m: Markup): List<PointF> = when (m) {
        is Markup.Shape -> if (m.kind == ShapeKind.Line || m.kind == ShapeKind.Arrow) listOf(m.p0, m.p1) else corners(bounds(m))
        is Markup.Curve -> listOf(m.p0, curveMid(m), m.p1)
        else -> corners(bounds(m))
    }

    fun dragHandle(m: Markup, i: Int, to: PointF): Markup = when (m) {
        is Markup.Shape -> if (m.kind == ShapeKind.Line || m.kind == ShapeKind.Arrow) {
            if (i == 0) m.copy(p0 = to) else m.copy(p1 = to)
        } else fit(m, bounds(m), newRect(bounds(m), i, to))
        is Markup.Curve -> when (i) {
            0 -> m.copy(p0 = to)
            2 -> m.copy(p1 = to)
            else -> m.copy(c = PointF(2 * to.x - 0.5f * (m.p0.x + m.p1.x), 2 * to.y - 0.5f * (m.p0.y + m.p1.y)))
        }
        else -> fit(m, bounds(m), newRect(bounds(m), i, to))
    }

    private fun newRect(old: RectF, i: Int, to: PointF): RectF {
        val fixed = corners(old)[(i + 2) % 4]
        val r = rectOf(fixed, to)
        if (r.width() < MIN_BOX) { if (to.x >= fixed.x) r.right = r.left + MIN_BOX else r.left = r.right - MIN_BOX }
        if (r.height() < MIN_BOX) { if (to.y >= fixed.y) r.bottom = r.top + MIN_BOX else r.top = r.bottom - MIN_BOX }
        return r
    }

    private fun fit(m: Markup, old: RectF, new: RectF): Markup = when (m) {
        is Markup.Text -> m.copy(rect = new)
        is Markup.Shape -> m.copy(p0 = PointF(new.left, new.top), p1 = PointF(new.right, new.bottom))
        is Markup.Ink -> {
            val ow = old.width(); val oh = old.height()
            m.copy(pts = m.pts.map { p ->
                PointF(
                    if (ow < 1e-3f) new.centerX() else new.left + (p.x - old.left) / ow * new.width(),
                    if (oh < 1e-3f) new.centerY() else new.top + (p.y - old.top) / oh * new.height(),
                )
            })
        }
        else -> m
    }

    // ───────── move ─────────
    private fun mv(p: PointF, dx: Float, dy: Float) = PointF(p.x + dx, p.y + dy)

    fun move(m: Markup, dx: Float, dy: Float): Markup = when (m) {
        is Markup.Ink -> m.copy(pts = m.pts.map { mv(it, dx, dy) })
        is Markup.Shape -> m.copy(p0 = mv(m.p0, dx, dy), p1 = mv(m.p1, dx, dy))
        is Markup.Curve -> m.copy(p0 = mv(m.p0, dx, dy), c = mv(m.c, dx, dy), p1 = mv(m.p1, dx, dy))
        is Markup.Text -> m.copy(rect = RectF(m.rect).apply { offset(dx, dy) })
    }

    /** Shifts [m] the least amount needed to sit fully inside a page of [pageW] x [pageH] points (page edge wins if it is bigger). */
    fun clampInto(m: Markup, pageW: Float, pageH: Float): Markup {
        val b = bounds(m)
        val dx = if (b.width() >= pageW) -b.left else min(max(b.left, 0f), pageW - b.width()) - b.left
        val dy = if (b.height() >= pageH) -b.top else min(max(b.top, 0f), pageH - b.height()) - b.top
        return if (dx == 0f && dy == 0f) m else move(m, dx, dy)
    }

    /** Initial box for a new text note, sized roughly to its content. */
    fun textRect(at: PointF, text: String, fontSize: Float): RectF {
        val lines = text.split('\n')
        val w = lines.maxOf { it.length }.coerceAtLeast(3) * fontSize * 0.56f + 10f
        val h = lines.size * fontSize * 1.25f + 8f
        return RectF(at.x, at.y, at.x + w, at.y + h)
    }

    @Suppress("unused") fun near(a: Float, b: Float, eps: Float = 1e-3f) = abs(a - b) < eps
}
