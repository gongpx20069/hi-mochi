package com.example.mochi_pet.core.skills

import com.example.mochi_pet.core.agent.tool.ToolDirectorySnapshot
import com.example.mochi_pet.core.agent.tool.ToolInputException
import com.example.mochi_pet.core.agent.tool.ToolSchemaLanguage
import java.security.MessageDigest

internal data class SkillToolDirectoryPage(
    val content: String,
    val nextCursor: String?,
    val totalCount: Int,
)

internal class SkillToolDirectoryChangedException : IllegalStateException()

internal object SkillToolDirectory {
    const val DESCRIPTION_CHARS = 100
    const val PAGE_BYTES = 12 * 1024
    const val HEADING = "\n\n## Related tools - definitions not loaded\n\n"
    private const val NOTICE =
        "Descriptions may be shortened. These are not callable schemas. " +
            "Use tool_search with an exact name and limit=1; call it only after its definition is provided.\n"
    private val whitespace = Regex("[\\s\\p{Z}]+")
    private val cursorPattern = Regex("[a-f0-9]{64}:[1-9][0-9]{0,8}")

    fun description(text: String): String {
        val normalized = whitespace.replace(ToolSchemaLanguage.requireEnglishText(text), " ").trim()
        if (normalized.codePointCount(0, normalized.length) <= DESCRIPTION_CHARS) return normalized
        val prefix = normalized.substring(0, normalized.offsetByCodePoints(0, DESCRIPTION_CHARS - 3))
        val boundary = if (normalized[prefix.length].isWhitespace()) prefix.length else prefix.lastIndexOf(' ')
        return (if (boundary > 0) prefix.substring(0, boundary) else prefix).trimEnd() + "..."
    }

    fun page(
        skill: MochiSkill,
        relatedNames: Set<String>,
        snapshot: ToolDirectorySnapshot?,
        cursor: String?,
    ): SkillToolDirectoryPage {
        if (cursor != null && !cursorPattern.matches(cursor)) throw ToolInputException("Invalid tool_cursor")
        val descriptions = snapshot?.descriptions.orEmpty()
        val suppliedNames = snapshot?.suppliedNames.orEmpty()
        val entries = relatedNames.asSequence()
            .filter { it in descriptions && it !in suppliedNames }
            .sorted()
            .map { "$it: ${description(descriptions.getValue(it))}\n" }
            .toList()
        if (entries.isEmpty() && cursor == null) return SkillToolDirectoryPage("", null, 0)
        val revision = MessageDigest.getInstance("SHA-256")
            .digest(("skill-tool-directory-v1\n$skill\n" + entries.joinToString("")).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (cursor != null && cursor.substringBefore(':') != revision) throw SkillToolDirectoryChangedException()
        val start = cursor?.substringAfter(':')?.toInt() ?: 0
        if (cursor != null && start !in entries.indices) throw ToolInputException("Invalid tool_cursor offset")
        if (entries.isEmpty()) return SkillToolDirectoryPage("", null, 0)
        val content = StringBuilder(HEADING).append(NOTICE)
        var bytes = content.toString().toByteArray(Charsets.UTF_8).size
        var next = start
        while (next < entries.size) {
            val entry = entries[next]
            val size = entry.toByteArray(Charsets.UTF_8).size
            if (bytes + size > PAGE_BYTES) break
            content.append(entry)
            bytes += size
            next++
        }
        check(next > start) { "A Tool directory entry exceeds the page budget" }
        return SkillToolDirectoryPage(content.toString(), if (next < entries.size) "$revision:$next" else null, entries.size)
    }
}
