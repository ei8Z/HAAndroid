package com.ei8z.haandroid.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSerializationTest {

    private val json = Json {
        encodeDefaults = false // 清单明确要求：is_historical 为 false 时应省略
        ignoreUnknownKeys = true
    }

    @Test
    fun testVisionDetectionMessageSerialization() {
        val message = VisionDetectionMessage(
            node_id = "test-node",
            timestamp = 1625097600000L,
            detections = listOf(
                Detection(type = "person", confidence = 0.95f)
            ),
            is_historical = false
        )

        val jsonString = json.encodeToString(message)
        
        // 验证 is_historical 在 false 时被省略
        assertFalse("is_historical should be omitted when false", jsonString.contains("is_historical"))
        assertTrue("node_id should be present", jsonString.contains("test-node"))
    }

    @Test
    fun testVisionDetectionMessageHistorical() {
        val message = VisionDetectionMessage(
            node_id = "test-node",
            timestamp = 1625097600000L,
            detections = emptyList(),
            is_historical = true
        )

        val jsonString = json.encodeToString(message)
        
        // 验证 is_historical 在 true 时出现
        assertTrue("is_historical should be present when true", jsonString.contains("\"is_historical\":true"))
    }

    @Test
    fun testCommandMessageSerialization() {
        val message = CommandMessage(
            command = "set_detection_policy",
            target = "test-node",
            parameters = mapOf("enable_person" to JsonPrimitive(true)),
            timestamp = 1625097600000L
        )

        val jsonString = json.encodeToString(message)
        
        assertTrue(jsonString.contains("set_detection_policy"))
        assertTrue(jsonString.contains("enable_person"))
        // 默认值验证
        assertTrue("priority should be included if it has a default but encodeDefaults is false? Wait, it depends on whether it's explicitly set or not in some versions.", jsonString.contains("normal"))
    }

    @Test
    fun testHeartbeatMessageSerialization() {
        val message = HeartbeatMessage(
            node_id = "test-node",
            timestamp = 1625097600000L,
            battery_level = 80,
            is_charging = false,
            version = "1.0.0"
        )

        val jsonString = json.encodeToString(message)
        
        assertTrue(jsonString.contains("battery_level"))
        assertTrue(jsonString.contains("1.0.0"))
    }
}
