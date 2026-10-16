package com.receiptclaims.android.reports

import java.math.BigDecimal

/** Pure OOXML formula helpers for account summaries. */
internal object AccountSummaryFormula {
    fun formula(
        accountId: String,
        receiptAccountIds: List<String?>,
        firstDataRow: Int = 21,
        column: String = "O",
    ): String {
        require(firstDataRow > 0)
        require(column.matches(Regex("[A-Z]+")))
        val rows = receiptAccountIds.mapIndexedNotNull { index, value ->
            (firstDataRow + index).takeIf { value == accountId }
        }
        if (rows.isEmpty()) return "SUM(0)"
        val ranges = mutableListOf<String>()
        var start = rows.first()
        var end = start
        rows.drop(1).forEach { row ->
            if (row == end + 1) {
                end = row
            } else {
                ranges += cellRange(column, start, end)
                start = row
                end = row
            }
        }
        ranges += cellRange(column, start, end)
        return "SUM(${ranges.joinToString(",")})"
    }

    fun cachedSgdTotal(
        accountId: String,
        receiptAccountIds: List<String?>,
        sgdAmounts: List<String>,
    ): BigDecimal {
        require(receiptAccountIds.size == sgdAmounts.size)
        return receiptAccountIds.indices.fold(BigDecimal.ZERO) { sum, index ->
            if (receiptAccountIds[index] == accountId) {
                sum.add(sgdAmounts[index].trim().replace(',', '.').toBigDecimal())
            } else sum
        }
    }

    private fun cellRange(column: String, start: Int, end: Int): String =
        if (start == end) "$column$start" else "$column$start:$column$end"
}
