package com.ei8z.haandroid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 检测策略（可被 MQTT 命令 set_detection_policy 动态更新） */
data class DetectionPolicy(
    val enablePerson: Boolean = true,
    val enableBarcode: Boolean = true,
)

class SettingsManager(private val context: Context) {

    companion object {
        val NODE_ID = stringPreferencesKey("node_id")
        val MQTT_BROKER = stringPreferencesKey("mqtt_broker")
        val DETECTION_FPS = intPreferencesKey("detection_fps")
        val POLICY_ENABLE_PERSON = booleanPreferencesKey("policy_enable_person")
        val POLICY_ENABLE_BARCODE = booleanPreferencesKey("policy_enable_barcode")
    }

    val nodeId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[NODE_ID] ?: "node_${UUID.randomUUID().toString().take(8)}"
    }

    val mqttBroker: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[MQTT_BROKER] ?: "tcp://192.168.0.3:1883"
    }

    val detectionFps: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[DETECTION_FPS] ?: 5
    }

    val detectionPolicy: Flow<DetectionPolicy> = context.dataStore.data.map { preferences ->
        DetectionPolicy(
            enablePerson = preferences[POLICY_ENABLE_PERSON] ?: true,
            enableBarcode = preferences[POLICY_ENABLE_BARCODE] ?: true,
        )
    }

    suspend fun updateNodeId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[NODE_ID] = id
        }
    }

    suspend fun updateMqttBroker(url: String) {
        context.dataStore.edit { preferences ->
            preferences[MQTT_BROKER] = url
        }
    }

    suspend fun updateDetectionPolicy(policy: DetectionPolicy) {
        context.dataStore.edit { preferences ->
            preferences[POLICY_ENABLE_PERSON] = policy.enablePerson
            preferences[POLICY_ENABLE_BARCODE] = policy.enableBarcode
        }
    }
}
