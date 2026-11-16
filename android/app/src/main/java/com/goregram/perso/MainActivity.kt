package com.goregram.perso

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GoregramApp() }
    }
}

private val httpClient = OkHttpClient()
private val jsonType = "application/json; charset=utf-8".toMediaType()

private suspend fun apiCall(path: String, method: String = "GET", token: String = "", payload: JSONObject? = null): JSONObject =
    withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/" + path.trimStart('/'))
        if (token.isNotBlank()) builder.addHeader("Authorization", "Bearer $token")
        when (method) {
            "POST" -> builder.post((payload ?: JSONObject()).toString().toRequestBody(jsonType))
            "PATCH" -> builder.patch((payload ?: JSONObject()).toString().toRequestBody(jsonType))
            else -> builder.get()
        }
        httpClient.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val data = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            if (!response.isSuccessful) throw IllegalStateException(data.optString("message", data.optString("error", "Erreur serveur")))
            data
        }
    }

private suspend fun getMessages(token: String, recipientId: String): List<JSONObject> = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/messages/$recipientId")
        .header("Authorization", "Bearer $token")
        .get().build()
    httpClient.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException("Chargement des messages impossible")
        val rows = JSONArray(raw)
        (0 until rows.length()).map { rows.getJSONObject(it) }
    }
}

@Composable
fun GoregramApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("goregram_session", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var myId by remember { mutableStateOf(prefs.getString("user_id", "") ?: "") }
    var myName by remember { mutableStateOf(prefs.getString("display_name", "") ?: "") }
    var phone by remember { mutableStateOf("+225") }
    var code by remember { mutableStateOf("") }
    var otpRequested by remember { mutableStateOf(false) }
    var recipientPhone by remember { mutableStateOf("+225") }
    var recipientId by remember { mutableStateOf("") }
    var recipientName by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<JSONObject>()) }
    var status by remember { mutableStateOf("Bienvenue sur ta messagerie indépendante.") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(token) {
        if (token.isNotBlank()) {
            try {
                val me = apiCall("me", token = token)
                myId = me.optString("id")
                myName = me.optString("display_name", "Utilisateur")
                prefs.edit().putString("user_id", myId).putString("display_name", myName).apply()
                status = "Connecté en tant que $myName"
            } catch (e: Exception) {
                token = ""
                prefs.edit().clear().apply()
                status = e.message ?: "Session expirée"
            }
        }
    }

    LaunchedEffect(token, recipientId) {
        if (token.isNotBlank() && recipientId.isNotBlank()) {
            val wsUrl = BuildConfig.API_BASE_URL.trimEnd('/')
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://") + "/ws"
            val wsRequest = Request.Builder().url(wsUrl).header("Authorization", "Bearer $token").build()
            val socket = httpClient.newWebSocket(wsRequest, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("message") && text.contains("type")) {
                        scope.launch {
                            try { messages = getMessages(token, recipientId) } catch (_: Exception) { }
                        }
                    }
                }
            })
            try {
                while (true) {
                    delay(12000)
                    try { messages = getMessages(token, recipientId) } catch (_: Exception) { }
                }
            } finally {
                socket.close(1000, "Conversation closed")
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF19C37D),
        background = androidx.compose.ui.graphics.Color(0xFF071A14),
        surface = androidx.compose.ui.graphics.Color(0xFF102A20)
    )) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (token.isBlank()) {
                Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.Center) {
                    Text("GOREGRAM", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    Text("PERSO · Ta messagerie, ton serveur", color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(24.dp))
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Téléphone (+225…)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                    if (otpRequested) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code SMS à 6 chiffres") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        scope.launch {
                            busy = true
                            try {
                                if (!otpRequested) {
                                    apiCall("auth/request-otp", "POST", payload = JSONObject().put("phone", phone.trim()))
                                    otpRequested = true
                                    status = "Code demandé. Consulte tes SMS."
                                } else {
                                    val result = apiCall("auth/verify-otp", "POST", payload = JSONObject().put("phone", phone.trim()).put("code", code))
                                    token = result.getString("token")
                                    val user = result.getJSONObject("user")
                                    myId = user.getString("id")
                                    myName = user.optString("display_name", "Utilisateur")
                                    prefs.edit().putString("token", token).putString("user_id", myId).putString("display_name", myName).apply()
                                    status = "Compte connecté."
                                }
                            } catch (e: Exception) { status = e.message ?: "Connexion impossible" }
                            busy = false
                        }
                    }) { Text(if (busy) "Patiente…" else if (otpRequested) "Vérifier le code" else "Recevoir mon code SMS") }
                    Spacer(Modifier.height(12.dp))
                    Text(status, color = MaterialTheme.colorScheme.onBackground)
                }
            } else {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("GOREGRAM PERSO", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                            Text("Connecté · $myName", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            token = ""
                            prefs.edit().clear().apply()
                            recipientId = ""
                            messages = emptyList()
                        }) { Text("Quitter") }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = myName, onValueChange = { myName = it.take(40) }, label = { Text("Mon nom") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        scope.launch {
                            try {
                                val me = apiCall("me", "PATCH", token, JSONObject().put("display_name", myName))
                                myName = me.optString("display_name", myName)
                                prefs.edit().putString("display_name", myName).apply()
                                status = "Profil enregistré."
                            } catch (e: Exception) { status = e.message ?: "Erreur profil" }
                        }
                    }) { Text("Enregistrer mon profil") }
                    HorizontalDivider()
                    Text("Nouvelle conversation", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = recipientPhone, onValueChange = { recipientPhone = it }, label = { Text("Numéro du correspondant (+225…)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val encoded = java.net.URLEncoder.encode(recipientPhone.trim(), "UTF-8")
                                val found = apiCall("users/lookup?phone=$encoded", token = token)
                                recipientId = found.getString("id")
                                recipientName = found.optString("display_name", recipientPhone)
                                messages = getMessages(token, recipientId)
                                status = "Conversation avec $recipientName"
                            } catch (e: Exception) { status = e.message ?: "Utilisateur introuvable" }
                            busy = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Rechercher et ouvrir") }
                    if (recipientId.isNotBlank()) {
                        Text("Conversation : $recipientName", style = MaterialTheme.typography.titleMedium)
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(messages) { msg ->
                                val mine = msg.optString("sender_id") == myId
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                                    Text(msg.optString("body"), Modifier.padding(12.dp), color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                        OutlinedTextField(value = draft, onValueChange = { draft = it.take(10000) }, label = { Text("Écrire un message…") }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = draft.isNotBlank() && !busy, onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    apiCall("messages", "POST", token, JSONObject().put("recipient_id", recipientId.toLong()).put("body", draft.trim()))
                                    messages = getMessages(token, recipientId)
                                    draft = ""
                                    status = "Message envoyé."
                                } catch (e: Exception) { status = e.message ?: "Envoi impossible" }
                                busy = false
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Envoyer") }
                        TextButton(onClick = {
                            scope.launch {
                                try { messages = getMessages(token, recipientId); status = "Messages actualisés." }
                                catch (e: Exception) { status = e.message ?: "Erreur" }
                            }
                        }) { Text("Actualiser les messages") }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }
}
