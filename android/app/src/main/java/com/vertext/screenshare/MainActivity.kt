package com.vertext.screenshare

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
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
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    // ── Update this after deploying to Render ─────────────────────
    private val BACKEND_URL = "https://your-vertext-backend.onrender.com"

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private var pendingPin = ""

    private val screenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startForegroundService(Intent(this, ScreenShareService::class.java).apply {
                action = ScreenShareService.ACTION_START
                putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenShareService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenShareService.EXTRA_BACKEND_URL, BACKEND_URL)
                putExtra(ScreenShareService.EXTRA_PIN, pendingPin)
            })
        } else {
            Toast.makeText(this, "Screen permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                StreamerUI(
                    backendUrl = BACKEND_URL,
                    onStartStream = { pin ->
                        pendingPin = pin
                        screenLauncher.launch(projectionManager.createScreenCaptureIntent())
                    },
                    onStopStream = {
                        startService(Intent(this, ScreenShareService::class.java).apply {
                            action = ScreenShareService.ACTION_STOP
                        })
                    }
                )
            }
        }
    }
}

@Composable
fun StreamerUI(
    backendUrl: String,
    onStartStream: (pin: String) -> Unit,
    onStopStream: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var roomName    by remember { mutableStateOf("") }
    var pin         by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var isLoading   by remember { mutableStateOf(false) }
    var error       by remember { mutableStateOf("") }

    val red   = Color(0xFFFF2D2D)
    val dark  = Color(0xFF080808)
    val panel = Color(0xFF101010)
    val muted = Color(0xFF444444)

    Column(
        modifier = Modifier.fillMaxSize().background(dark).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(52.dp))
        Text("VERTEXT LIVE", fontSize = 30.sp, fontWeight = FontWeight.Black,
            letterSpacing = 6.sp, color = Color.White)
        Text("STREAMER", fontSize = 10.sp, letterSpacing = 5.sp, color = red)
        Spacer(Modifier.height(36.dp))

        // PIN Display
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(panel, RoundedCornerShape(6.dp)).padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("YOUR STREAM PIN", fontSize = 9.sp, letterSpacing = 3.sp, color = muted)
                Spacer(Modifier.height(12.dp))
                if (pin.isEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(8) {
                            Box(
                                modifier = Modifier.size(30.dp, 42.dp)
                                    .background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center
                            ) { Text("–", fontSize = 18.sp, color = muted, fontWeight = FontWeight.Bold) }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pin.forEach { digit ->
                            Box(
                                modifier = Modifier.size(30.dp, 48.dp)
                                    .background(Color(0xFF1A0000), RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center
                            ) { Text(digit.toString(), fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Black) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Share this PIN with viewers", fontSize = 10.sp, color = muted, letterSpacing = 1.sp)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = roomName,
            onValueChange = { roomName = it },
            label = { Text("Stream Name") },
            placeholder = { Text("e.g. my-channel") },
            enabled = !isStreaming && !isLoading,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = red, focusedLabelColor = red)
        )

        if (error.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(error, fontSize = 11.sp, color = red, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(20.dp))

        if (!isStreaming) {
            Button(
                onClick = {
                    if (roomName.isBlank()) { error = "Enter a stream name first"; return@Button }
                    error = ""; isLoading = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            val conn = (URL("$backendUrl/pin/generate").openConnection()
                                    as HttpURLConnection).apply {
                                requestMethod = "POST"
                                setRequestProperty("Content-Type", "application/json")
                                doOutput = true
                                outputStream.write("""{"room_name":"$roomName"}""".toByteArray())
                            }
                            val json = JSONObject(conn.inputStream.bufferedReader().readText())
                            val generatedPin = json.getString("pin")
                            withContext(Dispatchers.Main) {
                                pin = generatedPin
                                isLoading = false
                                isStreaming = true
                                onStartStream(generatedPin)
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                isLoading = false
                                error = "Failed: ${e.message}"
                            }
                        }
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = red),
                shape = RoundedCornerShape(4.dp)
            ) {
                if (isLoading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp))
                else Text("GENERATE PIN & GO LIVE", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
        } else {
            Button(
                onClick = { isStreaming = false; pin = ""; onStopStream() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(4.dp)
            ) { Text("STOP STREAM", fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = red) }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(8.dp).background(red, RoundedCornerShape(50)))
                Spacer(Modifier.width(8.dp))
                Text("LIVE — $roomName", fontSize = 11.sp, color = red, letterSpacing = 2.sp)
            }
        }
    }
}
