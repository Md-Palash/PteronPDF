package app.pteronpdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pteronpdf.theme.*

/** Floating pill card — the "popped card" look from the desktop toolbar. */
@Composable
fun PillCard(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = LocalPteron.current
    Row(
        modifier
            .shadow(8.dp, RoundedCornerShape(26.dp), ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
            .clip(RoundedCornerShape(26.dp)).background(c.bar)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun BarIconButton(
    icon: PIcon, desc: String, onClick: () -> Unit,
    enabled: Boolean = true, selected: Boolean = false,
) {
    val c = LocalPteron.current
    val tint = when { selected -> c.onAccent; !enabled -> c.onBar.copy(alpha = 0.3f); else -> c.onBar }
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(if (selected) c.accent else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PIconView(icon, tint, Modifier.size(22.dp), desc) }
}

/** The PteronPDF logo (document leaf unfolding into a wing), drawn natively — no image file in the APK. */
@Composable
fun LogoMark(@Suppress("UNUSED_PARAMETER") accent: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val k = this.size.minDimension / 512f
        fun pt(x: Float, y: Float) = Offset((96f + x * 2.96f) * k, (96f + y * 2.96f) * k)
        fun poly(vararg p: Pair<Float, Float>): Path = Path().apply {
            p.forEachIndexed { i, (x, y) -> val o = pt(x, y); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            close()
        }
        fun facet(path: Path, c1: Color, c2: Color, up: Boolean) {
            val r = path.getBounds()
            val (s0, e0) = if (up) Offset(r.left, r.bottom) to Offset(r.right, r.top) else Offset(r.left, r.top) to Offset(r.right, r.bottom)
            drawPath(path, Brush.linearGradient(listOf(c1, c2), start = s0, end = e0))
        }
        // tile
        drawRoundRect(Color.White, Offset(32f * k, 32f * k), Size(448f * k, 448f * k), androidx.compose.ui.geometry.CornerRadius(112f * k))
        drawRoundRect(Color(0xFFE2E8F0), Offset(32f * k, 32f * k), Size(448f * k, 448f * k), androidx.compose.ui.geometry.CornerRadius(112f * k),
            style = androidx.compose.ui.graphics.drawscope.Stroke(3f * k))
        // glyph
        facet(poly(26f to 82f, 26f to 38f, 48f to 22f, 48f to 66f), Color(0xFF1E293B), Color(0xFF0F172A), false)
        facet(poly(48f to 22f, 84f to 26f, 62f to 70f, 48f to 66f), Color(0xFF00A87D), Color(0xFF00D29E), true)
        facet(poly(48f to 66f, 62f to 70f, 88f to 48f, 76f to 86f), Color(0xFF2590FF), Color(0xFF0066FF), false)
        drawLine(Color.White.copy(alpha = 0.95f), pt(48f, 22f), pt(48f, 66f), strokeWidth = 2.2f * 2.96f * k, cap = StrokeCap.Round)
        drawPath(Path().apply {
            val a1 = pt(84f, 26f); moveTo(a1.x, a1.y)
            val c1 = pt(90f, 20f); val c2 = pt(94f, 14f); val e1 = pt(96f, 10f); cubicTo(c1.x, c1.y, c2.x, c2.y, e1.x, e1.y)
            val d1 = pt(92f, 18f); val d2 = pt(86f, 24f); val e2 = pt(82f, 27f); cubicTo(d1.x, d1.y, d2.x, d2.y, e2.x, e2.y)
            close()
        }, Color(0xFF00B88A))
        drawPath(poly(62f to 70f, 76f to 86f, 70f to 88f, 56f to 76f), Color(0xFF0052CC).copy(alpha = 0.3f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSheetContent(themeIndex: Int, darkMode: DarkMode, onTheme: (Int) -> Unit, onDark: (DarkMode) -> Unit) {
    val c = LocalPteron.current
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding()) {
        Text("Theme", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.onBar)
        Spacer(Modifier.height(14.dp))
        ThemePresets.chunked(6).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { p ->
                    val i = ThemePresets.indexOf(p)
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(p.light, p.medium)))
                            .clickable { onTheme(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (i == themeIndex) Box(Modifier.size(18.dp).clip(CircleShape).background(p.dark))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(ThemePresets[themeIndex].name, fontSize = 13.sp, color = c.onBar.copy(alpha = 0.6f))
        Spacer(Modifier.height(18.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            DarkMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = m == darkMode, onClick = { onDark(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, DarkMode.entries.size),
                ) { Text(m.name) }
            }
        }
    }
}
