package com.trafficwatch.app.core.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun CellularConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Not connected to Wi-Fi") },
        text = { Text("Uploading now will use mobile data, which may incur charges. You can wait for Wi-Fi, or upload now anyway.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Upload Anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Wait for Wi-Fi") } }
    )
}
