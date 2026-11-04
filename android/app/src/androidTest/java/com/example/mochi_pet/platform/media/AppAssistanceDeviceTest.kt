package com.example.mochi_pet.platform.media

import android.util.Log
import android.app.UiAutomation
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mochi_pet.MainActivity
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolRegistry
import com.example.mochi_pet.core.extensions.ExtensionToolScope
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppAssistanceDeviceTest {
    @Test fun verifyAuthorizedXiaomiSong(): Unit = runBlocking(Dispatchers.Default) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mochiVerifyXiaomiSong") == "true")
        InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MochiApplication
        check(appAssistanceGranted(application)) { "Enable accessibility manually first" }
        check(mediaNotificationAccessGranted(application)) { "Notification access must already be granted" }
        val context = ToolExecutionContext(LocalDate.now(), MochiSurface.Conversation)
        val enabled: suspend (String) -> Boolean = {
            application.toolCatalogRepository.isBuiltInEnabled(it)
        }
        val registry = ToolRegistry(application.mediaToolRuntime.tools(ExtensionToolScope.FOREGROUND_MAIN, enabled) +
            application.appToolRuntime.tools(ExtensionToolScope.FOREGROUND_MAIN, enabled))
        suspend fun call(name: String, arguments: JsonObject): JsonObject {
            val result = registry.execute(name, arguments, context)
            check(result.status == "ok") { "$name failed: ${result.code}: ${result.message}" }
            return requireNotNull(result.data).jsonObject
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            delay(1_000)
            val players = call("app_read", buildJsonObject {}).getValue("apps").jsonArray.map { it.jsonObject }
            val player = players.single { it.getValue("packageName").jsonPrimitive.content == "com.miui.player" }
            val id = player.getValue("id").jsonPrimitive.content
            suspend fun ui(action: String, ref: String? = null, text: String? = null): JsonObject =
                call("app_$action", buildJsonObject {
                    put("app_id", id)
                    ref?.let { put("ref", it) }; text?.let { put("text", it) }
                })
            suspend fun controls(): List<JsonObject> = ui("read").getValue("snapshot").jsonObject
                .getValue("elements").jsonArray.map { it.jsonObject }
            fun JsonObject.label() = getValue("label").jsonPrimitive.content
            fun JsonObject.supports(action: String) = getValue("actions").jsonArray.any { it.jsonPrimitive.content == action }
            fun JsonObject.ref() = getValue("ref").jsonPrimitive.content
            fun JsonObject.role() = get("role")?.jsonPrimitive?.content
            suspend fun awaitControl(predicate: (JsonObject) -> Boolean): JsonObject {
                repeat(30) {
                    val found = controls().filter(predicate)
                    if (found.size == 1) return found.single()
                    delay(250)
                }
                val safe = controls().filter { it.label().contains("\u641c\u7d22") || it.label().contains("\u6674\u5929") }
                    .map { it.label() to it.getValue("actions").toString() }
                error("No unique supported control; approved-song controls: $safe")
            }
            call("app_navigate", buildJsonObject { put("app_id", id); put("action", "open") })
            delay(1_000)
            repeat(3) {
                val dismiss = controls().filter { it.supports("click") && it.role() == "dismiss" }
                check(dismiss.size <= 1) { "Ambiguous popup controls; a model must inspect this screen" }
                if (dismiss.isNotEmpty()) {
                    ui("click", dismiss.single().ref())
                    delay(350)
                }
            }
            val initial = controls()
            if (initial.none { it.supports("input") }) {
                val entry = initial.filter { it.supports("click") && it.role() == "search" }
                    .minByOrNull { it.label().length } ?: error("No exposed search entry; no guessing or coordinate clicks")
                ui("click", entry.ref())
                delay(350)
            }
            val input = awaitControl { it.supports("input") }
            if (input.supports("click")) {
                ui("click", input.ref())
                delay(250)
            }
            val focused = awaitControl { it.supports("input") }
            val query = "\u6674\u5929 \u5468\u6770\u4f26"
            ui("input", focused.ref(), query)
            val suggestion = awaitControl { it.supports("click") && !it.supports("input") && it.label() == query }
            ui("click", suggestion.ref())
            val song = awaitControl {
                it.supports("click") && it.label().contains("\u6674\u5929") && it.label().contains("\u5468\u6770\u4f26") &&
                    !Regex("Live|DJ|\u4f34\u594f|\u7ffb\u5531", RegexOption.IGNORE_CASE).containsMatchIn(it.label())
            }
            ui("click", song.ref())
            val mediaPlayers = call("media_read", buildJsonObject {}).getValue("snapshot").jsonObject
                .getValue("players").jsonArray.map { it.jsonObject }
            val mediaId = mediaPlayers.single { it.getValue("packageName").jsonPrimitive.content == "com.miui.player" }
                .getValue("id").jsonPrimitive.content
            val verified = call("media_read", buildJsonObject {
                put("player_id", mediaId)
                put("title", "\u6674\u5929"); put("artist", "\u5468\u6770\u4f26")
            })
            assertEquals("true", verified.getValue("verified").jsonPrimitive.content)
            val returned = call("app_navigate", buildJsonObject { put("app_id", id); put("action", "return") })
            assertEquals("true", returned.getValue("returnedToMochi").jsonPrimitive.content)
            Log.i("MochiAppUiCheck", "verified title=晴天 artist=周杰伦 playing=true returnedToMochi=true productionTools=true")
        }
    }
}
