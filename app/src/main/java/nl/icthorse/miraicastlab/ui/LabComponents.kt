package nl.icthorse.miraicastlab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation

/** Colour for an evidence grade. One place, so every screen agrees. */
fun statusColor(status: LabStatus): Color = when (status) {
    LabStatus.CONFIRMED -> LabColors.Confirmed
    LabStatus.OBSERVED -> LabColors.Observed
    LabStatus.INFERRED -> LabColors.Inferred
    LabStatus.UNSUPPORTED -> LabColors.Unsupported
    LabStatus.NOT_TESTED -> LabColors.NotTested
    LabStatus.ERROR -> LabColors.Error
}

/**
 * Standard screen frame: title bar with a back arrow, then scrolling content.
 * Every test screen uses this so the tester always knows how to get back.
 */
@Composable
fun LabScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.TextDim,
                        )
                    }
                }
                actions()
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

/** Card container used for every grouped block of information. */
@Composable
fun LabCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, LabColors.Line, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

/**
 * A very large primary action button.
 * Section 21 of the spec: the tester must hit these while seated in a vehicle.
 */
@Composable
fun BigActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = LabColors.Ink,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = MaterialTheme.typography.labelLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Secondary action, same touch target, less visual weight. */
@Composable
fun LabButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Compact coloured badge showing an evidence grade. */
@Composable
fun StatusChip(status: LabStatus, modifier: Modifier = Modifier) {
    val c = statusColor(status)
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.copy(alpha = 0.16f))
            .border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(status.name, style = LabMono, color = c, fontWeight = FontWeight.Bold)
    }
}

/** Big pass/fail/waiting banner for the top of a test screen. */
@Composable
fun VerdictBanner(status: LabStatus, headline: String, detail: String? = null) {
    val c = statusColor(status)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.copy(alpha = 0.14f))
            .border(2.dp, c, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(status.name, style = MaterialTheme.typography.displaySmall, color = c)
        Text(headline, style = MaterialTheme.typography.titleLarge)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextDim)
        }
    }
}

/** One key/value/grade row. */
@Composable
fun ObservationRow(o: Observation, onClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                o.key,
                style = LabMono,
                color = LabColors.TextDim,
                modifier = Modifier.weight(1f),
            )
            StatusChip(o.status)
        }
        Text(o.value, style = MaterialTheme.typography.bodyLarge)
        if (o.note != null) {
            Text(o.note, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextDim)
        }
    }
}

/** Scrolling list of observations with a running count in the header. */
@Composable
fun ObservationList(
    observations: List<Observation>,
    modifier: Modifier = Modifier,
    emptyText: String = "No observations yet.",
) {
    if (observations.isEmpty()) {
        Text(emptyText, color = LabColors.TextDim, modifier = Modifier.padding(8.dp))
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
        items(observations) { ObservationRow(it) }
    }
}

/** Fixed-width monospaced block for raw evidence (dumps, coordinates, codec names). */
@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LabColors.Ink)
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        Text(text, style = LabMono, color = LabColors.Text)
    }
}

/** Labelled statistic, e.g. "48.7" / "fps". */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, color: Color = LabColors.Accent) {
    Column(modifier.padding(end = 18.dp)) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = color)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextDim)
    }
}

/** Horizontal row of stat tiles. */
@Composable
fun StatRow(vararg stats: Pair<String, String>) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.Start,
    ) {
        stats.forEach { (v, l) -> StatTile(v, l) }
    }
}

/** A small coloured dot, for live/idle indicators. */
@Composable
fun Dot(color: Color, size: Int = 10) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}

/** Fixed-width gap helper used across screens. */
@Composable
fun Gap(height: Int = 8) = Spacer(Modifier.height(height.dp))

/** Fixed-width horizontal gap. */
@Composable
fun HGap(width: Int = 8) = Spacer(Modifier.width(width.dp))
