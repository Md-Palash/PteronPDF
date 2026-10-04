package app.pteronpdf.theme

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Font choices. Files live in res/font as <res>_regular.(ttf|otf) and optionally <res>_bold.(ttf|otf).
 * A font whose file is not in the APK is simply not offered, so the list never lies.
 */
enum class AppFont(val label: String, val res: String?) {
    System("Default", null),
    Inter("Inter", "inter"),
    Aptos("Aptos", "aptos"),
    Outfit("Outfit", "outfit"),
    Playfair("Playfair", "playfair"),
    Cinzel("Cinzel", "cinzel"),
    Roboto("Roboto", "roboto"),
    Lato("Lato", "lato"),
    PlaywriteUS("Playwrite US", "playwrite_us"),
}

object Fonts {
    private val cache = HashMap<AppFont, FontFamily?>()

    private fun id(ctx: Context, name: String) = ctx.resources.getIdentifier(name, "font", ctx.packageName)

    @Synchronized
    fun family(ctx: Context, f: AppFont): FontFamily? = cache.getOrPut(f) {
        val r = f.res ?: return@getOrPut null
        val reg = id(ctx, "${r}_regular")
        if (reg == 0) return@getOrPut null
        val bold = id(ctx, "${r}_bold")
        if (bold == 0) FontFamily(Font(reg, FontWeight.Normal))
        else FontFamily(Font(reg, FontWeight.Normal), Font(bold, FontWeight.Bold))
    }

    fun available(ctx: Context): List<AppFont> =
        AppFont.entries.filter { it == AppFont.System || family(ctx, it) != null }
}
