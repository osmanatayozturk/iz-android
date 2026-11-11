package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.SelectedOsmPlace

@Composable
internal fun CommonPlaceQuery(query: String, busy: Boolean, onEdit: (String) -> Unit,
    onSearch: () -> Unit, prefix: String) {
    val focus = LocalFocusManager.current
    val enabled = query.isNotBlank() && !busy
    val submit = { if (enabled) { focus.clearFocus(); onSearch() } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, onEdit, label = { Text("Yer adı veya adres") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            modifier = Modifier.weight(1f).testTag("$prefix-query"))
        Button(onClick = submit, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).testTag("$prefix-submit")) { Text("Ara") }
    }
}

@Composable
internal fun CommonPlaceResult(place: SelectedOsmPlace, onSelect: (SelectedOsmPlace) -> Unit) {
    OutlinedCard(onClick = { onSelect(place) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(place.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(place.displayName?.takeIf { it.isNotBlank() && it.trim() != place.name.trim() } ?: "Adres bilgisi yok",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
