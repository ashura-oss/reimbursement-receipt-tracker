package com.receiptclaims.android.core.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming
import retrofit2.Response
import okhttp3.ResponseBody
import retrofit2.http.Header

interface ApiService {
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): ApiEnvelope<LoginPayload>

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): ApiEnvelope<UserDto>

    @GET("categories")
    suspend fun categories(): ApiEnvelope<List<CategoryDto>>

    @Multipart
    @POST("receipts")
    suspend fun uploadReceipt(
        @Part image: MultipartBody.Part,
        @Part("clientReceiptId") clientReceiptId: RequestBody,
        @Part("amount") amount: RequestBody,
        @Part("currency") currency: RequestBody,
        @Part("receiptDate") receiptDate: RequestBody,
        @Part("categoryId") categoryId: RequestBody,
        @Part("description") description: RequestBody?,
        @Part("note") note: RequestBody?,
        @Header("Authorization") authorization: String
    ): ApiEnvelope<ReceiptDto>

    @GET("receipts")
    suspend fun receipts(@Header("Authorization") authorization: String): ApiEnvelope<List<ReceiptDto>>

    @GET("receipts/{id}")
    suspend fun receipt(@Path("id") id: Int): ApiEnvelope<ReceiptDto>

    @Streaming
    @GET("receipts/{id}/image")
    suspend fun receiptImage(@Path("id") id: Int, @Header("Authorization") authorization: String): Response<ResponseBody>

    @POST("claims")
    suspend fun createClaim(@Body request: CreateClaimRequest, @Header("Authorization") authorization: String): ApiEnvelope<ClaimDto>

    @GET("claims")
    suspend fun claims(@Header("Authorization") authorization: String): ApiEnvelope<List<ClaimDto>>

    @GET("claims/{id}")
    suspend fun claim(@Path("id") claimId: Int, @Header("Authorization") authorization: String): ApiEnvelope<ClaimDto>

    @POST("reports/claims/{id}")
    suspend fun generateReport(@Path("id") claimId: Int, @Header("Authorization") authorization: String): ApiEnvelope<ReportDto>

    @GET("reports/claims/{id}/latest")
    suspend fun latestReport(@Path("id") claimId: Int, @Header("Authorization") authorization: String): ApiEnvelope<ReportDto>

    /** Protected download; the auth interceptor supplies the user's bearer token. */
    @Streaming
    @GET("report-files/{id}/download")
    suspend fun downloadReportFile(@Path("id") fileId: Int, @Header("Authorization") authorization: String): Response<ResponseBody>
}
