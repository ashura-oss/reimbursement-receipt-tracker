package com.receiptclaims.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_settings")
data class LocalSettingEntity(@PrimaryKey val key: String, val value: String)
