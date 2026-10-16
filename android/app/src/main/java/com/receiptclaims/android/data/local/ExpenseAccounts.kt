package com.receiptclaims.android.data.local

data class ExpenseAccount(
    val id: String,
    val code: String,
    val label: String,
    val summaryText: String
) {
    val displayName: String get() = "$code · $label"
}

object ExpenseAccounts {
    val all: List<ExpenseAccount> = listOf(
        ExpenseAccount("office_maintenance", "751900", "SG Office - Maintenance Expenses", "SG Office - Maintenance Expenses"),
        ExpenseAccount("staff_medical", "759500", "Staff Medical claim", "Staff Medical claim"),
        ExpenseAccount("local_transport", "759600", "Local Transport", "Local Transport"),
        ExpenseAccount("meals_allowances", "759700", "Meals & Allowances", "Meals & Allowances"),
        ExpenseAccount("overseas_travel", "759800", "Travelling (overseas, including Bintan)", "Travelling (overseas, including Bintan)"),
        ExpenseAccount("it_expenses", "752300", "IT Expenses", "IT Expenses"),
        ExpenseAccount("telephone", "750500", "Telephone", "Telephone"),
        ExpenseAccount("stationery", "751500", "Stationeries, Office Supplies", "Stationeries, Office Supplies"),
        ExpenseAccount("motor_vehicle", "752000", "Motor Vehicle Expenses", "Motor Vehicle Expenses"),
        ExpenseAccount("forklift_maintenance", "752000", "Maintenance-Forklift", "Maintenance-Forklift"),
        ExpenseAccount("sundry", "752800", "G&A Sundry Expenses", "G&A Sundry Expenses"),
        ExpenseAccount("subscriptions", "752200", "Subscriptions", "Subscriptions"),
        ExpenseAccount("guest_meals", "751600", "Entertainment-Meals with guests", "Entertainment-Meals with guests"),
        ExpenseAccount("marketing_printing", "740140", "Printing of Marketing Materials", "Printing of Marketing Materials")
    )

    private val byId = all.associateBy(ExpenseAccount::id)

    fun find(id: String?): ExpenseAccount? = id?.let(byId::get)
}
