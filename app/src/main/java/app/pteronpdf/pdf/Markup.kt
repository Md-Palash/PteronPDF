package app.pteronpdf.pdf

import android.graphics.PointF
import android.graphics.RectF

/**
 * Everything is stored in PAGE coordinates (PDF points, origin top-left), so it is zoom-independent.
 * Values are immutable by convention: always build new PointF/RectF, never mutate one in place.
 * Widths and font sizes are in PDF points, so a marking looks the same at every zoom level.
 */
sealed interface Markup {
    val color: Int   // ARGB
    fun withColor(c: Int): Markup

    /** Freehand writing pen. */
    data class Ink(val pts: List<PointF>, override val color: Int, val width: Float, val opacity: Float) : Markup {
        override fun withColor(c: Int) = copy(color = c)
    }

    /** Straight line, arrow, square, circle: defined by two corner/end points. */
    data class Shape(val kind: ShapeKind, val p0: PointF, val p1: PointF, override val color: Int, val width: Float) : Markup {
        override fun withColor(c: Int) = copy(color = c)
    }

    /** Quadratic curve p0 → p1 pulled toward control point [c]. The on-screen handle sits ON the curve (see Geom). */
    data class Curve(val p0: PointF, val c: PointF, val p1: PointF, override val color: Int, val width: Float) : Markup {
        override fun withColor(c: Int) = copy(color = c)
    }

    /** Text box / comment. */
    data class Text(val rect: RectF, val text: String, override val color: Int, val fontSize: Float, val bold: Boolean) : Markup {
        override fun withColor(c: Int) = copy(color = c)
    }

    /** Text-snapped highlight: one rect per selected line fragment. */
    data class Highlight(val rects: List<RectF>, override val color: Int) : Markup {
        override fun withColor(c: Int) = copy(color = c)
    }
}

enum class ShapeKind { Line, Arrow, Rect, Circle }

enum class Tool { None, Select, Pen, Highlight, Line, Curve, Arrow, Rect, Circle, Text, Erase }

/** A markup placed on a page. [id] is also written to the PDF annotation's /NM ("pt-<id>"). */
data class Item(val id: Long, val page: Int, val m: Markup)

/** One undo step. */
sealed interface HistoryEntry {
    data class Added(val item: Item, val index: Int) : HistoryEntry
    data class Removed(val item: Item, val index: Int) : HistoryEntry
    data class Edited(val old: Item, val new: Item) : HistoryEntry
}
