package dev.amepaper.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import dev.amepaper.data.Crop
import dev.amepaper.data.Library
import dev.amepaper.data.CropMath
import dev.amepaper.data.Placement
import dev.amepaper.data.Prefs
import dev.amepaper.data.Screen
import dev.amepaper.data.Screens
import dev.amepaper.data.WallImage
import dev.amepaper.render.FillRenderer
import kotlin.math.roundToInt

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp, top = 22.dp, bottom = 10.dp),
    )
}

@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

@Composable
fun RowDivider() {
    HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.background)
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun SwitchPref(title: String, subtitle: String?, handle: PrefHandle<Boolean>, enabled: Boolean = true, onChanged: (Boolean) -> Unit = {}) {
    SwitchRow(title, subtitle, handle.value, enabled) {
        handle.value = it
        onChanged(it)
    }
}

/**
 * A slider bound to a float setting. With [live] the setting is written while dragging
 * (cheap rain settings); otherwise only when the finger lifts (blur needs a re-decode).
 */
@Composable
fun SliderPref(
    title: String,
    handle: PrefHandle<Float>,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    live: Boolean = true,
    format: (Float) -> String = { "${(it * 100).roundToInt()}%" },
) {
    var local by remember(handle.value) { mutableFloatStateOf(handle.value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(format(local), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local.coerceIn(range.start, range.endInclusive),
            onValueChange = {
                local = it
                if (live) handle.value = it
            },
            onValueChangeFinished = { if (!live) handle.value = local },
            valueRange = range,
        )
    }
}

@Composable
fun NavRow(title: String, subtitle: String?, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

/** Coil transformation that cuts a saved crop out of the image (with fill when zoomed out). */
class CropTransformation(private val crop: Crop) : Transformation {
    override val cacheKey: String = "crop-$crop"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val iw = input.width
        val ih = input.height
        if (crop.inside) {
            val x = (crop.l * iw).roundToInt().coerceIn(0, iw - 1)
            val y = (crop.t * ih).roundToInt().coerceIn(0, ih - 1)
            val w = ((crop.r - crop.l) * iw).roundToInt().coerceIn(1, iw - x)
            val h = ((crop.b - crop.t) * ih).roundToInt().coerceIn(1, ih - y)
            return Bitmap.createBitmap(input, x, y, w, h)
        }
        val cw = (crop.r - crop.l) * iw
        val ch = (crop.b - crop.t) * ih
        val k = minOf(1f, 1600f / maxOf(cw, ch))
        val ow = maxOf(1, (cw * k).roundToInt())
        val oh = maxOf(1, (ch * k).roundToInt())
        var ai: Bitmap? = null
        var aiDst: android.graphics.RectF? = null
        if (crop.fill == "ai" && crop.aiFile != null) {
            val f = Library.aiFile(crop.aiFile)
            if (f.exists()) {
                ai = android.graphics.BitmapFactory.decodeFile(
                    f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 },
                )
                val kx = ow / cw
                val ky = oh / ch
                aiDst = android.graphics.RectF(
                    (crop.aiL - crop.l) * iw * kx, (crop.aiT - crop.t) * ih * ky,
                    (crop.aiR - crop.l) * iw * kx, (crop.aiB - crop.t) * ih * ky,
                )
            }
        }
        return FillRenderer.compose(
            input, crop.l * iw, crop.t * ih, cw, ch, ow, oh,
            crop.fill, crop.fillBlur, crop.fillDim, crop.fillSoft, ai, aiDst,
        )
    }
}

/** The placement matching the screen the app is on right now (folded/unfolded, orientation). */
@Composable
fun currentPlacement(screen: Screen): Placement {
    val conf = LocalConfiguration.current
    return Placement.forSize(screen, conf.screenWidthDp, conf.screenHeightDp)
}

/** The framing [img] uses on [p] (its own or the one it follows). */
@Composable
fun cropOn(img: WallImage, p: Placement): Crop? {
    val ctx = LocalContext.current
    return remember(img, p) { CropMath.resolve(img, p, Screens.aspect(Prefs.sp(ctx), p)).crop }
}

/** Thumbnail of a library image, showing [crop] when there is one. */
@Composable
fun WallThumb(img: WallImage, crop: Crop?, modifier: Modifier = Modifier, sizePx: Int = 900) {
    val ctx = LocalContext.current
    val request = remember(img.id, crop, sizePx) {
        val b = ImageRequest.Builder(ctx)
            .data(Library.file(img))
            .size(sizePx)
            .crossfade(true)
        if (crop != null) b.transformations(CropTransformation(crop))
        b.build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

@Composable
fun SmallThumb(img: WallImage?) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (img != null) WallThumb(img, null, Modifier.size(52.dp), sizePx = 200)
    }
}
