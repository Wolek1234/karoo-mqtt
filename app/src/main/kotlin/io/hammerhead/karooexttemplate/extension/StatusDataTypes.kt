package io.hammerhead.karooexttemplate.extension

import android.content.Context
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karooexttemplate.R
import io.hammerhead.karooexttemplate.mqtt.MqttClientManager
import io.hammerhead.karooexttemplate.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TYPE_MQTT_STATUS = "mqtt-status"
private const val TYPE_WIFI_STATUS = "wifi-status"

abstract class StatusDataType(
    extension: String,
    typeId: String,
    private val label: String,
) : DataTypeImpl(extension, typeId) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    protected abstract fun readValue(): Double

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = scope.launch {
            while (isActive) {
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId,
                            mapOf(DataType.Field.SINGLE to readValue()),
                            extension,
                        ),
                    ),
                )
                delay(1000)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val views = RemoteViews(context.packageName, R.layout.view_status_indicator)
        views.setTextViewText(R.id.status_label, label)
        val job = scope.launch {
            while (isActive) {
                views.setInt(
                    R.id.status_root,
                    "setBackgroundResource",
                    if (readValue() > 0.5) R.drawable.status_bg_on else R.drawable.status_bg_off,
                )
                emitter.updateView(views)
                delay(1000)
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}

class MqttStatusDataType(extension: String) : StatusDataType(extension, TYPE_MQTT_STATUS, "MQTT") {
    override fun readValue(): Double =
        if (MqttClientManager.isConnected()) 1.0 else 0.0
}

class WifiStatusDataType(extension: String) : StatusDataType(extension, TYPE_WIFI_STATUS, "Wi-Fi") {
    override fun readValue(): Double =
        if (RuntimeState.wifiConnected.value) 1.0 else 0.0
}
