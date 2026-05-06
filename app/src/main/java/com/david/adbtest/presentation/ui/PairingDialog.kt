package com.david.adbtest.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.david.adbtest.domain.model.PairingInfo

@Composable
fun PairingDialog(
    onDismiss: () -> Unit,
    onPair: (PairingInfo) -> Unit
) {
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "📱 Wireless Pairing",
                    style = MaterialTheme.typography.headlineSmall
                )

                Text(
                    text = "1. Settings → Developer Options\n" +
                            "2. Enable Wireless Debugging\n" +
                            "3. Tap 'Pair device with pairing code'\n" +
                            "4. Enter details below:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    placeholder = { Text("e.g., 42161") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Pairing Code") },
                    placeholder = { Text("e.g., 123456") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Skip")
                    }

                    Button(
                        onClick = {
                            if (port.isNotBlank() && code.isNotBlank()) {
                                onPair(PairingInfo(port, code))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = port.isNotBlank() && code.isNotBlank()
                    ) {
                        Text("Pair")
                    }
                }
            }
        }
    }
}