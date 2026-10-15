package com.receiptclaims.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocalSettingDao {
    @Query("SELECT * FROM local_settings ORDER BY `key`") suspend fun all(): List<LocalSettingEntity>
    @Query("SELECT value FROM local_settings WHERE `key` = :key LIMIT 1") suspend fun get(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(setting: LocalSettingEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(settings: List<LocalSettingEntity>)
    @Query("DELETE FROM local_settings") suspend fun deleteAll()
}
