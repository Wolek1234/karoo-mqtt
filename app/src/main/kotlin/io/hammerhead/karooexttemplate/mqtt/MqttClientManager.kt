package io.hammerhead.karooexttemplate.mqtt

import io.hammerhead.karooexttemplate.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

object MqttClientManager {

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val lock = Any()

    private var client: MqttClient? = null
    private var lastTopic: String? = null

    private val _status = MutableStateFlow(Status.DISCONNECTED)
    val status: StateFlow<Status> = _status

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    fun isConnected(): Boolean = _status.value == Status.CONNECTED

    fun connect(settings: AppSettings) {
        synchronized(lock) {
            if (_status.value == Status.CONNECTED || _status.value == Status.CONNECTING) return
            disconnectLocked()
            val host = settings.mqttHost.trim()
            if (host.isEmpty()) {
                _status.value = Status.DISCONNECTED
                return
            }
            _status.value = Status.CONNECTING
            _lastError.value = null
            val topic = settings.mqttTopic.trim().ifEmpty { "karoo" }
            lastTopic = topic
            try {
                val uri = "tcp://$host:${settings.mqttPort}"
                val newClient = MqttClient(uri, "karoo-${UUID.randomUUID()}", MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                    isAutomaticReconnect = true
                    setWill("$topic/status", "offline".toByteArray(Charsets.UTF_8), 0, true)
                    if (settings.mqttUser.isNotBlank()) {
                        userName = settings.mqttUser
                        password = settings.mqttPassword.toCharArray()
                    }
                }
                newClient.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        synchronized(lock) {
                            if (client === newClient) {
                                _status.value = Status.CONNECTED
                                if (reconnect) publishStatusLocked(true)
                            }
                        }
                    }

                    override fun connectionLost(cause: Throwable?) {
                        synchronized(lock) {
                            if (client === newClient) {
                                _status.value = Status.ERROR
                                _lastError.value = cause?.message ?: "connection lost"
                            }
                        }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {}

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                newClient.connect(options)
                synchronized(lock) {
                    client = newClient
                    _status.value = Status.CONNECTED
                    publishStatusLocked(true)
                }
            } catch (e: Exception) {
                _status.value = Status.ERROR
                _lastError.value = e.message
                client = null
            }
        }
    }

    fun publish(topic: String, payload: String) {
        val c = synchronized(lock) { client } ?: return
        try {
            c.publish(topic, MqttMessage(payload.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            _lastError.value = e.message
        }
    }

    fun disconnect() {
        synchronized(lock) { disconnectLocked() }
    }

    private fun disconnectLocked() {
        val c = client
        client = null
        if (c != null) {
            lastTopic?.let { topic ->
                runCatching {
                    val msg = MqttMessage("offline".toByteArray(Charsets.UTF_8)).apply {
                        isRetained = true
                    }
                    c.publish("$topic/status", msg)
                }
            }
            runCatching { c.disconnect() }
            runCatching { c.close() }
        }
        _status.value = Status.DISCONNECTED
        _lastError.value = null
    }

    // Must be called under lock.
    private fun publishStatusLocked(online: Boolean) {
        val c = client ?: return
        val topic = lastTopic ?: return
        runCatching {
            val msg = MqttMessage(
                (if (online) "online" else "offline").toByteArray(Charsets.UTF_8),
            ).apply { isRetained = true }
            c.publish("$topic/status", msg)
        }
    }

    suspend fun testConnection(settings: AppSettings): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        _status.value = Status.CONNECTING
        _lastError.value = null
        val host = settings.mqttHost.trim()
        if (host.isEmpty()) {
            _status.value = Status.ERROR
            _lastError.value = "Missing broker host"
            return@withContext false
        }
        var c: MqttClient? = null
        return@withContext try {
            val uri = "tcp://$host:${settings.mqttPort}"
            c = MqttClient(uri, "karoo-test-${UUID.randomUUID()}", MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
                if (settings.mqttUser.isNotBlank()) {
                    userName = settings.mqttUser
                    password = settings.mqttPassword.toCharArray()
                }
            }
            c.connect(options)
            val topic = settings.mqttTopic.trim().ifEmpty { "karoo" }
            val payload = """{"power":200,"hr":140,"test":true}"""
            c.publish("$topic/test", MqttMessage(payload.toByteArray(Charsets.UTF_8)))
            Thread.sleep(300)
            _status.value = Status.CONNECTED
            true
        } catch (e: Exception) {
            _status.value = Status.ERROR
            _lastError.value = e.message ?: e.javaClass.simpleName
            false
        } finally {
            runCatching { c?.disconnect() }
            runCatching { c?.close() }
            _status.value = Status.DISCONNECTED
        }
    }
}
