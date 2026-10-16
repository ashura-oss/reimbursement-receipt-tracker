package com.receiptclaims.android.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSummaryFormulaTest {
    @Test fun fiveHundredContiguousReceiptsCompressToOneRangeAndKeepTheirTotal() {
        val ids = List(500) { "motor_vehicle" }
        val amounts = List(500) { "10.00" }

        assertEquals("SUM(O21:O520)", AccountSummaryFormula.formula("motor_vehicle", ids))
        assertEquals("5000", AccountSummaryFormula.cachedSgdTotal("motor_vehicle", ids, amounts).stripTrailingZeros().toPlainString())
    }

    @Test fun alternatingDuplicateCodeSelectionsStaySeparateAndBelowExcelArgumentLimit() {
        val ids = List(500) { index -> if (index % 2 == 0) "motor_vehicle" else "forklift_maintenance" }
        val amounts = List(500) { "1.00" }
        val motor = AccountSummaryFormula.formula("motor_vehicle", ids)
        val forklift = AccountSummaryFormula.formula("forklift_maintenance", ids)

        assertTrue(motor.startsWith("SUM(O21,O23,O25"))
        assertTrue(motor.endsWith(",O517,O519)"))
        assertTrue(forklift.startsWith("SUM(O22,O24,O26"))
        assertTrue(forklift.endsWith(",O518,O520)"))
        assertEquals(250, argumentCount(motor))
        assertEquals(250, argumentCount(forklift))
        assertTrue("Each summary SUM call must respect Excel's 255-argument limit", argumentCount(motor) <= 255 && argumentCount(forklift) <= 255)
        assertEquals("250", AccountSummaryFormula.cachedSgdTotal("motor_vehicle", ids, amounts).stripTrailingZeros().toPlainString())
        assertEquals("250", AccountSummaryFormula.cachedSgdTotal("forklift_maintenance", ids, amounts).stripTrailingZeros().toPlainString())
        assertTrue("The duplicate account summaries must remain distinct", motor != forklift)
    }

    private fun argumentCount(formula: String): Int = formula.substringAfter("SUM(").substringBeforeLast(')').split(',').size
}
