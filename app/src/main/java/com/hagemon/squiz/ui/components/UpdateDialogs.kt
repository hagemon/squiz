package com.hagemon.squiz.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hagemon.squiz.data.update.AppUpdateInfo
import com.hagemon.squiz.data.update.DownloadedUpdate
import java.util.Locale

@Composable
fun AvailableUpdateDialog(
    info: AppUpdateInfo,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${info.versionName}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (info.assetSize > 0L) {
                    Text(
                        text = "安装包 ${formatFileSize(info.assetSize)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = info.notes.ifBlank { "本次更新暂无说明。" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
        confirmButton = { TextButton(onClick = onDownload) { Text("立即更新") } },
    )
}

@Composable
fun DownloadedUpdateDialog(
    update: DownloadedUpdate,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新已下载") },
        text = { Text("Squiz ${update.info.versionName} 已通过校验，可以交给系统安装。") },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
        confirmButton = { TextButton(onClick = onInstall) { Text("安装") } },
    )
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f)
    else -> "$bytes B"
}
