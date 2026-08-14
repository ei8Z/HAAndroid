package com.ei8z.haandroid.data.model

import kotlinx.serialization.Serializable

@Serializable
data class VisionDetectionMessage(
    val node_id: String,
    val timestamp: Long,        // Unix 时间戳 (ms)
    val detections: List<Detection>,
    val environment: Environment? = null,
    val is_historical: Boolean = false // 标志是否为补报的离线数据
)

@Serializable
data class Detection(
    val type: String,           // "person", "barcode"
    val identity: String? = null,
    val confidence: Float,
    val bbox: List<Float>? = null, // [x, y, w, h]
    val action: String? = null,    // 人员行为描述（预留）
    val landmarks: List<Point>? = null, // 关键点坐标
    val value: String? = null,   // 条码/二维码解码内容（type=barcode 时）
    val format: String? = null,  // 条码制式：QR_CODE / CODE_128 / EAN_13 ...
)

@Serializable
data class Environment(
    val location: String? = null,
    val light_level: Float? = null
)

@Serializable
data class Point(
    val x: Float,
    val y: Float,
    val z: Float? = null
)

@Serializable
data class HeartbeatMessage(
    val node_id: String,
    val timestamp: Long,
    val battery_level: Int,
    val is_charging: Boolean,
    val version: String
)
