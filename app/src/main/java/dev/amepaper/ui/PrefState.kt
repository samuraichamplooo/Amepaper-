package dev.amepaper.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.amepaper.data.BoolKey
import dev.amepaper.data.FloatKey
import dev.amepaper.data.IntKey
import dev.amepaper.data.LongKey
import dev.amepaper.data.Prefs
import dev.amepaper.data.StrKey

/** A setting that Compose can read and write; it also updates when something else changes it. */
class PrefHandle<T>(
    private val sp: SharedPreferences,
    private val reader: (SharedPreferences) -> T,
    private val writer: (SharedPreferences.Editor, T) -> Unit,
) {
    private val state: MutableState<T> = mutableStateOf(reader(sp))

    var value: T
        get() = state.value
        set(v) {
            state.value = v
            val e = sp.edit()
            writer(e, v)
            e.apply()
        }

    fun reload() {
        state.value = reader(sp)
    }
}

@Composable
private fun <T> rememberPref(
    key: String,
    reader: (SharedPreferences) -> T,
    writer: (SharedPreferences.Editor, T) -> Unit,
): PrefHandle<T> {
    val ctx = LocalContext.current
    val sp = remember { Prefs.sp(ctx) }
    val handle = remember(key) { PrefHandle(sp, reader, writer) }
    DisposableEffect(key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (k == null || k == key) handle.reload()
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return handle
}

@Composable
fun pref(k: BoolKey): PrefHandle<Boolean> =
    rememberPref(k.name, { it.getBoolean(k.name, k.def) }, { e, v -> e.putBoolean(k.name, v) })

@Composable
fun pref(k: IntKey): PrefHandle<Int> =
    rememberPref(k.name, { it.getInt(k.name, k.def) }, { e, v -> e.putInt(k.name, v) })

@Composable
fun pref(k: LongKey): PrefHandle<Long> =
    rememberPref(k.name, { it.getLong(k.name, k.def) }, { e, v -> e.putLong(k.name, v) })

@Composable
fun pref(k: FloatKey): PrefHandle<Float> =
    rememberPref(k.name, { it.getFloat(k.name, k.def) }, { e, v -> e.putFloat(k.name, v) })

@Composable
fun pref(k: StrKey): PrefHandle<String> =
    rememberPref(k.name, { it.getString(k.name, k.def) ?: k.def }, { e, v -> e.putString(k.name, v) })
