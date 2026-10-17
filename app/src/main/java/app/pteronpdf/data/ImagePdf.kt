package app.pteronpdf.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import kotlin.math.max
import kotlin.math.min

/** Turns pictures into a PDF with the platform's own writer (no library). Pictures are handled one at a time, so memory stays small. */
object ImagePdf {
    enum class PageMode(val label: String) { Match("Match image"), A4("A4 page") }

    private const val MAX_SIDE = 2200          // pixels kept per picture: sharp when read on a phone, files stay reasonable
    private const val A4_W = 595; private const val A4_H = 842
    private const val MATCH_W = 595            // "match image" pages are this wide (points), as tall as the picture needs

    /** Writes [images] (in order) to [out]. Returns how many could not be read. [onProgress] gets the number finished. */
    fun write(ctx: Context, images: List<Uri>, mode: PageMode, out: Uri, onProgress: (Int) -> Unit): Int {
        val doc = PdfDocument()
        var skipped = 0
        var pageNo = 0
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            images.forEachIndexed { i, uri ->
                val bmp = decode(ctx, uri, MAX_SIDE)
                if (bmp == null) skipped++ else {
                    try {
                        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
                        val (pw, ph) = when (mode) {
                            PageMode.A4 -> (if (bw > bh) A4_H to A4_W else A4_W to A4_H)
                            PageMode.Match -> MATCH_W to max(1, (MATCH_W * bh / bw).toInt())
                        }
                        val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, ++pageNo).create())
                        val canvas: Canvas = page.canvas
                        canvas.drawColor(Color.WHITE)
                        val margin = if (mode == PageMode.A4) 18f else 0f
                        val s = min((pw - 2 * margin) / bw, (ph - 2 * margin) / bh)
                        val dw = bw * s; val dh = bh * s
                        val left = (pw - dw) / 2f; val top = (ph - dh) / 2f
                        canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), paint)
                        doc.finishPage(page)
                    } finally { bmp.recycle() }
                }
                onProgress(i + 1)
            }
            if (pageNo == 0) throw IllegalStateException("None of the pictures could be read")
            ctx.contentResolver.openOutputStream(out, "wt")!!.use { doc.writeTo(it) }
        } finally { doc.close() }
        return skipped
    }

    /** Small decode for list thumbnails. */
    fun thumbnail(ctx: Context, uri: Uri, side: Int): Bitmap? = decode(ctx, uri, side)

    private fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null

        // phone photos are often stored sideways with a rotation flag: apply it so pages come out upright
        val m = Matrix()
        if (Build.VERSION.SDK_INT >= 24) {
            val orientation = cr.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            } ?: android.media.ExifInterface.ORIENTATION_NORMAL
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            }
        }
        val longest = max(bmp.width, bmp.height)
        if (longest > maxSide) { val k = maxSide.toFloat() / longest; m.postScale(k, k) }
        if (!m.isIdentity) {
            val t = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (t !== bmp) { bmp.recycle(); bmp = t }
        }
        bmp
    }.getOrNull()
}
