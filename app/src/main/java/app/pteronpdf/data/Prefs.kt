package app.pteronpdf.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.pteronpdf.theme.AppFont
import app.pteronpdf.theme.DarkMode

data class RecentDoc(val uri: Uri, val name: String, val lastPage: Int, val pages: Int, val time: Long)

/** Tiny SharedPreferences wrapper — no DataStore/Room to keep the APK and startup lean. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("pteron", Context.MODE_PRIVATE)

    var themeIndex: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()

    var darkMode: DarkMode
        get() = DarkMode.entries.getOrElse(sp.getInt("dark", 0)) { DarkMode.System }
        set(v) = sp.edit().putInt("dark", v.ordinal).apply()

    var font: AppFont
        get() = AppFont.entries.firstOrNull { it.name == sp.getString("font", "") } ?: AppFont.System
        set(v) = sp.edit().putString("font", v.name).apply()

    var readingMode: Boolean
        get() = sp.getBoolean("readingMode", false)
        set(v) = sp.edit().putBoolean("readingMode", v).apply()

    var keepAwake: Boolean
        get() = sp.getBoolean("keepAwake", false)
        set(v) = sp.edit().putBoolean("keepAwake", v).apply()

    var rememberPosition: Boolean
        get() = sp.getBoolean("rememberPosition", true)
        set(v) = sp.edit().putBoolean("rememberPosition", v).apply()

    fun recents(): List<RecentDoc> =
        (sp.getString("recents", "") ?: "").lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 5) null else runCatching {
                RecentDoc(Uri.parse(p[0]), p[1], p[2].toInt(), p[3].toInt(), p[4].toLong())
            }.getOrNull()
        }.toList()

    fun touchRecent(doc: RecentDoc) {
        val list = (listOf(doc) + recents().filter { it.uri != doc.uri }).take(12)
        sp.edit().putString("recents", list.joinToString("\n") {
            "${it.uri}\t${it.name.replace('\t', ' ').replace('\n', ' ')}\t${it.lastPage}\t${it.pages}\t${it.time}"
        }).apply()
    }

    fun forgetRecent(uri: Uri) {
        val list = recents().filter { it.uri != uri }
        sp.edit().putString("recents", list.joinToString("\n") {
            "${it.uri}\t${it.name}\t${it.lastPage}\t${it.pages}\t${it.time}"
        }).apply()
    }
}

/** Observable view of the settings: UI reads these, writes go through to [Prefs]. */
class AppSettings(private val p: Prefs) {
    var themeIndex by mutableIntStateOf(p.themeIndex); private set
    var darkMode by mutableStateOf(p.darkMode); private set
    var font by mutableStateOf(p.font); private set
    var readingMode by mutableStateOf(p.readingMode); private set
    var keepAwake by mutableStateOf(p.keepAwake); private set
    var rememberPosition by mutableStateOf(p.rememberPosition); private set

    fun setTheme(v: Int) { themeIndex = v; p.themeIndex = v }
    fun setDark(v: DarkMode) { darkMode = v; p.darkMode = v }
    fun setFont(v: AppFont) { font = v; p.font = v }
    fun setReadingMode(v: Boolean) { readingMode = v; p.readingMode = v }
    fun setKeepAwake(v: Boolean) { keepAwake = v; p.keepAwake = v }
    fun setRememberPosition(v: Boolean) { rememberPosition = v; p.rememberPosition = v }
}
