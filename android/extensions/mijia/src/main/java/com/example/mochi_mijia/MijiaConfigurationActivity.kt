package com.example.mochi_mijia

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class MijiaConfigurationActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var qrView: ImageView
    private lateinit var primaryButton: Button
    private lateinit var saveButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var deviceContainer: LinearLayout
    private val graph by lazy { MijiaGraph.get(this) }
    private val checkBoxes = LinkedHashMap<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildContent()
        refresh()
    }

    private fun buildContent() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        root.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 24f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            TextView(this).apply {
                text = getString(R.string.extension_description)
                textSize = 14f
                setTextColor(0xFFCAC4D0.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(16))
            },
        )
        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFEADDFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(statusView)
        qrView = ImageView(this).apply {
            visibility = View.GONE
            contentDescription = getString(R.string.qr_content_description)
            adjustViewBounds = true
        }
        root.addView(
            qrView,
            LinearLayout.LayoutParams(dp(300), dp(300)),
        )
        primaryButton = Button(this).apply {
            text = getString(R.string.generate_qr)
            setOnClickListener { startQrLogin() }
        }
        root.addView(primaryButton)
        deviceContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(
            deviceContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        saveButton = Button(this).apply {
            text = getString(R.string.save_selected_devices)
            visibility = View.GONE
            setOnClickListener { saveSelection() }
        }
        root.addView(saveButton)
        disconnectButton = Button(this).apply {
            text = getString(R.string.disconnect_mijia)
            visibility = View.GONE
            setOnClickListener { disconnect() }
        }
        root.addView(disconnectButton)
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(0xFF141218.toInt())
                addView(root)
            },
        )
    }

    private fun refresh() {
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) {
                graph.sessionStore.load()
            }
            if (session == null) {
                showDisconnected()
                startQrLogin()
            } else {
                showDeviceSelection()
            }
        }
    }

    private fun showDisconnected() {
        statusView.text = getString(R.string.scan_instructions)
        qrView.visibility = View.GONE
        primaryButton.visibility = View.VISIBLE
        primaryButton.isEnabled = true
        deviceContainer.visibility = View.GONE
        saveButton.visibility = View.GONE
        disconnectButton.visibility = View.GONE
    }

    private fun startQrLogin() {
        primaryButton.isEnabled = false
        statusView.text = getString(R.string.requesting_qr)
        lifecycleScope.launch {
            try {
                val challenge = withContext(Dispatchers.IO) {
                    graph.passportQrClient.begin()
                }
                qrView.setImageBitmap(
                    withContext(Dispatchers.Default) {
                        qrBitmap(challenge.loginUrl)
                    },
                )
                qrView.visibility = View.VISIBLE
                val timeoutSeconds = challenge.timeoutSeconds
                    .coerceIn(1, Int.MAX_VALUE.toLong())
                    .toInt()
                statusView.text = resources.getQuantityString(
                    R.plurals.qr_expires,
                    timeoutSeconds,
                    timeoutSeconds,
                )
                val session = withTimeout(
                    (challenge.timeoutSeconds + 15) * 1_000,
                ) {
                    graph.passportQrClient.complete(challenge)
                }
                statusView.text = getString(R.string.detecting_region)
                withContext(Dispatchers.IO) {
                    graph.repository.ensureRegion()
                }
                statusView.text = getString(
                    R.string.connected_as,
                    session.userId.takeLast(4),
                )
                qrView.visibility = View.GONE
                showDeviceSelection()
            } catch (error: Exception) {
                statusView.text = error.message
                    ?: getString(R.string.connection_failed)
                qrView.visibility = View.GONE
                primaryButton.isEnabled = true
                primaryButton.text = getString(R.string.generate_new_qr)
            }
        }
    }

    private suspend fun showDeviceSelection() {
        primaryButton.visibility = View.GONE
        qrView.visibility = View.GONE
        statusView.text = getString(R.string.loading_devices)
        try {
            val (homes, devices) = withContext(Dispatchers.IO) {
                graph.repository.homesAndDevices()
            }
            val session = withContext(Dispatchers.IO) {
                checkNotNull(graph.sessionStore.load())
            }
            val supported = devices
                .filter { it.category in SUPPORTED_MIJIA_CATEGORIES }
                .sortedWith(
                    compareBy(
                        MijiaDevice::homeName,
                        { it.roomName.orEmpty() },
                        MijiaDevice::name,
                    ),
                )
            statusView.text = if (supported.isEmpty()) {
                resources.getQuantityString(
                    R.plurals.no_supported_devices,
                    homes.size,
                    homes.size,
                )
            } else {
                getString(R.string.choose_devices)
            }
            checkBoxes.clear()
            deviceContainer.removeAllViews()
            supported.forEach { device ->
                val label = getString(
                    R.string.device_label,
                    device.name,
                    device.homeName,
                    device.roomName ?: getString(R.string.no_room),
                    device.category.wireName.replace('_', ' '),
                )
                val checkBox = CheckBox(this).apply {
                    text = label
                    isChecked = device.id in session.selectedDeviceIds
                    setTextColor(Color.WHITE)
                }
                checkBoxes[device.id] = checkBox
                deviceContainer.addView(checkBox)
            }
            deviceContainer.visibility = View.VISIBLE
            saveButton.visibility =
                if (supported.isEmpty()) View.GONE else View.VISIBLE
            disconnectButton.visibility = View.VISIBLE
        } catch (error: Exception) {
            statusView.text =
                error.message ?: getString(R.string.load_devices_failed)
            disconnectButton.visibility = View.VISIBLE
        }
    }

    private fun saveSelection() {
        saveButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val selected = checkBoxes
                    .filterValues(CheckBox::isChecked)
                    .keys
                withContext(Dispatchers.IO) {
                    graph.repository.saveSelectedDevices(selected)
                }
                setResult(Activity.RESULT_OK)
                finish()
            } catch (error: Exception) {
                statusView.text =
                    error.message ?: getString(R.string.save_devices_failed)
                saveButton.isEnabled = true
            }
        }
    }

    private fun disconnect() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                graph.sessionStore.clear()
            }
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun qrBitmap(value: String): Bitmap {
        val matrix = MultiFormatWriter().encode(
            value,
            BarcodeFormat.QR_CODE,
            720,
            720,
        )
        return createBitmap(
            matrix.width,
            matrix.height,
            config = Bitmap.Config.ARGB_8888,
        ).apply {
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    this[x, y] =
                        if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
        }
    }
}
