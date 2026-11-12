package io.hammerhead.karooexttemplate.extension

import android.content.Intent
import androidx.core.content.ContextCompat
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooexttemplate.BuildConfig
import io.hammerhead.karooexttemplate.service.KarooMqttService

class TemplateExtension : KarooExtension("karoo-mqtt", BuildConfig.VERSION_NAME) {

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
}
