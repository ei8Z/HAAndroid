package com.ei8z.haandroid.net

import android.util.Log
import com.ei8z.haandroid.data.model.CommandMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MqttManager(private val nodeId: String) {

    private var mqttClient: MqttAsyncClient? = null
    private var commandListener: CommandListener? = null

    @PublishedApi
    internal val TAG = "MqttManager"

    private val statusTopic = "ind/vision/$nodeId/status"

    interface CommandListener {
        fun onCommandReceived(command: CommandMessage)
    }

    fun connect(brokerUrl: String, onConnect: () -> Unit) {
        try {
            mqttClient = MqttAsyncClient(brokerUrl, "ind_vision_$nodeId", MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = false
                isAutomaticReconnect = true
                connectionTimeout = 10
                keepAliveInterval = 60
                // 设置遗言 (LWT)
                setWill(statusTopic, "offline".toByteArray(Charsets.UTF_8), 1, true)
            }

            val client = mqttClient ?: return

            // 回调必须在 connect 之前注册（Paho 要求），否则下行消息无法分发
            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i(TAG, "connectComplete reconnect=$reconnect")
                    resubscribe()
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Connection lost, auto-reconnect enabled: ${cause?.message}")
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    Log.d(TAG, "Message arrived on topic: $topic")
                    when {
                        topic.startsWith("ind/command/") || topic == "ind/vision/$nodeId/config" -> {
                            try {
                                val cmd = Json.decodeFromString<CommandMessage>(message.toString())
                                commandListener?.onCommandReceived(cmd)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse command: $message", e)
                            }
                        }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })

            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "MQTT Connected")
                    publishStatus("online")
                    onConnect()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "MQTT Connection Failed: ${exception?.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "connect error", e)
        }
    }

    fun subscribeToCommands(listener: CommandListener, nodeId: String) {
        commandListener = listener
        resubscribe()
    }

    private fun resubscribe() {
        if (mqttClient?.isConnected != true) return
        try {
            mqttClient?.subscribe(
                arrayOf("ind/command/#", "ind/vision/$nodeId/config"),
                intArrayOf(1, 1)
            )
            Log.i(TAG, "Subscribed: ind/command/#")
        } catch (e: MqttException) {
            Log.e(TAG, "Error subscribing", e)
        }
    }

    fun publishStatus(status: String) {
        publish(statusTopic, status, qos = 1, retained = true)
    }

    /**
     * 使用 inline + reified 修复序列化丢失类型的问题
     */
    inline fun <reified T> publishMessage(topic: String, message: T, qos: Int = 0) {
        try {
            val jsonString = if (message is String) {
                message
            } else {
                Json.encodeToString(message)
            }
            publish(topic, jsonString, qos)
        } catch (e: Exception) {
            Log.e(TAG, "Serialization error: ${e.message}")
        }
    }

    @PublishedApi
    internal fun publish(topic: String, payload: String, qos: Int = 0, retained: Boolean = false) {
        if (mqttClient?.isConnected == true) {
            val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                this.qos = qos
                isRetained = retained
            }
            mqttClient?.publish(topic, message)
        }
    }

    /**
     * 发布并在 Broker 确认后回调（QoS1）。
     * 用于离线补报：确认送达后才允许删除本地缓存。
     */
    fun publishWithCallback(topic: String, payload: String, qos: Int = 1, onDelivered: suspend () -> Unit) {
        if (mqttClient?.isConnected == true) {
            val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                this.qos = qos
            }
            mqttClient?.publish(topic, message, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    CoroutineScope(Dispatchers.IO).launch { onDelivered() }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.w(TAG, "Publish failed, keep local cache: ${exception?.message}")
                }
            })
        }
    }

    fun disconnect() {
        if (mqttClient?.isConnected == true) {
            publishStatus("offline")
            mqttClient?.disconnect()
        }
    }

    fun isConnected() = mqttClient?.isConnected ?: false
}
