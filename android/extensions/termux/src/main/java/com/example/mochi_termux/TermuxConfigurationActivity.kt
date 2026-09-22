package com.example.mochi_termux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.mochi_extension.MochiExtensionProtocol
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

class TermuxConfigurationActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var localized: Context
    private val bridge by lazy { TermuxBridge(this) }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            updateStatus()
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val language = intent.getStringExtra(MochiExtensionProtocol.EXTRA_UI_LANGUAGE_TAG)
        localized = createConfigurationContext(Configuration(resources.configuration).apply {
            if (language != null && language in setOf("zh", "zh-CN", "en", "en-US")) {
                setLocale(Locale.forLanguageTag(language))
            }
        })
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun text(value: String): TextView = TextView(this).apply {
            text = value
            textSize = 16f
            setPadding(0, 12, 0, 12)
            column.addView(this)
        }
        fun button(label: Int, action: () -> Unit) = Button(this).apply {
            text = localized.getString(label)
            setOnClickListener { action() }
            column.addView(this)
        }
        text(localized.getString(R.string.setup_title)).textSize = 24f
        text(localized.getString(R.string.setup_help))
        status = text("")
        button(R.string.install) { openTermux() }
        text(localized.getString(R.string.bootstrap_help))
        text(SETUP_COMMAND).setTextIsSelectable(true)
        button(R.string.bootstrap) {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Termux setup", SETUP_COMMAND))
            Toast.makeText(this, localized.getString(R.string.copied), Toast.LENGTH_SHORT).show()
            openTermux()
        }
        button(R.string.grant) { permission.launch(TERMUX_PERMISSION) }
        val test = button(R.string.test) { }
        test.setOnClickListener {
            lifecycleScope.launch {
                test.isEnabled = false
                status.text = localized.getString(R.string.testing)
                try {
                    bridge.connect()
                    status.text = localized.getString(R.string.ready)
                } catch (_: TimeoutCancellationException) {
                    status.text = localized.getString(R.string.failed)
                } catch (_: TermuxException) {
                    status.text = localized.getString(R.string.failed)
                } catch (error: CancellationException) {
                    throw error
                } finally {
                    test.isEnabled = true
                }
            }
        }
        button(R.string.done) { finish() }
        val scroll = ScrollView(this).apply { addView(column) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) updateStatus()
    }

    private fun updateStatus() {
        status.text = localized.getString(if (bridge.connected()) R.string.ready else R.string.not_ready)
    }

    private fun openTermux() {
        val intent = packageManager.getLaunchIntentForPackage("com.termux")
            ?: Intent(Intent.ACTION_VIEW, "https://github.com/termux/termux-app#installation".toUri())
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            status.text = localized.getString(R.string.failed)
        }
    }
}
