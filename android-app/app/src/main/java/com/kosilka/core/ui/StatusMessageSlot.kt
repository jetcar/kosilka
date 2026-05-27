package com.kosilka.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Stable single-line status area that prevents layout jumps when a message appears/disappears.
 * Use this in screens above primary content (maps/lists/forms) instead of conditional Text blocks.
 */
@Composable
fun StatusMessageSlot(
    message: String?,
    modifier: Modifier = Modifier,
    isError: Boolean = true,
    emptyText: String = " "
) {
    val textColor = if (isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        AnimatedContent(
            targetState = message?.trim().orEmpty(),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "status-message-slot"
        ) { current ->
            Text(
                text = if (current.isBlank()) emptyText else current,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
        }
    }
}
