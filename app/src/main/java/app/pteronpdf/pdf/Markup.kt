package app.pteronpdf.pdf

import android.graphics.PointF
import android.graphics.RectF

/** Everything stored in PAGE coordinates (PDF points), so it is zoom-independent. */
sealed interface Markup {
    val color: Int   // ARGB
    data class Ink(val pts: List<PointF>, override val color: Int, val width: Float, val opacity: Float) : Markup
    data class Shape(val kind: ShapeKind, val p0: PointF, val p1: PointF, override val color: Int, val width: Float) : Markup
    data class Text(val rect: RectF, val text: String, override val color: Int, val fontSize: Float, val bold: Boolean) : Markup
}

enum class ShapeKind { Line, Arrow, Rect, Circle }

enum class Tool { None, PenWrite, PenMark, Highlighter, Line, Arrow, Rect, Circle, Text, Erase }

/** One undo step. [id] is stored in the annotation's /NM so we can find it again without holding handles. */
sealed interface HistoryEntry {
    val page: Int; val id: Long; val markup: Markup
    data class Added(override val page: Int, override val id: Long, override val markup: Markup) : HistoryEntry
    data class Removed(override val page: Int, override val id: Long, override val markup: Markup) : HistoryEntry
}
