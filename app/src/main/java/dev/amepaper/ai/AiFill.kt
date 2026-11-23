package dev.amepaper.ai

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import dev.amepaper.data.Crop
import dev.amepaper.data.K
import dev.amepaper.data.Library
import dev.amepaper.data.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Generative fill for the empty space around a zoomed-out picture.
 *
 * Every engine gets the current framing with the empty space painted black and returns a picture
 * of the whole frame. The app keeps the original picture on top (with blended edges), so the AI
 * only ever supplies what's around it.
 */
object AiFill {

    enum class Engine(val label: String, val short: String) {
        DEVICE("On this phone", "On-device"),
        FLUX("FLUX · free (Cloudflare)", "FLUX"),
        GEMINI("Gemini · paid", "Gemini"),
    }

    /** The finished fill: saved file name and the part of the picture's space it covers. */
    data class Result(val file: String, val l: Float, val t: Float, val r: Float, val b: Float)

    // ---------------------------------------------------------------- on-device model (MI-GAN)

    const val MODEL_URL =
        "https://huggingface.co/litert-community/MI-GAN-512-Places2-LiteRT/resolve/main/migan_fp16.tflite"
    const val MODEL_MB = 16
    private const val S = 512

    fun modelFile(ctx: Context): File = File(File(ctx.filesDir, "models").also { it.mkdirs() }, "migan_fp16.tflite")

    fun modelReady(ctx: Context): Boolean = modelFile(ctx).let { it.exists() && it.length() > 1_000_000 }

    fun deleteModel(ctx: Context) {
        runCatching { modelFile(ctx).delete() }
    }

    suspend fun downloadModel(ctx: Context, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dst = modelFile(ctx)
        val part = File(dst.parentFile, dst.name + ".part")
        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Download failed (HTTP $code)")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (MODEL_MB * 1_000_000L)
            var done = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > 256 * 1024) {
                            last = done
                            progress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (done < 1_000_000L) throw IOException("Download was incomplete")
            if (!part.renameTo(dst)) throw IOException("Couldn't save the model")
            progress(1f)
        } catch (e: Exception) {
            part.delete()
            throw if (e is IOException) e else IOException(e.message ?: "Download failed")
        } finally {
            conn.disconnect()
        }
    }

    /** MI-GAN inpainting on the phone's GPU (CPU if the GPU can't run it). */
    suspend fun onDevice(ctx: Context, src: Bitmap, c: Crop): Result = withContext(Dispatchers.Default) {
        if (!modelReady(ctx)) throw IOException("The on-device model isn't downloaded yet")
        val frame = framing(src, c, S, S)
        val keep = keepRect(c, S, S)
        // shrink the kept area a little so the resampled edge of the picture isn't treated as content
        val e = 3f
        val kl = if (keep.left > 0.5f) keep.left + e else 0f
        val kt = if (keep.top > 0.5f) keep.top + e else 0f
        val kr = if (keep.right < S - 0.5f) keep.right - e else S.toFloat()
        val kb = if (keep.bottom < S - 0.5f) keep.bottom - e else S.toFloat()

        val plane = S * S
        val px = IntArray(plane)
        frame.getPixels(px, 0, S, 0, 0, S, S)
        val mask = FloatArray(plane)
        val input = FloatArray(4 * plane)
        for (y in 0 until S) {
            val cy = y + 0.5f
            for (x in 0 until S) {
                val i = y * S + x
                val cx = x + 0.5f
                val m = if (cx >= kl && cx <= kr && cy >= kt && cy <= kb) 1f else 0f
                mask[i] = m
                val p = px[i]
                input[i] = m - 0.5f
                input[plane + i] = (((p shr 16) and 0xff) / 127.5f - 1f) * m
                input[2 * plane + i] = (((p shr 8) and 0xff) / 127.5f - 1f) * m
                input[3 * plane + i] = ((p and 0xff) / 127.5f - 1f) * m
            }
        }
        val out = infer(modelFile(ctx).absolutePath, input)
        if (out.size < 3 * plane) throw IOException("The on-device model returned an unexpected result")
        fun ch(v: Float) = ((v + 1f) * 127.5f).roundToInt().coerceIn(0, 255)
        for (i in 0 until plane) {
            if (mask[i] < 0.5f) {
                px[i] = Color.rgb(ch(out[i]), ch(out[plane + i]), ch(out[2 * plane + i]))
            }
        }
        val result = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        result.setPixels(px, 0, S, 0, 0, S, S)
        save(result, c)
    }

    private fun infer(path: String, input: FloatArray): FloatArray {
        var last: Throwable? = null
        for (acc in listOf(Accelerator.GPU, Accelerator.CPU)) {
            try {
                val model = CompiledModel.create(path, CompiledModel.Options(acc))
                try {
                    val ins = model.createInputBuffers()
                    val outs = model.createOutputBuffers()
                    try {
                        ins[0].writeFloat(input)
                        model.run(ins, outs)
                        return outs[0].readFloat()
                    } finally {
                        ins.forEach { runCatching { it.close() } }
                        outs.forEach { runCatching { it.close() } }
                    }
                } finally {
                    runCatching { model.close() }
                }
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IOException("On-device AI couldn't run on this phone (${last?.message ?: last?.javaClass?.simpleName})")
    }

    // ---------------------------------------------------------------- shared prompt

    private const val PROMPT =
        "Outpaint this image. The picture sits on a canvas whose empty parts are solid black. " +
            "Replace every black area with a seamless, natural continuation of the picture: the same scene, " +
            "lighting, colors, perspective, depth of field and style, as if it had been captured wider. " +
            "Keep the existing picture exactly as it is. No black bars, borders, frames, text, logos or watermarks."

    /** Shorter wording used when retrying after the safety filter flags a result. */
    private const val PROMPT_PLAIN =
        "Extend this picture outward to fill the black areas around it with more of the same scenery. " +
            "Keep the picture itself unchanged. Seamless and natural, no borders or text."

    // ---------------------------------------------------------------- FLUX.2 [klein] on Cloudflare Workers AI

    val FLUX_MODELS = listOf(
        "@cf/black-forest-labs/flux-2-klein-4b" to "Klein 4B · about 80 free fills a day",
        "@cf/black-forest-labs/flux-2-klein-9b" to "Klein 9B · better, about 6 free a day",
    )

    fun fluxReady(sp: SharedPreferences) = sp.get(K.CF_ACCOUNT).isNotBlank() && sp.get(K.CF_TOKEN).isNotBlank()

    suspend fun flux(sp: SharedPreferences, src: Bitmap, c: Crop): Result = withContext(Dispatchers.IO) {
        val account = sp.get(K.CF_ACCOUNT).trim()
        val token = sp.get(K.CF_TOKEN).trim()
        if (account.isEmpty() || token.isEmpty()) throw IOException("Add your Cloudflare account ID and token first")
        if (!Regex("^[0-9a-fA-F]{32}$").matches(account)) {
            throw IOException(
                "That Account ID doesn't look right. It's a 32-character code of letters and numbers, not your account " +
                    "name. In the Cloudflare dashboard: Workers & Pages → Account details → copy Account ID.",
            )
        }
        val model = sp.get(K.CF_MODEL).ifBlank { K.CF_MODEL.def }

        // input images must be under 512×512; the output can be much larger
        val (iw, ih) = sizeFor(src, c, 496)
        val input = framing(src, c, iw, ih)
        val png = ByteArrayOutputStream().also { input.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val aspect = iw.toFloat() / ih
        val (ow, oh) = if (aspect >= 1f) {
            Pair(1280, snap16(1280 / aspect))
        } else {
            Pair(snap16(1280 * aspect), 1280)
        }

        val url = "https://api.cloudflare.com/client/v4/accounts/$account/ai/run/$model"
        // FLUX's safety filter checks every generated picture and sometimes flags harmless ones.
        // It's random per picture, so a new seed (and a plainer prompt) almost always gets through.
        val prompts = listOf(PROMPT, PROMPT_PLAIN, PROMPT_PLAIN)
        var lastError = ""
        for (attempt in prompts.indices) {
            val boundary = "----amepaper" + UUID.randomUUID().toString().replace("-", "")
            val body = ByteArrayOutputStream()
            fun field(name: String, value: String) {
                body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("prompt", prompts[attempt])
            field("width", ow.toString())
            field("height", oh.toString())
            field("seed", (1 + Random.nextInt(1_000_000_000)).toString())
            body.write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"input_image_0\"; filename=\"frame.png\"\r\n" +
                    "Content-Type: image/png\r\n\r\n").toByteArray(),
            )
            body.write(png)
            body.write("\r\n--$boundary--\r\n".toByteArray())

            val resp = post(
                url,
                mapOf("Authorization" to "Bearer $token", "Content-Type" to "multipart/form-data; boundary=$boundary"),
                body.toByteArray(),
            )
            val decoded: Bitmap? = if (resp.contentType.startsWith("image/")) {
                BitmapFactory.decodeByteArray(resp.bytes, 0, resp.bytes.size)
            } else {
                val text = String(resp.bytes)
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (resp.code !in 200..299 || json == null || json.optBoolean("success", true) == false) {
                    val msg = fluxError(resp.code, json, text)
                    if (msg.contains("flagged", true)) {
                        lastError = msg
                        continue
                    }
                    throw IOException(msg)
                }
                val b64 = json.optJSONObject("result")?.optString("image")?.takeIf { it.isNotEmpty() }
                    ?: json.optString("image").takeIf { it.isNotEmpty() }
                    ?: throw IOException("Cloudflare didn't send back a picture")
                decodeB64(b64)
            }
            val bmp = decoded ?: throw IOException("Couldn't read the picture Cloudflare sent back")
            return@withContext save(bmp, c)
        }
        throw IOException(
            "FLUX's safety filter blocked the result ${prompts.size} times in a row. On a picture like this it's a false " +
                "alarm. Tap FLUX again, switch to Klein 9B in Settings → AI fill, or use On-device or Gemini. ($lastError)",
        )
    }

    private fun fluxError(code: Int, json: JSONObject?, text: String): String {
        val msg = json?.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
            ?: text.take(160)
        return when {
            code == 401 || code == 403 ->
                "Cloudflare didn't accept the token. Check the account ID, and that the token has Workers AI permission. ($msg)"
            code == 429 || msg.contains("neuron", true) || msg.contains("limit", true) ->
                "Today's free Cloudflare allowance is used up. It resets at midnight UTC. ($msg)"
            else -> "Cloudflare error ($code): $msg"
        }
    }

    // ---------------------------------------------------------------- Gemini (Nano Banana)

    val GEMINI_MODELS = listOf(
        "gemini-nano-banana-2.1" to "Nano Banana 2.1 · newest, Flash-level cost",
        "gemini-3.1-flash-image" to "Nano Banana 2 · about 7¢ a fill",
        "gemini-3-pro-image" to "Nano Banana Pro · about 13¢ a fill",
    )

    fun geminiReady(sp: SharedPreferences) = sp.get(K.GEMINI_KEY).isNotBlank()

    suspend fun gemini(sp: SharedPreferences, src: Bitmap, c: Crop): Result = withContext(Dispatchers.IO) {
        val key = sp.get(K.GEMINI_KEY).trim()
        if (key.isEmpty()) throw IOException("Add your Gemini API key first")
        val model = sp.get(K.GEMINI_MODEL).ifBlank { K.GEMINI_MODEL.def }
        val (w, h) = sizeFor(src, c, 1024)
        val frame = framing(src, c, w, h)
        val jpeg = ByteArrayOutputStream().also { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        val parts = JSONArray()
            .put(JSONObject().put("text", PROMPT))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
                ),
            )
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
            .toString().toByteArray()

        var resp: Resp? = null
        for (ver in listOf("v1beta", "v1")) {
            resp = post(
                "https://generativelanguage.googleapis.com/$ver/models/$model:generateContent",
                mapOf("x-goog-api-key" to key, "Content-Type" to "application/json"),
                body,
            )
            if (resp.code != 404) break
        }
        val r = resp ?: throw IOException("No response from Gemini")
        val text = String(r.bytes)
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (r.code !in 200..299 || json == null) {
            val msg = json?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: text.take(160)
            throw IOException(
                when (r.code) {
                    400 -> if (msg.contains("API key", true)) "Gemini didn't accept the API key. ($msg)" else "Gemini error: $msg"
                    401, 403 -> "Gemini didn't accept the API key, or billing isn't set up for it. ($msg)"
                    429 -> "Gemini says you're over your limit or need billing turned on. ($msg)"
                    else -> "Gemini error (${r.code}): $msg"
                },
            )
        }
        val cand = json.optJSONArray("candidates")?.optJSONObject(0)
        val outParts = cand?.optJSONObject("content")?.optJSONArray("parts")
        var b64: String? = null
        if (outParts != null) {
            for (i in 0 until outParts.length()) {
                val p = outParts.optJSONObject(i) ?: continue
                val d = (p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data"))?.optString("data")
                if (!d.isNullOrEmpty()) {
                    b64 = d
                    break
                }
            }
        }
        if (b64 == null) {
            val why = json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }
                ?: cand?.optString("finishReason")?.takeIf { it.isNotBlank() }
            throw IOException("Gemini didn't return a picture" + (why?.let { " ($it)" } ?: ""))
        }
        val bmp = decodeB64(b64) ?: throw IOException("Couldn't read the picture Gemini sent back")
        save(bmp, c)
    }

    // ---------------------------------------------------------------- helpers

    /** The framing at [w]×[h]: the picture where the crop puts it, black everywhere else. */
    fun framing(src: Bitmap, c: Crop, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(Color.BLACK)
        cv.drawBitmap(src, null, picRect(c, w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    private fun picRect(c: Crop, w: Int, h: Int): RectF {
        val fw = max(0.0001f, c.r - c.l)
        val fh = max(0.0001f, c.b - c.t)
        return RectF(-c.l / fw * w, -c.t / fh * h, (1f - c.l) / fw * w, (1f - c.t) / fh * h)
    }

    private fun keepRect(c: Crop, w: Int, h: Int): RectF {
        val p = picRect(c, w, h)
        return RectF(max(0f, p.left), max(0f, p.top), min(w.toFloat(), p.right), min(h.toFloat(), p.bottom))
    }

    /** Frame size with the crop's shape and [long] pixels on the long side. */
    private fun sizeFor(src: Bitmap, c: Crop, long: Int): Pair<Int, Int> {
        val a = ((c.r - c.l) * src.width) / max(0.0001f, (c.b - c.t) * src.height)
        return if (a >= 1f) {
            Pair(long, max(64, (long / a).roundToInt()))
        } else {
            Pair(max(64, (long * a).roundToInt()), long)
        }
    }

    private fun snap16(v: Float): Int = ((v / 16f).roundToInt() * 16).coerceIn(256, 1920)

    private fun decodeB64(s: String): Bitmap? {
        val clean = if (s.startsWith("data:")) s.substringAfter(",") else s
        val bytes = Base64.decode(clean, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun save(bmp: Bitmap, c: Crop): Result {
        val name = "ai-" + UUID.randomUUID().toString().take(12) + ".jpg"
        Library.aiFile(name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return Result(name, c.l, c.t, c.r, c.b)
    }

    private class Resp(val code: Int, val contentType: String, val bytes: ByteArray)

    private fun post(url: String, headers: Map<String, String>, body: ByteArray): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setFixedLengthStreamingMode(body.size)
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Resp(code, conn.contentType ?: "", bytes)
        } catch (e: IOException) {
            throw IOException("Couldn't reach the AI service. Check your connection. (${e.message})")
        } finally {
            conn.disconnect()
        }
    }
}
