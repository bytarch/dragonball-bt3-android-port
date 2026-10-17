package com.bytarch.dbt3.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytarch.dbt3.R
import com.bytarch.dbt3.data.InstallState
import com.bytarch.dbt3.data.formatBytes
import com.bytarch.dbt3.ui.theme.Dbz

private val Scarlet = Color(0xFFC8102E)
private val Crimson = Color(0xFF7A0B1E)

/**
 * The game's own data: what is installed, the two ways to get it (your own image, or the one on the project's
 * server), the way to take it away again, and the progress while it happens. It lives in Settings: the lobby keeps
 * its space for the things that are always on it.
 */
@Composable
fun GameDataCard(
    install: InstallState,
    onPick: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (install) {
            is InstallState.Installed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(20.dp), tint = Dbz.ShenronGreen)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(
                            if (install.modified) R.string.game_data_ok_mod else R.string.game_data_ok,
                            install.files, formatBytes(install.bytes),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (install.modified) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp), tint = Dbz.SaiyanGold)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.game_data_modified),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SlantedBox(Crimson, borderWidth = 2f, onClick = onRemove) {
                    Text(stringResource(R.string.remove_game_data), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
            is InstallState.Working -> {
                Text(install.step, style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(
                    progress = { install.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    install.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(onClick = onCancel) {
                    Icon(Icons.Rounded.Close, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.cancel))
                }
            }
            is InstallState.Failed -> {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Warning, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(10.dp))
                    Text(install.message, style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPick) { Text(stringResource(R.string.pick_other_iso)) }
                    TextButton(onClick = onDismissError) { Text(stringResource(R.string.close)) }
                }
            }
            InstallState.NotInstalled -> {
                Text(
                    stringResource(R.string.game_data_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SlantedBox(Crimson, onClick = onPick) {
                        Text(stringResource(R.string.pick_iso), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                    SlantedBox(Scarlet, skew = 0.14f, onClick = onDownload) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Download, null, Modifier.size(18.dp), tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.iso_dl), color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Text(
                    stringResource(R.string.iso_dl_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
