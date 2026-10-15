package com.receiptclaims.android.ui.claims

import com.receiptclaims.android.data.local.ReportFileEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ClaimPresentationTest {
    @Test fun claimSummaryOmitsMissingPeriodAndFormatsPartialRanges() {
        assertEquals("2 receipts", claimReceiptSummary(2, null, null))
        assertEquals("2 receipts", claimReceiptSummary(2, "", " "))
        assertEquals("2 receipts · Period: 2026-10-01 to 2026-10-31", claimReceiptSummary(2, "2026-10-01", "2026-10-31"))
        assertEquals("2 receipts · From: 2026-10-01", claimReceiptSummary(2, "2026-10-01", null))
        assertEquals("2 receipts · Through: 2026-10-31", claimReceiptSummary(2, null, "2026-10-31"))
    }

    @Test fun reportListKeepsNewestFilePerTypeAndUsesIdForTimestampTies() {
        val oldPdf = report(id = 1, type = "PDF", createdAt = 10)
        val oldXlsx = report(id = 2, type = "XLSX", createdAt = 10)
        val newestPdf = report(id = 3, type = "PDF", createdAt = 20)
        val tieWinnerDocx = report(id = 5, type = "DOCX", createdAt = 20)
        val tieLoserDocx = report(id = 4, type = "DOCX", createdAt = 20)

        assertEquals(listOf(tieWinnerDocx, newestPdf, oldXlsx), latestReportsByType(listOf(oldPdf, oldXlsx, newestPdf, tieLoserDocx, tieWinnerDocx)))
    }

    private fun report(id: Long, type: String, createdAt: Long) = ReportFileEntity(
        id = id,
        claimId = 7,
        type = type,
        name = "$type-$id",
        mimeType = "application/octet-stream",
        path = "/reports/$type-$id",
        createdAt = createdAt
    )
}
