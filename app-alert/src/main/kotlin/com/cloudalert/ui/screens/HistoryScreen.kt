package com.cloudalert.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cloudalert.data.local.AlertEventEntity
import com.cloudmonitor.shared.ErrorCode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History of every event, filterable by error code.
 */
@Composable
fun HistoryScreen(
    history: List<AlertEventEntity>,
    codeFilter: String?,
    codes: List<String>,
    onFilter: (String?) -> Unit,
) {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val filtered = if (codeFilter == null) history else history.filter { it.code == codeFilter }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("History", style = MaterialTheme.typography.headlineSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                FilterChip(
                    selected = codeFilter == null,
                    onClick = { onFilter(null) },
                    label = { Text("All") },
                )
            }
            items(codes) { code ->
                FilterChip(
                    selected = codeFilter == code,
                    onClick = { onFilter(if (codeFilter == code) null else code) },
                    label = { Text(code) },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (filtered.isEmpty()) {
                item { Text("No events recorded yet.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(filtered) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${row.code} · ${row.type}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                row.deviceLabel.ifBlank { row.deviceId },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                row.packageName ?: "-",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                formatter.format(Date(row.occurredAt)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                ErrorCode.fromWire(row.code)?.title ?: row.code,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
