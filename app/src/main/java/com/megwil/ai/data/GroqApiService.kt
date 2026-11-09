package com.megwil.ai.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

data class Message(val role: String, val content: String)
data class GroqRequest(val model: String, val messages: List<Message>)
data class GroqResponse(val choices: List<Choice>)
data class Choice(val message: Message)

interface GroqApi {
    @POST("v1/chat/completions")
    suspend fun getCompletion(
        @Header("Authorization") authHeader: String,
        @Body request: GroqRequest
    ): GroqResponse
}

object GroqApiClient {
    private const val BASE_URL = "https://api.groq.com/"

    val api: GroqApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApi::class.java)
    }
}
