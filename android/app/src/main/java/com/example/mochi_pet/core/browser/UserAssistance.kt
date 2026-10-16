package com.example.mochi_pet.core.browser

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

data class UserAssistanceRequest(
    val id: String,
    val message: String,
    val browser: Boolean,
    val origin: String?,
    val options: List<String>,
)

data class UserAssistanceAnswer(val outcome: String, val answer: String = "")

class UserAssistanceCoordinator(private val timeoutMillis: Long = 10 * 60_000L) {
    private val mutableRequest = MutableStateFlow<UserAssistanceRequest?>(null)
    val request = mutableRequest.asStateFlow()
    private var pending: CompletableDeferred<UserAssistanceAnswer>? = null

    suspend fun ask(message: String, browser: Boolean, origin: String?, options: List<String>): UserAssistanceAnswer {
        require(message.isNotBlank() && message.length <= 1_000) { "Assistance message must contain 1-1000 characters." }
        require(options.size <= 4 && options.distinct().size == options.size && options.all { it.isNotBlank() && it.length <= 100 }) {
            "Use at most four distinct short choices."
        }
        val response = CompletableDeferred<UserAssistanceAnswer>()
        val request = UserAssistanceRequest(UUID.randomUUID().toString(), message, browser, origin, options)
        synchronized(this) {
            check(pending == null) { "A user assistance request is already pending." }
            pending = response
            mutableRequest.value = request
        }
        return try {
            withTimeoutOrNull(timeoutMillis) { response.await() } ?: UserAssistanceAnswer("timed_out")
        } finally {
            synchronized(this) {
                if (pending === response) {
                    pending = null
                    mutableRequest.value = null
                }
            }
            response.cancel()
        }
    }

    @Synchronized
    fun respond(id: String, answer: UserAssistanceAnswer): Boolean {
        require(answer.outcome in setOf("completed", "cancelled") && answer.answer.length <= 2_000)
        if (mutableRequest.value?.id != id) return false
        return pending?.complete(answer) == true
    }

    @Synchronized
    fun cancel() {
        pending?.cancel(CancellationException("User assistance cancelled"))
    }
}
