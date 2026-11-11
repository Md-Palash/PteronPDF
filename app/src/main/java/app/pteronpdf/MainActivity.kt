package app.pteronpdf

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.pteronpdf.ui.TabItem
import app.pteronpdf.ui.TabsBar
import app.pteronpdf.ui.TabsBarHeight
import app.pteronpdf.ui.TopBarHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.pteronpdf.data.AppSettings
import app.pteronpdf.data.Prefs
import app.pteronpdf.pdf.ReaderViewModel
import app.pteronpdf.theme.Fonts
import app.pteronpdf.theme.PteronTheme
import app.pteronpdf.ui.HomeScreen
import app.pteronpdf.ui.ImagesToPdfScreen
import app.pteronpdf.ui.ReaderScreen
import app.pteronpdf.ui.SettingsScreen

/** Night-light style tint: at full strength the screen is multiplied by this, which cuts blue most, green a little, red not at all. */
private val WarmTint = Color(0xFFFFAA5A)

/** A PDF that is open. [seq] makes every opening its own reader (and its own memory), even for the same file. */
private data class Opened(val uri: Uri, val seq: Int)

private const val MAX_OPEN = 8

class MainActivity : ComponentActivity() {
    /** The open PDFs, in the order of their tabs. Empty = the home screen. */
    private val docs = mutableStateListOf<Opened>()
    private var active by mutableIntStateOf(0)
    private var askClose by mutableStateOf<Opened?>(null)
    private var seq = 0
    /** Bumps every time the app comes back to the front, so the home screen re-checks file access after the system settings page. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onResume() { super.onResume(); resumeTick++ }

    /** One reader per open PDF, kept in the activity so that switching tabs doesn't lose its markup or undo history. */
    private fun vmFor(d: Opened): ReaderViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(application as Application, d.uri) as T
    })["${d.uri}#${d.seq}", ReaderViewModel::class.java]

    /** Opens [u] in a new tab and shows it; a PDF that is already open is just brought to the front. */
    private fun openPdf(u: Uri) {
        val i = docs.indexOfFirst { it.uri == u }
        if (i >= 0) { active = i; return }
        if (docs.size >= MAX_OPEN) { docs.getOrNull(active)?.let { vmFor(it).message = "Close a PDF first ($MAX_OPEN is the most at once)" }; return }
        seq++; docs.add(Opened(u, seq)); active = docs.lastIndex
    }

    private val pickAnother = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .onFailure { runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            openPdf(uri)
        }
    }

    /** Takes the tab out; its reader is freed once its screen has finished leaving (see the reader's DisposableEffect). */
    private fun closeDoc(d: Opened) {
        val i = docs.indexOf(d)
        if (i < 0) return
        vmFor(d).touchRecent()
        val wasActive = i == active
        docs.removeAt(i)
        active = when {
            docs.isEmpty() -> 0
            wasActive -> i.coerceAtMost(docs.lastIndex)
            i < active -> active - 1
            else -> active
        }
    }

    /** Close from a pill: asks first when the PDF has unsaved marks. */
    private fun requestClose(d: Opened) { if (vmFor(d).dirty) askClose = d else closeDoc(d) }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        seq = savedInstanceState?.getInt("seq") ?: 0
        val uris = savedInstanceState?.getStringArray("uris")
        val seqs = savedInstanceState?.getIntArray("seqs")
        if (uris != null && seqs != null && uris.size == seqs.size) {
            uris.indices.forEach { docs.add(Opened(Uri.parse(uris[it]), seqs[it])) }
            active = (savedInstanceState.getInt("active")).coerceIn(0, (docs.size - 1).coerceAtLeast(0))
        } else intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let(::openPdf)
        val prefs = Prefs(this)
        val settings = AppSettings(prefs)
        setContent {
            val ctx = LocalContext.current
            val font = remember(settings.font) { Fonts.family(ctx, settings.font) }
            PteronTheme(settings.preset, settings.darkMode, font) {
                var showSettings by remember { mutableStateOf(false) }
                var showImages by remember { mutableStateOf(false) }
                var barShown by remember { mutableStateOf(true) }      // the reader's top bar is showing (reading mode hides it, and the tabs with it)

                // Eye protection: a warm Multiply layer over the finished frame (black stays black, white turns warm).
                // The strength glides when switched on/off or dragged; it is read at draw time, so it costs no recomposition,
                // and the layer is dropped completely when off. No off-screen buffer is needed: every screen paints an opaque background.
                val strength by animateFloatAsState(if (settings.eyeProtection) settings.eyeStrength else 0f, tween(300), label = "eye")
                val tintOn by remember { derivedStateOf { strength > 0.002f } }
                val warm = if (tintOn) Modifier.drawWithContent {
                    drawContent()
                    drawRect(lerp(Color.White, WarmTint, strength), blendMode = BlendMode.Multiply)
                } else Modifier

                val current = docs.getOrNull(active)
                val tabsH = if (docs.size > 1) TabsBarHeight else 0.dp

                Box(Modifier.fillMaxSize().then(warm)) {
                    AnimatedContent(
                        targetState = current,
                        transitionSpec = {
                            val opening = targetState != null && initialState == null
                            val closing = targetState == null && initialState != null
                            val slide = tween<IntOffset>(340, easing = FastOutSlowInEasing)
                            val t = when {
                                // opening: the reader glides in from the right over the home screen
                                opening -> (slideInHorizontally(slide) { it / 4 } + fadeIn(tween(280))) togetherWith
                                    (slideOutHorizontally(slide) { -it / 10 } + fadeOut(tween(240)))
                                // closing: the reader slides away to the right, home settles back in
                                closing -> (slideInHorizontally(slide) { -it / 10 } + fadeIn(tween(280))) togetherWith
                                    (slideOutHorizontally(slide) { it / 4 } + fadeOut(tween(240)))
                                else -> {
                                    // changing between PDFs: the new one comes in from the side its tab is on, a short soft slide
                                    val dir = if (docs.indexOf(targetState) >= docs.indexOf(initialState)) 1 else -1
                                    val sl = tween<IntOffset>(300, easing = FastOutSlowInEasing)
                                    (slideInHorizontally(sl) { dir * it / 6 } + fadeIn(tween(260))) togetherWith
                                        (slideOutHorizontally(sl) { -dir * it / 6 } + fadeOut(tween(180)))
                                }
                            }
                            t.targetContentZIndex = if (closing) -1f else 1f
                            t
                        },
                        modifier = Modifier.fillMaxSize(), label = "screen",
                    ) { doc ->
                        if (doc == null) {
                            HomeScreen(
                                prefs = prefs, settings = settings, resumeTick = resumeTick,
                                onOpen = ::openPdf, onSettings = { showSettings = true }, onImages = { showImages = true },
                            )
                        } else {
                            val vm = remember(doc) { vmFor(doc) }
                            // When this reader leaves the screen: if its PDF was closed, its document and page images are freed right
                            // away; if only another tab was chosen, just the page images go (the document and marks stay).
                            // (Not on rotation: the same reader carries on.)
                            DisposableEffect(vm) {
                                onDispose { if (!isChangingConfigurations) { if (doc in docs) vm.park() else vm.release() } }
                            }
                            ReaderScreen(
                                vm = vm, settings = settings,
                                onClose = { closeDoc(doc) },
                                onSettings = { showSettings = true },
                                tabsH = tabsH, onChrome = { barShown = it },
                                onOpenAnother = { pickAnother.launch(arrayOf("application/pdf")) },
                            )
                        }
                    }
                    // The open PDFs as pills under the reader's top bar; nothing at all while only one PDF is open.
                    if (docs.size > 1) {
                        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                        TabsBar(
                            tabs = docs.map { d -> vmFor(d).let { TabItem(d.seq, it.name, it.dirty) } },
                            activeId = current?.seq ?: -1, shown = barShown && !showSettings && !showImages,
                            top = statusTop + TopBarHeight,
                            onSelect = { id -> docs.indexOfFirst { it.seq == id }.takeIf { it >= 0 }?.let { active = it } },
                            onClose = { id -> docs.firstOrNull { it.seq == id }?.let(::requestClose) },
                        )
                    }
                    // Opens above whichever screen is showing, so the reader's state is untouched underneath.
                    AnimatedVisibility(
                        showSettings,
                        enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)),
                        exit = slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200)),
                    ) {
                        SettingsScreen(settings, onClose = { showSettings = false })
                    }
                    // Images -> PDF: also an overlay, so the home screen underneath keeps its state.
                    AnimatedVisibility(
                        showImages,
                        enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(200)),
                        exit = slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(200)),
                    ) {
                        ImagesToPdfScreen(
                            onClose = { showImages = false },
                            onOpen = { u -> showImages = false; openPdf(u) },
                        )
                    }
                }

                askClose?.let { d ->
                    val vm = vmFor(d)
                    AlertDialog(
                        onDismissRequest = { askClose = null },
                        title = { Text("Save your markup?") },
                        text = { Text("\u201C${vm.name}\u201D has unsaved changes.") },
                        confirmButton = {
                            TextButton({
                                askClose = null
                                // closed only once the file is written; if that fails the PDF stays open, on screen, so the problem is visible
                                vm.saveThen { ok -> if (ok) closeDoc(d) else docs.indexOf(d).takeIf { it >= 0 }?.let { active = it } }
                            }) { Text("Save") }
                        },
                        dismissButton = {
                            androidx.compose.foundation.layout.Row {
                                TextButton({ askClose = null }) { Text("Cancel") }
                                TextButton({ askClose = null; closeDoc(d) }) { Text("Discard") }
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let(::openPdf)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("seq", seq)
        outState.putStringArray("uris", docs.map { it.uri.toString() }.toTypedArray())
        outState.putIntArray("seqs", docs.map { it.seq }.toIntArray())
        outState.putInt("active", active)
    }
}
