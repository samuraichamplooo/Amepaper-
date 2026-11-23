package dev.amepaper.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.amepaper.ai.AiFill
import dev.amepaper.data.K
import kotlinx.coroutines.launch

const val CF_TOKEN_URL = "https://dash.cloudflare.com/profile/api-tokens"
const val GEMINI_KEY_URL = "https://aistudio.google.com/apikey"

fun openUrl(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Cloudflare (FLUX) sign-in fields, shared by Settings and the editor's setup dialog. */
@Composable
private fun FluxFields() {
    val ctx = LocalContext.current
    val account = pref(K.CF_ACCOUNT)
    val token = pref(K.CF_TOKEN)
    val model = pref(K.CF_MODEL)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Free with a Cloudflare account: 10,000 \"neurons\" a day, no card needed. " +
                "1. Sign up at cloudflare.com. 2. My Profile → API Tokens → Create Token → \"Workers AI\" template. " +
                "3. Paste the token and your account ID (the long code in the dashboard's web address).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { openUrl(ctx, CF_TOKEN_URL) }) { Text("Open Cloudflare") }
        OutlinedTextField(
            value = account.value,
            onValueChange = { account.value = it.trim() },
            label = { Text("Account ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token.value,
            onValueChange = { token.value = it.trim() },
            label = { Text("API token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiFill.FLUX_MODELS.forEach { (id, name) ->
                FilterChip(selected = model.value == id, onClick = { model.value = id }, label = { Text(name) })
            }
        }
        Text(
            "Your framing is sent to Cloudflare only when you tap Generate. Cloudflare lists these models as zero data retention.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Gemini key and model, shared by Settings and the editor's setup dialog. */
@Composable
private fun GeminiFields() {
    val ctx = LocalContext.current
    val key = pref(K.GEMINI_KEY)
    val model = pref(K.GEMINI_MODEL)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Best quality, paid per fill on your own Google account (Google has no free tier for image models). " +
                "Create a key in Google AI Studio and turn on billing for it; Google bills you directly.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { openUrl(ctx, GEMINI_KEY_URL) }) { Text("Open AI Studio") }
        OutlinedTextField(
            value = key.value,
            onValueChange = { key.value = it.trim() },
            label = { Text("Gemini API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiFill.GEMINI_MODELS.forEach { (id, name) ->
                FilterChip(selected = model.value == id, onClick = { model.value = id }, label = { Text(name) })
            }
        }
        Text(
            "Your framing is sent to Google only when you tap Generate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** On-device model status with download / delete. */
@Composable
private fun DeviceModelRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(AiFill.modelReady(ctx)) }
    var progress by remember { mutableFloatStateOf(-1f) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Runs offline on your phone's GPU in about a second. Plainer than FLUX or Gemini, but free and private.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    progress >= 0f -> "Downloading… ${(progress * 100).toInt()}%"
                    ready -> "Model downloaded"
                    else -> "Not downloaded (${AiFill.MODEL_MB} MB)"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (progress < 0f) {
                if (ready) {
                    TextButton(onClick = {
                        AiFill.deleteModel(ctx)
                        ready = false
                    }) { Text("Delete") }
                } else {
                    TextButton(onClick = {
                        progress = 0f
                        scope.launch {
                            try {
                                AiFill.downloadModel(ctx) { p -> progress = p }
                                ready = true
                            } catch (e: Exception) {
                                Toast.makeText(ctx, e.message ?: "Download failed", Toast.LENGTH_LONG).show()
                            }
                            progress = -1f
                        }
                    }) { Text("Download") }
                }
            }
        }
        if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

/** Settings → AI fill. */
@Composable
fun AiSettingsSection() {
    var open by remember { mutableIntStateOf(-1) }
    SectionLabel("AI fill")
    GroupCard {
        val cf = pref(K.CF_TOKEN).value.isNotBlank() && pref(K.CF_ACCOUNT).value.isNotBlank()
        val gm = pref(K.GEMINI_KEY).value.isNotBlank()
        val cfModel = pref(K.CF_MODEL).value
        val gmModel = pref(K.GEMINI_MODEL).value
        NavRow("FLUX · free (Cloudflare)", if (cf) "Set up · ${modelName(AiFill.FLUX_MODELS, cfModel)}" else "Not set up") { open = 1 }
        RowDivider()
        NavRow("Gemini · paid", if (gm) "Set up · ${modelName(AiFill.GEMINI_MODELS, gmModel)}" else "Not set up") { open = 2 }
        RowDivider()
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("On this phone", style = MaterialTheme.typography.titleSmall)
            DeviceModelRow()
        }
    }
    when (open) {
        1 -> AiSetupDialog(AiFill.Engine.FLUX, onDone = { open = -1 })
        2 -> AiSetupDialog(AiFill.Engine.GEMINI, onDone = { open = -1 })
    }
}

private fun modelName(list: List<Pair<String, String>>, id: String?): String =
    list.firstOrNull { it.first == id }?.second?.substringBefore(" ·") ?: (id ?: "")

/** Setup for a cloud engine; used from Settings and straight from the editor. */
@Composable
fun AiSetupDialog(engine: AiFill.Engine, onDone: () -> Unit, confirm: String = "Done") {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (engine == AiFill.Engine.FLUX) "FLUX (free, via Cloudflare)" else "Gemini (paid)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (engine == AiFill.Engine.FLUX) FluxFields() else GeminiFields()
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(confirm) } },
    )
}
