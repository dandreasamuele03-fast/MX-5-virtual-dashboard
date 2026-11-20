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
    var oilTemp by remember { mutableIntStateOf(-999) }
    var kmPerLiter by remember { mutableDoubleStateOf(0.0) }
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
                oilTemp = (96 + 5 * sin(t * 0.15)).toInt()
                kmPerLiter = (13.5 + 5.5 * sin(t * 0.4)).coerceIn(3.5, 28.0)
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

                var useExtendedOilPid = true

                while (isActive) {
                    // 1. Giri Motore (010C)
                    parseMode01(sendCommand(output, input, "010C", 40))?.let { b ->
                        if (b.size >= 4) rpm = ((b[2] * 256) + b[3]) / 4
                    }

                    // 2. Temperatura Acqua (0105)
                    parseMode01(sendCommand(output, input, "0105", 40))?.let { b ->
                        if (b.size >= 3) coolantTemp = b[2] - 40
                    }

                    // 3. Apertura Farfalla (0111)
                    parseMode01(sendCommand(output, input, "0111", 40))?.let { b ->
                        if (b.size >= 3) throttlePos = (b[2] * 100) / 255
                    }

                    // 4. Consumo Istantaneo (Velocità 010D + MAF 0110)
                    var currentSpeed = 0
                    var mafGramsSec = 0.0
                    parseMode01(sendCommand(output, input, "010D", 40))?.let { b ->
                        if (b.size >= 3) currentSpeed = b[2]
                    }
                    parseMode01(sendCommand(output, input, "0110", 40))?.let { b ->
                        if (b.size >= 4) mafGramsSec = ((b[2] * 256) + b[3]) / 100.0
                    }
                    kmPerLiter = if (currentSpeed > 2 && mafGramsSec > 0.1) {
                        val litersPerHour = mafGramsSec * 0.3287
                        (currentSpeed / litersPerHour).coerceIn(0.0, 50.0)
                    } else {
                        0.0
                    }

                    // 5. Temperatura Aria Aspirata IAT (010F)
                    parseMode01(sendCommand(output, input, "010F", 40))?.let { b ->
                        if (b.size >= 3) intakeTemp = b[2] - 40
                    }

                    // 6. Carico Motore (0104)
                    parseMode01(sendCommand(output, input, "0104", 40))?.let { b ->
                        if (b.size >= 3) engineLoad = (b[2] * 100) / 255
                    }

                    // 7. Temperatura Olio Motore (PID Mazda 221310 o Standard 015C)
                    if (useExtendedOilPid) {
                        val rawOil = sendCommand(output, input, "221310", 45)
                        val parsedExt = parseMode22(rawOil, "621310")
                        if (parsedExt != null && parsedExt.size >= 4) {
                            val valC = ((parsedExt[3] * 256) + (parsedExt.getOrElse(4) { 0 })) / 100
                            oilTemp = if (valC in -20..180) valC else (parsedExt[3] - 40)
                        } else {
                            useExtendedOilPid = false
                        }
                    } else {
                        parseMode01(sendCommand(output, input, "015C", 40))?.let { b ->
                            if (b.size >= 3) oilTemp = b[2] - 40
                        }
                    }
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
    val oilColor = when {
        oilTemp >= 120 -> Color(0xFFFF1744)
        oilTemp >= 110 -> Color(0xFFFFAB00)
        oilTemp in 1..65 -> Color(0xFF29B6F6)
        else -> Color(0xFFFF9100)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Barra superiore: Stato e pulsanti
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

        // Barra LED Giri Motore
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

        // Riga 1: Temperatura Acqua + Temperatura Olio
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
                title = "TEMP. OLIO",
                value = if (oilTemp > -100) "$oilTemp°C" else "--°C",
                valueColor = oilColor,
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
                value = if (kmPerLiter > 0.1) String.format(Locale.US, "%.1f", kmPerLiter) else "0.0",
                valueColor = Color(0xFF00E676),
                sub = "km/l",
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

private fun parseMode01(raw: String): List<Int>? {
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

private fun parseMode22(raw: String, header: String): List<Int>? {
    val idx = raw.indexOf(header)
    if (idx == -1) return null
    val hex = raw.substring(idx)
    if (hex.length < 8) return null
    return try {
        hex.chunked(2).map { it.toInt(16) }
    } catch (_: Exception) {
        null
    }
}
