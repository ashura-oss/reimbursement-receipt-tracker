package com.receiptclaims.android.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExpenseAccountsTest {
    @Test fun catalogMatchesTemplateAndPreservesTheIntentionalDuplicateCode() {
        assertEquals(14, ExpenseAccounts.all.size)
        assertEquals("751900", ExpenseAccounts.find("office_maintenance")?.code)
        assertEquals("SG Office - Maintenance Expenses", ExpenseAccounts.find("office_maintenance")?.summaryText)
        assertEquals("740140", ExpenseAccounts.find("marketing_printing")?.code)
        assertEquals("Printing of Marketing Materials", ExpenseAccounts.find("marketing_printing")?.summaryText)
        assertEquals(
            listOf("forklift_maintenance", "motor_vehicle"),
            ExpenseAccounts.all.filter { it.code == "752000" }.map { it.id }.sorted()
        )
        assertNotNull(ExpenseAccounts.find("forklift_maintenance"))
        assertEquals(null, ExpenseAccounts.find("unknown"))
    }
}
