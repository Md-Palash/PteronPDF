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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pteronpdf.data.AppSettings
import app.pteronpdf.data.Prefs
import app.pteronpdf.pdf.ReaderViewModel
import app.pteronpdf.theme.Fonts
import app.pteronpdf.theme.PteronTheme
import app.pteronpdf.ui.HomeScreen
import app.pteronpdf.ui.ReaderScreen
import app.pteronpdf.ui.SettingsScreen

/** Night-light style tint: at full strength the screen is multiplied by this, which cuts blue most, green a little, red not at all. */
private val WarmTint = Color(0xFFFFAA5A)

/** A PDF that is open. [seq] makes every opening its own reader (and its own memory), even for the same file. */
private data class Opened(val uri: Uri, val seq: Int)

class MainActivity : ComponentActivity() {
    private var opened by mutableStateOf<Opened?>(null)
    private var seq = 0

    private fun openPdf(u: Uri) { seq++; opened = Opened(u, seq) }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        seq = savedInstanceState?.getInt("seq") ?: 0
        val restored = savedInstanceState?.getString("uri")
        if (restored != null) opened = Opened(Uri.parse(restored), seq)
        else intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let(::openPdf)
        val prefs = Prefs(this)
        val settings = AppSettings(prefs)
        setContent {
            val ctx = LocalContext.current
            val font = remember(settings.font) { Fonts.family(ctx, settings.font) }
            PteronTheme(settings.preset, settings.darkMode, font) {
                var showSettings by remember { mutableStateOf(false) }

                // Eye protection: a warm Multiply layer over the finished frame (black stays black, white turns warm).
                // The strength glides when switched on/off or dragged; it is read at draw time, so it costs no recomposition,
                // and the layer is dropped completely when off. No off-screen buffer is needed: every screen paints an opaque background.
                val strength by animateFloatAsState(if (settings.eyeProtection) settings.eyeStrength else 0f, tween(300), label = "eye")
                val tintOn by remember { derivedStateOf { strength > 0.002f } }
                val warm = if (tintOn) Modifier.drawWithContent {
                    drawContent()
                    drawRect(lerp(Color.White, WarmTint, strength), blendMode = BlendMode.Multiply)
                } else Modifier

                Box(Modifier.fillMaxSize().then(warm)) {
                    AnimatedContent(
                        targetState = opened,
                        transitionSpec = {
                            val opening = targetState != null && initialState == null
                            val closing = targetState == null && initialState != null
                            val slide = tween<androidx.compose.ui.unit.IntOffset>(340, easing = FastOutSlowInEasing)
                            val t = when {
                                // opening: the reader glides in from the right over the home screen
                                opening -> (slideInHorizontally(slide) { it / 4 } + fadeIn(tween(280))) togetherWith
                                    (slideOutHorizontally(slide) { -it / 10 } + fadeOut(tween(240)))
                                // closing: the reader slides away to the right, home settles back in
                                closing -> (slideInHorizontally(slide) { -it / 10 } + fadeIn(tween(280))) togetherWith
                                    (slideOutHorizontally(slide) { it / 4 } + fadeOut(tween(240)))
                                else -> fadeIn(tween(260)) togetherWith fadeOut(tween(200))
                            }
                            t.targetContentZIndex = if (closing) -1f else 1f
                            t
                        },
                        modifier = Modifier.fillMaxSize(), label = "screen",
                    ) { doc ->
                        if (doc == null) {
                            HomeScreen(prefs = prefs, settings = settings, onOpen = ::openPdf, onSettings = { showSettings = true })
                        } else {
                            val vm: ReaderViewModel = viewModel(key = "${doc.uri}#${doc.seq}", factory = object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                    ReaderViewModel(application as Application, doc.uri) as T
                            })
                            // The reader leaving the screen frees its document and page images right away, instead of keeping
                            // them alive in the activity until the app closes. (Not on rotation: the same reader carries on.)
                            DisposableEffect(vm) { onDispose { if (!isChangingConfigurations) vm.release() } }
                            ReaderScreen(
                                vm = vm, settings = settings,
                                onClose = { vm.touchRecent(); opened = null },
                                onSettings = { showSettings = true },
                            )
                        }
                    }
                    // Opens above whichever screen is showing, so the reader's state is untouched underneath.
                    AnimatedVisibility(
                        showSettings,
                        enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)),
                        exit = slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200)),
                    ) {
                        SettingsScreen(settings, onClose = { showSettings = false })
                    }
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
        opened?.let { outState.putString("uri", it.uri.toString()); outState.putInt("seq", it.seq) }
    }
}
