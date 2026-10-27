package com.hawksnest.ui.everyday

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.logic.EverydayDay
import com.hawksnest.core.logic.EverydayFixture
import com.hawksnest.core.logic.EverydayReel
import com.hawksnest.core.logic.EverydaySummary
import com.hawksnest.core.net.EverydayClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class EverydayUi(
    /** True when there's no HA token — the screen runs on [EverydayFixture]'s sample days. */
    val demo: Boolean = false,
    val loading: Boolean = true,
    val days: List<EverydaySummary> = emptyList(),
    val reels: List<EverydayReel> = emptyList(),
    val selected: String? = null,
    val day: EverydayDay? = null,
    val preview: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    /** For media requests (ExoPlayer/Coil headers). Null in demo. */
    val token: String? = null,
    val baseUrl: String = "",
)

/**
 * Review surface for the `everyday` service — Kotlin twin of the web `EverydayScreen`. Demo
 * mode (no saved token, the same rule [ConnectionManager] uses to pick the fixture source)
 * serves sample days from [EverydayFixture], with picks held in memory so the flow is real.
 */
@HiltViewModel
class EverydayViewModel @Inject constructor(
    private val connection: ConnectionManager,
    private val client: EverydayClient,
) : ViewModel() {

    private val _ui = MutableStateFlow(EverydayUi())
    val ui: StateFlow<EverydayUi> = _ui.asStateFlow()

    /** Demo-mode store: mutable so "Use this one" works without a backend. */
    private var demoDays: List<EverydayDay> = emptyList()
    private var dayJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val token = connection.haToken()
            val base = connection.state.baseUrl.value
            if (token == null || base.isBlank()) {
                if (demoDays.isEmpty()) demoDays = EverydayFixture.days(LocalDate.now())
                val summaries = EverydayFixture.summaries(demoDays)
                _ui.update {
                    it.copy(
                        demo = true, loading = false, days = summaries,
                        reels = EverydayFixture.reels(demoDays),
                        selected = it.selected ?: summaries.firstOrNull()?.date,
                        token = null, baseUrl = "",
                    )
                }
            } else {
                _ui.update { it.copy(demo = false, token = token, baseUrl = base) }
                try {
                    val days = client.days(base, token)
                    val reels = runCatching { client.reels(base, token) }.getOrDefault(emptyList())
                    _ui.update {
                        it.copy(
                            loading = false, days = days, reels = reels, message = null,
                            selected = it.selected ?: days.firstOrNull()?.date,
                        )
                    }
                } catch (e: Exception) {
                    _ui.update { it.copy(loading = false, message = e.message) }
                }
            }
            _ui.value.selected?.let { load(it) }
        }
    }

    fun select(date: String) {
        _ui.update { it.copy(selected = date, preview = null) }
        load(date)
    }

    private fun load(date: String) {
        dayJob?.cancel()
        dayJob = viewModelScope.launch {
            val s = _ui.value
            if (s.demo) {
                _ui.update { it.copy(day = demoDays.firstOrNull { d -> d.date == date }) }
                return@launch
            }
            _ui.update { it.copy(day = null) }
            try {
                val day = client.day(s.baseUrl, s.token ?: return@launch, date)
                _ui.update { it.copy(day = day) }
            } catch (e: Exception) {
                _ui.update { it.copy(message = e.message) }
            }
        }
    }

    fun preview(eventId: String) = _ui.update { it.copy(preview = eventId) }

    fun choose(eventId: String) {
        val s = _ui.value
        val day = s.day ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                val updated = if (s.demo) {
                    day.copy(pick = eventId, manual = true).also { u ->
                        demoDays = demoDays.map { if (it.date == u.date) u else it }
                    }
                } else {
                    client.pick(s.baseUrl, s.token ?: return@launch, day.date, eventId)
                }
                _ui.update { it.copy(day = updated, preview = null) }
                refresh()
            } catch (e: Exception) {
                _ui.update { it.copy(message = e.message) }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    /** Save a yearly reel to Movies/Hawksnest (streamed, token-authenticated). */
    fun downloadReel(context: android.content.Context, reel: EverydayReel) {
        val s = _ui.value
        if (s.demo) {
            _ui.update { it.copy(message = "Demo mode: connect Home Assistant to download your reel.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "Saving ${reel.year} reel…") }
            val result = com.hawksnest.ui.cameras.saveClipExport(
                context.applicationContext,
                client.mediaUrl(s.baseUrl, reel.url),
                "everyday-${reel.year}${if (reel.edit) "-edit" else ""}.mp4",
                authToken = s.token,
            )
            val msg = when (result) {
                is com.hawksnest.ui.cameras.ClipSaveResult.Saved -> "Saved to Movies/Hawksnest."
                is com.hawksnest.ui.cameras.ClipSaveResult.Failed -> result.message
            }
            _ui.update { it.copy(busy = false, message = msg) }
        }
    }

    fun rescan() {
        val s = _ui.value
        val date = s.selected ?: return
        if (s.demo) {
            _ui.update { it.copy(message = "Demo mode: sample days don't rescan.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                client.scan(s.baseUrl, s.token ?: return@launch, date)
                _ui.update { it.copy(message = "Rescan queued. Check back in a few minutes.") }
            } catch (e: Exception) {
                _ui.update { it.copy(message = e.message) }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }
}
