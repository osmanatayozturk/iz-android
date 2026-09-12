package org.iz.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.iz.navigation.weather.StopOrderProposal

@Composable
internal fun StopOrderProposalCard(proposal: StopOrderProposal, enabled: Boolean, apply: () -> Unit, dismiss: () -> Unit) {
    Card(Modifier.testTag("matrix-order-proposal")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Daha hızlı durak sırası", style = MaterialTheme.typography.titleMedium)
            Text("Mevcut: " + proposal.originalStops.joinToString(" → ") { it.label })
            Text("Öneri: " + proposal.orderedStops.joinToString(" → ") { it.label })
            Text("${formatDuration(proposal.originalRoute.durationSeconds)} → ${formatDuration(proposal.proposedRoute.durationSeconds)} · ${proposal.savedSeconds.toInt()} sn daha kısa")
            Text("Mesafe: ${formatDistance(proposal.originalRoute.distanceMeters)} → ${formatDistance(proposal.proposedRoute.distanceMeters)}",
                modifier = Modifier.testTag("matrix-comparison-distances"), style = MaterialTheme.typography.bodySmall)
            Text("Karşılaştırma saati: ${weatherTime(proposal.comparisonDepartureAt)}. Başlangıç ve varış sabit; yalnızca durak sırası uygulanır.",
                style = MaterialTheme.typography.bodySmall)
            if (proposal.approximateMotorcycle) Text("Sıra önerisi otomobil Matrix tahmininden üretildi. Yukarıdaki süreler motosiklet rotasıyla yeniden hesaplandı.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(apply, enabled = enabled, modifier = Modifier.testTag("matrix-apply-order")) { Text("Bu sırayı uygula") }
                TextButton(dismiss, enabled = enabled) { Text("Mevcut sırayı koru") }
            }
        }
    }
}
