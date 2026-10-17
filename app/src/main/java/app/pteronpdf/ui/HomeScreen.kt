package app.pteronpdf.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import app.pteronpdf.data.AppSettings
import app.pteronpdf.data.DevicePdf
import app.pteronpdf.data.Prefs
import app.pteronpdf.data.Storage
import app.pteronpdf.data.RecentDoc
import app.pteronpdf.theme.DarkMode
import app.pteronpdf.theme.LocalPteron

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    prefs: Prefs, settings: AppSettings, resumeTick: Int,
    onOpen: (Uri) -> Unit, onSettings: () -> Unit, onImages: () -> Unit,
) {
    val c = LocalPteron.current
    val ctx = LocalContext.current
    val recents by remember { mutableStateOf(prefs.recents()) }
    // rows present at launch rise in one after another; rows that scroll in later simply appear
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(700); settled = true }

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

    // ── access to the PDFs on the phone ──
    var hasAccess by remember { mutableStateOf(Storage.granted(ctx)) }
    LaunchedEffect(resumeTick) { hasAccess = Storage.granted(ctx) }     // coming back from the system settings page re-checks
    var askAccess by remember { mutableStateOf(!hasAccess && !prefs.storagePrompted) }
    val readPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasAccess = Storage.granted(ctx) }
    fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+ has a dedicated "all files access" page; the user switches the app on there
            runCatching { ctx.startActivity(Storage.allFilesIntent(ctx)) }
                .onFailure { runCatching { ctx.startActivity(Storage.allFilesFallbackIntent()) } }
        } else readPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val device by produceState(emptyList<DevicePdf>(), hasAccess, resumeTick) {
        value = if (hasAccess) withContext(Dispatchers.IO) { Storage.listPdfs(ctx) } else emptyList()
    }
    var showDevice by remember { mutableStateOf(recents.isEmpty() && hasAccess) }

    if (askAccess) AlertDialog(
        onDismissRequest = { askAccess = false; prefs.storagePrompted = true },
        containerColor = c.bar,
        title = { Text("Find your PDFs", color = c.onBar) },
        text = { Text("PteronPDF can list the PDFs stored on this phone so you can open them straight from the app. You can also skip this and use \"Open PDF\" to pick files yourself.", color = c.onBar.copy(alpha = 0.8f)) },
        confirmButton = { TextButton({ askAccess = false; prefs.storagePrompted = true; showDevice = true; requestAccess() }) { Text("Allow", color = c.accent) } },
        dismissButton = { TextButton({ askAccess = false; prefs.storagePrompted = true }) { Text("Not now", color = c.onBar) } },
    )

    Box(Modifier.fillMaxSize().background(c.canvas).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                PillCard { BarIconButton(PIcon.Image, "Images to PDF", onImages) }
                PillCard {
                    // shows what you will switch TO: a moon while the app is light, a sun while it is dark
                    BarIconButton(
                        if (c.isDark) PIcon.Sun else PIcon.Moon,
                        if (c.isDark) "Switch to light mode" else "Switch to dark mode",
                        { settings.setDark(if (c.isDark) DarkMode.Light else DarkMode.Dark) },
                        animateIcon = true,
                    )
                    BarIconButton(PIcon.Settings, "Settings", onSettings)
                }
            }
            if (recents.isEmpty() && !showDevice && !hasAccess) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.staggerIn(0)) { LogoMark(c.accent, 96.dp) }
                Spacer(Modifier.height(28.dp))
                Text("PteronPDF", Modifier.staggerIn(1), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.onBar)
                Spacer(Modifier.height(10.dp))
                Text("Every page, beautifully handled.", Modifier.staggerIn(2), fontSize = 15.sp, color = c.onBar.copy(alpha = 0.6f))
                Spacer(Modifier.height(18.dp))
                TextButton({ showDevice = true; askAccess = true }, Modifier.staggerIn(3)) { Text("Find PDFs on this phone", color = c.accent) }
                Spacer(Modifier.weight(1f))
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LogoMark(c.accent, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("PteronPDF", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.onBar)
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("Recent", !showDevice) { showDevice = false }
                    ChoiceChip("On this phone", showDevice) { showDevice = true }
                }
                if (!showDevice) {
                    if (recents.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("Nothing opened yet", fontSize = 14.sp, color = c.onBar.copy(alpha = 0.55f))
                        }
                    } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(recents, key = { _, r -> r.uri.toString() }) { i, r ->
                            RecentRow(r, Modifier.animateItem().staggerIn(i, enabled = !settled && i < 8), onClick = { onOpen(r.uri) })
                        }
                    }
                } else if (!hasAccess) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text("Allow access to see the PDFs stored on this phone and open them right here.", fontSize = 14.sp, color = c.onBar.copy(alpha = 0.7f))
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { prefs.storagePrompted = true; requestAccess() },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                        ) { Text("Allow access", fontWeight = FontWeight.SemiBold) }
                    }
                } else if (device.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No PDFs found on this phone", fontSize = 14.sp, color = c.onBar.copy(alpha = 0.55f))
                    }
                } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(device, key = { it.uri.toString() }) { d ->
                        DeviceRow(d, Modifier.animateItem(), onClick = { onOpen(d.uri) })
                    }
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
private fun DeviceRow(d: DevicePdf, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalPteron.current
    Row(
        modifier.fillMaxWidth().pressable(RoundedCornerShape(18.dp), c.bar, pressedScale = 0.97f, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.onBar, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            val date = remember(d.modifiedSec) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(d.modifiedSec * 1000)) }
            Text("${sizeText(d.size)} · $date", fontSize = 12.sp, color = c.onBar.copy(alpha = 0.6f))
        }
    }
}

private fun sizeText(b: Long): String = when {
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    b >= 1L shl 10 -> "${b shr 10} KB"
    else -> "$b B"
}

@Composable
private fun RecentRow(r: RecentDoc, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalPteron.current
    Row(
        modifier.fillMaxWidth().pressable(RoundedCornerShape(18.dp), c.bar, pressedScale = 0.97f, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.onBar, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text("Page ${r.lastPage + 1} of ${r.pages}", fontSize = 12.sp, color = c.onBar.copy(alpha = 0.6f))
        }
    }
}
