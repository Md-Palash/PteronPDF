package app.pteronpdf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pteronpdf.data.AppSettings
import app.pteronpdf.theme.CUSTOM_THEME
import app.pteronpdf.theme.DarkMode
import app.pteronpdf.theme.LocalPteron
import app.pteronpdf.theme.ThemePresets
import app.pteronpdf.theme.customPreset

/**
 * Theme sheet: the ready-made presets, plus a "custom" swatch that opens a colour (hue) and richness slider.
 * The whole app re-colours live while a slider moves; the choice is only written to storage when the finger lifts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSheetContent(settings: AppSettings) {
    val c = LocalPteron.current
    val customOn = settings.themeIndex == CUSTOM_THEME
    val custom = remember(settings.customHue, settings.customSat) { customPreset(settings.customHue, settings.customSat) }
    val swatches = ThemePresets.indices.toList() + CUSTOM_THEME

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Text("Theme", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.onBar)
        Spacer(Modifier.height(14.dp))
        swatches.chunked(6).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { i ->
                    val p = if (i == CUSTOM_THEME) custom else ThemePresets[i]
                    val on = i == settings.themeIndex
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(p.light, p.medium)))
                            .then(
                                if (i == CUSTOM_THEME) Modifier.border(
                                    2.5.dp, Brush.sweepGradient((0..6).map { Color.hsv(it * 60f, 0.85f, 0.95f) }), CircleShape,
                                ) else Modifier
                            )
                            .clickable { settings.setTheme(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Box(Modifier.size(18.dp).clip(CircleShape).background(p.dark))
                        else if (i == CUSTOM_THEME) PIconView(PIcon.Palette, p.dark, Modifier.size(20.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (customOn) "Custom — choose your own colour" else settings.preset.name,
            fontSize = 13.sp, color = c.onBar.copy(alpha = 0.6f),
        )

        AnimatedVisibility(customOn) {
            Column(Modifier.padding(top = 14.dp)) {
                Text("Colour", fontSize = 13.sp, color = c.onBar.copy(alpha = 0.75f))
                GradientSlider(
                    settings.customHue, 0f..360f,
                    Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 0.85f, 0.95f) }),
                    onChange = { settings.previewCustomTheme(it, settings.customSat) }, onDone = settings::saveCustomTheme,
                )
                Spacer(Modifier.height(6.dp))
                Text("Richness", fontSize = 13.sp, color = c.onBar.copy(alpha = 0.75f))
                GradientSlider(
                    settings.customSat, 0f..1f,
                    Brush.horizontalGradient(listOf(Color.hsv(settings.customHue, 0.2f, 0.9f), Color.hsv(settings.customHue, 1f, 0.7f))),
                    onChange = { settings.previewCustomTheme(settings.customHue, it) }, onDone = settings::saveCustomTheme,
                )
                Spacer(Modifier.height(8.dp))
                // the three shades the app is built from: cards, in-between, and the darker "selected" shade
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(custom.light, custom.medium, custom.dark).forEach {
                        Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(12.dp)).background(it).border(1.dp, c.divider, RoundedCornerShape(12.dp)))
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            DarkMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = m == settings.darkMode, onClick = { settings.setDark(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, DarkMode.entries.size),
                ) { Text(m.name) }
            }
        }
    }
}

/** A slider whose track is painted with [brush] (the colours it chooses between). */
@Composable
private fun GradientSlider(
    value: Float, range: ClosedFloatingPointRange<Float>, brush: Brush, onChange: (Float) -> Unit, onDone: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush))
        Slider(
            value.coerceIn(range), onChange, valueRange = range, onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
            ),
        )
    }
}
