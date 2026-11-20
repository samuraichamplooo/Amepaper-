package dev.amepaper.render

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import dev.amepaper.data.Crop
import dev.amepaper.data.Importer
import dev.amepaper.data.Library
import dev.amepaper.data.WallImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Generative fill isn't something Amepaper can do on-device, but the phone's own photo editor
 * (Galaxy AI Generative edit, Google Photos Magic Editor) can. We save the zoomed-out picture
 * with black empty space to Pictures/Amepaper and open it in the editor; the result can then
 * be brought back with "Use edited picture".
 */
object AiHandoff {

    fun supported(): Boolean = Build.VERSION.SDK_INT >= 29

    fun export(ctx: Context, img: WallImage, c: Crop): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val src = Importer.decodeForEditor(Library.file(img), 4096) ?: return null
            val iw = src.width
            val ih = src.height
            val cw = (c.r - c.l) * iw
            val ch = (c.b - c.t) * ih
            val k = min(1f, 4096f / max(cw, ch))
            val out = FillRenderer.compose(
                src, c.l * iw, c.t * ih, cw, ch,
                max(1, (cw * k).roundToInt()), max(1, (ch * k).roundToInt()),
                "black", 0f, 0f, 0f,
            )
            val cr = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "amepaper_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Amepaper")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            cr.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            uri
        } catch (t: Throwable) {
            null
        }
    }

    fun openEditor(ctx: Context, uri: Uri) {
        val edit = Intent(Intent.ACTION_EDIT)
            .setDataAndType(uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            ctx.startActivity(Intent.createChooser(edit, "Fill the empty space with…"))
        } catch (e: Exception) {
            Toast.makeText(
                ctx,
                "Saved to Pictures/Amepaper. Open it in Gallery and use Generative edit.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
