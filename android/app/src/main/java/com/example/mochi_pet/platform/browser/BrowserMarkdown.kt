package com.example.mochi_pet.platform.browser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BrowserMarkdown(
    val content: String,
    val truncated: Boolean,
)

internal fun formatBrowserMarkdown(
    blocks: JsonArray,
    maxChars: Int = 8_000,
): BrowserMarkdown {
    val output = StringBuilder()
    var truncated = false
    var previousText: String? = null
    var previousKind: String? = null

    fun appendBlock(value: String) {
        val normalized = value.trim()
        if (normalized.isEmpty() || normalized == previousText) {
            return
        }
        val separator = if (output.isEmpty()) "" else "\n\n"
        if (output.length + separator.length + normalized.length > maxChars) {
            val remaining = maxChars - output.length - separator.length
            if (remaining > 0) {
                output.append(separator)
                output.append(normalized.take(remaining).trimEnd())
            }
            truncated = true
            return
        }
        output.append(separator)
        output.append(normalized)
        previousText = normalized
    }

    blocks.forEach { element ->
        if (truncated) {
            return@forEach
        }
        val block = element.jsonObject
        val kind = block["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val text = block["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val markdown = when (kind) {
            "heading" -> {
                val level = block["level"]
                    ?.jsonPrimitive
                    ?.intOrNull
                    ?.coerceIn(1, 6)
                    ?: 2
                "${"#".repeat(level)} $text"
            }
            "list_item" -> "- $text"
            "code" -> "```\n$text\n```"
            "quote" -> text.lineSequence()
                .joinToString("\n") { "> $it" }
            "table_row" -> {
                val cells = block["cells"]
                    ?.jsonArray
                    ?.map { cell ->
                        cell.jsonPrimitive.contentOrNull
                            .orEmpty()
                            .replace("|", "\\|")
                    }
                    .orEmpty()
                if (cells.isEmpty()) {
                    ""
                } else {
                    val row = "| ${cells.joinToString(" | ")} |"
                    if (previousKind == "table_row") {
                        row
                    } else {
                        val divider = cells.joinToString(
                            prefix = "| ",
                            postfix = " |",
                            separator = " | ",
                        ) { "---" }
                        "$row\n$divider"
                    }
                }
            }
            else -> text
        }
        appendBlock(markdown)
        previousKind = kind
    }

    return BrowserMarkdown(
        content = output.toString().ifBlank {
            "_No readable text in the current viewport._"
        },
        truncated = truncated,
    )
}
