package com.goregram.perso

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GoregramApp() }
    }
}

@Composable
fun GoregramApp() {
    var phone by remember { mutableStateOf("+225") }
    var code by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Entre ton numéro au format international.") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val client = remember { OkHttpClient() }
    val jsonType = "application/json; charset=utf-8".toMediaType()

    MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF16B978), background = androidx.compose.ui.graphics.Color(0xFF071A14))) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("GOREGRAM", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                Text("PERSO · Messagerie indépendante", color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(28.dp))
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Numéro de téléphone (+225…)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                if (stage) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = code, onValueChange = { code = it.take(6).filter(Char::isDigit) }, label = { Text("Code reçu par SMS") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(16.dp))
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        status = try {
                            withContext(Dispatchers.IO) {
                                val path = if (stage) "auth/verify-otp" else "auth/request-otp"
                                val payload = JSONObject().put("phone", phone).apply { if (stage) put("code", code) }
                                val req = Request.Builder().url(BuildConfig.API_BASE_URL + path).post(payload.toString().toRequestBody(jsonType)).build()
                                client.newCall(req).execute().use { response ->
                                    val body = response.body?.string().orEmpty()
                                    if (!response.isSuccessful) throw IllegalStateException(JSONObject(body).optString("error", "Erreur serveur"))
                                    if (stage) "Compte connecté. La messagerie arrive dans la prochaine étape." else { stage = true; "Code envoyé. Vérifie tes SMS." }
                                }
                            }
                        } catch (e: Exception) { e.message ?: "Connexion impossible" }
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Patiente…" else if (stage) "Vérifier le code" else "Recevoir mon code SMS") }
                Spacer(Modifier.height(12.dp))
                Text(status, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}
