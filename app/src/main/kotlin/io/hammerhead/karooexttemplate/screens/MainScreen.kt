package io.hammerhead.karooexttemplate.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.hammerhead.karooexttemplate.R
import io.hammerhead.karooexttemplate.data.AppSettings
import io.hammerhead.karooexttemplate.data.SettingsRepository
import io.hammerhead.karooexttemplate.mqtt.MqttClientManager
import io.hammerhead.karooexttemplate.runtime.RuntimeState
import io.hammerhead.karooexttemplate.service.KarooMqttService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainScreen(settingsRepo: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val initial = remember { settingsRepo.load() }
    val appVersion = remember {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "karooMQTT v${info.versionName} (${info.versionCode})"
    }
    var settings by remember { mutableStateOf(initial) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    val mqttStatus by MqttClientManager.status.collectAsState()
    val mqttError by MqttClientManager.lastError.collectAsState()
    val serviceRunning by RuntimeState.serviceRunning.collectAsState()
    val karooStatus by RuntimeState.karooStatus.collectAsState()
    val rideState by RuntimeState.rideState.collectAsState()
    val activeProfile by RuntimeState.activeProfile.collectAsState()
    val wifiStatus by RuntimeState.wifiStatus.collectAsState()
    val lastSent by RuntimeState.lastSent.collectAsState()
    val lastSentInfo by RuntimeState.lastSentInfo.collectAsState()
    val lastPower by RuntimeState.lastPower.collectAsState()
    val lastHr by RuntimeState.lastHr.collectAsState()

    val mqttConnected = mqttStatus == MqttClientManager.Status.CONNECTED

    fun retryMqtt() {
        scope.launch {
            withContext(Dispatchers.IO) {
                MqttClientManager.disconnect()
                MqttClientManager.connect(settings)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // --- Top bar ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { (context as? Activity)?.finish() }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_back),
                    contentDescription = "Back",
                )
            }
            Text("karooMQTT", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                settingsRepo.save(settings)
                savedMessage = "Settings saved"
            }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_check),
                    contentDescription = "Save",
                    tint = Color(0xFF4CAF50),
                )
            }
        }
        savedMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        // --- Status ---
        SectionTitle("Status")
        StatusLine("Service", if (serviceRunning) "running" else "stopped")
        StatusLine("Karoo", "$karooStatus · $rideState")
        StatusLine("Profile", activeProfile)
        StatusLine("Wi-Fi", wifiStatus)
        StatusLine("Last reading", lastSent)
        StatusLine(
            "Reading",
            "Power: " + (lastPower?.let { "${Math.round(it)} W" } ?: "—") +
                " · HR: " + (lastHr?.let { "${Math.round(it)} bpm" } ?: "—"),
        )

        MqttStatusField(
            status = mqttStatus,
            error = mqttError,
            onClick = { retryMqtt() },
        )

        HorizontalDivider()

        // --- Service control ---
        SectionTitle("Service")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (serviceRunning) {
                        context.stopService(
                            android.content.Intent(context, KarooMqttService::class.java),
                        )
                    } else {
                        ContextCompat.startForegroundService(
                            context,
                            android.content.Intent(context, KarooMqttService::class.java),
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(if (serviceRunning) "Stop" else "Start")
            }
            Button(
                onClick = {
                    scope.launch {
                        testing = true
                        testResult = null
                        val ok = MqttClientManager.testConnection(settings)
                        testResult = if (ok) "Test OK — packet sent" else "Test failed"
                        testing = false
                    }
                },
                enabled = !testing && settings.mqttHost.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (testing) "Testing..." else "Test MQTT")
            }
        }
        testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        HorizontalDivider()

        // --- Data ---
        SectionTitle("Data")
        val sentInfo = lastSentInfo
        when {
            sentInfo != null -> StatusLine("Last sent", sentInfo)
            !mqttConnected -> Text(
                "mqtt offline",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            else -> StatusLine("Last sent", "—")
        }

        HorizontalDivider()

        // --- MQTT config ---
        SectionTitle("MQTT broker")
        OutlinedTextField(
            value = settings.mqttHost,
            onValueChange = { v -> settings = settings.copy(mqttHost = v) },
            label = { Text("Broker IP address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = settings.mqttPort.toString(),
                onValueChange = { v ->
                    v.toIntOrNull()?.let { port -> settings = settings.copy(mqttPort = port) }
                },
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = settings.mqttTopic,
                onValueChange = { v -> settings = settings.copy(mqttTopic = v) },
                label = { Text("Root topic") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = settings.mqttUser,
            onValueChange = { v -> settings = settings.copy(mqttUser = v) },
            label = { Text("Username (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = settings.mqttPassword,
            onValueChange = { v -> settings = settings.copy(mqttPassword = v) },
            label = { Text("Password (optional)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        // --- Averaging ---
        SectionTitle("Averaging (window)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 3, 5).forEach { sec ->
                FilterChip(
                    selected = settings.avgWindowSec == sec,
                    onClick = { settings = settings.copy(avgWindowSec = sec) },
                    label = { Text("${sec}s") },
                )
            }
        }

        HorizontalDivider()

        // --- Send interval ---
        SectionTitle("MQTT send interval")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(5, 7, 10).forEach { sec ->
                FilterChip(
                    selected = settings.sendIntervalSec == sec,
                    onClick = { settings = settings.copy(sendIntervalSec = sec) },
                    label = { Text("${sec}s") },
                )
            }
            OutlinedTextField(
                value = settings.sendIntervalSec.toString(),
                onValueChange = { v ->
                    v.toIntOrNull()?.let { sec ->
                        if (sec > 0) settings = settings.copy(sendIntervalSec = sec)
                    }
                },
                label = { Text("custom (s)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider()

        // --- Profiles ---
        SectionTitle("Ride profiles")
        Text(
            "Data is sent only while recording on a profile marked as " +
                "indoor (trainer). During such a ride the app keeps " +
                "the Wi-Fi connection active.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            appVersion,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun MqttStatusField(
    status: MqttClientManager.Status,
    error: String?,
    onClick: () -> Unit,
) {
    val (color, label) = when (status) {
        MqttClientManager.Status.CONNECTED -> Color(0xFF4CAF50) to "MQTT: connected"
        MqttClientManager.Status.CONNECTING -> Color(0xFFFFC107) to "MQTT: connecting..."
        MqttClientManager.Status.ERROR -> Color(0xFFF44336) to "MQTT: error"
        MqttClientManager.Status.DISCONNECTED -> Color(0xFFF44336) to "MQTT: disconnected"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label + (error?.let { " ($it)" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = if (status == MqttClientManager.Status.CONNECTED) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Spacer(Modifier.weight(1f))
        Text("tap to retry", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
