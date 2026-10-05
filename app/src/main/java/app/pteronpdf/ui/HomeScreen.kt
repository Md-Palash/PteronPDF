package app.pteronpdf.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pteronpdf.data.AppSettings
import app.pteronpdf.data.Prefs
import app.pteronpdf.data.RecentDoc
import app.pteronpdf.theme.DarkMode
import app.pteronpdf.theme.LocalPteron

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(prefs: Prefs, settings: AppSettings, onOpen: (Uri) -> Unit, onSettings: () -> Unit) {
    val c = LocalPteron.current
    val ctx = LocalContext.current
    val recents by remember { mutableStateOf(prefs.recents()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep access across restarts so "Recent" keeps working. Write may be refused by read-only providers.
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.onFailure {
                runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            onOpen(uri)
        }
    }

    Box(Modifier.fillMaxSize().background(c.canvas).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                PillCard {
                    // shows what you will switch TO: a moon while the app is light, a sun while it is dark
                    BarIconButton(
                        if (c.isDark) PIcon.Sun else PIcon.Moon,
                        if (c.isDark) "Switch to light mode" else "Switch to dark mode",
                        { settings.setDark(if (c.isDark) DarkMode.Light else DarkMode.Dark) },
                    )
                    BarIconButton(PIcon.Settings, "Settings", onSettings)
                }
            }
            if (recents.isEmpty()) {
                Spacer(Modifier.weight(1f))
                LogoMark(c.accent, 96.dp)
                Spacer(Modifier.height(28.dp))
                Text("PteronPDF", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.onBar)
                Spacer(Modifier.height(10.dp))
                Text("Every page, beautifully handled.", fontSize = 15.sp, color = c.onBar.copy(alpha = 0.6f))
                Spacer(Modifier.weight(1f))
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    LogoMark(c.accent, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("PteronPDF", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.onBar)
                }
                Text("Recent", Modifier.fillMaxWidth().padding(bottom = 8.dp), fontSize = 13.sp, color = c.onBar.copy(alpha = 0.6f))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recents, key = { it.uri.toString() }) { r -> RecentRow(r, onClick = { onOpen(r.uri) }) }
                }
            }
            Button(
                onClick = { picker.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth().height(54.dp).padding(vertical = 0.dp),
                shape = RoundedCornerShape(27.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
            ) {
                PIconView(PIcon.Open, c.onAccent, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp)); Text("Open PDF", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RecentRow(r: RecentDoc, onClick: () -> Unit) {
    val c = LocalPteron.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.bar).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.onBar, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text("Page ${r.lastPage + 1} of ${r.pages}", fontSize = 12.sp, color = c.onBar.copy(alpha = 0.6f))
        }
    }
}
