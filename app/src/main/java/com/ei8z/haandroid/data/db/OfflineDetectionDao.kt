package com.ei8z.haandroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OfflineDetectionDao {
    @Insert
    suspend fun insert(detection: OfflineDetection)

    @Query("SELECT * FROM offline_detections ORDER BY timestamp ASC LIMIT 100")
    suspend fun getOldestDetections(): List<OfflineDetection>

    @Query("DELETE FROM offline_detections WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM offline_detections")
    suspend fun getCount(): Int

    @Query("DELETE FROM offline_detections WHERE id IN (SELECT id FROM offline_detections ORDER BY timestamp ASC LIMIT :limit)")
    suspend fun deleteOldest(limit: Int)
}
