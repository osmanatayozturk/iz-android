package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun StatisticsSurface(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = if (emphasized) colors.primaryContainer else colors.surfaceContainerLow,
        contentColor = if (emphasized) colors.onPrimaryContainer else colors.onSurface,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun StatisticsMetricGrid(values: List<Pair<String, String>>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 288.dp || LocalDensity.current.fontScale >= 1.5f) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, value) ->
                        Surface(
                            Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
                                Text(value, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

internal fun statisticsDuration(value: Long): String {
    val seconds = value.coerceAtLeast(0) / 1_000
    return when {
        seconds < 60 -> "$seconds sn"
        seconds < 3_600 -> "${seconds / 60} dk"
        else -> "${seconds / 3_600} sa ${seconds % 3_600 / 60} dk"
    }
}

internal fun statisticsSpeed(value: Double?): String = value?.takeIf { it.isFinite() && it >= 0.0 }
    ?.let { String.format(Locale.forLanguageTag("tr-TR"), "%.1f km/sa", it) } ?: "-"
