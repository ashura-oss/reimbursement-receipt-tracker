package com.receiptclaims.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.receiptclaims.android.data.repository.ReceiptRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ReceiptUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: ReceiptRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val clientId = inputData.getString(KEY_CLIENT_RECEIPT_ID) ?: return Result.failure()
        return try {
            repository.upload(clientId)
            Result.success()
        } catch (_: Throwable) {
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val KEY_CLIENT_RECEIPT_ID = "clientReceiptId"
        private const val MAX_ATTEMPTS = 5
    }
}
