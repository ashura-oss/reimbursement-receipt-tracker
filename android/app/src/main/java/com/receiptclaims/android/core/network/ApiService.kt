package com.receiptclaims.android.core.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

interface ApiService {
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): ApiEnvelope<LoginPayload>

    @GET("categories")
    suspend fun categories(): ApiEnvelope<List<CategoryDto>>

    @Multipart
    @POST("receipts")
    suspend fun uploadReceipt(
        @Header("Idempotency-Key") clientReceiptId: String,
        @Part image: MultipartBody.Part,
        @Part("amount") amount: RequestBody,
        @Part("currency") currency: RequestBody,
        @Part("receiptDate") receiptDate: RequestBody,
        @Part("categoryId") categoryId: RequestBody,
        @Part("description") description: RequestBody?,
        @Part("note") note: RequestBody?
    ): ApiEnvelope<ReceiptDto>

    @GET("receipts")
    suspend fun receipts(): ApiEnvelope<List<ReceiptDto>>

    @GET("receipts/{id}")
    suspend fun receipt(@Path("id") id: Int): ApiEnvelope<ReceiptDto>

    @POST("claims")
    suspend fun createClaim(@Body request: CreateClaimRequest): ApiEnvelope<ClaimDto>

    @GET("claims")
    suspend fun claims(): ApiEnvelope<List<ClaimDto>>

    @POST("reports/claims/{id}")
    suspend fun generateReport(@Path("id") claimId: Int): ApiEnvelope<ReportDto>
}
