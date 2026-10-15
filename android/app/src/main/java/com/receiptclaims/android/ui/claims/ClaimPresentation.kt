package com.receiptclaims.android.ui.claims

import com.receiptclaims.android.data.local.ReportFileEntity

internal fun claimReceiptSummary(receiptCount: Int, startDate: String?, endDate: String?): String {
    val start = startDate?.takeIf(String::isNotBlank)
    val end = endDate?.takeIf(String::isNotBlank)
    val period = when {
        start != null && end != null -> "Period: $start to $end"
        start != null -> "From: $start"
        end != null -> "Through: $end"
        else -> null
    }
    return if (period == null) "$receiptCount receipts" else "$receiptCount receipts · $period"
}

internal fun latestReportsByType(files: List<ReportFileEntity>): List<ReportFileEntity> =
    files.groupBy { it.type }.values.mapNotNull { group ->
        group.maxWithOrNull(compareBy<ReportFileEntity> { it.createdAt }.thenBy { it.id })
    }.sortedBy { it.type }
