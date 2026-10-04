package app.pteronpdf

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import app.pteronpdf.theme.ThemePresets
import app.pteronpdf.ui.HomeScreen
import app.pteronpdf.ui.ReaderScreen
import app.pteronpdf.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private var openUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openUri = savedInstanceState?.getString("uri")?.let(Uri::parse) ?: intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        val prefs = Prefs(this)
        val settings = AppSettings(prefs)
        setContent {
            val ctx = LocalContext.current
            val font = remember(settings.font) { Fonts.family(ctx, settings.font) }
            PteronTheme(ThemePresets[settings.themeIndex], settings.darkMode, font) {
                var showSettings by remember { mutableStateOf(false) }
                val u = openUri
                Box(Modifier.fillMaxSize()) {
                    if (u == null) {
                        HomeScreen(prefs = prefs, settings = settings, onOpen = { openUri = it }, onSettings = { showSettings = true })
                    } else {
                        val vm: ReaderViewModel = viewModel(key = u.toString(), factory = object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                ReaderViewModel(application as Application, u) as T
                        })
                        ReaderScreen(
                            vm = vm, settings = settings,
                            onClose = { vm.touchRecent(); openUri = null },
                            onSettings = { showSettings = true },
                        )
                    }
                    // Opens above whichever screen is showing, so the reader's state is untouched underneath.
                    if (showSettings) SettingsScreen(settings, onClose = { showSettings = false })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { openUri = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        openUri?.let { outState.putString("uri", it.toString()) }
    }
}
