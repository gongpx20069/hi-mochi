package com.example.mochi_pet.core.agent.llm

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.createAgentRunner
import com.example.mochi_pet.core.agent.AgentPipelineObserver
import com.example.mochi_pet.core.agent.AgentRunRequest
import com.example.mochi_pet.core.agent.tool.ToolDiscovery
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.model.MochiSurface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.GzipSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AzureTransportDiagnosticTest {
    private class SchemasCaptured : RuntimeException()

    @Test fun byteCountersDistinguishCompressedAndDecodedBodies() {
        val text = "Synthetic repeated response. ".repeat(1000)
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val encodedBytes = AtomicLong()
        val decodedBytes = AtomicLong()
        val encoded = compressed.toResponseBody().counted(encodedBytes)
        val decoded = GzipSource(encoded.source()).buffer().asResponseBody().counted(decodedBytes)
        decoded.use { assertEquals(text, it.string()) }
        assertEquals(compressed.size.toLong(), encodedBytes.get())
        assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), decodedBytes.get())
    }

    @Test fun measureRealResponseCompression() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("mochiAzureTransportDiagnostic") == "true")
        val app = instrumentation.targetContext.applicationContext as MochiApplication
        val before = app.providerSettingsRepository.loadProfiles()
        val speechBefore = app.speechSettingsRepository.loadProfiles()
        val config = app.providerSettingsRepository.loadRuntimeConfig().forForegroundRequest()
        check(config.providerType == ProviderType.AZURE_OPENAI && config.apiProtocol == ApiProtocol.RESPONSES)
        check(config.endpoint.toHttpUrl().host == arguments.getString("mochiAzureResourceHost") &&
            config.model == arguments.getString("mochiAzureDeployment")) {
            "The active connection must match the explicitly authorized Azure deployment"
        }
        val schemaSets = captureSchemaSets(app, config)
        val rows = mutableListOf<JsonObject>()
        val successful = mutableSetOf<Pair<String, Boolean>>()
        val destination = app.cacheDir.resolve("azure-transport-results.json")
        fun save(complete: Boolean) = destination.writeText(buildJsonObject {
            put("complete", complete)
            put("protocol", config.apiProtocol.name)
            put("timeout_seconds", config.timeoutSeconds)
            put("business_calls_executed", 0)
            put("rows", JsonArray(rows))
        }.toString())
        val active = AtomicReference<Trace>()
        val base = OkHttpClient.Builder()
            .eventListenerFactory { checkNotNull(active.get()) }
            .addInterceptor { chain ->
                val trace = checkNotNull(active.get())
                val request = chain.request().newBuilder().tag(Trace::class.java, trace).apply {
                    if (trace.identity) header("Accept-Encoding", "identity")
                }.build()
                val response = chain.proceed(request)
                response.newBuilder().body(response.body.counted(trace.decodedBytes)).build()
            }
            .addNetworkInterceptor { chain ->
                val trace = checkNotNull(chain.request().tag(Trace::class.java))
                val response = chain.proceed(chain.request())
                trace.headers(response)
                response.newBuilder().body(response.body.counted(trace.encodedBytes)).build()
            }
            .build()
        val client = OkHttpOpenAiChatClient(base)
        save(false)
        try {
            withTimeout(6 * 60_000L) {
                repeat(2) { repetition ->
                    schemaSets.entries.forEachIndexed { index, (set, schemas) ->
                        val order = if ((repetition + index) % 2 == 0) listOf(false, true) else listOf(true, false)
                        for (identity in order) {
                            val trace = Trace(identity)
                            active.set(trace)
                            var failure: String? = null
                            try {
                                client.complete(config, OpenAiChatRequest(
                                    model = config.model,
                                    messages = listOf(OpenAiChatMessage("user", "Reply exactly OK. Do not call any tools.")),
                                    tools = schemas,
                                ))
                                successful += set to identity
                            } catch (error: OpenAiProviderException) {
                                failure = error.javaClass.simpleName
                            }
                            rows += trace.result(set, repetition, schemas.size, failure)
                            save(false)
                        }
                    }
                }
            }
            save(true)
            check(schemaSets.keys.all { (it to false) in successful && (it to true) in successful }) {
                "Each schema set needs a successful response in both variants; inspect the recorded failures"
            }
        } finally {
            base.dispatcher.executorService.shutdown()
            base.connectionPool.evictAll()
            check(before == app.providerSettingsRepository.loadProfiles()) { "Provider settings changed" }
            check(speechBefore == app.speechSettingsRepository.loadProfiles()) { "Speech settings changed" }
        }
    }

    private suspend fun captureSchemaSets(app: MochiApplication, config: OpenAiProviderConfig): Map<String, List<JsonObject>> {
        val sets = linkedMapOf("no_tools" to emptyList<JsonObject>())
        val target = "tencent_docs_smartsheet_add_fields"
        val client = object : OpenAiChatClient {
            override suspend fun complete(config: OpenAiProviderConfig, request: OpenAiChatRequest): OpenAiChatResponse {
                if ("base" !in sets) {
                    sets["base"] = request.tools
                    return OpenAiChatResponse(listOf(OpenAiChoice(OpenAiChatMessage("assistant", toolCalls = listOf(
                        OpenAiToolCall("transport-search", function = OpenAiFunctionCall(
                            "tool_search", """{"query":"$target","limit":1}""",
                        )),
                    )))))
                }
                check(request.tools.any { ToolDiscovery.schemaName(it) == target }) {
                    "The large-schema fixture must already be enabled; no settings are changed for this diagnostic"
                }
                sets["base_plus_large_tool"] = request.tools
                throw SchemasCaptured()
            }
        }
        val runner = app.createAgentRunner(
            sink = { error("Transport capture must not navigate") },
            observer = AgentPipelineObserver { _, _ -> },
            onWeatherLoaded = { error("Transport capture must not read location") },
            includeBrowser = true, chatClient = client,
        )
        try {
            runner.run(AgentRunRequest(
                provider = config, query = "Collect transport fixture schemas only.", currentEmotion = "neutral",
                context = ToolExecutionContext(LocalDate.now(), MochiSurface.Conversation),
            ))
            error("Schema capture must stop before a real model or business call")
        } catch (_: SchemasCaptured) {
            return sets
        }
    }

    private class Trace(val identity: Boolean) : EventListener() {
        private val started = SystemClock.elapsedRealtime()
        private val events = mutableListOf<JsonObject>()
        private var droppedEvents = 0
        private var responseCode: Int? = null
        private var responseEncoding: String? = null
        private var requestEncoding: String? = null
        private var httpProtocol: String? = null
        private var connectStarts = 0
        private var exchanges = 0
        private var requestBytes = 0L
        val encodedBytes = AtomicLong()
        val decodedBytes = AtomicLong()

        @Synchronized private fun event(name: String) {
            if (events.size == 64) { droppedEvents++; return }
            events += buildJsonObject {
                put("event", name)
                put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            }
        }

        @Synchronized fun headers(response: Response) {
            exchanges++
            responseCode = response.code
            responseEncoding = encoding(response.header("Content-Encoding"))
            requestEncoding = encoding(response.request.header("Accept-Encoding"))
            httpProtocol = response.protocol.toString()
        }

        @Synchronized fun result(set: String, repetition: Int, count: Int, failure: String?) = buildJsonObject {
            put("schema_set", set)
            put("repetition", repetition)
            put("variant", if (identity) "identity" else "default")
            put("tool_count", count)
            put("status", if (failure == null) "success" else "failure")
            failure?.let { put("error_type", it) }
            responseCode?.let { put("http_status", it) }
            responseEncoding?.let { put("content_encoding", it) }
            requestEncoding?.let { put("accept_encoding", it) }
            httpProtocol?.let { put("http_protocol", it) }
            put("connect_attempts", connectStarts)
            put("network_exchanges", exchanges)
            put("request_body_bytes", requestBytes)
            put("encoded_body_bytes", encodedBytes.get())
            put("decoded_body_bytes", decodedBytes.get())
            put("total_ms", SystemClock.elapsedRealtime() - started)
            put("dropped_events", droppedEvents)
            put("events", JsonArray(events.toList()))
        }

        override fun callStart(call: Call) = event("call_start")
        override fun dnsStart(call: Call, domainName: String) = event("dns_start")
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = event("dns_end")
        @Synchronized override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStarts++
            event("connect_start")
        }
        override fun secureConnectStart(call: Call) = event("tls_start")
        override fun secureConnectEnd(call: Call, handshake: Handshake?) = event("tls_end")
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = event("connect_end")
        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) =
            event("connect_failed")
        override fun connectionAcquired(call: Call, connection: Connection) = event("connection_acquired")
        override fun requestHeadersStart(call: Call) = event("request_headers_start")
        @Synchronized override fun requestBodyEnd(call: Call, byteCount: Long) {
            requestBytes += byteCount
            event("request_body_end")
        }
        override fun responseHeadersEnd(call: Call, response: Response) = event("response_headers_end")
        override fun responseBodyStart(call: Call) = event("response_body_start")
        override fun responseBodyEnd(call: Call, byteCount: Long) = event("response_body_end")
        override fun callEnd(call: Call) = event("call_end")
        override fun callFailed(call: Call, ioe: IOException) = event("call_failed")

        private fun encoding(value: String?): String = when (value?.lowercase()) {
            null, "identity" -> "identity"
            "gzip" -> "gzip"
            else -> "other"
        }
    }

    private companion object {
        fun ResponseBody.counted(bytes: AtomicLong): ResponseBody {
            val delegate = this
            val source = object : ForwardingSource(delegate.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                    if (it > 0) bytes.addAndGet(it)
                }
            }.buffer()
            return object : ResponseBody() {
                override fun contentType() = delegate.contentType()
                override fun contentLength() = delegate.contentLength()
                override fun source() = source
            }
        }
    }
}
