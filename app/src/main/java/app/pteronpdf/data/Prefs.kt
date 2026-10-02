package app.pteronpdf.data

import android.content.Context
import android.net.Uri
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
