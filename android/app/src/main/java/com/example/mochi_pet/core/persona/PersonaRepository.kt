package com.example.mochi_pet.core.persona

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption

enum class PersonaDocument(
    val fileName: String,
) {
    SOUL("SOUL.md"),
    USER("USER.md"),
    AGENTS("AGENTS.md"),
}

data class PersonaContext(
    val soul: String,
    val user: String,
    val agents: String,
) {
    val sections: List<String>
        get() = listOf(soul, user, agents).filter(String::isNotBlank)
}

interface PersonaRepository {
    suspend fun load(): PersonaContext

    suspend fun updateAll(context: PersonaContext): PersonaContext

    suspend fun update(
        document: PersonaDocument,
        content: String,
    ): PersonaContext
}

class FilePersonaRepository(
    context: Context,
) : PersonaRepository {
    private val applicationContext = context.applicationContext
    private val directory = File(applicationContext.filesDir, "persona")

    override suspend fun load(): PersonaContext {
        ensureSeeded()
        return PersonaContext(
            soul = read(PersonaDocument.SOUL),
            user = read(PersonaDocument.USER),
            agents = read(PersonaDocument.AGENTS),
        )
    }

    override suspend fun updateAll(context: PersonaContext): PersonaContext {
        val contents = mapOf(
            PersonaDocument.SOUL to validate(
                PersonaDocument.SOUL,
                context.soul,
            ),
            PersonaDocument.USER to validate(
                PersonaDocument.USER,
                context.user,
            ),
            PersonaDocument.AGENTS to validate(
                PersonaDocument.AGENTS,
                context.agents,
            ),
        )
        ensureSeeded()
        val previous = PersonaDocument.entries.associateWith(::read)
        val temporaryFiles = contents.mapValues { (document, content) ->
            File(directory, "${document.fileName}.tmp").also {
                it.writeText("$content\n")
            }
        }
        try {
            PersonaDocument.entries.forEach { document ->
                replace(
                    temporary = checkNotNull(temporaryFiles[document]),
                    target = File(directory, document.fileName),
                )
            }
        } catch (error: IOException) {
            previous.forEach { (document, content) ->
                File(directory, document.fileName).writeText("$content\n")
            }
            temporaryFiles.values.forEach(File::delete)
            throw IOException("Failed to update persona files", error)
        }
        return load()
    }

    override suspend fun update(
        document: PersonaDocument,
        content: String,
    ): PersonaContext {
        val normalized = validate(document, content)
        ensureSeeded()
        val target = File(directory, document.fileName)
        val temporary = File(directory, "${document.fileName}.tmp")
        temporary.writeText("$normalized\n")
        try {
            replace(temporary, target)
        } catch (error: IOException) {
            temporary.delete()
            throw IOException("Failed to update ${document.fileName}", error)
        }
        return load()
    }

    private fun validate(
        document: PersonaDocument,
        content: String,
    ): String {
        val normalized = content.trim()
        require(normalized.isNotEmpty()) {
            "${document.fileName} must not be empty"
        }
        require(normalized.length <= MAX_PERSONA_CHARS) {
            "${document.fileName} is too large"
        }
        return normalized
    }

    private fun replace(
        temporary: File,
        target: File,
    ) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun ensureSeeded() {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Failed to create persona directory")
        }
        PersonaDocument.entries.forEach { document ->
            val target = File(directory, document.fileName)
            if (!target.isFile) {
                applicationContext.assets
                    .open("persona/${document.fileName}")
                    .use { input ->
                        target.outputStream().use(input::copyTo)
                    }
            }
        }
    }

    private fun read(document: PersonaDocument): String {
        val content = File(directory, document.fileName).readText().trim()
        if (content.isEmpty() || content.length > MAX_PERSONA_CHARS) {
            throw IOException("${document.fileName} is invalid")
        }
        return content
    }

    private companion object {
        const val MAX_PERSONA_CHARS = 50_000
    }
}
