package dev.amepaper.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A crop chosen in the editor, stored as fractions (0..1) of the image. */
data class Crop(val aspect: String, val l: Float, val t: Float, val r: Float, val b: Float) {
    fun toJson(): JSONObject = JSONObject()
        .put("a", aspect).put("l", l.toDouble()).put("t", t.toDouble())
        .put("r", r.toDouble()).put("b", b.toDouble())

    companion object {
        fun from(o: JSONObject?): Crop? {
            if (o == null) return null
            val c = Crop(
                o.optString("a", "custom"),
                o.optDouble("l", 0.0).toFloat(), o.optDouble("t", 0.0).toFloat(),
                o.optDouble("r", 1.0).toFloat(), o.optDouble("b", 1.0).toFloat(),
            )
            return if (c.r - c.l > 0.001f && c.b - c.t > 0.001f) c else null
        }
    }
}

data class WallImage(
    val id: String,
    val file: String,
    val w: Int,
    val h: Int,
    val tall: Crop? = null,
    val wide: Crop? = null,
) {
    fun crop(slot: Slot): Crop? = if (slot == Slot.TALL) tall else wide
    val hasCrop: Boolean get() = tall != null || wide != null
}

data class Album(val id: String, val name: String, val images: List<WallImage>)

object Library {
    private const val TAG = "AmeLibrary"
    private const val FILE = "library.json"

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums

    @Volatile
    private var loaded = false
    private var dir: File? = null

    fun init(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val d = ctx.applicationContext.filesDir
            dir = d
            var list = read(d)
            if (list.isEmpty()) {
                list = listOf(Album(newId(), "My walls", emptyList()))
                _albums.value = list
                save()
            } else {
                _albums.value = list
            }
            loaded = true
            val sp = Prefs.sp(ctx)
            val first = list.first().id
            val e = sp.edit()
            if (sp.get(K.HOME_ALBUM).isEmpty()) e.putString(K.HOME_ALBUM.name, first)
            if (sp.get(K.LOCK_ALBUM).isEmpty()) e.putString(K.LOCK_ALBUM.name, first)
            e.apply()
        }
    }

    fun file(img: WallImage): File = File(dir ?: File("/"), img.file)

    fun imageDir(): File = File(dir ?: File("/"), "img").also { it.mkdirs() }

    fun album(id: String): Album? = _albums.value.firstOrNull { it.id == id }

    fun createAlbum(name: String): Album {
        val a = Album(newId(), name.ifBlank { "Album" }, emptyList())
        update { it + a }
        return a
    }

    fun renameAlbum(id: String, name: String) {
        if (name.isBlank()) return
        update { list -> list.map { if (it.id == id) it.copy(name = name.trim()) else it } }
    }

    fun deleteAlbum(id: String) {
        val a = album(id) ?: return
        a.images.forEach { runCatching { file(it).delete() } }
        update { list ->
            val rest = list.filter { it.id != id }
            rest.ifEmpty { listOf(Album(newId(), "My walls", emptyList())) }
        }
    }

    fun addImage(albumId: String, img: WallImage) {
        update { list -> list.map { if (it.id == albumId) it.copy(images = it.images + img) else it } }
    }

    fun removeImage(albumId: String, imageId: String) {
        val img = album(albumId)?.images?.firstOrNull { it.id == imageId }
        update { list ->
            list.map { a -> if (a.id == albumId) a.copy(images = a.images.filter { it.id != imageId }) else a }
        }
        if (img != null) runCatching { file(img).delete() }
    }

    fun setCrops(albumId: String, imageId: String, tall: Crop?, wide: Crop?) {
        update { list ->
            list.map { a ->
                if (a.id != albumId) a
                else a.copy(images = a.images.map { if (it.id == imageId) it.copy(tall = tall, wide = wide) else it })
            }
        }
    }

    private fun update(f: (List<Album>) -> List<Album>) {
        synchronized(this) {
            _albums.value = f(_albums.value)
            save()
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun read(d: File): List<Album> {
        val f = File(d, FILE)
        if (!f.exists()) return emptyList()
        return try {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("albums") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                val imgs = a.optJSONArray("images") ?: JSONArray()
                Album(
                    id = a.getString("id"),
                    name = a.optString("name", "Album"),
                    images = (0 until imgs.length()).map { j ->
                        val o = imgs.getJSONObject(j)
                        WallImage(
                            id = o.getString("id"),
                            file = o.getString("file"),
                            w = o.optInt("w", 0),
                            h = o.optInt("h", 0),
                            tall = Crop.from(o.optJSONObject("tall")),
                            wide = Crop.from(o.optJSONObject("wide")),
                        )
                    },
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't read library", e)
            emptyList()
        }
    }

    private fun save() {
        val d = dir ?: return
        try {
            val arr = JSONArray()
            for (a in _albums.value) {
                val imgs = JSONArray()
                for (img in a.images) {
                    val o = JSONObject().put("id", img.id).put("file", img.file).put("w", img.w).put("h", img.h)
                    img.tall?.let { o.put("tall", it.toJson()) }
                    img.wide?.let { o.put("wide", it.toJson()) }
                    imgs.put(o)
                }
                arr.put(JSONObject().put("id", a.id).put("name", a.name).put("images", imgs))
            }
            val tmp = File(d, "$FILE.tmp")
            tmp.writeText(JSONObject().put("albums", arr).toString())
            if (!tmp.renameTo(File(d, FILE))) {
                File(d, FILE).writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't save library", e)
        }
    }
}
