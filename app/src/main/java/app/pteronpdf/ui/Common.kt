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

@Composable
fun LogoMark(accent: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        drawRoundRect(
            Brush.linearGradient(listOf(lerp(accent, Color.White, 0.12f), lerp(accent, Color.Black, 0.18f))),
            size = Size(s, s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.26f),
        )
        val pw = s * 0.40f; val ph = s * 0.50f
        val px = s / 2 - pw / 2; val py = s / 2 - ph / 2 - s * 0.02f; val fold = s * 0.12f
        val page = Path().apply {
            moveTo(px, py); lineTo(px + pw - fold, py); lineTo(px + pw, py + fold)
            lineTo(px + pw, py + ph); lineTo(px, py + ph); close()
        }
        drawPath(page, Color.White.copy(alpha = 0.93f))
        val f = Path().apply { moveTo(px + pw - fold, py); lineTo(px + pw, py + fold); lineTo(px + pw - fold, py + fold); close() }
        drawPath(f, lerp(accent, Color.Black, 0.22f))
        val ink = lerp(accent, Color.Black, 0.4f)
        for (i in 0..2) {
            val y = py + ph * 0.44f + i * ph * 0.17f
            val xe = px + pw * (if (i == 2) 0.60f else 0.82f)
            drawLine(ink, Offset(px + pw * 0.18f, y), Offset(xe, y), strokeWidth = (s * 0.035f).coerceAtLeast(1.4f), cap = StrokeCap.Round)
        }
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
