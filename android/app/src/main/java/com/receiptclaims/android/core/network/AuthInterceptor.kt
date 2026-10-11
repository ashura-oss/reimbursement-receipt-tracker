package com.receiptclaims.android.core.network

import com.receiptclaims.android.core.security.SessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val sessionStore: SessionStore
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { sessionStore.accessToken() }
        val request = chain.request().newBuilder().apply {
            if (chain.request().header("Authorization").isNullOrBlank() && !token.isNullOrBlank()) header("Authorization", "Bearer $token")
            addHeader("Accept", "application/json")
        }.build()
        val response = chain.proceed(request)
        val authHeader = request.header("Authorization")
        val requestedPath = request.url.encodedPath
        val isPublicAuthRequest = requestedPath.endsWith("/auth/login") || requestedPath.endsWith("/auth/register")
        if (response.code == 401 && !isPublicAuthRequest && authHeader?.startsWith("Bearer ") == true) {
            val failedToken = authHeader.removePrefix("Bearer ").trim()
            if (failedToken.isNotEmpty()) runBlocking { sessionStore.clearIfAccessTokenMatches(failedToken) }
        }
        return response
    }
}
