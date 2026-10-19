package app.pteronpdf.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/**
 * Pictures -> one PDF. Pick pictures, arrange them on a grid (page numbers shown), choose a page style, create.
 * [onOpen] opens the finished file in the reader.
 */
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
    fun addPictures() = pick.launch(arrayOf("image/*"))

    Column(Modifier.fillMaxSize().background(c.canvas).pointerInput(Unit) { }) {
        FixedTopBar {
            BarIconButton(PIcon.Back, "Back", onClose, enabled = !busy)
            Text("Images to PDF", Modifier.padding(start = 6.dp).weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.onBar)
            AnimatedVisibility(images.isNotEmpty()) {
                Text(
                    if (images.size == 1) "1 picture" else "${images.size} pictures",
                    Modifier.padding(end = 14.dp), fontSize = 13.sp, color = c.onBar.copy(alpha = 0.6f),
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (images.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.staggerIn(0).clip(RoundedCornerShape(30.dp)).background(c.bar).padding(horizontal = 26.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) { ImageToPdfGlyph(c.accent, scale = 2.1f) }
                    Spacer(Modifier.height(24.dp))
                    Text("Pictures in, PDF out", Modifier.staggerIn(1), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.onBar)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Add photos or scans, arrange the pages, and save them together as one PDF.",
                        Modifier.staggerIn(2), fontSize = 14.sp, color = c.onBar.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(26.dp))
                    Row(
                        Modifier.staggerIn(3).height(52.dp).pressable(RoundedCornerShape(26.dp), c.accent) { addPictures() }.padding(horizontal = 28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PIconView(PIcon.Plus, c.onAccent, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text("Add pictures", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
                    }
                }
            } else {
                LazyVerticalGrid(
                    GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(images, key = { _, u -> u.toString() }) { i, uri ->
                        PageTile(
                            uri, number = i + 1, enabled = !busy,
                            canLeft = i > 0, canRight = i < images.lastIndex,
                            onLeft = { images.add(i - 1, images.removeAt(i)) },
                            onRight = { images.add(i + 1, images.removeAt(i)) },
                            onRemove = { images.removeAt(i) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    item(key = "add") {
                        Box(
                            Modifier.animateItem().fillMaxWidth().aspectRatio(0.75f)
                                .border(1.5.dp, c.divider, RoundedCornerShape(16.dp))
                                .pressable(RoundedCornerShape(16.dp), c.bar.copy(alpha = 0.5f), enabled = !busy) { addPictures() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PIconView(PIcon.Plus, c.onBar.copy(alpha = 0.8f), Modifier.size(26.dp))
                                Spacer(Modifier.height(4.dp))
                                Text("Add", fontSize = 12.sp, color = c.onBar.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }
        }

        // page style + create
        AnimatedVisibility(images.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.bar)
                    .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp).animateContentSize(tween(200)),
            ) {
                Text("Page style", fontSize = 13.sp, color = c.onBar.copy(alpha = 0.6f))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StyleCard(ImagePdf.PageMode.Match, "Match image", "Page fits each picture", mode == ImagePdf.PageMode.Match, Modifier.weight(1f)) { if (!busy) mode = ImagePdf.PageMode.Match }
                    StyleCard(ImagePdf.PageMode.A4, "A4 page", "Centred with margins", mode == ImagePdf.PageMode.A4, Modifier.weight(1f)) { if (!busy) mode = ImagePdf.PageMode.A4 }
                }
                Spacer(Modifier.height(14.dp))
                val ready = !busy
                Box(
                    Modifier.fillMaxWidth().height(54.dp).pressable(RoundedCornerShape(27.dp), if (ready) c.accent else c.accent.copy(alpha = 0.55f), ready) {
                        create.launch("Images " + SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date()) + ".pdf")
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Creating page $done of ${images.size}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
                            Spacer(Modifier.height(7.dp))
                            LinearProgressIndicator(
                                progress = { done.toFloat() / images.size.coerceAtLeast(1) },
                                Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = c.onAccent, trackColor = c.onAccent.copy(alpha = 0.25f),
                            )
                        }
                    } else Row(verticalAlignment = Alignment.CenterVertically) {
                        ImageToPdfGlyph(c.onAccent, scale = 0.9f); Spacer(Modifier.width(10.dp))
                        Text("Create PDF", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
                    }
                }
            }
        }
    }

    finished?.let { out ->
        AlertDialog(
            onDismissRequest = { finished = null; images.clear() },
            containerColor = c.bar,
            title = { Text("PDF created", color = c.onBar) },
            text = { Text(note ?: "Your pictures are saved as one PDF.", color = c.onBar.copy(alpha = 0.8f)) },
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

/** One picture on the grid: page number, remove, and move-earlier / move-later buttons. */
@Composable
private fun PageTile(
    uri: Uri, number: Int, enabled: Boolean, canLeft: Boolean, canRight: Boolean,
    onLeft: () -> Unit, onRight: () -> Unit, onRemove: () -> Unit, modifier: Modifier,
) {
    val c = LocalPteron.current
    val ctx = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { ImagePdf.thumbnail(ctx, uri, 360) } }
    Box(modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(16.dp)).background(c.bar)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(
            Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp).clip(CircleShape).background(c.accent),
            contentAlignment = Alignment.Center,
        ) { Text("$number", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.onAccent) }
        TileButton(PIcon.Close, "Remove", enabled, Modifier.align(Alignment.TopEnd).padding(6.dp), onRemove)
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TileButton(PIcon.Back, "Move earlier", enabled && canLeft, Modifier, onLeft)
            TileButton(PIcon.Chevron, "Move later", enabled && canRight, Modifier, onRight)
        }
    }
}

@Composable
private fun TileButton(icon: PIcon, desc: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(28.dp).pressable(CircleShape, Color.Black.copy(alpha = if (enabled) 0.55f else 0.2f), enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PIconView(icon, Color.White.copy(alpha = if (enabled) 1f else 0.4f), Modifier.size(14.dp), desc) }
}

/** A page-style choice drawn as a little page preview, with the selected one outlined in the accent colour. */
@Composable
private fun StyleCard(mode: ImagePdf.PageMode, title: String, subtitle: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalPteron.current
    val border by animateColorAsState(if (selected) c.accent else c.divider, tween(180), label = "styleBorder")
    val bg by animateColorAsState(if (selected) c.accent.copy(alpha = 0.14f) else c.chip, tween(180), label = "styleBg")
    Row(
        modifier.pressable(RoundedCornerShape(18.dp), bg, pressedScale = 0.97f, onClick = onClick)
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(18.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // preview: the picture (accent) sitting on the page (white)
        Box(Modifier.size(width = 30.dp, height = 40.dp).clip(RoundedCornerShape(4.dp)).background(Color.White).border(1.dp, c.divider, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            val inset = if (mode == ImagePdf.PageMode.A4) 5.dp else 0.dp
            Box(Modifier.fillMaxSize().padding(horizontal = inset, vertical = if (mode == ImagePdf.PageMode.A4) 9.dp else 0.dp).background(c.accent.copy(alpha = 0.75f)))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.onBar, maxLines = 1)
            Text(subtitle, fontSize = 11.sp, color = c.onBar.copy(alpha = 0.6f), maxLines = 2, lineHeight = 13.sp)
        }
    }
}
