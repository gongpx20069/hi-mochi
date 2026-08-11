package com.example.mochi_pet.core.memory

import android.icu.text.BreakIterator
import com.example.mochi_pet.core.database.entity.AgentMemoryEntity
import java.util.Locale
import kotlin.math.ln

internal object MemoryLexicalSearch {
    fun terms(
        value: String,
        limit: Int = MAX_INDEX_TERMS,
    ): List<String> {
        val primary = linkedSetOf<String>()
        val bigrams = linkedSetOf<String>()
        val unigrams = linkedSetOf<String>()
        ALPHANUMERIC_RUN.findAll(value.lowercase(Locale.ROOT))
            .flatMap { splitAtHanTransitions(it.value) }
            .forEach { run ->
                if (run.isHan) {
                    addHanTerms(run.value, primary, bigrams, unigrams)
                } else {
                    wordSegments(run.value, Locale.ROOT)
                        .filter(::isUsefulNonHanTerm)
                        .forEach(primary::add)
                }
            }
        return sequenceOf(primary, bigrams, unigrams)
            .flatten()
            .distinct()
            .take(limit)
            .toList()
    }

    fun searchableText(value: String): String =
        " ${terms(value).joinToString(" ")} "

    fun candidateLookupTerms(terms: List<String>): List<String> {
        val specificTerms = terms.filter {
            it.codePointCount() >= MIN_WORD_CODE_POINTS
        }
        return specificTerms
            .ifEmpty { terms }
            .take(MAX_SEARCH_TERMS)
    }

    fun matchQuery(terms: List<String>): String =
        terms.joinToString(" OR ") { term ->
            """"${term.replace("\"", "\"\"")}""""
        }

    fun rank(
        query: String,
        queryTerms: List<String>,
        candidates: List<AgentMemoryEntity>,
    ): List<AgentMemoryEntity> {
        val selectedTerms = queryTerms
            .take(MAX_SEARCH_TERMS)
            .toSet()
        if (selectedTerms.isEmpty() || candidates.isEmpty()) {
            return emptyList()
        }
        val documents = candidates.map { memory ->
            RankedDocument(
                memory = memory,
                terms = terms(memory.content).toSet(),
                comparableText = comparableText(memory.content),
            )
        }
        val documentFrequency = selectedTerms.associateWith { term ->
            documents.count { term in it.terms }
        }
        val queryWeight = selectedTerms.sumOf(::termWeight)
        val queryPhrase = comparableText(query)
            .takeIf { it.codePointCount() >= MIN_PHRASE_CODE_POINTS }
        val oldestTimestamp = candidates.minOf(
            AgentMemoryEntity::createdAtEpochMillis,
        )
        val newestTimestamp = candidates.maxOf(
            AgentMemoryEntity::createdAtEpochMillis,
        )

        return documents.mapNotNull { document ->
            val matchedTerms = selectedTerms.intersect(document.terms)
            if (matchedTerms.isEmpty()) {
                return@mapNotNull null
            }
            val weightedMatches = matchedTerms.sumOf { term ->
                termWeight(term) * inverseDocumentFrequency(
                    documentCount = documents.size,
                    matchingDocumentCount = documentFrequency.getValue(term),
                )
            }
            val coverage = matchedTerms.sumOf(::termWeight) / queryWeight
            val phraseBoost = if (
                queryPhrase != null &&
                queryPhrase in document.comparableText
            ) {
                EXACT_PHRASE_BOOST
            } else {
                0.0
            }
            ScoredDocument(
                memory = document.memory,
                score = weightedMatches +
                    (coverage * COVERAGE_BOOST) +
                    phraseBoost +
                    recencyBoost(
                        timestamp = document.memory.createdAtEpochMillis,
                        oldestTimestamp = oldestTimestamp,
                        newestTimestamp = newestTimestamp,
                    ),
            )
        }.sortedWith(
            compareByDescending<ScoredDocument>(ScoredDocument::score)
                .thenByDescending {
                    it.memory.createdAtEpochMillis
                },
        ).map(ScoredDocument::memory)
    }

    private fun addHanTerms(
        value: String,
        primary: MutableSet<String>,
        bigrams: MutableSet<String>,
        unigrams: MutableSet<String>,
    ) {
        wordSegments(value, Locale.SIMPLIFIED_CHINESE)
            .filter { it.codePointCount() >= MIN_WORD_CODE_POINTS }
            .forEach(primary::add)
        val characters = value.codePointStrings()
        characters.zipWithNext { first, second -> first + second }
            .forEach(bigrams::add)
        characters.forEach(unigrams::add)
    }

    private fun wordSegments(
        value: String,
        locale: Locale,
    ): Sequence<String> =
        sequence {
            val iterator = BreakIterator.getWordInstance(locale)
            iterator.setText(value)
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                val segment = value.substring(start, end)
                if (segment.any(Character::isLetterOrDigit)) {
                    yield(segment)
                }
                start = end
                end = iterator.next()
            }
        }

    private fun splitAtHanTransitions(value: String): Sequence<ScriptRun> =
        sequence {
            var start = 0
            var index = 0
            var currentIsHan: Boolean? = null
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                val isHan = codePoint.isHan()
                if (currentIsHan != null && currentIsHan != isHan) {
                    yield(
                        ScriptRun(
                            value = value.substring(start, index),
                            isHan = currentIsHan,
                        ),
                    )
                    start = index
                }
                currentIsHan = isHan
                index += Character.charCount(codePoint)
            }
            if (start < value.length) {
                yield(
                    ScriptRun(
                        value = value.substring(start),
                        isHan = currentIsHan == true,
                    ),
                )
            }
        }

    private fun isUsefulNonHanTerm(value: String): Boolean =
        value.codePointCount() >= MIN_WORD_CODE_POINTS ||
            value.all(Character::isDigit)

    private fun inverseDocumentFrequency(
        documentCount: Int,
        matchingDocumentCount: Int,
    ): Double =
        ln(
            (documentCount + 1.0) /
                (matchingDocumentCount + 1.0),
        ) + 1.0

    private fun termWeight(value: String): Double {
        val length = value.codePointCount()
        return when {
            length == 1 && value.codePointAt(0).isHan() ->
                HAN_UNIGRAM_WEIGHT
            length == 1 ->
                SINGLE_CHARACTER_WEIGHT
            else ->
                1.0 + ((length - 2).coerceAtMost(4) * LENGTH_WEIGHT_STEP)
        }
    }

    private fun recencyBoost(
        timestamp: Long,
        oldestTimestamp: Long,
        newestTimestamp: Long,
    ): Double {
        val range = newestTimestamp - oldestTimestamp
        if (range <= 0) {
            return 0.0
        }
        return ((timestamp - oldestTimestamp).toDouble() / range) *
            MAX_RECENCY_BOOST
    }

    private fun comparableText(value: String): String =
        buildString {
            val normalized = value.lowercase(Locale.ROOT)
            var index = 0
            while (index < normalized.length) {
                val codePoint = normalized.codePointAt(index)
                if (Character.isLetterOrDigit(codePoint)) {
                    appendCodePoint(codePoint)
                }
                index += Character.charCount(codePoint)
            }
        }

    private fun String.codePointStrings(): List<String> =
        buildList {
            var index = 0
            while (index < length) {
                val codePoint = codePointAt(index)
                add(String(Character.toChars(codePoint)))
                index += Character.charCount(codePoint)
            }
        }

    private fun String.codePointCount(): Int =
        codePointCount(0, length)

    private fun Int.isHan(): Boolean =
        Character.UnicodeScript.of(this) == Character.UnicodeScript.HAN

    private data class ScriptRun(
        val value: String,
        val isHan: Boolean,
    )

    private data class RankedDocument(
        val memory: AgentMemoryEntity,
        val terms: Set<String>,
        val comparableText: String,
    )

    private data class ScoredDocument(
        val memory: AgentMemoryEntity,
        val score: Double,
    )

    private val ALPHANUMERIC_RUN = Regex("[\\p{L}\\p{N}]+")
    private const val MIN_WORD_CODE_POINTS = 2
    private const val MIN_PHRASE_CODE_POINTS = 2
    private const val HAN_UNIGRAM_WEIGHT = 0.25
    private const val SINGLE_CHARACTER_WEIGHT = 0.6
    private const val LENGTH_WEIGHT_STEP = 0.1
    private const val COVERAGE_BOOST = 2.0
    private const val EXACT_PHRASE_BOOST = 1.5
    private const val MAX_RECENCY_BOOST = 0.1
}

internal const val MAX_INDEX_TERMS = 256
internal const val MAX_SEARCH_TERMS = 16
