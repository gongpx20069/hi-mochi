package com.example.mochi_pet.core.tools

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.core.mcp.NOTION_SERVER_ID
import com.example.mochi_pet.core.mcp.TENCENT_DOCS_SERVER_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentToolCatalogDiagnosticTest {
    @Test
    fun inspectConnectedDocumentTools(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mochiDocumentToolDiagnostic") == "true")
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MochiApplication
        val summary = application.toolCatalogRepository.loadSummary()
        assertNull("Document Tool discovery failed; inspect connection in Tools", summary.feedback)
        summary.servers.filter { it.id in setOf(NOTION_SERVER_ID, TENCENT_DOCS_SERVER_ID) }.forEach { server ->
            Log.i("MochiDocumentTools", "provider=${server.id} connected=${server.connected} enabled=${server.enabled} tools=${server.tools.size}")
            server.tools.forEach { tool ->
                Log.i("MochiDocumentTools", "provider=${server.id} tool=${tool.remoteName} enabled=${tool.enabled}")
            }
        }
    }
}
