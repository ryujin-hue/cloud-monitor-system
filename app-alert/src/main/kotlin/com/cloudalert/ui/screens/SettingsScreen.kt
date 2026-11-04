package com.cloudalert.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    apiKey: String,
    label: String,
    subscriptions: Set<String>,
    pushAvailable: Boolean,
    onSaveApiKey: (String) -> Unit,
    onSaveLabel: (String) -> Unit,
    onRegister: () -> Unit,
    onPair: (String) -> Unit,
) {
    var key by remember(apiKey) { mutableStateOf(apiKey) }
    var name by remember(label) { mutableStateOf(label) }
    var pairingCode by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backend API key", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The alert key of the Cloudflare backend. Stored encrypted with an " +
                        "Android Keystore key.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("x-api-key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onSaveApiKey(key) }) { Text("Save key") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This device", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSaveLabel(name) }) { Text("Save name") }
                    Button(onClick = onRegister) { Text("Register") }
                }
                Text(
                    if (pushAvailable) "Push (FCM) is active." else "Push (FCM) is not configured.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pair with a cloud phone", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Enter the 6-digit code shown on the cloud phone. One alert device can " +
                        "subscribe to any number of cloud phones.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                    label = { Text("Pairing code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onPair(pairingCode) },
                    enabled = pairingCode.length == 6,
                ) { Text("Pair") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Subscribed cloud phones", style = MaterialTheme.typography.titleMedium)
                if (subscriptions.isEmpty()) {
                    Text("None yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    subscriptions.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
