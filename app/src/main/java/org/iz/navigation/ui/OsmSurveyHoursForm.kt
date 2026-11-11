@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.iz.navigation.integration.SurveyDayHours
import org.iz.navigation.integration.SurveyDayState
import org.iz.navigation.integration.SurveyHoursInterval

@Composable
internal fun SurveyHoursDay(index: Int, day: SurveyDayHours, enabled: Boolean, onChange: (SurveyDayHours) -> Unit) {
    val title = listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")[index]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((state, label) in listOf(SurveyDayState.OPEN to "Açık", SurveyDayState.CLOSED to "Kapalı", SurveyDayState.UNKNOWN to "Bilmiyorum")) {
                FilterChip(day.state == state, onClick = {
                    onChange(day.copy(state = state, intervals = if (state == SurveyDayState.OPEN && day.intervals.isEmpty())
                        listOf(SurveyHoursInterval("", "")) else day.intervals))
                }, enabled = enabled, label = { Text(label) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("osm-survey-hours-day-$index-${state.name}"))
            }
        }
        if (day.state == SurveyDayState.OPEN) {
            day.intervals.forEachIndexed { intervalIndex, interval ->
                fun change(updated: SurveyHoursInterval) {
                    onChange(day.copy(intervals = day.intervals.toMutableList().apply { set(intervalIndex, updated) }))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(interval.from, { change(interval.copy(from = it)) }, enabled = enabled,
                        label = { Text("$title başlangıç") }, placeholder = { Text("09:00") }, singleLine = true,
                        modifier = Modifier.weight(1f).testTag("osm-survey-hours-day-$index-$intervalIndex-from"))
                    OutlinedTextField(interval.until, { change(interval.copy(until = it)) }, enabled = enabled,
                        label = { Text("$title bitiş") }, placeholder = { Text("18:00") }, singleLine = true,
                        modifier = Modifier.weight(1f).testTag("osm-survey-hours-day-$index-$intervalIndex-until"))
                }
                if (day.intervals.size > 1) TextButton(onClick = {
                    onChange(day.copy(intervals = day.intervals.filterIndexed { current, _ -> current != intervalIndex }))
                }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("${intervalIndex + 1}. aralığı kaldır") }
            }
            TextButton(onClick = { onChange(day.copy(intervals = day.intervals + SurveyHoursInterval("", ""))) },
                enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("osm-survey-hours-day-$index-add-interval")) {
                Text("Saat aralığı ekle")
            }
            Text("Bitiş başlangıçtan erkense aralık ertesi güne uzanır. Tüm gün için 00:00–24:00 gir.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
