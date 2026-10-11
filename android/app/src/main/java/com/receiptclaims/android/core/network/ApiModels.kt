package com.receiptclaims.android.core.network

data class ApiEnvelope<T>(val message: String? = null, val data: T? = null)
data class LoginRequest(val username: String, val password: String)
data class RegisterRequest(val username: String, val password: String, val displayName: String)
data class LoginPayload(val token: String, val user: UserDto)
data class UserDto(val id: Int, val username: String, val displayName: String? = null, val role: String = "USER")
data class CategoryDto(val id: Int, val name: String)
data class ReceiptDto(
    val id: Int,
    val clientReceiptId: String? = null,
    val amount: String,
    val currency: String,
    val exchangeRate: String? = null,
    val sgdEquivalent: String? = null,
    val receiptDate: String,
    val categoryId: Int,
    val description: String? = null,
    val note: String? = null,
    val imageUrl: String? = null,
    val status: String = "UNCLAIMED",
    val categoryName: String? = null,
    val storageKey: String? = null,
    val originalFileName: String? = null,
    val mimeType: String? = null
)
data class ClaimDto(
    val id: Int,
    val status: String,
    val title: String? = null,
    val receiptIds: List<String>? = emptyList(),
    val totalSgd: String? = null,
    val createdAt: String? = null,
    val receipts: List<ClaimReceiptDto>? = emptyList()
)
data class ClaimReceiptDto(val id: Int)
data class CreateClaimRequest(
    val title: String,
    val startDate: String? = null,
    val endDate: String? = null,
    val receiptIds: List<Int>
)
data class ReportDto(val report: ReportSummary? = null, val files: List<ReportFileDto> = emptyList())
data class ReportSummary(val id: Int, val claimId: Int, val status: String, val createdAt: String? = null, val completedAt: String? = null)
data class ReportFileDto(val id: Int, val reportId: Int, val fileType: String, val storageKey: String, val fileName: String, val mimeType: String, val createdAt: String? = null)
