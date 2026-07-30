package com.hawksnest.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hawksnest.core.logic.CameraEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Frigate's AI description of the event under the playhead — the in-app answer to
 * "the push said *a person at the Kitchen*, but what were they doing?"
 *
 * The notification deliberately carries only a short Ring-style line: the
 * description is multi-paragraph, and it doesn't exist yet when the alert fires
 * (Frigate generates it after the event ends). So this is where you read it, and
 * it needs no new interaction to reach — tapping a timeline chip already seeks,
 * and the transport bar's prev/next already steps between events.
 *
 * Two states worth a row: a description exists, or a PERSON event is still
 * waiting for one. A dog/cat event with no description renders nothing — Frigate
 * only describes people, so that is a fact about the system rather than about
 * this event, and a permanent "not applicable" row costs real height.
 * Twin of the web `EventDescription.tsx`.
 */
@Composable
fun EventDescription(event: CameraEvent?, modifier: Modifier = Modifier) {
    // Nothing under the playhead (live, or scrubbed to a gap) — render nothing
    // rather than an empty box that makes the player jump.
    if (event == null) return

    var expanded by remember(event.id) { mutableStateOf(false) }
    val description = event.description
    val isPerson = event.label == "person"
    // Nothing useful to say about this one.
    if (description == null && !isPerson) return

    val body = description ?: "Description not ready yet."
    val time = remember(event.startMs) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(event.startMs))
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // Only a real description is worth expanding; the placeholders are one line.
            .let { if (description != null) it.clickable { expanded = !expanded } else it }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
            Text(
                "${event.label.replaceFirstChar { it.uppercase() }} · $time",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
