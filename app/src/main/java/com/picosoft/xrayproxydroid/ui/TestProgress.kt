package com.picosoft.xrayproxydroid.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Снимок прогресса Полного теста для бара. ОТДЕЛЬНЫЙ StateFlow (не state всего экрана):
 * onProgress тикает по числу серверов (61+), а collectAsState вызывается ТОЛЬКО внутри composable
 * самого бара — значит на каждый тик рекомпозится лишь бар, а не LazyColumn со строками.
 */
data class TestProgressState(
    val active: Boolean = false,       // бар виден
    val indeterminate: Boolean = false,// total неизвестен (подписка/подъём ядра/старт)
    val fraction: Float = 0f,          // доля 0..1 текущей фазы
    val phase: String = "",            // текст «что происходит» (под баром)
)

/** Прогресс Полного теста. Один процесс → singleton со StateFlow (как ProxyState). */
object TestProgress {
    private val _state = MutableStateFlow(TestProgressState())
    val state: StateFlow<TestProgressState> = _state.asStateFlow()

    /** Старт/индетерминатность (total неизвестен): бар виден, ползунок бесконечный. */
    fun startIndeterminate(phase: String) {
        _state.value = TestProgressState(active = true, indeterminate = true, fraction = 0f, phase = phase)
    }

    /** Determinate-тик фазы. total<=0 → снова indeterminate. Фаза-текст не трогаем (идёт из phase()). */
    fun progress(done: Int, total: Int) {
        val f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        _state.value = _state.value.copy(active = true, indeterminate = total <= 0, fraction = f)
    }

    /** Только текст фазы (бар/доля не трогаем). */
    fun phase(text: String) {
        _state.value = _state.value.copy(phase = text)
    }

    /** Завершение прогона / отмена: бар гаснет, итоговый текст остаётся. */
    fun finish(text: String) {
        _state.value = _state.value.copy(active = false, phase = text)
    }

    /** Полный сброс (бар и текст). */
    fun clear() {
        _state.value = TestProgressState()
    }
}
