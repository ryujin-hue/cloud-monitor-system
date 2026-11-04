package com.cloudmonitor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cloudmonitor.data.local.EventLogEntity
import com.cloudmonitor.shared.ErrorCode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EventLogScreen(
    log: List<EventLogEntity>,
    onClear: () -> Unit,
) {
    var codeFilter by remember { mutableStateOf<String?>(null) }
    val filtered = if (codeFilter == null) log else log.filter { it.code == codeFilter }
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Local history", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Everything this cloud phone observed. The authoritative history lives on the backend.",
            style = MaterialTheme.typography.bodySmall,
        )
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                FilterChip(
                    selected = codeFilter == null,
                    onClick = { codeFilter = null },
                    label = { Text("All") },
                )
            }
            items(ErrorCode.all.map { it.wire }) { code ->
                FilterChip(
                    selected = codeFilter == code,
                    onClick = { codeFilter = if (codeFilter == code) null else code },
                    label = { Text(code) },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(filtered) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${row.code} · ${row.type}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                row.packageName ?: "-",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Text(
                            formatter.format(Date(row.occurredAt)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
