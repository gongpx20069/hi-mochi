package com.example.mochi_pet.core.model

import java.time.LocalDate
import java.time.YearMonth

sealed interface MochiSurface {
    data object Face : MochiSurface

    data object DateTime : MochiSurface

    data object Weather : MochiSurface

    data object Card : MochiSurface

    data object Conversation : MochiSurface

    data object Settings : MochiSurface

    data object Skills : MochiSurface

    data object Tools : MochiSurface

    data object Today : MochiSurface

    data class CalendarMonth(
        val month: YearMonth,
    ) : MochiSurface

    data class CalendarDay(
        val date: LocalDate,
    ) : MochiSurface

    data class Todo(
        val date: LocalDate? = null,
        val status: TodoStatus? = null,
    ) : MochiSurface
}

enum class TodoStatus {
    ACTIVE,
    COMPLETED,
}
