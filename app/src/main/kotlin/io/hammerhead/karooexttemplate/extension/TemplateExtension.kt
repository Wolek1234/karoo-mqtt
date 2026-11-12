package io.hammerhead.karooexttemplate.extension

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooexttemplate.R
import io.hammerhead.karooexttemplate.service.KarooMqttService

class TemplateExtension : KarooExtension("template-id", "1.1") {

    override val types by lazy {
        listOf(
            MqttStatusDataType(extension),
            WifiStatusDataType(extension),
        )
    }

    private val karooSystem by lazy { KarooSystemService(applicationContext) }

    @Volatile private var rideState: RideState? = null
    @Volatile private var activeProfile: RideProfile? = null
    @Volatile private var autoStarted = false

    override fun onCreate() {
        super.onCreate()
        karooSystem.connect { }

        karooSystem.addConsumer<RideState> { state ->
            rideState = state
            maybeAutoStart()
        }
        karooSystem.addConsumer<ActiveRideProfile>(ActiveRideProfile.Params) { event ->
            activeProfile = event.profile
            maybeAutoStart()
        }
    }

    private fun maybeAutoStart() {
        val indoorRecording = rideState is RideState.Recording && activeProfile?.indoor == true
        if (indoorRecording && !autoStarted) {
            autoStarted = true
            ContextCompat.startForegroundService(
                this,
                Intent(this, KarooMqttService::class.java),
            )
        }
        if (!indoorRecording && autoStarted) {
            autoStarted = false
            stopService(Intent(this, KarooMqttService::class.java))
        }
    }

    override fun onBonusAction(actionId: String) {
        if (actionId == ACTION_TOGGLE_WIFI) toggleWifi()
    }

    private fun toggleWifi() {
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val targetEnabled = !wifiManager.isWifiEnabled
        val accepted = runCatching {
            @Suppress("DEPRECATION")
            wifiManager.setWifiEnabled(targetEnabled)
        }.getOrDefault(false)

        val detail = when {
            accepted -> if (targetEnabled) "Enabling Wi-Fi..." else "Disabling Wi-Fi..."
            targetEnabled -> "Failed to enable Wi-Fi"
            else -> "Failed to disable Wi-Fi"
        }
        showAlert(detail)
    }

    private fun showAlert(detail: String) {
        karooSystem.connect {
            karooSystem.dispatch(
                InRideAlert(
                    id = "wifi-toggle",
                    icon = R.drawable.ic_mqtt_icon,
                    title = "Wi-Fi",
                    detail = detail,
                    autoDismissMs = 4000L,
                    backgroundColor = 0xFF1976D2.toInt(),
                    textColor = 0xFFFFFFFF.toInt(),
                ),
            )
        }
    }

    private companion object {
        const val ACTION_TOGGLE_WIFI = "toggle-wifi"
    }
}
