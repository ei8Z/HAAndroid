package com.ei8z.haandroid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offline_detections")
data class OfflineDetection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val jsonContent: String // 序列化后的 VisionDetectionMessage
)
