package io.hammerhead.karooexttemplate.data

import android.content.Context
import android.content.SharedPreferences

data class AppSettings(
    val mqttHost: String = "",
    val mqttPort: Int = 1883,
    val mqttUser: String = "",
    val mqttPassword: String = "",
    val mqttTopic: String = "karoo",
    val avgWindowSec: Int = 3,
    val sendIntervalSec: Int = 7,
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        mqttHost = prefs.getString(KEY_HOST, "") ?: "",
        mqttPort = prefs.getInt(KEY_PORT, 1883),
        mqttUser = prefs.getString(KEY_USER, "") ?: "",
        mqttPassword = prefs.getString(KEY_PASSWORD, "") ?: "",
        mqttTopic = prefs.getString(KEY_TOPIC, "karoo") ?: "karoo",
        avgWindowSec = prefs.getInt(KEY_AVG_WINDOW, 3),
        sendIntervalSec = prefs.getInt(KEY_INTERVAL, 7),
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_HOST, settings.mqttHost)
            .putInt(KEY_PORT, settings.mqttPort)
            .putString(KEY_USER, settings.mqttUser)
            .putString(KEY_PASSWORD, settings.mqttPassword)
            .putString(KEY_TOPIC, settings.mqttTopic)
            .putInt(KEY_AVG_WINDOW, settings.avgWindowSec)
            .putInt(KEY_INTERVAL, settings.sendIntervalSec)
            .apply()
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        save(transform(load()))
    }

    private companion object {
        const val PREFS_NAME = "karoo_mqtt_settings"
        const val KEY_HOST = "mqtt_host"
        const val KEY_PORT = "mqtt_port"
        const val KEY_USER = "mqtt_user"
        const val KEY_PASSWORD = "mqtt_password"
        const val KEY_TOPIC = "mqtt_topic"
        const val KEY_AVG_WINDOW = "avg_window_sec"
        const val KEY_INTERVAL = "send_interval_sec"
    }
}
