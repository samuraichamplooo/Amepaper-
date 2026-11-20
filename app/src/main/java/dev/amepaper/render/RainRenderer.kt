package dev.amepaper.render

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import dev.amepaper.R
import dev.amepaper.data.RainParams
import dev.amepaper.data.ScaleMode
import dev.amepaper.data.Screen
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the wallpaper with the rain shader. Two layers (0 and 1) are kept so a new image can
 * cross-fade in. All GL work happens in onSurfaceCreated / onDrawFrame; [show] and [updateFx]
 * only change state and must be called on the GL thread (via queueEvent).
 */
class RainRenderer(ctx: Context) : GLSurfaceView.Renderer {

    private class Layer {
        var prepared: Prepared? = null
        var tex = 0
        var fog = 0
        var texGen = -1
        var dirty = false
        var fx = floatArrayOf(1f, 0f, 0f, 1f)
        var scroll = false
        val rect = floatArrayOf(0f, 0f, 1f, 1f)
        val cover = floatArrayOf(0f, 0f, 1f, 1f)
    }

    private val vertSrc = ctx.resources.openRawResource(R.raw.rain_vert).bufferedReader().use { it.readText() }
    private val fragSrc = ctx.resources.openRawResource(R.raw.rain_frag).bufferedReader().use { it.readText() }

    private val layers = arrayOf(Layer(), Layer())
    private val pendingDelete = ArrayList<Int>()

    @Volatile
    var params: RainParams = RainParams.OFF

    @Volatile
    var xOffset = 0.5f

    /** True while a cross-fade is running (read from the main thread). */
    @Volatile
    var fading = false
        private set

    /** Called on the GL thread when the surface size changes. */
    var onSize: ((Int, Int) -> Unit)? = null

    private var cur = 0
    private var mix = 0f
    private var fadeMs = 700f
    private var gen = 0
    private var program = 0
    private var vbo = 0
    private var blank = 0
    private var w = 1
    private var h = 1
    private var time = 0f
    private var lastNanos = 0L
    private val loc = HashMap<String, Int>()

    // ---------------------------------------------------------------- state (GL thread)

    fun show(p: Prepared, fx: FloatArray, scroll: Boolean, fade: Boolean, durationMs: Float) {
        val target = if (fade && layers[cur].prepared != null) 1 - cur else cur
        val l = layers[target]
        if (l.tex != 0 && l.texGen == gen) {
            pendingDelete.add(l.tex)
            pendingDelete.add(l.fog)
        }
        l.tex = 0
        l.fog = 0
        l.prepared = p
        l.fx = fx
        l.scroll = scroll
        l.dirty = true
        if (target != cur) {
            cur = target
            fadeMs = max(50f, durationMs)
            fading = true
        } else {
            mix = cur.toFloat()
        }
    }

    fun updateFx(screen: Screen, fx: FloatArray, scroll: Boolean) {
        for (l in layers) {
            if (l.prepared?.screen == screen) {
                l.fx = fx
                l.scroll = scroll
            }
        }
    }

    // ---------------------------------------------------------------- GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        gen++
        pendingDelete.clear()
        program = buildProgram()
        loc.clear()
        if (program != 0) {
            for (n in UNIFORMS) loc[n] = GLES30.glGetUniformLocation(program, n)
        }
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(quad).position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
        blank = makeBlank()
        for (l in layers) {
            l.tex = 0
            l.fog = 0
            l.texGen = gen
            l.dirty = l.prepared != null
        }
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = max(1, width)
        h = max(1, height)
        GLES30.glViewport(0, 0, w, h)
        onSize?.invoke(w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = now
        val p = params
        time += dt * p.speed
        if (time > 20000f) time -= 20000f

        val target = cur.toFloat()
        if (mix != target) {
            val step = dt * 1000f / fadeMs
            mix = if (mix < target) min(target, mix + step) else max(target, mix - step)
        }
        fading = mix != target

        if (pendingDelete.isNotEmpty()) {
            val ids = pendingDelete.filter { it != 0 }.toIntArray()
            if (ids.isNotEmpty()) GLES30.glDeleteTextures(ids.size, ids, 0)
            pendingDelete.clear()
        }
        for (l in layers) if (l.dirty) upload(l)

        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (program == 0) return

        GLES30.glUseProgram(program)
        bindTex(0, layers[0].tex)
        bindTex(1, layers[0].fog)
        bindTex(2, layers[1].tex)
        bindTex(3, layers[1].fog)
        GLES30.glUniform1i(u("uTexA"), 0)
        GLES30.glUniform1i(u("uFogA"), 1)
        GLES30.glUniform1i(u("uTexB"), 2)
        GLES30.glUniform1i(u("uFogB"), 3)

        computeRects(layers[0])
        computeRects(layers[1])
        setLayerUniforms(layers[0], "A")
        setLayerUniforms(layers[1], "B")

        GLES30.glUniform1f(u("uMix"), mix)
        GLES30.glUniform2f(u("uRes"), w.toFloat(), h.toFloat())
        GLES30.glUniform1f(u("uTime"), time)
        GLES30.glUniform1f(u("uRain"), p.rain)
        GLES30.glUniform1f(u("uDrops"), p.drops)
        GLES30.glUniform1f(u("uSize"), p.size)
        GLES30.glUniform1f(u("uFog"), p.fog)
        GLES30.glUniform1f(u("uRefr"), p.refraction)
        GLES30.glUniform1f(u("uTrail"), p.trails)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    // ---------------------------------------------------------------- helpers

    private fun u(name: String): Int = loc[name] ?: -1

    private fun bindTex(unit: Int, id: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (id != 0) id else blank)
    }

    private fun setLayerUniforms(l: Layer, s: String) {
        GLES30.glUniform4f(u("uRect$s"), l.rect[0], l.rect[1], l.rect[2], l.rect[3])
        GLES30.glUniform4f(u("uCover$s"), l.cover[0], l.cover[1], l.cover[2], l.cover[3])
        GLES30.glUniform4f(u("uFx$s"), l.fx[0], l.fx[1], l.fx[2], l.fx[3])
    }

    /** Where the image sits on screen, in 0..1 screen coordinates (top-left origin). */
    private fun computeRects(l: Layer) {
        val p = l.prepared
        if (p == null) {
            l.rect[0] = 0f; l.rect[1] = 0f; l.rect[2] = 1f; l.rect[3] = 1f
            l.cover[0] = 0f; l.cover[1] = 0f; l.cover[2] = 1f; l.cover[3] = 1f
            return
        }
        val sw = w.toFloat()
        val sh = h.toFloat()
        val iw = max(1f, p.srcW)
        val ih = max(1f, p.srcH)
        val xo = if (l.scroll) xOffset.coerceIn(0f, 1f) else 0.5f

        val cs = max(sw / iw, sh / ih)
        val cw = iw * cs / sw
        val ch = ih * cs / sh
        val cx0 = (1f - cw) * xo
        val cy0 = (1f - ch) * 0.5f
        l.cover[0] = cx0; l.cover[1] = cy0; l.cover[2] = cx0 + cw; l.cover[3] = cy0 + ch

        when (p.mode) {
            ScaleMode.FILL -> {
                l.rect[0] = l.cover[0]; l.rect[1] = l.cover[1]; l.rect[2] = l.cover[2]; l.rect[3] = l.cover[3]
            }
            ScaleMode.FIT -> {
                val fs = min(sw / iw, sh / ih)
                val fw = iw * fs / sw
                val fh = ih * fs / sh
                l.rect[0] = (1f - fw) / 2f; l.rect[1] = (1f - fh) / 2f
                l.rect[2] = l.rect[0] + fw; l.rect[3] = l.rect[1] + fh
            }
            ScaleMode.STRETCH -> {
                l.rect[0] = 0f; l.rect[1] = 0f; l.rect[2] = 1f; l.rect[3] = 1f
            }
            ScaleMode.NONE -> {
                val nw = iw / sw
                val nh = ih / sh
                l.rect[0] = (1f - nw) / 2f; l.rect[1] = (1f - nh) / 2f
                l.rect[2] = l.rect[0] + nw; l.rect[3] = l.rect[1] + nh
            }
        }
    }

    private fun upload(l: Layer) {
        val p = l.prepared ?: run { l.dirty = false; return }
        l.tex = texFrom(p.sharp)
        l.fog = texFrom(p.fog)
        l.texGen = gen
        l.dirty = false
    }

    private fun texFrom(b: Bitmap): Int {
        if (b.isRecycled) return 0
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0)
        return ids[0]
    }

    private fun makeBlank(): Int {
        val b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFF000000.toInt())
        return texFrom(b)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES30.glGetShaderInfoLog(s))
            GLES30.glDeleteShader(s)
            return 0
        }
        return s
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        if (vs == 0 || fs == 0) return 0
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (ok[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES30.glGetProgramInfoLog(p))
            GLES30.glDeleteProgram(p)
            return 0
        }
        return p
    }

    companion object {
        private const val TAG = "AmeRenderer"
        private val UNIFORMS = listOf(
            "uTexA", "uFogA", "uRectA", "uCoverA", "uFxA",
            "uTexB", "uFogB", "uRectB", "uCoverB", "uFxB",
            "uMix", "uRes", "uTime", "uRain", "uDrops", "uSize", "uFog", "uRefr", "uTrail",
        )
    }
}
