package com.trafficwatch.app.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.trafficwatch.app.core.data.local.entity.ReportEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReportDao {

    @Query("SELECT * FROM reports ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ReportEntity>>

    @Query("SELECT * FROM reports WHERE id = :id")
    suspend fun getById(id: String): ReportEntity?

    @Query("SELECT * FROM reports WHERE status NOT IN ('CONFIRMED', 'REJECTED') AND serverId IS NOT NULL")
    suspend fun getPendingRemoteReports(): List<ReportEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(report: ReportEntity)

    @Update
    suspend fun update(report: ReportEntity)

    @Query("UPDATE reports SET status = :status, serverId = :serverId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, serverId: String?, updatedAt: Long)

    @Query("""
        UPDATE reports
        SET status = :status, licensePlate = :licensePlate, confidence = :confidence,
            analysisMessage = :message, hasWrongWayFrame = :hasWrongWayFrame,
            wrongWayConfidence = :wrongWayConfidence, evidenceBreakdownJson = :evidenceBreakdownJson, updatedAt = :updatedAt
        WHERE id = :id
    """)
    suspend fun updateAnalysisResult(
        id: String,
        status: String,
        licensePlate: String?,
        confidence: Float?,
        message: String?,
        hasWrongWayFrame: Boolean,
        wrongWayConfidence: Float?,
        evidenceBreakdownJson: String?,
        updatedAt: Long
    )

    @Query("DELETE FROM reports WHERE id = :id")
    suspend fun deleteById(id: String)
}
