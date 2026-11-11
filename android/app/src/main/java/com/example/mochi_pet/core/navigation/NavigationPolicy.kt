package com.example.mochi_pet.core.navigation

import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolInputException
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agent.tool.functionToolSchema
import com.example.mochi_pet.core.agent.tool.optionalEnum
import com.example.mochi_pet.core.agent.tool.optionalString
import com.example.mochi_pet.core.agent.tool.optionalStringList
import com.example.mochi_pet.core.agent.tool.requiredEnum
import com.example.mochi_pet.core.agent.tool.requiredString
import com.example.mochi_pet.core.model.TodoStatus
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class NavigationOperation {
    END_VOICE_SESSION,
    ENTER_FOCUS,
    EXIT_FOCUS,
    SHOW_FACE,
    SHOW_DATE_TIME,
    SHOW_WEATHER,
    SHOW_CONVERSATION,
    SHOW_SCHEDULE_RESULTS,
    SHOW_SETTINGS,
    SHOW_TODAY,
    SHOW_CALENDAR_MONTH,
    SHOW_CALENDAR_DAY,
    SHOW_TODO,
}

enum class NavigationReason {
    CURRENT_TIME_DATE,
    CURRENT_WEATHER,
    TODAY_PLANNER,
    OTHER_DATE,
    TODO_LIST,
    ITEM_MUTATION,
    EXPLICIT_UI_REQUEST,
    GENERIC_KNOWLEDGE,
}

enum class UiSection {
    TIME,
    DATE,
    WEATHER,
    AGENDA,
    EVENTS,
    TODOS,
}

data class UiDirectiveRequest(
    val operation: NavigationOperation,
    val reason: NavigationReason,
    val date: LocalDate? = null,
    val month: YearMonth? = null,
    val section: UiSection? = null,
    val status: TodoStatus? = null,
    val highlightIds: List<String> = emptyList(),
    val scheduleId: String? = null,
    val runId: String? = null,
)

data class UiDirective(
    val surface: String,
    val date: LocalDate? = null,
    val month: YearMonth? = null,
    val section: UiSection? = null,
    val status: TodoStatus? = null,
    val highlightIds: List<String> = emptyList(),
    val focusMode: Boolean? = null,
    val followUpListening: Boolean? = null,
    val scheduleId: String? = null,
    val runId: String? = null,
)

data class NavigationDecision(
    val directive: UiDirective,
    val intent: MochiNavigationIntent,
)

fun interface UiDirectiveSink {
    suspend fun apply(decision: NavigationDecision)
}

class NavigationPolicy {
    fun decide(
        request: UiDirectiveRequest,
        context: ToolExecutionContext,
    ): NavigationDecision {
        if (request.reason == NavigationReason.GENERIC_KNOWLEDGE) {
            throw ToolInputException(
                "Generic calendar or time knowledge must not navigate the UI",
            )
        }
        if (request.operation != NavigationOperation.SHOW_SCHEDULE_RESULTS &&
            (request.scheduleId != null || request.runId != null)) {
            throw ToolInputException("Execution identifiers are only valid for show_schedule_results")
        }
        val highlights = request.highlightIds
            .distinct()
            .also {
                if (it.size > MAX_HIGHLIGHT_IDS) {
                    throw ToolInputException(
                        "highlight_ids must contain at most $MAX_HIGHLIGHT_IDS items",
                    )
                }
            }

        return when (request.operation) {
            NavigationOperation.SHOW_SCHEDULE_RESULTS -> {
                request.requireExplicitUiRequest()
                require(request.date == null && request.month == null && request.section == null &&
                    request.status == null && request.highlightIds.isEmpty()) { "Execution navigation does not accept calendar arguments" }
                val id = request.scheduleId?.takeIf { it.isNotBlank() && it.length <= 200 }
                    ?: throw ToolInputException("schedule_id is required")
                require(request.runId == null || request.runId.length in 1..200) { "Invalid run_id" }
                NavigationDecision(
                    UiDirective(surface = "schedule_results", scheduleId = id, runId = request.runId),
                    MochiNavigationIntent.ShowToday,
                )
            }
            NavigationOperation.ENTER_FOCUS,
            NavigationOperation.EXIT_FOCUS,
            NavigationOperation.END_VOICE_SESSION,
            -> {
                request.requireExplicitUiRequest()
                if (request.date != null || request.month != null || request.section != null ||
                    request.status != null || request.highlightIds.isNotEmpty()) {
                    throw ToolInputException("${request.operation.name.lowercase()} does not accept planner arguments")
                }
                if (request.operation == NavigationOperation.END_VOICE_SESSION) {
                    return NavigationDecision(
                        directive = UiDirective(surface = "end_voice_session", followUpListening = false),
                        intent = MochiNavigationIntent.EndVoiceSession,
                    )
                }
                val enabled = request.operation == NavigationOperation.ENTER_FOCUS
                val intent = MochiNavigationIntent.SetFocusMode(enabled)
                val target = MochiNavigationReducer.reduce(intent, context.currentSurface)
                NavigationDecision(target.toDirective().copy(focusMode = enabled), intent)
            }
            NavigationOperation.SHOW_FACE -> {
                request.requireExplicitUiRequest()
                NavigationDecision(
                    directive = UiDirective(surface = "face"),
                    intent = MochiNavigationIntent.ShowFace,
                )
            }
            NavigationOperation.SHOW_DATE_TIME -> {
                request.requireReason(
                    NavigationReason.CURRENT_TIME_DATE,
                    NavigationReason.EXPLICIT_UI_REQUEST,
                )
                NavigationDecision(
                    directive = UiDirective(
                        surface = "date_time",
                        date = context.currentDate,
                        section = request.section ?: UiSection.TIME,
                    ),
                    intent = MochiNavigationIntent.ShowDateTime,
                )
            }
            NavigationOperation.SHOW_WEATHER -> {
                request.requireReason(
                    NavigationReason.CURRENT_WEATHER,
                    NavigationReason.EXPLICIT_UI_REQUEST,
                )
                NavigationDecision(
                    directive = UiDirective(
                        surface = "weather",
                        date = context.currentDate,
                        section = UiSection.WEATHER,
                    ),
                    intent = MochiNavigationIntent.ShowWeather,
                )
            }
            NavigationOperation.SHOW_CONVERSATION -> {
                request.requireExplicitUiRequest()
                NavigationDecision(
                    directive = UiDirective(surface = "conversation"),
                    intent = MochiNavigationIntent.ShowConversation,
                )
            }
            NavigationOperation.SHOW_SETTINGS -> {
                request.requireExplicitUiRequest()
                NavigationDecision(
                    directive = UiDirective(surface = "settings"),
                    intent = MochiNavigationIntent.ShowSettings,
                )
            }
            NavigationOperation.SHOW_TODAY -> {
                request.requireReason(
                    NavigationReason.CURRENT_TIME_DATE,
                    NavigationReason.TODAY_PLANNER,
                    NavigationReason.ITEM_MUTATION,
                    NavigationReason.EXPLICIT_UI_REQUEST,
                )
                if (request.date != null && request.date != context.currentDate) {
                    throw ToolInputException(
                        "show_today cannot target a non-today date",
                    )
                }
                if (request.reason == NavigationReason.CURRENT_TIME_DATE) {
                    return NavigationDecision(
                        directive = UiDirective(
                            surface = "date_time",
                            date = context.currentDate,
                            section = request.section ?: UiSection.TIME,
                        ),
                        intent = MochiNavigationIntent.ShowDateTime,
                    )
                }
                NavigationDecision(
                    directive = UiDirective(
                        surface = "today",
                        date = context.currentDate,
                        section = request.section,
                        highlightIds = highlights,
                    ),
                    intent = MochiNavigationIntent.ShowToday,
                )
            }
            NavigationOperation.SHOW_CALENDAR_MONTH -> {
                request.requireReason(
                    NavigationReason.OTHER_DATE,
                    NavigationReason.TODO_LIST,
                    NavigationReason.ITEM_MUTATION,
                    NavigationReason.EXPLICIT_UI_REQUEST,
                )
                val month = request.month
                    ?: request.date?.let(YearMonth::from)
                    ?: throw ToolInputException(
                        "show_calendar_month requires month or date",
                    )
                NavigationDecision(
                    directive = UiDirective(
                        surface = "calendar_month",
                        month = month,
                        section = request.section,
                        highlightIds = highlights,
                    ),
                    intent = MochiNavigationIntent.ShowCalendarMonth(month),
                )
            }
            NavigationOperation.SHOW_CALENDAR_DAY -> {
                request.requireReason(
                    NavigationReason.OTHER_DATE,
                    NavigationReason.ITEM_MUTATION,
                    NavigationReason.EXPLICIT_UI_REQUEST,
                )
                val date = request.date
                    ?: throw ToolInputException(
                        "show_calendar_day requires date",
                    )
                if (
                    date == context.currentDate &&
                    request.reason != NavigationReason.EXPLICIT_UI_REQUEST
                ) {
                    throw ToolInputException(
                        "Use show_today for today's planner context",
                    )
                }
                NavigationDecision(
                    directive = UiDirective(
                        surface = "calendar_day",
                        date = date,
                        section = request.section,
                        highlightIds = highlights,
                    ),
                    intent = MochiNavigationIntent.ShowCalendarDay(date),
                )
            }
            NavigationOperation.SHOW_TODO -> {
                request.requireReason(
                    NavigationReason.TODO_LIST,
                    NavigationReason.ITEM_MUTATION,
                    NavigationReason.EXPLICIT_UI_REQUEST,
                )
                val date = request.date ?: context.currentDate
                NavigationDecision(
                    directive = UiDirective(
                        surface = if (date == context.currentDate) {
                            "today"
                        } else {
                            "calendar_day"
                        },
                        date = date,
                        section = request.section ?: UiSection.TODOS,
                        status = request.status,
                        highlightIds = highlights,
                    ),
                    intent = if (date == context.currentDate) {
                        MochiNavigationIntent.ShowToday
                    } else {
                        MochiNavigationIntent.ShowCalendarDay(date)
                    },
                )
            }
        }
    }

    private fun UiDirectiveRequest.requireExplicitUiRequest() {
        requireReason(NavigationReason.EXPLICIT_UI_REQUEST)
    }

    private fun UiDirectiveRequest.requireReason(
        vararg allowed: NavigationReason,
    ) {
        if (reason !in allowed) {
            throw ToolInputException(
                "${operation.name.lowercase()} is not valid for reason " +
                    reason.name.lowercase(),
            )
        }
    }

    private companion object {
        const val MAX_HIGHLIGHT_IDS = 20
    }
}

class NavigateMochiUiTool(
    private val policy: NavigationPolicy,
    private val sink: UiDirectiveSink,
) : AgentTool {
    override val name: String = "navigate_mochi_ui"

    override val schema: JsonObject = functionToolSchema(
        name = name,
        description =
            "Navigate Mochi's own UI after resolving conversational intent. enter_focus and exit_focus explicitly set (never toggle) Focus mode " +
                "and require reason=explicit_ui_request. Enter preserves the current Home presentation, or opens Home from another page; exit preserves the current page. " +
                "end_voice_session also requires reason=explicit_ui_request and accepts no planner arguments. " +
                "Use it when the user asks to finish talking or stop follow-up listening, not for quoted or negated requests. " +
                "It lets the current reply finish but prevents automatic follow-up STT for this interaction only, without changing the screen, Focus or wake-word settings. " +
                "Returning from an external app uses app_navigate return, not this Tool: preserve Mochi's existing screen and Focus state.",
        properties = buildJsonObject {
            put("operate", stringProperty(NavigationOperation.entries))
            put("reason", stringProperty(NavigationReason.entries))
            put("date", stringProperty(description = "ISO local date"))
            put("month", stringProperty(description = "Year-month as YYYY-MM"))
            put("section", stringProperty(UiSection.entries))
            put("status", stringProperty(TodoStatus.entries))
            put("schedule_id", stringProperty(description = "Schedule ID for show_schedule_results"))
            put("run_id", stringProperty(description = "Optional exact execution ID"))
            put(
                "highlight_ids",
                buildJsonObject {
                    put("type", "array")
                    put(
                        "items",
                        buildJsonObject {
                            put("type", "string")
                        },
                    )
                },
            )
        },
        required = listOf("operate", "reason"),
    )

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope {
        val request = parseUiDirectiveRequest(arguments)
        val decision = policy.decide(request, context)
        sink.apply(decision)
        return ToolResultEnvelope.success(decision.directive.toJson())
    }
}

fun parseUiDirectiveRequest(
    arguments: JsonObject,
    operationField: String = "operate",
): UiDirectiveRequest {
    val rawOperation = arguments.requiredString(operationField)
    val operationName = rawOperation.uppercase()
    val operation = NavigationOperation.entries.firstOrNull {
        it.name == operationName || it.name == "SHOW_$operationName"
    } ?: throw ToolInputException(
        "$operationField must be one of: " +
            NavigationOperation.entries.joinToString {
                it.name.removePrefix("SHOW_").lowercase()
            },
    )
    return UiDirectiveRequest(
        operation = operation,
        reason = arguments.requiredEnum("reason"),
        date = arguments.optionalString("date")?.let(LocalDate::parse),
        month = arguments.optionalString("month")?.let(YearMonth::parse),
        section = arguments.optionalEnum<UiSection>("section"),
        status = arguments.optionalEnum<TodoStatus>("status"),
        highlightIds = arguments.optionalStringList("highlight_ids"),
        scheduleId = arguments.optionalString("schedule_id"),
        runId = arguments.optionalString("run_id"),
    )
}

fun UiDirective.toJson(): JsonObject =
    buildJsonObject {
        put("surface", surface)
        focusMode?.let { put("focus_mode", it) }
        followUpListening?.let { put("follow_up_listening", it) }
        scheduleId?.let { put("schedule_id", it) }
        runId?.let { put("run_id", it) }
        date?.let { put("date", it.toString()) }
        month?.let { put("month", it.toString()) }
        section?.let { put("section", it.name.lowercase()) }
        status?.let { put("status", it.name.lowercase()) }
        if (highlightIds.isNotEmpty()) {
            put(
                "highlight_ids",
                JsonArray(highlightIds.map(::JsonPrimitive)),
            )
        }
    }

private fun MochiSurface.toDirective(): UiDirective = when (this) {
    MochiSurface.Face -> UiDirective("face")
    MochiSurface.DateTime -> UiDirective("date_time")
    MochiSurface.Weather -> UiDirective("weather")
    MochiSurface.Card -> UiDirective("card")
    MochiSurface.Conversation -> UiDirective("conversation")
    MochiSurface.Settings -> UiDirective("settings")
    MochiSurface.Skills -> UiDirective("skills")
    MochiSurface.Tools -> UiDirective("tools")
    MochiSurface.Today -> UiDirective("today")
    is MochiSurface.CalendarMonth -> UiDirective("calendar_month", month = month)
    is MochiSurface.CalendarDay -> UiDirective("calendar_day", date = date)
    is MochiSurface.Todo -> UiDirective("todo", date = date, status = status)
}

private fun stringProperty(
    values: List<Enum<*>>? = null,
    description: String? = null,
): JsonObject =
    buildJsonObject {
        put("type", "string")
        description?.let { put("description", it) }
        values?.let {
            put(
                "enum",
                JsonArray(it.map { value -> JsonPrimitive(value.name.lowercase()) }),
            )
        }
    }
