package com.vertext.screenshare

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    private val BACKEND_URL = "https://vertextlive.onrender.com"
    private val TAG = "MainActivity"

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private var pendingPin = ""

    private val screenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                val serviceIntent = Intent(this, ScreenShareService::class.java).apply {
                    action = ScreenShareService.ACTION_START
                    putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenShareService.EXTRA_RESULT_DATA, result.data)
                    putExtra(ScreenShareService.EXTRA_BACKEND_URL, BACKEND_URL)
                    putExtra(ScreenShareService.EXTRA_PIN, pendingPin)
                }
                startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service: ${e.message}", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF080808)) {
                    StreamerUI(
                        backendUrl = BACKEND_URL,
                        onStartStream = { pin ->
                            pendingPin = pin
                            try { screenLauncher.launch(projectionManager.createScreenCaptureIntent()) }
                            catch (e: Exception) { Log.e(TAG, "Launch error: ${e.message}", e) }
                        },
                        onStopStream = {
                            try { startService(Intent(this, ScreenShareService::class.java).apply { action = ScreenShareService.ACTION_STOP }) }
                            catch (e: Exception) { Log.e(TAG, "Stop error: ${e.message}", e) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun StreamerUI(backendUrl: String, onStartStream: (pin: String) -> Unit, onStopStream: () -> Unit) {
    val scope = rememberCoroutineScope()
    var roomName    by remember { mutableStateOf("") }
    var pin         by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var isLoading   by remember { mutableStateOf(false) }
    var statusMsg   by remember { mutableStateOf("") }
    var isError     by remember { mutableStateOf(false) }

    val red = Color(0xFFFF2D2D); val dark = Color(0xFF080808)
    val panel = Color(0xFF101010); val muted = Color(0xFF444444)

    Column(modifier = Modifier.fillMaxSize().background(dark).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(52.dp))
        Text("VERTEXT LIVE", fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp, color = Color.White)
        Text("STREAMER", fontSize = 10.sp, letterSpacing = 5.sp, color = red)
        Spacer(Modifier.height(36.dp))

        Box(modifier = Modifier.fillMaxWidth().background(panel, RoundedCornerShape(6.dp)).padding(28.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("YOUR STREAM PIN", fontSize = 9.sp, letterSpacing = 3.sp, color = muted)
                Spacer(Modifier.height(12.dp))
                if (pin.isEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(8) { Box(modifier = Modifier.size(30.dp, 42.dp).background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) { Text("–", fontSize = 18.sp, color = muted, fontWeight = FontWeight.Bold) } }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pin.forEach { digit -> Box(modifier = Modifier.size(30.dp, 48.dp).background(Color(0xFF1A0000), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) { Text(digit.toString(), fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Black) } }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Share this PIN with viewers", fontSize = 10.sp, color = muted, letterSpacing = 1.sp)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        OutlinedTextField(value = roomName, onValueChange = { roomName = it }, label = { Text("Stream Name") }, placeholder = { Text("e.g. my-channel") }, enabled = !isStreaming && !isLoading, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = red, focusedLabelColor = red, cursorColor = red))

        if (statusMsg.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(statusMsg, fontSize = 11.sp, color = if (isError) red else muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        Spacer(Modifier.height(20.dp))

        if (!isStreaming) {
            Button(onClick = {
                val name = roomName.trim()
                if (name.isEmpty()) { statusMsg = "Please enter a stream name"; isError = true; return@Button }
                isError = false; isLoading = true; statusMsg = "Connecting to server..."
                scope.launch {
                    try {
                        val generatedPin = withContext(Dispatchers.IO) { generatePin(backendUrl, name) }
                        pin = generatedPin; isLoading = false; isStreaming = true; statusMsg = ""; isError = false
                        onStartStream(generatedPin)
                    } catch (e: Exception) {
                        isLoading = false; isError = true
                        statusMsg = when {
                            e.message?.contains("timeout", ignoreCase = true) == true -> "Server timeout — Render may be waking up, try again in 30s"
                            e.message?.contains("refused", ignoreCase = true) == true -> "Cannot reach server"
                            else -> "Error: ${e.message ?: "Unknown"}"
                        }
                        Log.e("StreamerUI", "PIN generation failed", e)
                    }
                }
            }, enabled = !isLoading, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = red), shape = RoundedCornerShape(4.dp)) {
                if (isLoading) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp); Text("CONNECTING...", fontWeight = FontWeight.Bold, letterSpacing = 2.sp) } }
                else Text("GENERATE PIN & GO LIVE", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
        } else {
            Button(onClick = { isStreaming = false; pin = ""; statusMsg = ""; onStopStream() }, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(4.dp)) { Text("STOP STREAM", fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = red) }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(8.dp).background(red, RoundedCornerShape(50))); Spacer(Modifier.width(8.dp))
                Text("LIVE — $roomName", fontSize = 11.sp, color = red, letterSpacing = 2.sp)
            }
        }
    }
}

fun generatePin(backendUrl: String, roomName: String): String {
    val conn = URL("$backendUrl/pin/generate").openConnection() as HttpURLConnection
    return try {
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write("""{"room_name":"${roomName.replace("\"","")}"}""") }
        val code = conn.responseCode
        if (code != 200) throw Exception("Server returned $code")
        val p = JSONObject(BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).readText()).getString("pin")
        if (p.length != 8 || !p.all { it.isDigit() }) throw Exception("Invalid PIN format")
        p
    } finally { conn.disconnect() }
}
