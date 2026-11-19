package com.mx5.dash

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestBtPermissions()

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter: BluetoothAdapter? = btManager.adapter

        setContent {
            DashboardScreen(btAdapter = btAdapter, sppUuid = sppUuid)
        }
    }

    private fun requestBtPermissions() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DashboardScreen(btAdapter: BluetoothAdapter?, sppUuid: UUID) {
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf("Disconnesso") }
    var isConnected by remember { mutableStateOf(false) }
    var isDemo by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }

    var rpm by remember { mutableIntStateOf(0) }
    var speed by remember { mutableIntStateOf(0) }
    var coolantTemp by remember { mutableIntStateOf(0) }
    var intakeTemp by remember { mutableIntStateOf(0) }
    var voltage by remember { mutableDoubleStateOf(0.0) }
    var engineLoad by remember { mutableIntStateOf(0) }

    var obdJob by remember { mutableStateOf<Job?>(null) }
    var activeSocket by remember { mutableStateOf<BluetoothSocket?>(null) }

    fun stopAll() {
        obdJob?.cancel()
        try { activeSocket?.close() } catch (_: Exception) {}
        activeSocket = null
        isConnected = false
        isDemo = false
        statusText = "Disconnesso"
    }

    fun startDemo() {
        stopAll()
        isDemo = true
        statusText = "Modalità Demo"
        obdJob = scope.launch(Dispatchers.Default) {
            var t = 0.0
            while (isActive) {
                t += 0.15
                rpm = (3200 + 2800 * sin(t)).toInt().coerceIn(850, 6500)
                speed = (rpm / 45).coerceIn(0, 180)
                coolantTemp = (88 + 4 * sin(t * 0.2)).toInt()
                intakeTemp = (26 + 3 * sin(t * 0.1)).toInt()
                voltage = 14.1 + 0.2 * sin(t * 0.3)
                engineLoad = (45 + 35 * sin(t)).toInt().coerceIn(10, 95)
                delay(80)
            }
        }
    }

    fun connectObd(device: BluetoothDevice) {
        stopAll()
        statusText = "Connessione a ${device.name}..."
        obdJob = scope.launch(Dispatchers.IO) {
            try {
                btAdapter?.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket.connect()
                activeSocket = socket
                val input = socket.inputStream
                val output = socket.outputStream

                statusText = "Inizializzazione ECU Mazda..."
                sendCommand(output, input, "ATZ", 600)
                sendCommand(output, input, "ATE0", 150)
                sendCommand(output, input, "ATL0", 150)
                sendCommand(output, input, "ATS0", 150)
                sendCommand(output, input, "ATSP6", 200)

                isConnected = true
                statusText = "Connesso: ${device.name}"

                while (isActive) {
                    parseResponse(sendCommand(output, input, "010C", 45))?.let { bytes ->
                        if (bytes.size >= 4) rpm = ((bytes[2] * 256) + bytes[3]) / 4
                    }
                    parseResponse(sendCommand(output, input, "0105", 45))?.let { bytes ->
                        if (bytes.size >= 3) coolantTemp = bytes[2] - 40
                    }
                    parseResponse(sendCommand(output, input, "010D", 45))?.let { bytes ->
                        if (bytes.size >= 3) speed = bytes[2]
                    }
                    parseResponse(sendCommand(output, input, "0142", 45))?.let { bytes ->
                        if (bytes.size >= 4) voltage = ((bytes[2] * 256) + bytes[3]) / 1000.0
                    }
                    parseResponse(sendCommand(output, input, "010F", 45))?.let { bytes ->
                        if (bytes.size >= 3) intakeTemp = bytes[2] - 40
                    }
                    parseResponse(sendCommand(output, input, "0104", 45))?.let { bytes ->
                        if (bytes.size >= 3) engineLoad = (bytes[2] * 100) / 255
                    }
                }
            } catch (e: Exception) {
                statusText = "Errore: ${e.localizedMessage ?: "Connessione fallita"}"
                isConnected = false
            }
        }
    }

    val rpmFraction = (rpm / 6500f).coerceIn(0f, 1f)
    val shiftColor = when {
        rpm >= 6200 -> Color(0xFFFF1744)
        rpm >= 5200 -> Color(0xFFFFAB00)
        else -> Color(0xFF00E676)
    }
    val coolantColor = when {
        coolantTemp >= 102 -> Color(0xFFFF1744)
        coolantTemp >= 96 -> Color(0xFFFFAB00)
        coolantTemp in 1..65 -> Color(0xFF29B6F6)
        else -> Color.White
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MX-5 NC1  |  $statusText",
                color = if (isConnected) Color(0xFF00E676) else Color(0xFFB0BEC5),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    Button(
                        onClick = { showDevices = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                    ) {
                        Text("Connetti OBD", fontSize = 12.sp)
                    }
                    DropdownMenu(expanded = showDevices, onDismissRequest = { showDevices = false }) {
                        val paired = btAdapter?.bondedDevices?.toList().orEmpty()
                        if (paired.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Nessun dispositivo accoppiato") },
                                onClick = { showDevices = false }
                            )
                        } else {
                            paired.forEach { dev ->
                                DropdownMenuItem(
                                    text = { Text(dev.name ?: dev.address) },
                                    onClick = {
                                        showDevices = false
                                        connectObd(dev)
                                    }
                                )
                            }
                        }
                    }
                }
                Button(
                    onClick = { if (isDemo || isConnected) stopAll() else startDemo() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDemo || isConnected) Color(0xFFD32F2F) else Color(0xFF37474F)
                    )
                ) {
                    Text(if (isDemo || isConnected) "Stop" else "Demo", fontSize = 12.sp)
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("GIRI MOTORE", color = Color.Gray, fontSize = 12.sp)
                Text("$rpm RPM", color = shiftColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(6.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(rpmFraction)
                        .fillMaxHeight()
                        .background(shiftColor, RoundedCornerShape(6.dp))
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GaugeBox("ACQUA", "$coolantTemp°C", coolantColor, Modifier.weight(1f).fillMaxHeight())
            GaugeBox("VELOCITÀ", "$speed", Color.White, Modifier.weight(1f).fillMaxHeight(), "km/h")
            GaugeBox("BATTERIA", String.format("%.1f V", voltage), Color(0xFF81C784), Modifier.weight(1f).fillMaxHeight())
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(0.85f).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GaugeBox("ARIA ASPIRATA (IAT)", "$intakeTemp°C", Color(0xFF4FC3F7), Modifier.weight(1f).fillMaxHeight())
            GaugeBox("CARICO MOTORE", "$engineLoad%", Color(0xFFFFD54F), Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
fun GaugeBox(title: String, value: String, valueColor: Color, modifier: Modifier = Modifier, sub: String = "") {
    Column(
        modifier = modifier
            .background(Color(0xFF121212), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = title, color = Color(0xFF90A4AE), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(text = value, color = valueColor, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        if (sub.isNotEmpty()) {
            Text(text = sub, color = Color.Gray, fontSize = 12.sp)
        }
    }
}

private suspend fun sendCommand(out: OutputStream, input: InputStream, cmd: String, waitMs: Long): String {
    while (input.available() > 0) input.read()
    out.write("$cmd\r".toByteArray())
    out.flush()
    delay(waitMs)
    val sb = StringBuilder()
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < 300) {
        if (input.available() > 0) {
            val c = input.read().toChar()
            if (c == '>') break
            sb.append(c)
        } else {
            delay(10)
        }
    }
    return sb.toString().replace("\\s".toRegex(), "")
}

private fun parseResponse(raw: String): List<Int>? {
    val idx = raw.indexOf("41")
    if (idx == -1) return null
    val hex = raw.substring(idx)
    if (hex.length < 6) return null
    return try {
        hex.chunked(2).map { it.toInt(16) }
    } catch (_: Exception) {
        null
    }
}
