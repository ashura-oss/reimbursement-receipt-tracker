package com.receiptclaims.android

import android.app.Application
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ReceiptClaimsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).cancelAllWork()
    }
}
