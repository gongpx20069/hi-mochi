package com.example.mochi_pet.core.location

data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double? = null,
    val capturedAtEpochMillis: Long? = null,
    val provider: String? = null,
)

fun interface DeviceLocationProvider {
    suspend fun currentLocation(): DeviceLocation
}

open class DeviceLocationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class LocationPermissionDeniedException :
    DeviceLocationException(
        "Location permission is required to read current location",
    )

class LocationUnavailableException(message: String) :
    DeviceLocationException(message)

class LocationRequestTimeoutException :
    DeviceLocationException("Timed out while determining current location")

internal data class MapCoordinate(
    val latitude: Double,
    val longitude: Double,
)

internal object ChinaCoordinateConverter {
    fun wgs84ToGcj02(
        latitude: Double,
        longitude: Double,
    ): MapCoordinate? {
        if (isOutsideChina(latitude, longitude)) {
            return null
        }
        var latitudeOffset = transformLatitude(
            longitude - 105.0,
            latitude - 35.0,
        )
        var longitudeOffset = transformLongitude(
            longitude - 105.0,
            latitude - 35.0,
        )
        val latitudeRadians = latitude / 180.0 * Math.PI
        var magic = Math.sin(latitudeRadians)
        magic = 1 - EARTH_ECCENTRICITY * magic * magic
        val squareRootMagic = Math.sqrt(magic)
        latitudeOffset = (
            latitudeOffset * 180.0
        ) / (
            (EARTH_RADIUS * (1 - EARTH_ECCENTRICITY)) /
                (magic * squareRootMagic) *
                Math.PI
        )
        longitudeOffset = (
            longitudeOffset * 180.0
        ) / (
            EARTH_RADIUS / squareRootMagic *
                Math.cos(latitudeRadians) *
                Math.PI
        )
        return MapCoordinate(
            latitude = latitude + latitudeOffset,
            longitude = longitude + longitudeOffset,
        )
    }

    private fun isOutsideChina(
        latitude: Double,
        longitude: Double,
    ): Boolean =
        longitude !in MIN_CHINA_LONGITUDE..MAX_CHINA_LONGITUDE ||
            latitude !in MIN_CHINA_LATITUDE..MAX_CHINA_LATITUDE

    private fun transformLatitude(
        longitude: Double,
        latitude: Double,
    ): Double {
        var value = -100.0 +
            2.0 * longitude +
            3.0 * latitude +
            0.2 * latitude * latitude +
            0.1 * longitude * latitude +
            0.2 * Math.sqrt(kotlin.math.abs(longitude))
        value += (
            20.0 * Math.sin(6.0 * longitude * Math.PI) +
                20.0 * Math.sin(2.0 * longitude * Math.PI)
        ) * 2.0 / 3.0
        value += (
            20.0 * Math.sin(latitude * Math.PI) +
                40.0 * Math.sin(latitude / 3.0 * Math.PI)
        ) * 2.0 / 3.0
        value += (
            160.0 * Math.sin(latitude / 12.0 * Math.PI) +
                320.0 * Math.sin(latitude * Math.PI / 30.0)
        ) * 2.0 / 3.0
        return value
    }

    private fun transformLongitude(
        longitude: Double,
        latitude: Double,
    ): Double {
        var value = 300.0 +
            longitude +
            2.0 * latitude +
            0.1 * longitude * longitude +
            0.1 * longitude * latitude +
            0.1 * Math.sqrt(kotlin.math.abs(longitude))
        value += (
            20.0 * Math.sin(6.0 * longitude * Math.PI) +
                20.0 * Math.sin(2.0 * longitude * Math.PI)
        ) * 2.0 / 3.0
        value += (
            20.0 * Math.sin(longitude * Math.PI) +
                40.0 * Math.sin(longitude / 3.0 * Math.PI)
        ) * 2.0 / 3.0
        value += (
            150.0 * Math.sin(longitude / 12.0 * Math.PI) +
                300.0 * Math.sin(longitude / 30.0 * Math.PI)
        ) * 2.0 / 3.0
        return value
    }

    private const val EARTH_RADIUS = 6_378_245.0
    private const val EARTH_ECCENTRICITY = 0.006693421622965943
    private const val MIN_CHINA_LONGITUDE = 72.004
    private const val MAX_CHINA_LONGITUDE = 137.8347
    private const val MIN_CHINA_LATITUDE = 0.8293
    private const val MAX_CHINA_LATITUDE = 55.8271
}
