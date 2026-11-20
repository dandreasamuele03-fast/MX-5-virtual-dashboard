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
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
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
    var coolantTemp by remember { mutableIntStateOf(0) }
    var stft by remember { mutableDoubleStateOf(0.0) }
    var ltft by remember { mutableDoubleStateOf(0.0) }
    var fuelValueText by remember { mutableStateOf("0.0") }
    var fuelUnitText by remember { mutableStateOf("L/h") }
    var throttlePos by remember { mutableIntStateOf(0) }
    var intakeTemp by remember { mutableIntStateOf(0) }
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
                coolantTemp = (89 + 4 * sin(t * 0.2)).toInt()
                stft = 3.2 * sin(t * 0.5)
                ltft = 1.6
                val simKml = (13.5 + 5.5 * sin(t * 0.4)).coerceIn(3.5, 28.0)
                fuelValueText = String.format(Locale.US, "%.1f", simKml)
                fuelUnitText = "km/l"
                throttlePos = (35 + 30 * sin(t)).toInt().coerceIn(13, 86)
                intakeTemp = (26 + 3 * sin(t * 0.1)).toInt()
                engineLoad = (45 + 35 * sin(t)).toInt().coerceIn(12, 95)
                delay(80)
            }
        }
    }

    fun connectObd(device: BluetoothDevice) {
        stopAll()
        statusText = "Connessione..."
        obdJob = scope.launch(Dispatchers.IO) {
            try {
                btAdapter?.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket.connect()
                activeSocket = socket
                val input = socket.inputStream
                val output = socket.outputStream

                statusText = "Init ECU Mazda..."
                sendCommand(output, input, "ATZ", 600)
                sendCommand(output, input, "ATE0", 150)
                sendCommand(output, input, "ATL0", 150)
                sendCommand(output, input, "ATS0", 150)
                sendCommand(output, input, "ATSP6", 200)

                isConnected = true
                statusText = "Connesso"

                var lastSpeed = 0
                var lastMafGramsSec = 0.0
                var loopCount = 0

                while (isActive) {
                    // 1. Giri Motore (010C)
                    parsePid(sendCommand(output, input, "010C", 45), "410C")?.let { b ->
                        if (b.size >= 4) rpm = ((b[2] * 256) + b[3]) / 4
                    }

                    // 2. Temperatura Acqua (0105)
                    parsePid(sendCommand(output, input, "0105", 45), "4105")?.let { b ->
                        if (b.size >= 3) coolantTemp = b[2] - 40
                    }

                    // 3. Correzione Carburante Breve Termine STFT (0106)
                    parsePid(sendCommand(output, input, "0106", 45), "4106")?.let { b ->
                        if (b.size >= 3) stft = ((b[2] - 128) * 100.0) / 128.0
                    }

                    // 4. Correzione Carburante Lungo Termine LTFT (0107 - letta ogni 5 cicli)
                    if (loopCount % 5 == 0) {
                        parsePid(sendCommand(output, input, "0107", 45), "4107")?.let { b ->
                            if (b.size >= 3) ltft = ((b[2] - 128) * 100.0) / 128.0
                        }
                    }

                    // 5. Apertura Farfalla (0111)
                    parsePid(sendCommand(output, input, "0111", 45), "4111")?.let { b ->
                        if (b.size >= 3) throttlePos = (b[2] * 100) / 255
                    }

                    // 6. Velocità (010D) + Portata Aria MAF (0110) per Consumo Istantaneo
                    parsePid(sendCommand(output, input, "010D", 45), "410D")?.let { b ->
                        if (b.size >= 3) lastSpeed = b[2]
                    }
                    parsePid(sendCommand(output, input, "0110", 45), "4110")?.let { b ->
                        if (b.size >= 4) lastMafGramsSec = ((b[2] * 256) + b[3]) / 100.0
                    }

                    if (lastMafGramsSec > 0.05) {
                        val litersPerHour = lastMafGramsSec * 0.3287
                        if (lastSpeed > 3) {
                            val kml = (lastSpeed / litersPerHour).coerceIn(0.0, 50.0)
                            fuelValueText = String.format(Locale.US, "%.1f", kml)
                            fuelUnitText = "km/l"
                        } else {
                            fuelValueText = String.format(Locale.US, "%.1f", litersPerHour)
                            fuelUnitText = "L/h"
                        }
                    }

                    // 7. Temperatura Aria Aspirata IAT (010F)
                    parsePid(sendCommand(output, input, "010F", 45), "410F")?.let { b ->
                        if (b.size >= 3) intakeTemp = b[2] - 40
                    }

                    // 8. Carico Motore (0104)
                    parsePid(sendCommand(output, input, "0104", 45), "4104")?.let { b ->
                        if (b.size >= 3) engineLoad = (b[2] * 100) / 255
                    }

                    loopCount++
                }
            } catch (e: Exception) {
                statusText = "Errore BT"
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
        else -> Color(0xFF80D8FF)
    }
    val trimColor = when {
        abs(stft) >= 12.0 -> Color(0xFFFF1744)
        abs(stft) >= 5.0 -> Color(0xFFFFAB00)
        else -> Color(0xFF00E676)
    }

    val stftFormatted = String.format(Locale.US, "%+.1f%%", stft)
    val ltftFormatted = String.format(Locale.US, "LTFT: %+.1f%%", ltft)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "MX-5 NC1",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = statusText,
                    color = if (isConnected) Color(0xFF00E676) else Color(0xFF90A4AE),
                    fontSize = 13.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    Button(
                        onClick = { showDevices = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Connetti", fontSize = 13.sp)
                    }
                    DropdownMenu(expanded = showDevices, onDismissRequest = { showDevices = false }) {
                        val paired = btAdapter?.bondedDevices?.toList().orEmpty()
                        if (paired.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Nessun dispositivo") },
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
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(if (isDemo || isConnected) "Stop" else "Demo", fontSize = 13.sp)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101010), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF262626), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text("GIRI MOTORE", color = Color(0xFF90A4AE), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("$rpm RPM", color = shiftColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(rpmFraction)
                        .fillMaxHeight()
                        .background(shiftColor, RoundedCornerShape(6.dp))
                )
            }
        }

        // Riga 1: Temperatura Acqua + Correzione Carburante (STFT / LTFT)
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GaugeBox(
                title = "TEMP. ACQUA",
                value = "$coolantTemp°C",
                valueColor = coolantColor,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            GaugeBox(
                title = "CORR. CARBURANTE",
                value = stftFormatted,
                valueColor = trimColor,
                sub = ltftFormatted,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }

        // Riga 2: Consumo Istantaneo + Apertura Farfalla
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GaugeBox(
                title = "CONSUMO IST.",
                value = fuelValueText,
                valueColor = Color(0xFF00E676),
                sub = fuelUnitText,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            GaugeBox(
                title = "FARFALLA",
                value = "$throttlePos%",
                valueColor = Color(0xFFE040FB),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }

        // Riga 3: Aria Aspirata (IAT) + Carico Motore
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GaugeBox(
                title = "ARIA ASPIRATA",
                value = "$intakeTemp°C",
                valueColor = Color(0xFF40C4FF),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            GaugeBox(
                title = "CARICO MOTORE",
                value = "$engineLoad%",
                valueColor = Color(0xFFFFD740),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

@Composable
fun GaugeBox(title: String, value: String, valueColor: Color, modifier: Modifier = Modifier, sub: String = "") {
    Column(
        modifier = modifier
            .background(Color(0xFF101010), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFF262626), RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = title, color = Color(0xFF90A4AE), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = value, color = valueColor, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        if (sub.isNotEmpty()) {
            Text(text = sub, color = Color(0xFF78909C), fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
    while (System.currentTimeMillis() - start < 250) {
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

private fun parsePid(raw: String, expectedHeader: String): List<Int>? {
    val idx = raw.indexOf(expectedHeader)
    if (idx == -1) return null
    val hex = raw.substring(idx)
    if (hex.length < 6) return null
    return try {
        hex.chunked(2).map { it.toInt(16) }
    } catch (_: Exception) {
        null
    }
}
