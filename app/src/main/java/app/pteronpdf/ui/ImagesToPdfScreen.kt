package app.pteronpdf.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pteronpdf.data.ImagePdf
import app.pteronpdf.theme.LocalPteron
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pick pictures, put them in order, choose a page style, save one PDF. [onOpen] opens the finished file in the reader. */
@Composable
fun ImagesToPdfScreen(onClose: () -> Unit, onOpen: (Uri) -> Unit) {
    val c = LocalPteron.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val images = remember { mutableStateListOf<Uri>() }
    var mode by remember { mutableStateOf(ImagePdf.PageMode.Match) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf<Uri?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = !busy, onBack = onClose)

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { list ->
        list.forEach { if (it !in images) images += it }
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { target ->
        if (target != null) {
            busy = true; done = 0
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) { ImagePdf.write(ctx, images.toList(), mode, target) { done = it } }
                }
                busy = false
                result.onSuccess { skipped ->
                    finished = target
                    note = if (skipped > 0) "$skipped picture(s) could not be read and were left out." else null
                }.onFailure { note = "Couldn't create the PDF: ${it.message}" }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.canvas).pointerInput(Unit) { }) {
        FixedTopBar {
            BarIconButton(PIcon.Back, "Back", onClose, enabled = !busy)
            Text("Images to PDF", Modifier.padding(start = 6.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.onBar)
        }

        if (images.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(c.bar), contentAlignment = Alignment.Center) {
                    PIconView(PIcon.Image, c.onBar, Modifier.size(38.dp))
                }
                Spacer(Modifier.height(20.dp))
                Text("Make a PDF from pictures", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.onBar)
                Spacer(Modifier.height(6.dp))
                Text("Add photos or scans, put them in order, and save them as one PDF.", fontSize = 14.sp, color = c.onBar.copy(alpha = 0.6f))
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(images, key = { _, u -> u.toString() }) { i, uri ->
                    Row(
                        Modifier.fillMaxWidth().animateItem().clip(RoundedCornerShape(18.dp)).background(c.bar).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumb(uri)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Page ${i + 1}", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.onBar)
                        }
                        BarIconButton(PIcon.Up, "Move up", { images.add(i - 1, images.removeAt(i)) }, enabled = i > 0 && !busy)
                        BarIconButton(PIcon.Down, "Move down", { images.add(i + 1, images.removeAt(i)) }, enabled = i < images.lastIndex && !busy)
                        BarIconButton(PIcon.Close, "Remove", { images.removeAt(i) }, enabled = !busy)
                    }
                }
            }
        }

        // page style + actions
        Column(Modifier.fillMaxWidth().background(c.bar).navigationBarsPadding().padding(16.dp).animateContentSize()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Pages", fontSize = 13.sp, color = c.onBar.copy(alpha = 0.6f))
                ImagePdf.PageMode.entries.forEach { m -> ChoiceChip(m.label, mode == m) { if (!busy) mode = m } }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.weight(1f).height(52.dp)
                        .pressable(RoundedCornerShape(26.dp), c.chip, enabled = !busy) { pick.launch(arrayOf("image/*")) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    PIconView(PIcon.Image, c.onBar, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text("Add pictures", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onBar)
                }
                val ready = images.isNotEmpty() && !busy
                Row(
                    Modifier.weight(1f).height(52.dp).pressable(RoundedCornerShape(26.dp), if (ready) c.accent else c.accent.copy(alpha = 0.35f), ready) {
                        create.launch("Images " + SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date()) + ".pdf")
                    },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.onAccent)
                        Spacer(Modifier.width(10.dp))
                        Text("$done / ${images.size}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
                    } else Text("Create PDF", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
                }
            }
        }
    }

    finished?.let { out ->
        AlertDialog(
            onDismissRequest = { finished = null; images.clear() },
            containerColor = c.bar,
            title = { Text("PDF created", color = c.onBar) },
            text = { Text((note ?: "Your pictures are saved as one PDF."), color = c.onBar.copy(alpha = 0.8f)) },
            confirmButton = { TextButton({ finished = null; images.clear(); onOpen(out) }) { Text("Open", color = c.accent) } },
            dismissButton = { TextButton({ finished = null; images.clear() }) { Text("Done", color = c.onBar) } },
        )
    }
    if (finished == null) note?.let { msg ->
        AlertDialog(
            onDismissRequest = { note = null }, containerColor = c.bar,
            text = { Text(msg, color = c.onBar) },
            confirmButton = { TextButton({ note = null }) { Text("OK", color = c.accent) } },
        )
    }
}

@Composable
private fun Thumb(uri: Uri) {
    val c = LocalPteron.current
    val ctx = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { ImagePdf.thumbnail(ctx, uri, 220) } }
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(c.chip), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
