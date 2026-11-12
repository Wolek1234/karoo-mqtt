package io.hammerhead.karooexttemplate.runtime

import kotlinx.coroutines.flow.MutableStateFlow

object RuntimeState {
    val serviceRunning = MutableStateFlow(false)
    val karooStatus = MutableStateFlow("—")
    val rideState = MutableStateFlow("—")
    val activeProfile = MutableStateFlow("—")
    val wifiStatus = MutableStateFlow("inactive")
    val wifiConnected = MutableStateFlow(false)
    val lastSent = MutableStateFlow("—")
    val lastSentInfo = MutableStateFlow<String?>(null)
    val lastPower = MutableStateFlow<Double?>(null)
    val lastHr = MutableStateFlow<Double?>(null)
}
