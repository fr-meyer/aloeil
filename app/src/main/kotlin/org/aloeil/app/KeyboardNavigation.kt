package org.aloeil.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/** Tab navigates between controls without inserting whitespace into user input. */
@Composable
internal fun Modifier.moveFocusOnTab(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.key != Key.Tab) {
            false
        } else {
            if (event.type == KeyEventType.KeyDown) {
                focusManager.moveFocus(
                    if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next,
                )
            }
            true
        }
    }
}
