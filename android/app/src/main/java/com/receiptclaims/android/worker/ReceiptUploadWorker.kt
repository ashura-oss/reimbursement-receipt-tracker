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
        val ownerUserId = inputData.getInt(KEY_OWNER_USER_ID, -1).takeIf { it > 0 } ?: return Result.failure()
        return try {
            repository.upload(clientId, ownerUserId)
            Result.success()
        } catch (_: ReceiptRepository.AccountMismatchException) {
            Result.failure()
        } catch (_: Throwable) {
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val KEY_CLIENT_RECEIPT_ID = "clientReceiptId"
        const val KEY_OWNER_USER_ID = "ownerUserId"
        private const val MAX_ATTEMPTS = 5
    }
}
