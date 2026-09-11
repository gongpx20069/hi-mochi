package com.example.mochi_pet.feature.home

import com.example.mochi_pet.core.agent.llm.ProviderConfigurationException
import com.example.mochi_pet.core.agent.llm.ProviderHttpException
import com.example.mochi_pet.core.agent.llm.ProviderNetworkException
import com.example.mochi_pet.core.agent.llm.ProviderProtocolException
import com.example.mochi_pet.core.agent.llm.ProviderResponseTooLargeException
import java.io.InterruptedIOException

internal fun conversationErrorMessage(error: Throwable): String =
    when (error) {
        is ProviderNetworkException -> {
            if (error.cause is InterruptedIOException) {
                "The AI service timed out. Check your network and provider status."
            } else {
                "Could not connect to the AI service. Check your network, proxy, and provider endpoint."
            }
        }
        is ProviderHttpException -> when (error.statusCode) {
            408, 504 ->
                "The AI service timed out. Check your network and provider status."
            401, 403 ->
                "AI service access was denied. Check your API key and model permissions in Settings."
            429 ->
                "The AI service is rate-limited or out of quota. Check your quota or try again later."
            in 500..599 ->
                "The AI service is temporarily unavailable. Try again later."
            else ->
                "The AI service rejected this request. Check your provider and model settings."
        }
        is ProviderConfigurationException ->
            "The AI provider configuration is invalid. Check the endpoint and model in Settings."
        is ProviderProtocolException ->
            "The AI service returned an unsupported response. Check model compatibility."
        is ProviderResponseTooLargeException ->
            "The AI service response exceeded the size limit. Try a smaller request."
        else -> "Mochi could not complete this request"
    }
