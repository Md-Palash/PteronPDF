package app.pteronpdf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pteronpdf.data.AppSettings
import app.pteronpdf.theme.AppFont
import app.pteronpdf.theme.Fonts
import app.pteronpdf.theme.LocalPteron
import app.pteronpdf.theme.ThemePresets

private enum class Section(val title: String) { Appearance("Appearance"), Reading("Reading"), About("About") }

/** Full-screen settings. Main page = stacked top/middle/bottom cards; each opens a sub-page with one rounded options card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, onClose: () -> Unit) {
    val c = LocalPteron.current
    val ctx = LocalContext.current
    var section by remember { mutableStateOf<Section?>(null) }
    var showTheme by remember { mutableStateOf(false) }
    var showFont by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }

    fun back() { if (section != null) section = null else onClose() }
    BackHandler(onBack = ::back)

    Column(
        Modifier.fillMaxSize().background(c.canvas)
            .pointerInput(Unit) { }   // swallow touches so nothing behind the settings screen reacts
    ) {
        FixedTopBar {
            BarIconButton(PIcon.Back, "Back", ::back)
            Text(section?.title ?: "Settings", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.onBar, modifier = Modifier.padding(start = 6.dp))
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 18.dp).navigationBarsPadding()
        ) {
            when (section) {
                null -> CardStack {
                    CategoryCard(PIcon.Palette, "Appearance", "Theme and fonts", CardPos.Top) { section = Section.Appearance }
                    CategoryCard(PIcon.Book, "Reading", "Reading mode, screen, position", CardPos.Middle) { section = Section.Reading }
                    CategoryCard(PIcon.Info, "About", "Version and privacy policy", CardPos.Bottom) { section = Section.About }
                }

                Section.Appearance -> OptionsCard {
                    val p = ThemePresets[settings.themeIndex]
                    OptionRow("Theme", "${p.name} · ${settings.darkMode.name}", onClick = { showTheme = true }) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Brush.linearGradient(listOf(p.light, p.medium, p.dark))))
                    }
                    OptionDivider()
                    OptionRow("Font", settings.font.label, onClick = { showFont = true }) {
                        Text("Aa", fontSize = 20.sp, fontFamily = Fonts.family(ctx, settings.font), color = c.onBar)
                    }
                }

                Section.Reading -> OptionsCard {
                    ToggleRow("Reading mode", "Hide the top bar until you tap the page", settings.readingMode, settings::setReadingMode)
                    OptionDivider()
                    ToggleRow("Keep screen awake", "Don't let the screen turn off while reading", settings.keepAwake, settings::setKeepAwake)
                    OptionDivider()
                    ToggleRow("Remember reading position", "Reopen each PDF on the page you left", settings.rememberPosition, settings::setRememberPosition)
                }

                Section.About -> OptionsCard {
                    val version = remember {
                        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "1.0"
                    }
                    OptionRow("App version") { Text(version, fontSize = 15.sp, color = c.onBar.copy(alpha = 0.7f)) }
                    OptionDivider()
                    OptionRow("Privacy policy", onClick = { showPrivacy = true }) {
                        PIconView(PIcon.Chevron, c.onBar.copy(alpha = 0.5f), Modifier.size(20.dp))
                    }
                }
            }
        }
    }

    if (showTheme) ModalBottomSheet(onDismissRequest = { showTheme = false }, containerColor = c.bar) {
        ThemeSheetContent(settings.themeIndex, settings.darkMode, settings::setTheme, settings::setDark)
    }
    if (showFont) ModalBottomSheet(onDismissRequest = { showFont = false }, containerColor = c.bar) {
        FontSheetContent(settings.font) { settings.setFont(it); showFont = false }
    }
    if (showPrivacy) AlertDialog(
        onDismissRequest = { showPrivacy = false },
        title = { Text("Privacy policy") },
        text = {
            Text(
                "PteronPDF works entirely on your device.\n\n" +
                    "• Your PDFs are opened through the system file picker and are never uploaded anywhere.\n" +
                    "• The app has no ads and no analytics, and it does not ask for internet access.\n" +
                    "• Markup you save is written into your own PDF file.\n" +
                    "• Your preferences (theme, font, reading options, recent files) are stored only on this device.",
                fontSize = 14.sp,
            )
        },
        confirmButton = { TextButton({ showPrivacy = false }) { Text("Close") } },
    )
}

/** Each font is shown in its own typeface; the selected one takes the theme's darker shade. */
@Composable
private fun FontSheetContent(current: AppFont, onPick: (AppFont) -> Unit) {
    val c = LocalPteron.current
    val ctx = LocalContext.current
    val fonts = remember { Fonts.available(ctx) }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Text("Font", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.onBar)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fonts.forEach { f ->
                val on = f == current
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (on) c.accent else c.chip).clickable { onPick(f) }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(f.label, Modifier.weight(1f), fontSize = 17.sp, fontFamily = Fonts.family(ctx, f), color = if (on) c.onAccent else c.onBar)
                    if (on) PIconView(PIcon.Check, c.onAccent, Modifier.size(20.dp))
                }
            }
        }
    }
}
