package com.ygosandbox.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ygosandbox.app.platform.CrashReports

/** Shown when the app starts after a crash: what went wrong, what it was doing, and the full report. */
@Composable
fun CrashNoticeDialog() {
    val notice by CrashReports.notice.collectAsState()
    val n = notice ?: return
    val clipboard = LocalClipboardManager.current
    var showReport by remember(n) { mutableStateOf(false) }
    var copied by remember(n) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { CrashReports.dismiss() },
        title = { Text("YGO Sandbox closed unexpectedly") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(n.summary, style = MaterialTheme.typography.bodyLarge)
                if (n.whileDoing.isNotBlank()) {
                    Column {
                        Text("What it was doing", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(n.whileDoing, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "A detailed report (your version and system, the last actions before the crash and the error) " +
                        "was saved to:\n${n.file.absolutePath}\nCopy it and send it to get the crash fixed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showReport) Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Text(
                            n.report, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()).padding(8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(n.report)); copied = true }) {
                Text(if (copied) "Copied" else "Copy report")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { showReport = !showReport }) { Text(if (showReport) "Hide report" else "Show report") }
                TextButton(onClick = { CrashReports.dismiss() }) { Text("Close") }
            }
        },
    )
}
