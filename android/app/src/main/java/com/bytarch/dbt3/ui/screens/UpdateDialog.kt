package com.bytarch.dbt3.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bytarch.dbt3.ui.components.MarkdownNotes
import com.bytarch.dbt3.ui.theme.Dbz
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bytarch.dbt3.AppViewModel
import com.bytarch.dbt3.BuildConfig
import com.bytarch.dbt3.R
import com.bytarch.dbt3.data.UpdateState
import com.bytarch.dbt3.data.formatBytes

/** The pop-up for a new version: offer, download with progress, and the "install unknown apps" permission step. */
@Composable
fun UpdateDialog(vm: AppViewModel) {
    val st by vm.update.collectAsStateWithLifecycle()
    when (val s = st) {
        is UpdateState.Available -> Dialog(
            onDismissRequest = vm::dismissUpdate,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val maxH = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 640.dp).heightIn(max = maxH),
            ) {
                Column {
                    // header: what is out, and from which version
                    Row(
                        Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Dbz.GiOrangeDeep.copy(alpha = 0.55f), Dbz.GiBlue.copy(alpha = 0.45f))))
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(34.dp), tint = Dbz.SaiyanGold)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.update_available), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.update_versions, s.info.version, BuildConfig.VERSION_NAME),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (s.info.size > 0) {
                            Text(formatBytes(s.info.size), style = MaterialTheme.typography.labelLarge, color = Dbz.SaiyanGold)
                        }
                    }
                    // the changelog (CHANGELOG-vX.Y.Z.md), scrollable
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (s.info.nightly) Text(stringResource(R.string.update_nightly), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s.info.notes.isNotBlank()) MarkdownNotes(s.info.notes)
                        Text(stringResource(R.string.update_keeps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = vm::dismissUpdate) { Text(stringResource(R.string.update_later)) }
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = vm::startUpdate) { Text(stringResource(R.string.update_now)) }
                    }
                }
            }
        }

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_downloading)) },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (s.total > 0) LinearProgressIndicator(progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        formatBytes(s.done) + if (s.total > 0) " / ${formatBytes(s.total)}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::cancelUpdate) { Text(stringResource(R.string.cancel)) } },
        )

        is UpdateState.NeedPermission -> AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            title = { Text(stringResource(R.string.update_permission_title)) },
            text = { Text(stringResource(R.string.update_permission_text)) },
            confirmButton = { Button(onClick = vm::openInstallPermission) { Text(stringResource(R.string.update_permission_open)) } },
            dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text(stringResource(R.string.cancel)) } },
        )

        else -> {}
    }
}
