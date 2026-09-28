package com.example.mochi_pet.core.tools

import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mochi_pet.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeishuCallbackDiagnosticTest {
    @Test fun samePhoneBrowserReturnsSyntheticCallback(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mochiFeishuCallbackDiagnostic") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                withTimeout(30_000) {
                    FeishuLoopbackCallback().use { callback ->
                        coroutineScope {
                            val result = async { callback.awaitCode("synthetic-state") }
                            activity.onActivity {
                                it.startActivity(Intent(
                                    Intent.ACTION_VIEW,
                                    "$FEISHU_REDIRECT_URI?state=synthetic-state&code=synthetic-code".toUri(),
                                ))
                            }
                            assertEquals("synthetic-code", result.await())
                        }
                    }
                }
            }
            FeishuLoopbackCallback().close()
        } finally {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
