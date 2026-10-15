package com.receiptclaims.android.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ReceiptUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = Result.success()

    companion object {
        const val KEY_CLIENT_RECEIPT_ID = "clientReceiptId"
        const val KEY_OWNER_USER_ID = "ownerUserId"
    }
}
