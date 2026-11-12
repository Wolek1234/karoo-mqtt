package io.hammerhead.karooexttemplate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooexttemplate.MainActivity
import io.hammerhead.karooexttemplate.R
import io.hammerhead.karooexttemplate.data.AppSettings
import io.hammerhead.karooexttemplate.data.SettingsRepository
import io.hammerhead.karooexttemplate.mqtt.MqttClientManager
import io.hammerhead.karooexttemplate.runtime.RuntimeState
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class KarooMqttService : Service() {

    private val karooSystem by lazy { KarooSystemService(applicationContext) }
    private val settingsRepo by lazy { SettingsRepository(applicationContext) }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val consumerIds = mutableListOf<String>()

    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var powerAvg = RollingAverage(DEFAULT_WINDOW_MS)
    private var hrAvg = RollingAverage(DEFAULT_WINDOW_MS)

    @Volatile private var avgWindowSec = DEFAULT_WINDOW_SEC
    @Volatile private var rideState: RideState? = null
    @Volatile private var activeProfile: RideProfile? = null
    @Volatile private var lastSendMs = 0L
    @Volatile private var lastMqttAttemptMs = 0L
    @Volatile private var wifiHeld = false
    @Volatile private var wifiInterfaceUp = false
    @Volatile private var networkBound = false
    @Volatile private var lastNetworkEventMs = 0L

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Karoo MQTT — connecting to Karoo..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        connectKaroo()
        executor.scheduleWithFixedDelay({ safeTick() }, 1, 1, TimeUnit.SECONDS)
        RuntimeState.serviceRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        RuntimeState.serviceRunning.value = false
        executor.shutdownNow()
        consumerIds.forEach { runCatching { karooSystem.removeConsumer(it) } }
        consumerIds.clear()
        karooSystem.disconnect()
        MqttClientManager.disconnect()
        releaseWifi()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Karoo ----------

    private fun connectKaroo() {
        RuntimeState.karooStatus.value = "connecting..."
        karooSystem.connect { connected ->
            RuntimeState.karooStatus.value = if (connected) "connected" else "connection error"
        }

        runCatching {
            consumerIds += karooSystem.addConsumer<RideState> { state ->
                rideState = state
                RuntimeState.rideState.value = when (state) {
                    is RideState.Recording -> "Recording"
                    is RideState.Paused -> "Paused"
                    else -> "Idle"
                }
            }
        }.onFailure { Log.e(TAG, "RideState consumer failed", it) }

        runCatching {
            consumerIds += karooSystem.addConsumer<OnStreamState>(
                OnStreamState.StartStreaming(DataType.Type.POWER),
            ) { event ->
                val streaming = event.state as? StreamState.Streaming ?: return@addConsumer
                streaming.dataPoint.singleValue?.let {
                    powerAvg.add(System.currentTimeMillis(), it)
                }
            }
        }.onFailure { Log.e(TAG, "POWER consumer failed", it) }

        runCatching {
            consumerIds += karooSystem.addConsumer<OnStreamState>(
                OnStreamState.StartStreaming(DataType.Type.HEART_RATE),
            ) { event ->
                val streaming = event.state as? StreamState.Streaming ?: return@addConsumer
                streaming.dataPoint.singleValue?.let {
                    hrAvg.add(System.currentTimeMillis(), it)
                }
            }
        }.onFailure { Log.e(TAG, "HEART_RATE consumer failed", it) }

        runCatching {
            consumerIds += karooSystem.addConsumer<ActiveRideProfile>(ActiveRideProfile.Params) { event ->
                activeProfile = event.profile
                RuntimeState.activeProfile.value = event.profile.name +
                    if (event.profile.indoor) " (indoor)" else ""
            }
        }.onFailure { Log.e(TAG, "ActiveRideProfile consumer failed", it) }
    }

    // ---------- Wi-Fi keep-alive ----------

    private fun acquireWifiLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        // Attempt to enable Wi-Fi programmatically (blocked on some ROMs).
        @Suppress("DEPRECATION")
        runCatching { wifiManager?.setWifiEnabled(true) }
        wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL, "karoo-mqtt:wifi-lock")
        wifiLock?.setReferenceCounted(false)
        wifiLock?.acquire()
    }

    private fun requestWifiNetwork() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityManager?.bindProcessToNetwork(network)
                networkBound = true
                RuntimeState.wifiConnected.value = true
                lastNetworkEventMs = System.currentTimeMillis()
                RuntimeState.wifiStatus.value = "Wi-Fi network active"
            }

            override fun onLost(network: Network) {
                connectivityManager?.bindProcessToNetwork(null)
                networkBound = false
                RuntimeState.wifiConnected.value = false
                lastNetworkEventMs = System.currentTimeMillis()
                RuntimeState.wifiStatus.value = "Wi-Fi network lost"
            }
        }
        networkCallback = callback
        lastNetworkEventMs = System.currentTimeMillis()
        RuntimeState.wifiStatus.value = "waiting for Wi-Fi..."
        runCatching { connectivityManager?.requestNetwork(request, callback) }
            .onFailure {
                RuntimeState.wifiStatus.value = "network request error"
                Log.e(TAG, "requestNetwork failed", it)
            }
    }

    private fun releaseNetworkRequest() {
        networkCallback?.let { runCatching { connectivityManager?.unregisterNetworkCallback(it) } }
        networkCallback = null
        networkBound = false
        RuntimeState.wifiConnected.value = false
        runCatching { connectivityManager?.bindProcessToNetwork(null) }
    }

    private fun releaseWifi() {
        releaseNetworkRequest()
        wifiLock?.let { runCatching { if (it.isHeld) it.release() } }
        wifiLock = null
        RuntimeState.wifiStatus.value = "inactive"
    }

    // ---------- MQTT + sending ----------

    private fun safeTick() {
        try {
            tick()
        } catch (t: Throwable) {
            Log.w(TAG, "tick failed", t)
        }
    }

    private fun tick() {
        val settings = settingsRepo.load()

        if (settings.avgWindowSec != avgWindowSec) {
            avgWindowSec = settings.avgWindowSec
            powerAvg = RollingAverage(settings.avgWindowSec * 1000L)
            hrAvg = RollingAverage(settings.avgWindowSec * 1000L)
        }

        RuntimeState.activeProfile.value = activeProfile?.name ?: "—"

        // Wi-Fi and MQTT are active only while recording on an indoor (trainer) profile.
        val indoorActive = rideState is RideState.Recording && activeProfile?.indoor == true
        setWifiHeld(indoorActive)

        // If the network was not granted, retry the request (the system may have rejected it).
        if (wifiHeld && !networkBound &&
            System.currentTimeMillis() - lastNetworkEventMs > NETWORK_RETRY_MS
        ) {
            releaseNetworkRequest()
            requestWifiNetwork()
        }

        // Actual Wi-Fi interface state (also when enabled manually).
        val wifiUp = connectivityManager?.allNetworks?.any { n ->
            connectivityManager?.getNetworkCapabilities(n)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } == true
        wifiInterfaceUp = wifiUp
        RuntimeState.wifiConnected.value = wifiUp
        if (wifiHeld && !networkBound && wifiUp) {
            RuntimeState.wifiStatus.value = "Wi-Fi connected"
        }

        RuntimeState.lastPower.value = powerAvg.current()
        RuntimeState.lastHr.value = hrAvg.current()

        if (!indoorActive) {
            if (MqttClientManager.status.value != MqttClientManager.Status.DISCONNECTED) {
                MqttClientManager.disconnect()
            }
            return
        }

        maintainMqttConnection(settings)

        val now = System.currentTimeMillis()
        if (now - lastSendMs < settings.sendIntervalSec.coerceAtLeast(1) * 1000L) return
        lastSendMs = now

        if (!MqttClientManager.isConnected()) return

        val power = powerAvg.current()
        val hr = hrAvg.current()
        if (power == null && hr == null) return

        val topic = settings.mqttTopic.trim().ifEmpty { "karoo" }
        val json = JSONObject()
        power?.let { json.put("power", Math.round(it).toInt()) }
        hr?.let { json.put("hr", Math.round(it).toInt()) }

        MqttClientManager.publish(topic, json.toString())
        power?.let {
            MqttClientManager.publish("$topic/power", Math.round(it).toString())
        }
        hr?.let {
            MqttClientManager.publish("$topic/hr", Math.round(it).toString())
        }

        val sentTime = android.text.format.DateFormat.format("HH:mm:ss", now).toString()
        RuntimeState.lastSent.value = sentTime
        RuntimeState.lastSentInfo.value = "$sentTime — $json"
    }

    private fun maintainMqttConnection(settings: AppSettings) {
        // Don't try to connect before the Wi-Fi interface is actually up —
        // otherwise the first connect attempt fails and the status flickers.
        if (wifiHeld && !wifiInterfaceUp) return
        val wantsConnection = settings.mqttHost.isNotBlank()
        if (!wantsConnection) {
            if (MqttClientManager.isConnected() || MqttClientManager.status.value == MqttClientManager.Status.CONNECTING) {
                MqttClientManager.disconnect()
            }
            return
        }
        if (MqttClientManager.isConnected() || MqttClientManager.status.value == MqttClientManager.Status.CONNECTING) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastMqttAttemptMs < MQTT_RETRY_MS) return
        lastMqttAttemptMs = now
        MqttClientManager.connect(settings)
    }

    private fun setWifiHeld(held: Boolean) {
        if (held == wifiHeld) return
        wifiHeld = held
        if (held) {
            acquireWifiLock()
            requestWifiNetwork()
        } else {
            releaseWifi()
        }
    }

    // ---------- Notification ----------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val TAG = "KarooMqttService"
        const val NOTIFICATION_ID = 42
        const val CHANNEL_ID = "karoo_mqtt"
        const val DEFAULT_WINDOW_SEC = 3
        const val DEFAULT_WINDOW_MS = 3000L
        const val MQTT_RETRY_MS = 10_000L
        const val NETWORK_RETRY_MS = 15_000L
    }
}
