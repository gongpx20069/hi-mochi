package com.example.mochi_pet.core.presentation

import com.example.mochi_pet.core.agent.tool.ToolInputException
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.navigation.UiDirective
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class CardType(val wireName: String) {
    DAILY_BRIEFING("daily_briefing"),
    AGENDA_TIMELINE("agenda_timeline"),
    TODO_FOCUS("todo_focus"),
    CONTENT("content"),
    RESEARCH_SUMMARY("research_summary"),
    COMPARISON("comparison"),
    INSIGHT("insight"),
    PROGRESS("progress"),
}

enum class CardPlacement(val wireName: String) {
    AUTO("auto"),
    HOME("home"),
    INLINE("inline"),
    DEFERRED("deferred"),
}

enum class CardSource(
    val wireName: String,
    val toolName: String,
) {
    WEATHER("weather", "get_current_weather"),
    CALENDAR("calendar", "manage_mochi_calendar"),
    TODOS("todos", "manage_mochi_todo"),
    WEB_SEARCH("web_search", "browser_navigate"),
    WEB_PAGE("web_page", "browser_read"),
}

enum class CardActionType(val wireName: String) {
    OPEN_TODAY("open_today"),
    OPEN_CALENDAR("open_calendar"),
    OPEN_TALK("open_talk"),
    COMPLETE_TODO("complete_todo"),
    OPEN_SOURCE("open_source"),
    EXPAND("expand"),
    DISMISS("dismiss"),
}

data class CardDirectiveRequest(
    val type: CardType,
    val placement: CardPlacement,
    val title: String? = null,
    val subtitle: String? = null,
    val body: String? = null,
    val items: List<CardItem> = emptyList(),
    val sources: List<CardSource> = emptyList(),
    val evidenceTools: List<String> = emptyList(),
    val actions: List<CardActionRequest> = emptyList(),
)

data class CardActionRequest(
    val type: CardActionType,
    val label: String?,
    val targetId: String?,
    val date: LocalDate?,
    val sourceIndex: Int?,
)

data class CardToolEvidence(
    val toolName: String,
    val data: JsonObject,
)

data class CardMetric(
    val label: String,
    val value: String,
)

data class CardItem(
    val id: String? = null,
    val title: String,
    val detail: String? = null,
)

data class CardSourceLink(
    val title: String,
    val url: String,
    val source: String? = null,
)

data class CardPresentation(
    val id: String = UUID.randomUUID().toString(),
    val type: CardType,
    val placement: CardPlacement,
    val title: String,
    val subtitle: String? = null,
    val body: String? = null,
    val hero: String? = null,
    val metrics: List<CardMetric> = emptyList(),
    val items: List<CardItem> = emptyList(),
    val sources: List<CardSourceLink> = emptyList(),
    val actions: List<CardAction> = emptyList(),
)

data class CardAction(
    val type: CardActionType,
    val label: String,
    val targetId: String? = null,
    val date: LocalDate? = null,
    val url: String? = null,
)

fun parseCardDirective(
    arguments: JsonObject,
    evidence: List<CardToolEvidence>,
    reply: String,
): CardPresentation? {
    val type = arguments.string("type")
        ?.let { raw ->
            CardType.entries.firstOrNull { it.wireName == raw.lowercase() }
                ?: throw ToolInputException(
                    "card_directive.type is not supported",
                )
        }
        ?: CardType.CONTENT
    val rawPlacement = arguments.string("placement")
    val placement = if (rawPlacement == null) {
        CardPlacement.AUTO
    } else {
        CardPlacement.entries.firstOrNull {
            it != CardPlacement.DEFERRED &&
                it.wireName == rawPlacement.lowercase()
        } ?: throw ToolInputException(
            "card_directive.placement is not supported",
        )
    }
    val requestedSources = if (type == CardType.CONTENT) {
        emptyList()
    } else {
        arguments.stringArray("sources")
            ?.map { raw ->
                CardSource.entries.firstOrNull {
                    it.wireName == raw.lowercase()
                } ?: throw ToolInputException(
                    "Unsupported card source: $raw",
                )
            }
            ?.distinct()
            ?: defaultSources(type)
    }
    val evidenceTools = arguments.stringArray("evidence_tools")
        ?.distinct()
        .orEmpty()
    if (
        evidenceTools.size > MAX_EVIDENCE_TOOLS ||
        evidenceTools.any { it.length > MAX_TOOL_NAME_CHARS }
    ) {
        throw ToolInputException(
            "card_directive.evidence_tools is too large",
        )
    }
    val request = CardDirectiveRequest(
        type = type,
        placement = placement,
        title = arguments.string("title")?.take(MAX_TITLE_CHARS),
        subtitle = arguments.string("subtitle")?.take(MAX_SUBTITLE_CHARS),
        body = arguments.string("body")?.take(MAX_BODY_CHARS),
        items = arguments.parseCardItems(),
        sources = requestedSources,
        evidenceTools = evidenceTools,
        actions = arguments.parseActionRequests(),
    )
    return bindCardPresentation(request, evidence, reply)
}

class CardPresentationPolicy {
    fun resolve(
        card: CardPresentation,
        currentSurface: MochiSurface,
        uiDirective: UiDirective?,
    ): CardPresentation {
        val placement = when {
            uiDirective != null -> {
                if (
                    uiDirective.surface == "conversation" &&
                    card.placement != CardPlacement.HOME
                ) {
                    CardPlacement.INLINE
                } else {
                    CardPlacement.DEFERRED
                }
            }
            card.placement == CardPlacement.HOME &&
                currentSurface.isProtectedCardSurface() ->
                CardPlacement.DEFERRED
            currentSurface.isHomeCardSurface() -> CardPlacement.HOME
            card.placement == CardPlacement.HOME -> CardPlacement.HOME
            card.placement == CardPlacement.INLINE &&
                currentSurface == MochiSurface.Conversation ->
                CardPlacement.INLINE
            card.placement == CardPlacement.INLINE -> CardPlacement.DEFERRED
            currentSurface == MochiSurface.Conversation -> CardPlacement.INLINE
            currentSurface.isProtectedCardSurface() -> CardPlacement.DEFERRED
            else -> CardPlacement.HOME
        }
        return card.copy(placement = placement)
    }
}

private fun bindCardPresentation(
    request: CardDirectiveRequest,
    evidence: List<CardToolEvidence>,
    reply: String,
): CardPresentation? {
    val available = request.sources.flatMap { source ->
        evidence.filter { it.toolName == source.toolName }.map {
            source to it.data
        }
    }
    val genericEvidence = if (request.type == CardType.CONTENT) {
        evidence.filter { item ->
            item.toolName !in LOCAL_NON_CONTENT_TOOL_NAMES &&
                (
                    request.evidenceTools.isEmpty() ||
                        item.toolName in request.evidenceTools
                )
        }
    } else {
        emptyList()
    }
    if (
        available.isEmpty() &&
        genericEvidence.isEmpty() &&
        request.type !in setOf(CardType.INSIGHT, CardType.PROGRESS)
    ) {
        return null
    }

    val weather = available.allData(CardSource.WEATHER).lastOrNull()
    val calendar = available.allData(CardSource.CALENDAR)
    val todos = available.allData(CardSource.TODOS)
    val webSearch = available.allData(CardSource.WEB_SEARCH)
    val webPage = available.allData(CardSource.WEB_PAGE)
    val weatherCondition = weather?.string("condition")
    val temperature = weather?.numberText("temperature_c")
    val metrics = buildList {
        temperature?.let { add(CardMetric("Temperature", "$it\u00B0")) }
        weather?.numberText("apparent_temperature_c")
            ?.let { add(CardMetric("Feels like", "$it\u00B0")) }
        weather?.numberText("humidity_percent")
            ?.let { add(CardMetric("Humidity", "$it%")) }
    }.take(MAX_METRICS)
    val eventItems = calendar.flatMap(JsonObject::toEventItems)
        .distinctBy { it.title to it.detail }
    val todoItems = todos.flatMap(JsonObject::toTodoItems)
        .distinctBy { it.title to it.detail }
    val sourceLinks = buildList {
        addAll(webSearch.flatMap(JsonObject::toSourceLinks))
        addAll(webPage.mapNotNull(JsonObject::toPageSourceLink))
        addAll(genericEvidence.flatMap(CardToolEvidence::toSourceLinks))
    }.distinctBy(CardSourceLink::url).take(MAX_SOURCES)
    val actions = resolveActions(
        requests = request.actions,
        todoItems = todoItems,
        sourceLinks = sourceLinks,
    )

    return CardPresentation(
        type = request.type,
        placement = request.placement,
        title = request.title ?: request.type.defaultTitle(),
        subtitle = request.subtitle ?: when (request.type) {
            CardType.DAILY_BRIEFING -> weatherCondition
            CardType.AGENDA_TIMELINE -> "${eventItems.size} upcoming items"
            CardType.TODO_FOCUS -> "${todoItems.size} active tasks"
            CardType.CONTENT -> null
            CardType.RESEARCH_SUMMARY -> "${sourceLinks.size} sources"
            CardType.COMPARISON -> "Evidence-backed comparison"
            CardType.INSIGHT -> "Mochi insight"
            CardType.PROGRESS -> "Current progress"
        },
        body = when (request.type) {
            CardType.CONTENT -> request.body ?: reply.take(MAX_BODY_CHARS)
            CardType.RESEARCH_SUMMARY,
            CardType.COMPARISON,
            CardType.INSIGHT,
            CardType.PROGRESS,
            -> reply.take(MAX_BODY_CHARS)
            else -> null
        },
        hero = when (request.type) {
            CardType.DAILY_BRIEFING -> temperature?.let { "$it\u00B0" }
            CardType.TODO_FOCUS -> todoItems.firstOrNull()?.title
            else -> null
        },
        metrics = if (
            request.type == CardType.DAILY_BRIEFING
        ) {
            metrics
        } else {
            emptyList()
        },
        items = when (request.type) {
            CardType.DAILY_BRIEFING ->
                (eventItems + todoItems).take(MAX_ITEMS)
            CardType.AGENDA_TIMELINE -> eventItems.take(MAX_ITEMS)
            CardType.TODO_FOCUS -> todoItems.take(MAX_ITEMS)
            CardType.CONTENT -> request.items
            CardType.RESEARCH_SUMMARY,
            CardType.COMPARISON,
            -> emptyList()
            CardType.INSIGHT,
            CardType.PROGRESS,
            -> emptyList()
        },
        sources = sourceLinks,
        actions = actions,
    )
}

private fun JsonObject.parseActionRequests(): List<CardActionRequest> {
    val actions = this["actions"] ?: return emptyList()
    val array = runCatching { actions.jsonArray }
        .getOrElse {
            throw ToolInputException(
                "card_directive.actions must be an array",
            )
        }
    if (array.size > MAX_ACTIONS) {
        throw ToolInputException(
            "card_directive.actions supports at most $MAX_ACTIONS actions",
        )
    }
    return array.map { element ->
        val action = runCatching { element.jsonObject }
            .getOrElse {
                throw ToolInputException(
                    "card_directive.actions must contain objects",
                )
            }
        val rawType = action.string("type")
            ?: throw ToolInputException("Card action type is required")
        val type = CardActionType.entries.firstOrNull {
            it.wireName == rawType.lowercase()
        } ?: throw ToolInputException("Unsupported card action: $rawType")
        val sourceIndex = action["source_index"]?.let {
            runCatching { it.jsonPrimitive.intOrNull }.getOrNull()
                ?: throw ToolInputException(
                    "source_index must be an integer",
                )
        }
        CardActionRequest(
            type = type,
            label = action.string("label")?.take(MAX_ACTION_LABEL_CHARS),
            targetId = action.string("target_id"),
            date = action.string("date")?.let { rawDate ->
                runCatching { LocalDate.parse(rawDate) }
                    .getOrElse {
                        throw ToolInputException(
                            "Card action date must be an ISO local date",
                        )
                    }
            },
            sourceIndex = sourceIndex,
        )
    }
}

private fun JsonObject.parseCardItems(): List<CardItem> {
    val items = this["items"] ?: return emptyList()
    val array = runCatching { items.jsonArray }
        .getOrElse {
            throw ToolInputException(
                "card_directive.items must be an array",
            )
        }
    if (array.size > MAX_ITEMS) {
        throw ToolInputException(
            "card_directive.items supports at most $MAX_ITEMS items",
        )
    }
    return array.map { element ->
        val item = runCatching { element.jsonObject }
            .getOrElse {
                throw ToolInputException(
                    "card_directive.items must contain objects",
                )
            }
        CardItem(
            title = item.string("title")
                ?.take(MAX_ITEM_TITLE_CHARS)
                ?: throw ToolInputException(
                    "card_directive item title is required",
                ),
            detail = item.string("detail")?.take(MAX_ITEM_DETAIL_CHARS),
        )
    }
}

private fun resolveActions(
    requests: List<CardActionRequest>,
    todoItems: List<CardItem>,
    sourceLinks: List<CardSourceLink>,
): List<CardAction> =
    requests.map { request ->
        when (request.type) {
            CardActionType.OPEN_TODAY ->
                CardAction(
                    type = request.type,
                    label = request.label ?: "View today",
                )
            CardActionType.OPEN_CALENDAR -> {
                val date = request.date
                    ?: throw ToolInputException(
                        "open_calendar requires date",
                    )
                CardAction(
                    type = request.type,
                    label = request.label ?: "Open calendar",
                    date = date,
                )
            }
            CardActionType.OPEN_TALK ->
                CardAction(
                    type = request.type,
                    label = request.label ?: "Open conversation",
                )
            CardActionType.COMPLETE_TODO -> {
                val targetId = request.targetId
                    ?: throw ToolInputException(
                        "complete_todo requires target_id",
                    )
                if (todoItems.none { it.id == targetId }) {
                    throw ToolInputException(
                        "complete_todo target must come from todo evidence",
                    )
                }
                CardAction(
                    type = request.type,
                    label = request.label ?: "Complete",
                    targetId = targetId,
                )
            }
            CardActionType.OPEN_SOURCE -> {
                val sourceIndex = request.sourceIndex
                    ?: throw ToolInputException(
                        "open_source requires source_index",
                    )
                val source = sourceLinks.getOrNull(sourceIndex)
                    ?: throw ToolInputException(
                        "open_source index is outside retained evidence",
                    )
                CardAction(
                    type = request.type,
                    label = request.label ?: "Open source",
                    url = source.url,
                )
            }
            CardActionType.EXPAND ->
                CardAction(
                    type = request.type,
                    label = request.label ?: "Full screen",
                )
            CardActionType.DISMISS ->
                CardAction(
                    type = request.type,
                    label = request.label ?: "Dismiss",
                )
        }
    }.distinctBy { action ->
        listOf(
            action.type,
            action.targetId,
            action.date,
            action.url,
        )
    }

private fun defaultSources(type: CardType): List<CardSource> =
    when (type) {
        CardType.DAILY_BRIEFING -> listOf(
            CardSource.WEATHER,
            CardSource.CALENDAR,
            CardSource.TODOS,
        )
        CardType.AGENDA_TIMELINE -> listOf(CardSource.CALENDAR)
        CardType.TODO_FOCUS -> listOf(CardSource.TODOS)
        CardType.CONTENT -> emptyList()
        CardType.RESEARCH_SUMMARY,
        CardType.COMPARISON,
        -> listOf(CardSource.WEB_SEARCH, CardSource.WEB_PAGE)
        CardType.INSIGHT,
        CardType.PROGRESS,
        -> emptyList()
    }

private fun CardType.defaultTitle(): String =
    when (this) {
        CardType.DAILY_BRIEFING -> "Daily briefing"
        CardType.AGENDA_TIMELINE -> "Agenda"
        CardType.TODO_FOCUS -> "Focus"
        CardType.CONTENT -> "Result"
        CardType.RESEARCH_SUMMARY -> "Research summary"
        CardType.COMPARISON -> "Comparison"
        CardType.INSIGHT -> "Insight"
        CardType.PROGRESS -> "Progress"
    }

private fun List<Pair<CardSource, JsonObject>>.allData(
    source: CardSource,
): List<JsonObject> = filter { it.first == source }.map { it.second }

private fun JsonObject.toEventItems(): List<CardItem> {
    val objects = objectList("events").ifEmpty {
        if (containsKey("title") && containsKey("start_iso")) {
            listOf(this)
        } else {
            emptyList()
        }
    }
    return objects.mapNotNull { event ->
        event.string("title")?.let {
            CardItem(
                id = event.string("id"),
                title = it,
                detail = event.string("start_iso"),
            )
        }
    }
}

private fun JsonObject.toTodoItems(): List<CardItem> {
    val objects = objectList("todos").ifEmpty {
        this["todo"]?.let { element ->
            runCatching { listOf(element.jsonObject) }.getOrDefault(emptyList())
        } ?: if (containsKey("content")) {
            listOf(this)
        } else {
            emptyList()
        }
    }
    return objects.mapNotNull { todo ->
        todo.string("content")?.let {
            CardItem(
                id = todo.string("id"),
                title = it,
                detail = listOfNotNull(
                    todo.string("scheduled_date"),
                    todo.string("priority"),
                ).joinToString(" \u00B7 ").ifBlank { null },
            )
        }
    }
}

private fun JsonObject.toSourceLinks(): List<CardSourceLink> {
    return objectList("results").mapNotNull { result ->
        val title = result.string("title")
        val url = result.string("url")
        if (title == null || url == null) {
            null
        } else {
            CardSourceLink(
                title = title,
                url = url,
                source = result.string("source"),
            )
        }
    }
}

private fun JsonObject.toPageSourceLink(): CardSourceLink? {
    val url = string("url") ?: return null
    return CardSourceLink(
        title = string("title") ?: url,
        url = url,
        source = "page",
    )
}

private fun CardToolEvidence.toSourceLinks(): List<CardSourceLink> =
    buildList {
        data.collectSourceLinks(
            destination = this,
            source = toolName,
            depth = 0,
        )
    }

private fun JsonElement.collectSourceLinks(
    destination: MutableList<CardSourceLink>,
    source: String,
    depth: Int,
) {
    if (destination.size >= MAX_SOURCES || depth > MAX_SOURCE_DEPTH) {
        return
    }
    when (this) {
        is JsonObject -> {
            val url = GENERIC_URL_FIELDS.firstNotNullOfOrNull { field ->
                string(field)
            }
                ?.takeIf { it.startsWith("https://", ignoreCase = true) }
            if (url != null) {
                destination += CardSourceLink(
                    title = GENERIC_TITLE_FIELDS
                        .firstNotNullOfOrNull { field -> string(field) }
                        ?.take(MAX_SOURCE_TITLE_CHARS)
                        ?: url,
                    url = url,
                    source = source,
                )
            }
            values.forEach { value ->
                value.collectSourceLinks(destination, source, depth + 1)
            }
        }
        is JsonArray -> forEach { element ->
            element.collectSourceLinks(destination, source, depth + 1)
        }
        is JsonPrimitive -> Unit
    }
}

private fun JsonObject.objectList(name: String): List<JsonObject> =
    this[name]?.let { element ->
        runCatching {
            element.jsonArray.mapNotNull {
                runCatching { it.jsonObject }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }.orEmpty()

private fun JsonObject.string(name: String): String? =
    this[name]?.let { element ->
        runCatching { element.jsonPrimitive.contentOrNull }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

private fun JsonObject.stringArray(name: String): List<String>? =
    this[name]?.let { element ->
        runCatching {
            element.jsonArray.map { item ->
                item.jsonPrimitive.contentOrNull
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: throw ToolInputException(
                        "card_directive.$name must contain strings",
                    )
            }
        }.getOrElse { error ->
            if (error is ToolInputException) {
                throw error
            }
            throw ToolInputException(
                "card_directive.$name must be an array",
            )
        }
    }

private fun JsonObject.numberText(name: String): String? =
    string(name)?.toDoubleOrNull()?.let { number ->
        if (number % 1.0 == 0.0) {
            number.toInt().toString()
        } else {
            "%.1f".format(Locale.US, number)
        }
    }

private fun MochiSurface.isProtectedCardSurface(): Boolean =
    this == MochiSurface.Settings ||
        this == MochiSurface.Skills ||
        this == MochiSurface.Tools

private fun MochiSurface.isHomeCardSurface(): Boolean =
    this == MochiSurface.Face ||
        this == MochiSurface.DateTime ||
        this == MochiSurface.Weather ||
        this == MochiSurface.Card

private const val MAX_TITLE_CHARS = 80
private const val MAX_SUBTITLE_CHARS = 160
private const val MAX_BODY_CHARS = 2_000
private const val MAX_METRICS = 4
private const val MAX_ITEMS = 8
private const val MAX_SOURCES = 6
private const val MAX_ACTIONS = 3
private const val MAX_ACTION_LABEL_CHARS = 32
private const val MAX_ITEM_TITLE_CHARS = 160
private const val MAX_ITEM_DETAIL_CHARS = 320
private const val MAX_SOURCE_TITLE_CHARS = 160
private const val MAX_SOURCE_DEPTH = 8
private const val MAX_EVIDENCE_TOOLS = 16
private const val MAX_TOOL_NAME_CHARS = 128
private val LOCAL_NON_CONTENT_TOOL_NAMES = setOf(
    CardSource.WEATHER.toolName,
    CardSource.CALENDAR.toolName,
    CardSource.TODOS.toolName,
    "navigate_mochi_ui",
)
private val GENERIC_URL_FIELDS = listOf(
    "url",
    "href",
    "link",
    "web_url",
    "public_url",
)
private val GENERIC_TITLE_FIELDS = listOf(
    "title",
    "name",
    "filename",
)
