package app.pteronpdf.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings

data class DevicePdf(val uri: Uri, val name: String, val size: Long, val modifiedSec: Long)

/** Permission to see the PDFs on the phone, and the list of them. Without it the app still works through the system file picker. */
object Storage {
    /** Android 11+ guards "all files" behind its own settings page; older versions use the normal read permission. */
    fun granted(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= 23 -> ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    /** The settings page where the user switches "allow access to all files" on for this app (Android 11+). */
    fun allFilesIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))

    fun allFilesFallbackIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    /** Every PDF the system has indexed, newest first. Cheap: one database query, nothing is opened. */
    fun listPdfs(ctx: Context, limit: Int = 300): List<DevicePdf> = runCatching {
        val out = ArrayList<DevicePdf>()
        val base = MediaStore.Files.getContentUri("external")
        ctx.contentResolver.query(
            base,
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED),
            "${MediaStore.Files.FileColumns.MIME_TYPE}=?", arrayOf("application/pdf"),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val name = c.getString(1) ?: continue
                out += DevicePdf(android.content.ContentUris.withAppendedId(base, c.getLong(0)), name, c.getLong(2), c.getLong(3))
            }
        }
        out
    }.getOrDefault(emptyList())
}
