package com.receiptclaims.android.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object UploadScheduler {
    fun enqueue(context: Context, clientReceiptId: String) {
        val request = OneTimeWorkRequestBuilder<ReceiptUploadWorker>()
            .setInputData(Data.Builder().putString(ReceiptUploadWorker.KEY_CLIENT_RECEIPT_ID, clientReceiptId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "receipt-upload-$clientReceiptId",
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
