package com.example.mochi_mijia

import kotlinx.serialization.Serializable

@Serializable
data class MijiaSession(
    val userId: String,
    val cUserId: String,
    val passToken: String,
    val ssecurity: String,
    val serviceToken: String,
    val deviceId: String,
    val region: String? = null,
    val selectedHomeIds: Set<String> = emptySet(),
    val selectedDeviceIds: Set<String> = emptySet(),
)

data class MijiaQrChallenge(
    val loginUrl: String,
    val longPollUrl: String,
    val timeoutSeconds: Long,
)

data class MijiaHome(
    val id: String,
    val ownerId: String,
    val name: String,
    val rooms: List<MijiaRoom>,
)

data class MijiaRoom(
    val id: String,
    val name: String,
    val deviceIds: Set<String>,
)

data class MijiaDevice(
    val id: String,
    val name: String,
    val model: String,
    val specificationType: String?,
    val online: Boolean,
    val homeId: String,
    val homeName: String,
    val roomId: String?,
    val roomName: String?,
    val category: MijiaDeviceCategory,
)

enum class MijiaDeviceCategory(val wireName: String) {
    LIGHT("light"),
    SWITCH("switch"),
    PLUG("plug"),
    FAN("fan"),
    AIR_CONDITIONER("air_conditioner"),
    AIR_PURIFIER("air_purifier"),
    HUMIDIFIER("humidifier"),
    CURTAIN("curtain"),
    SENSOR("sensor"),
    TELEVISION("television"),
    CAMERA("camera"),
    SCALE("scale"),
    UNKNOWN("unknown"),
}

fun classifyMijiaDevice(
    specificationType: String?,
    model: String,
): MijiaDeviceCategory {
    val source = "${specificationType.orEmpty()} $model".lowercase()
    return when {
        ":light:" in source || ".light." in source ->
            MijiaDeviceCategory.LIGHT
        ":switch:" in source || ".switch." in source ->
            MijiaDeviceCategory.SWITCH
        ":outlet:" in source || ":plug:" in source ||
            ".plug." in source -> MijiaDeviceCategory.PLUG
        ":fan:" in source || ".fan." in source ->
            MijiaDeviceCategory.FAN
        ":air-conditioner:" in source || ".aircondition." in source ||
            ".air-conditioner." in source ->
            MijiaDeviceCategory.AIR_CONDITIONER
        ":air-purifier:" in source || ".airpurifier." in source ->
            MijiaDeviceCategory.AIR_PURIFIER
        ":humidifier:" in source || ".humidifier." in source ->
            MijiaDeviceCategory.HUMIDIFIER
        ":curtain:" in source || ".curtain." in source ->
            MijiaDeviceCategory.CURTAIN
        ":television:" in source || ".tv." in source ||
            ".mitv." in source -> MijiaDeviceCategory.TELEVISION
        ":camera:" in source || ".camera." in source ||
            ".chuangmi." in source -> MijiaDeviceCategory.CAMERA
        ":scale:" in source || ".scale." in source ->
            MijiaDeviceCategory.SCALE
        ":sensor-" in source || ":temperature-" in source ||
            ":humidity-" in source || ".sensor_" in source ||
            ".sensor." in source -> MijiaDeviceCategory.SENSOR
        else -> MijiaDeviceCategory.UNKNOWN
    }
}

val SUPPORTED_MIJIA_CATEGORIES = setOf(
    MijiaDeviceCategory.LIGHT,
    MijiaDeviceCategory.SWITCH,
    MijiaDeviceCategory.PLUG,
    MijiaDeviceCategory.FAN,
    MijiaDeviceCategory.AIR_CONDITIONER,
    MijiaDeviceCategory.AIR_PURIFIER,
    MijiaDeviceCategory.HUMIDIFIER,
    MijiaDeviceCategory.CURTAIN,
    MijiaDeviceCategory.SENSOR,
    MijiaDeviceCategory.TELEVISION,
    MijiaDeviceCategory.CAMERA,
    MijiaDeviceCategory.SCALE,
)

class MijiaProviderException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

open class MijiaAuthorizationException(message: String) :
    IllegalStateException(message)

class MijiaAuthorizationExpiredException :
    MijiaAuthorizationException("Mi Home authorization expired.")

class MijiaNotFoundException(message: String) :
    IllegalArgumentException(message)
