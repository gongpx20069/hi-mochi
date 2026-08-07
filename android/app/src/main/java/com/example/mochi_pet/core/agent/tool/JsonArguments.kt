package com.example.mochi_pet.core.agent.tool

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

fun JsonObject.requiredString(name: String): String =
    optionalString(name)
        ?: throw ToolInputException("$name is required")

fun JsonObject.optionalString(name: String): String? {
    val value = this[name] ?: return null
    if (value is JsonNull) {
        return null
    }
    return value.jsonPrimitive.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: throw ToolInputException("$name must be a non-empty string")
}

fun JsonObject.optionalBoolean(name: String): Boolean? {
    val value = this[name] ?: return null
    if (value is JsonNull) {
        return null
    }
    return value.jsonPrimitive.booleanOrNull
        ?: throw ToolInputException("$name must be a boolean")
}

fun JsonObject.optionalInt(name: String): Int? {
    val value = this[name] ?: return null
    if (value is JsonNull) {
        return null
    }
    return value.jsonPrimitive.intOrNull
        ?: throw ToolInputException("$name must be an integer")
}

inline fun <reified T : Enum<T>> JsonObject.requiredEnum(name: String): T {
    val raw = requiredString(name)
    return enumValues<T>().firstOrNull {
        it.name.equals(raw, ignoreCase = true)
    } ?: throw ToolInputException(
        "$name must be one of: ${enumValues<T>().joinToString { it.name.lowercase() }}",
    )
}

inline fun <reified T : Enum<T>> JsonObject.optionalEnum(name: String): T? {
    val raw = optionalString(name) ?: return null
    return enumValues<T>().firstOrNull {
        it.name.equals(raw, ignoreCase = true)
    } ?: throw ToolInputException(
        "$name must be one of: ${enumValues<T>().joinToString { it.name.lowercase() }}",
    )
}

fun JsonObject.optionalStringList(name: String): List<String> {
    val value: JsonElement = this[name] ?: return emptyList()
    val array = value as? kotlinx.serialization.json.JsonArray
        ?: throw ToolInputException("$name must be an array of strings")
    return array.mapIndexed { index, element ->
        element.jsonPrimitive.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw ToolInputException("$name[$index] must be a non-empty string")
    }
}
