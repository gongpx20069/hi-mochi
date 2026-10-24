package com.example.mochi_pet.core.browser

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UserAssistanceTest {
    @Test fun `waits for matching answer and rejects duplicate or stale completion`() = runBlocking {
        val coordinator = UserAssistanceCoordinator()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.ask("Which city?", false, null, listOf("London", "Paris"))
        }
        val request = requireNotNull(coordinator.request.value)
        assertFalse(waiting.isCompleted)
        assertFalse(coordinator.respond("stale", UserAssistanceAnswer("completed", "London")))
        assertTrue(coordinator.respond(request.id, UserAssistanceAnswer("completed", "Paris")))
        assertFalse(coordinator.respond(request.id, UserAssistanceAnswer("completed", "London")))
        assertEquals(UserAssistanceAnswer("completed", "Paris"), waiting.await())
        assertNull(coordinator.request.value)
    }

    @Test fun `cancelling owner clears pending request and permits a new request`() = runBlocking {
        val coordinator = UserAssistanceCoordinator()
        val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.ask("Login", true, "https://example.com", emptyList()) }
        val staleId = coordinator.request.value!!.id
        first.cancelAndJoin()
        assertNull(coordinator.request.value)
        val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.ask("Next", false, null, emptyList()) }
        assertFalse(coordinator.respond(staleId, UserAssistanceAnswer("completed")))
        assertTrue(coordinator.respond(coordinator.request.value!!.id, UserAssistanceAnswer("cancelled")))
        assertEquals("cancelled", second.await().outcome)
        assertNull(coordinator.request.value)
    }

    @Test fun `timeout clears request without pretending user completed`() = runBlocking {
        val coordinator = UserAssistanceCoordinator(timeoutMillis = 1)
        assertEquals("timed_out", coordinator.ask("Question", false, null, emptyList()).outcome)
        assertNull(coordinator.request.value)
    }

    @Test fun `invalid request leaves no pending state`() {
        val coordinator = UserAssistanceCoordinator()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { coordinator.ask("", false, null, emptyList()) }
        }
        assertNull(coordinator.request.value)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { coordinator.ask("Question", false, null, listOf("same", "same")) }
        }
        assertNull(coordinator.request.value)
    }
}
