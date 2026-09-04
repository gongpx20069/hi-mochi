package com.example.mochi_mijia

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.example.mochi_extension.MochiExtensionProtocol
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
        intent.getStringExtra(MochiExtensionProtocol.EXTRA_UI_LANGUAGE_TAG)
            ?.takeIf(String::isNotBlank)
            ?.let { languageTag ->
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(languageTag),
                )
            }
        super.onCreate(savedInstanceState)
        buildContent()
        refresh()
    }

    private fun buildContent() {
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
            } catch (_: Exception) {
                statusView.text = getString(R.string.connection_failed)
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
                val (card, checkBox) = deviceSelectionCard(
                    device = device,
                    selected = device.id in session.selectedDeviceIds,
                )
                checkBoxes[device.id] = checkBox
                deviceContainer.addView(
                    card,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = dp(10)
                    },
                )
            }
            deviceContainer.visibility = View.VISIBLE
            saveButton.visibility =
                if (supported.isEmpty()) View.GONE else View.VISIBLE
            disconnectButton.visibility = View.VISIBLE
        } catch (_: Exception) {
            statusView.text = getString(R.string.load_devices_failed)
            disconnectButton.visibility = View.VISIBLE
        }
    }

    private fun deviceSelectionCard(
        device: MijiaDevice,
        selected: Boolean,
    ): Pair<View, CheckBox> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(88)
            isClickable = true
            isFocusable = true
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val checkBox = CheckBox(this).apply {
            isChecked = selected
            buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(),
                ),
                intArrayOf(
                    DEVICE_CARD_ACCENT,
                    DEVICE_CARD_SECONDARY_TEXT,
                ),
            )
            contentDescription = getString(
                R.string.select_device,
                device.name,
            )
        }
        card.addView(
            checkBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(12)
            },
        )
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isDuplicateParentStateEnabled = true
        }
        details.addView(
            TextView(this).apply {
                text = device.name
                textSize = 17f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        details.addView(
            TextView(this).apply {
                text = getString(
                    R.string.device_location,
                    device.homeName,
                    device.roomName ?: getString(R.string.no_room),
                )
                textSize = 13f
                setTextColor(DEVICE_CARD_SECONDARY_TEXT)
                setPadding(0, dp(4), 0, 0)
            },
        )
        details.addView(
            TextView(this).apply {
                text = deviceCategoryLabel(device.category)
                textSize = 12f
                setTextColor(DEVICE_CARD_ACCENT)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(5), 0, 0)
            },
        )
        card.addView(
            details,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        fun updateSelectionStyle(isSelected: Boolean) {
            card.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(
                    if (isSelected) {
                        DEVICE_CARD_SELECTED_BACKGROUND
                    } else {
                        DEVICE_CARD_BACKGROUND
                    },
                )
                setStroke(
                    dp(if (isSelected) 2 else 1),
                    if (isSelected) {
                        DEVICE_CARD_ACCENT
                    } else {
                        DEVICE_CARD_OUTLINE
                    },
                )
            }
        }
        checkBox.setOnCheckedChangeListener { _, isChecked ->
            updateSelectionStyle(isChecked)
        }
        card.setOnClickListener {
            checkBox.isChecked = !checkBox.isChecked
        }
        updateSelectionStyle(selected)
        return card to checkBox
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
            } catch (_: Exception) {
                statusView.text = getString(R.string.save_devices_failed)
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

    private fun deviceCategoryLabel(category: MijiaDeviceCategory): String =
        getString(
            when (category) {
                MijiaDeviceCategory.LIGHT -> R.string.category_light
                MijiaDeviceCategory.SWITCH -> R.string.category_switch
                MijiaDeviceCategory.PLUG -> R.string.category_plug
                MijiaDeviceCategory.FAN -> R.string.category_fan
                MijiaDeviceCategory.AIR_CONDITIONER ->
                    R.string.category_air_conditioner
                MijiaDeviceCategory.AIR_PURIFIER ->
                    R.string.category_air_purifier
                MijiaDeviceCategory.HUMIDIFIER ->
                    R.string.category_humidifier
                MijiaDeviceCategory.CURTAIN -> R.string.category_curtain
                MijiaDeviceCategory.SENSOR -> R.string.category_sensor
                MijiaDeviceCategory.TELEVISION ->
                    R.string.category_television
                MijiaDeviceCategory.CAMERA -> R.string.category_camera
                MijiaDeviceCategory.SCALE -> R.string.category_scale
                MijiaDeviceCategory.UNKNOWN -> R.string.category_unknown
            },
        )

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

    private companion object {
        val DEVICE_CARD_BACKGROUND = 0xFF211F26.toInt()
        val DEVICE_CARD_SELECTED_BACKGROUND = 0xFF2B2440.toInt()
        val DEVICE_CARD_OUTLINE = 0xFF49454F.toInt()
        val DEVICE_CARD_ACCENT = 0xFFD0BCFF.toInt()
        val DEVICE_CARD_SECONDARY_TEXT = 0xFFCAC4D0.toInt()
    }
}
