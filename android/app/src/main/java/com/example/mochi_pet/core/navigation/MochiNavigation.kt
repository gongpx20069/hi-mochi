package com.example.mochi_pet.core.navigation

import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.model.TodoStatus
import java.time.LocalDate
import java.time.YearMonth

sealed interface MochiNavigationIntent {
    data object ShowFace : MochiNavigationIntent

    data object ShowDateTime : MochiNavigationIntent

    data object ShowWeather : MochiNavigationIntent

    data object ShowCard : MochiNavigationIntent

    data object ShowConversation : MochiNavigationIntent

    data object ShowSettings : MochiNavigationIntent

    data object ShowSkills : MochiNavigationIntent

    data object ShowTools : MochiNavigationIntent

    data object ShowToday : MochiNavigationIntent

    data class ShowCalendarMonth(
        val month: YearMonth,
    ) : MochiNavigationIntent

    data class ShowCalendarDay(
        val date: LocalDate,
    ) : MochiNavigationIntent

    data class ShowTodo(
        val date: LocalDate? = null,
        val status: TodoStatus? = null,
    ) : MochiNavigationIntent
}

object MochiNavigationReducer {
    fun reduce(intent: MochiNavigationIntent): MochiSurface =
        when (intent) {
            MochiNavigationIntent.ShowFace -> MochiSurface.Face
            MochiNavigationIntent.ShowDateTime -> MochiSurface.DateTime
            MochiNavigationIntent.ShowWeather -> MochiSurface.Weather
            MochiNavigationIntent.ShowCard -> MochiSurface.Card
            MochiNavigationIntent.ShowConversation -> MochiSurface.Conversation
            MochiNavigationIntent.ShowSettings -> MochiSurface.Settings
            MochiNavigationIntent.ShowSkills -> MochiSurface.Skills
            MochiNavigationIntent.ShowTools -> MochiSurface.Tools
            MochiNavigationIntent.ShowToday -> MochiSurface.Today
            is MochiNavigationIntent.ShowCalendarMonth ->
                MochiSurface.CalendarMonth(intent.month)
            is MochiNavigationIntent.ShowCalendarDay ->
                MochiSurface.CalendarDay(intent.date)
            is MochiNavigationIntent.ShowTodo ->
                MochiSurface.Todo(date = intent.date, status = intent.status)
        }
}
