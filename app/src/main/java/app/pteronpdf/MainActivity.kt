package app.pteronpdf

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pteronpdf.data.Prefs
import app.pteronpdf.pdf.ReaderViewModel
import app.pteronpdf.theme.PteronTheme
import app.pteronpdf.theme.ThemePresets
import app.pteronpdf.ui.HomeScreen
import app.pteronpdf.ui.ReaderScreen

class MainActivity : ComponentActivity() {
    private var openUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openUri = savedInstanceState?.getString("uri")?.let(Uri::parse) ?: intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        val prefs = Prefs(this)
        setContent {
            var themeIndex by remember { mutableStateOf(prefs.themeIndex) }
            var dark by remember { mutableStateOf(prefs.darkMode) }
            PteronTheme(ThemePresets[themeIndex], dark) {
                val u = openUri
                if (u == null) {
                    HomeScreen(
                        prefs = prefs, themeIndex = themeIndex, darkMode = dark,
                        onTheme = { themeIndex = it; prefs.themeIndex = it },
                        onDark = { dark = it; prefs.darkMode = it },
                        onOpen = { openUri = it },
                    )
                } else {
                    val vm: ReaderViewModel = viewModel(key = u.toString(), factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            ReaderViewModel(application as Application, u) as T
                    })
                    ReaderScreen(
                        vm = vm, themeIndex = themeIndex, darkMode = dark,
                        onTheme = { themeIndex = it; prefs.themeIndex = it },
                        onDark = { dark = it; prefs.darkMode = it },
                        onClose = { vm.touchRecent(); openUri = null },
                    )
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
