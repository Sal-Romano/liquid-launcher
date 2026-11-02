package dev.sal.glass.ui.dock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

private const val LONG_PRESS_MS = 500L

/**
 * OK/Enter on a focused tile: a press shorter than 500 ms clicks, holding it long-clicks once.
 * The remote repeats DOWN events while held; the first DOWN starts the timer and the UP decides.
 */
fun Modifier.tileClicks(onClick: () -> Unit, onLongClick: () -> Unit): Modifier = composed {
    var downAt by remember { mutableLongStateOf(0L) }
    var longFired by remember { mutableStateOf(false) }
    onKeyEvent { e ->
        val isSelect = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        if (!isSelect) return@onKeyEvent false
        when (e.type) {
            KeyEventType.KeyDown -> {
                val now = System.currentTimeMillis()
                if (downAt == 0L) { downAt = now; longFired = false }
                else if (!longFired && now - downAt >= LONG_PRESS_MS) { longFired = true; onLongClick() }
                true
            }
            KeyEventType.KeyUp -> {
                if (!longFired && downAt != 0L) onClick()
                downAt = 0L; longFired = false
                true
            }
            else -> false
        }
    }
}
