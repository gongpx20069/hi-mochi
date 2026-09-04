package com.example.mochi_mijia

import android.content.Context
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import com.example.mochi_extension.ExtensionApiLimits
import com.example.mochi_extension.ExtensionAttachmentDescriptor
import com.example.mochi_extension.ExtensionAttachmentPresentation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class OpenedCameraAttachment(
    val descriptor: ExtensionAttachmentDescriptor,
    val fileDescriptor: ParcelFileDescriptor,
)

class CameraEventClient(
    context: Context,
    client: OkHttpClient = OkHttpClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val cacheDirectory = File(
        context.noBackupFilesDir,
        "camera-event-cache",
    ).apply {
        deleteRecursively()
        mkdirs()
    }
    private val client = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val attachments =
        ConcurrentHashMap<String, CachedCameraAttachment>()

    suspend fun latestEvent(
        session: MijiaSession,
        device: MijiaDevice,
    ): ExtensionAttachmentDescriptor = withContext(Dispatchers.IO) {
        cleanupExpired()
        val endTime = nowMillis()
        val eventData = buildJsonObject {
            put("did", device.id)
            put("model", device.model)
            put("doorBell", false)
            put("eventType", "Default")
            put("needMerge", true)
            put("sortType", "DESC")
            put("region", session.region.orEmpty().uppercase())
            put("language", "en_US")
            put("beginTime", endTime - EVENT_LOOKBACK_MILLIS)
            put("endTime", endTime)
            put("limit", 2)
        }
        val eventResponse = executeEncryptedJson(
            session = session,
            host = cameraHost(session.region, "business"),
            path = "/common/app/get/eventlist",
            data = eventData,
        )
        coroutineContext.ensureActive()
        val event = eventResponse["data"]?.jsonObject
            ?.get("thirdPartPlayUnits")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?: throw MijiaNotFoundException(
                "No recent camera motion or doorbell event was found.",
            )
        val fileId = event.stringOrNull("fileId")
            ?: throw MijiaNotFoundException(
                "The latest camera event has no image file.",
            )
        val storeId = event.stringOrNull("imgStoreId")
            ?: throw MijiaNotFoundException(
                "The latest camera event image is unsupported.",
            )
        val iv = ByteArray(16).also(SecureRandom()::nextBytes)
        val imageData = buildJsonObject {
            put("did", device.id)
            put("fileId", fileId)
            put("stoId", storeId)
            put("segmentIv", Base64.getEncoder().encodeToString(iv))
        }
        val encryptedImage = executeEncryptedBytes(
            session = session,
            host = cameraHost(session.region, "processor"),
            path = "/miot/camera/app/v1/img",
            data = imageData,
            additionalQuery = mapOf(
                "yetAnotherServiceToken" to session.serviceToken,
            ),
        )
        coroutineContext.ensureActive()
        val image = decryptImage(session.ssecurity, iv, encryptedImage)
        val mimeType = imageMimeType(image)
            ?: throw MijiaProviderException(
                "The camera event did not return a JPEG or PNG image.",
            )
        val dimensions = decodeDimensions(image)
        val id = "attachment-${UUID.randomUUID()}"
        val file = File(cacheDirectory, "$id.img")
        coroutineContext.ensureActive()
        file.writeBytes(image)
        try {
            coroutineContext.ensureActive()
        } catch (error: CancellationException) {
            file.delete()
            throw error
        }
        val metadata = buildJsonObject {
            put("camera_name", device.name)
            put("home", device.homeName)
            device.roomName?.let { put("room", it) }
            event.stringOrNull("eventType")?.let { put("event_type", it) }
            event.stringOrNull("createTime")?.let { put("captured_at", it) }
        }.toString()
        val descriptor = ExtensionAttachmentDescriptor(
            attachmentId = id,
            mimeType = mimeType,
            byteCount = image.size.toLong(),
            widthPixels = dimensions.first,
            heightPixels = dimensions.second,
            expiresAtEpochMillis = nowMillis() + ATTACHMENT_TTL_MILLIS,
            presentation = ExtensionAttachmentPresentation.CAMERA_SNAPSHOT,
            metadataJson = metadata,
        )
        attachments[id] = CachedCameraAttachment(descriptor, file)
        descriptor
    }

    fun open(attachmentId: String): OpenedCameraAttachment {
        cleanupExpired()
        val cached = attachments.remove(attachmentId)
            ?: throw MijiaNotFoundException(
                "The camera event image expired or was already opened.",
            )
        val descriptor = cached.descriptor
        if (descriptor.expiresAtEpochMillis <= nowMillis()) {
            cached.file.delete()
            throw MijiaNotFoundException("The camera event image expired.")
        }
        val fileDescriptor = ParcelFileDescriptor.open(
            cached.file,
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
        cached.file.delete()
        return OpenedCameraAttachment(descriptor, fileDescriptor)
    }

    fun clear() {
        attachments.clear()
        cacheDirectory.listFiles()?.forEach(File::delete)
    }

    private suspend fun executeEncryptedJson(
        session: MijiaSession,
        host: String,
        path: String,
        data: JsonObject,
    ): JsonObject {
        val prepared = CameraRc4Crypto.prepare(
            method = "GET",
            path = path,
            ssecurity = session.ssecurity,
            data = data.toString(),
        )
        val response = execute(
            session,
            host,
            path,
            prepared.parameters,
        )
        val decoded = CameraRc4Crypto.decryptResponse(
            response,
            prepared.signedNonce,
        )
        return try {
            json.parseToJsonElement(decoded.toString(Charsets.UTF_8)).jsonObject
        } catch (error: IllegalArgumentException) {
            throw MijiaProviderException(
                "Camera event response was invalid.",
                error,
            )
        }
    }

    private suspend fun executeEncryptedBytes(
        session: MijiaSession,
        host: String,
        path: String,
        data: JsonObject,
        additionalQuery: Map<String, String>,
    ): ByteArray {
        val prepared = CameraRc4Crypto.prepare(
            method = "GET",
            path = path,
            ssecurity = session.ssecurity,
            data = data.toString(),
        )
        return execute(
            session,
            host,
            path,
            prepared.parameters + additionalQuery,
        )
    }

    private suspend fun execute(
        session: MijiaSession,
        host: String,
        path: String,
        query: Map<String, String>,
    ): ByteArray {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(host)
            .encodedPath(path)
            .apply {
                query.forEach { (name, value) ->
                    addQueryParameter(name, value)
                }
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("MIOT-ENCRYPT-ALGORITHM", "ENCRYPT-RC4")
            .header("Accept-Encoding", "identity")
            .header(
                "Cookie",
                "PassportDeviceId=${session.deviceId};" +
                    "userId=${session.userId};" +
                    "serviceToken=${session.serviceToken}",
            )
            .get()
            .build()
        val response = try {
            client.newCall(request).awaitResponse()
        } catch (error: IOException) {
            throw MijiaProviderException("Camera event request failed.", error)
        }
        response.use {
            if (!it.isSuccessful) {
                if (it.code == 401 || it.code == 403) {
                    throw MijiaAuthorizationExpiredException()
                }
                throw MijiaProviderException(
                    "Camera event request failed with HTTP ${it.code}.",
                )
            }
            val declaredLength = it.body.contentLength()
            if (declaredLength > MAX_ENCRYPTED_BYTES) {
                throw MijiaProviderException("Camera event image is too large.")
            }
            val output = ByteArrayOutputStream()
            it.body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > MAX_ENCRYPTED_BYTES) {
                        throw MijiaProviderException(
                            "Camera event response is too large.",
                        )
                    }
                }
            }
            return output.toByteArray()
        }
    }

    private fun decryptImage(
        ssecurity: String,
        iv: ByteArray,
        encrypted: ByteArray,
    ): ByteArray {
        val key = Base64.getDecoder().decode(ssecurity)
        require(key.size == 16) {
            "Camera image encryption key is invalid."
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv),
        )
        val decrypted = try {
            cipher.doFinal(encrypted)
        } catch (error: Exception) {
            throw MijiaProviderException(
                "Camera event image could not be decrypted.",
                error,
            )
        }
        if (decrypted.size > ExtensionApiLimits.MAX_ATTACHMENT_BYTES) {
            throw MijiaProviderException("Camera event image is too large.")
        }
        return decrypted
    }

    private fun decodeDimensions(image: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(image, 0, image.size, options)
        if (
            options.outWidth !in 1..ExtensionApiLimits.MAX_IMAGE_DIMENSION ||
            options.outHeight !in 1..ExtensionApiLimits.MAX_IMAGE_DIMENSION
        ) {
            throw MijiaProviderException(
                "Camera event image dimensions are invalid.",
            )
        }
        return options.outWidth to options.outHeight
    }

    private fun cleanupExpired() {
        val now = nowMillis()
        attachments.entries.removeIf { entry ->
            val expired = entry.value.descriptor.expiresAtEpochMillis <= now
            if (expired) entry.value.file.delete()
            expired
        }
    }

    private fun imageMimeType(bytes: ByteArray): String? =
        when {
            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes.size >= PNG_MAGIC.size &&
                bytes.copyOf(PNG_MAGIC.size).contentEquals(PNG_MAGIC) ->
                "image/png"
            else -> null
        }

    private fun cameraHost(
        region: String?,
        service: String,
    ): String {
        val prefix = region?.takeUnless { it == "cn" || it.isBlank() }
            ?.let { "$it." }
            .orEmpty()
        return "$prefix$service.smartcamera.api.io.mi.com"
    }

    private data class CachedCameraAttachment(
        val descriptor: ExtensionAttachmentDescriptor,
        val file: File,
    )

    private companion object {
        const val EVENT_LOOKBACK_MILLIS = 7L * 24 * 60 * 60 * 1_000
        const val ATTACHMENT_TTL_MILLIS = 5L * 60 * 1_000
        const val MAX_ENCRYPTED_BYTES =
            ExtensionApiLimits.MAX_ATTACHMENT_BYTES + 64 * 1_024
        const val USER_AGENT =
            "Android-7.1.1-1.0.7-ONEPLUS A3010-136-" +
                "AB56E7B4A2-4"
        val PNG_MAGIC = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}

data class PreparedCameraRequest(
    val signedNonce: String,
    val parameters: LinkedHashMap<String, String>,
)

object CameraRc4Crypto {
    fun prepare(
        method: String,
        path: String,
        ssecurity: String,
        data: String,
        nonce: String = MiotCrypto.nonce(),
    ): PreparedCameraRequest {
        val signedNonce = MiotCrypto.signedNonce(ssecurity, nonce)
        val plain = linkedMapOf("data" to data)
        val firstSignature = sha1Signature(
            method,
            path,
            plain,
            signedNonce,
        )
        plain["rc4_hash__"] = firstSignature
        val encrypted = LinkedHashMap<String, String>()
        plain.forEach { (name, value) ->
            encrypted[name] = Base64.getEncoder().encodeToString(
                rc4Drop1024(
                    key = Base64.getDecoder().decode(signedNonce),
                    input = value.toByteArray(Charsets.UTF_8),
                ),
            )
        }
        val finalSignature = sha1Signature(
            method,
            path,
            encrypted,
            signedNonce,
        )
        encrypted["signature"] = finalSignature
        encrypted["ssecurity"] = ssecurity
        encrypted["_nonce"] = nonce
        return PreparedCameraRequest(signedNonce, encrypted)
    }

    fun decryptResponse(
        response: ByteArray,
        signedNonce: String,
    ): ByteArray {
        val encoded = response.toString(Charsets.UTF_8).trim()
        if (encoded.startsWith("{")) return response
        val encrypted = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            response
        }
        val decrypted = rc4Drop1024(
            Base64.getDecoder().decode(signedNonce),
            encrypted,
        )
        return if (
            decrypted.size >= 2 &&
            decrypted[0] == 0x1F.toByte() &&
            decrypted[1] == 0x8B.toByte()
        ) {
            GZIPInputStream(ByteArrayInputStream(decrypted)).use {
                it.readBytes()
            }
        } else {
            decrypted
        }
    }

    private fun sha1Signature(
        method: String,
        path: String,
        parameters: Map<String, String>,
        signedNonce: String,
    ): String {
        val canonical = buildList {
            add(method.uppercase())
            add(path)
            parameters.forEach { (name, value) -> add("$name=$value") }
            add(signedNonce)
        }.joinToString("&")
        return Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest(canonical.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun rc4Drop1024(
        key: ByteArray,
        input: ByteArray,
    ): ByteArray {
        require(key.isNotEmpty()) { "RC4 key must not be empty." }
        val state = IntArray(256) { it }
        var j = 0
        for (i in state.indices) {
            j = (j + state[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val temporary = state[i]
            state[i] = state[j]
            state[j] = temporary
        }
        var i = 0
        j = 0
        fun nextByte(): Int {
            i = (i + 1) and 0xFF
            j = (j + state[i]) and 0xFF
            val temporary = state[i]
            state[i] = state[j]
            state[j] = temporary
            return state[(state[i] + state[j]) and 0xFF]
        }
        repeat(1024) { nextByte() }
        return ByteArray(input.size) { index ->
            (input[index].toInt() xor nextByte()).toByte()
        }
    }
}
