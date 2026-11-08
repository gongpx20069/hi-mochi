package com.example.mochi_pet.feature.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mochi_pet.core.journal.DailyJournalController
import com.example.mochi_pet.core.journal.JournalRepository
import com.example.mochi_pet.core.journal.JournalSettings
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JournalViewModel(
    private val repository: JournalRepository,
    private val controller: DailyJournalController,
) : ViewModel() {
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    val settings: StateFlow<JournalSettings?> = repository.settings
        .catch { report(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val today = flow {
        while (true) {
            emit(repository.today())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.today())

    fun observe(date: LocalDate) = repository.observe(date).catch { report(it) }
    fun markers(month: YearMonth) = combine(repository.observeMonth(month), today) { dates, current ->
        dates.filterTo(mutableSetOf()) { it < current }
    }.catch { report(it) }

    fun setEnabled(enabled: Boolean) = act { controller.setEnabled(enabled) }
    fun retry(date: LocalDate) = act { controller.retry(date) }
    fun delete(date: LocalDate) = act { controller.delete(date) }
    fun refresh() = act { controller.reconcile() }

    private fun act(block: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        mutableError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try { block() } catch (error: Exception) {
                if (error is CancellationException) throw error
                report(error)
            } finally { mutableBusy.value = false }
        }
    }

    private fun report(error: Throwable) {
        if (error is CancellationException) throw error
        java.util.logging.Logger.getLogger("DailyJournal").warning("Journal UI operation failed: ${error.javaClass.simpleName}")
        mutableError.value = "Could not update Daily journal. Please retry."
    }
}
