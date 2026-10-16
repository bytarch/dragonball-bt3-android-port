package com.bytarch.dbt3.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.bytarch.dbt3.R
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bytarch.dbt3.AppViewModel
import com.bytarch.dbt3.data.InstallState
import com.bytarch.dbt3.data.formatBytes
import com.bytarch.dbt3.ui.components.DragonBall
import com.bytarch.dbt3.ui.components.SectionCard
import com.bytarch.dbt3.ui.components.StatPill
import com.bytarch.dbt3.ui.components.isWide
import com.bytarch.dbt3.ui.components.Page
import com.bytarch.dbt3.ui.theme.Dbz
import com.bytarch.dbt3.ui.theme.TitleStyle

@Composable
fun HomeScreen(vm: AppViewModel) {
    val install by vm.install.collectAsStateWithLifecycle()
    val packs by vm.texturePacks.collectAsStateWithLifecycle()
    val mods by vm.fileMods.collectAsStateWithLifecycle()
    val stages by vm.stages.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val playerName by vm.playerName.collectAsStateWithLifecycle()
    val code by vm.recoveryCode.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var codeInput by remember { mutableStateOf("") }
    var codeNote by remember { mutableStateOf(0) } // 0 none; a string resource, negative meaning it is an error
    val pickIso = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.installFromIso(uri)
    }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmIso by remember { mutableStateOf(false) }
    val installed = install is InstallState.Installed
    val wide = isWide()

    // (lambdas, not local @Composable funs: the Compose compiler does not support those)
    val main: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Hero(compact = wide)
        Button(
            onClick = vm::play,
            enabled = installed,
            modifier = Modifier.fillMaxWidth().height(if (wide) 72.dp else 64.dp),
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(30.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.play), style = MaterialTheme.typography.headlineSmall)
        }
        if (installed) {
            OutlinedButton(onClick = vm::createPlayShortcut, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.AddToHomeScreen, null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.shortcut_add))
            }
            Text(
                stringResource(R.string.shortcut_add_text),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        }
    }

    val side: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard(stringResource(R.string.game_data), Icons.Rounded.Album) {
            when (val s = install) {
                InstallState.NotInstalled -> {
                    Text(
                        stringResource(R.string.game_data_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = { pickIso.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.pick_iso)) }
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = { confirmIso = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.iso_dl))
                    }
                    Text(
                        stringResource(R.string.iso_dl_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is InstallState.Working -> {
                    Text(s.step, style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(
                        progress = { s.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = vm::cancelInstall) {
                        Icon(Icons.Rounded.Close, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.cancel))
                    }
                }
                is InstallState.Installed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Dbz.ShenronGreen)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(if (s.modified) R.string.game_data_ok_mod else R.string.game_data_ok, s.files, formatBytes(s.bytes)), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (s.modified) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.Warning, null, tint = Dbz.SaiyanGold)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                stringResource(R.string.game_data_modified),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.remove_game_data)) }
                }
                is InstallState.Failed -> {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.size(8.dp))
                        Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { pickIso.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.pick_other_iso)) }
                        TextButton(onClick = vm::dismissInstallError) { Text(stringResource(R.string.close)) }
                    }
                }
            }
        }

        SectionCard(stringResource(R.string.profile), Icons.Rounded.Person) {
            OutlinedTextField(
                value = playerName,
                onValueChange = vm::setPlayerName,
                label = { Text(stringResource(R.string.profile_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                stringResource(R.string.profile_name_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(10.dp))
            // The recovery code: what carries the record to another phone or to the PC. Shown, copyable, and
            // another one can be pasted in here - do that on the device that still has the record first.
            Text(
                stringResource(R.string.profile_recovery),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                    Text(stringResource(R.string.profile_copy))
                }
                Spacer(Modifier.size(10.dp))
                Text(
                    code,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.profile_recovery_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = codeInput,
                onValueChange = { codeInput = it.trim().lowercase() },
                label = { Text(stringResource(R.string.profile_code_in)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (codeNote != 0) {
                Text(
                    stringResource(codeNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (codeNote < 0) MaterialTheme.colorScheme.error else Dbz.ShenronGreen,
                )
            }
            FilledTonalButton(
                onClick = {
                    codeNote = if (vm.setRecoveryCode(codeInput)) {
                        codeInput = ""
                        R.string.profile_taken
                    } else {
                        R.string.profile_bad_code
                    }
                },
            ) { Text(stringResource(R.string.profile_use_code)) }
            Spacer(Modifier.size(10.dp))
            // Which kind of machine this is, said next to the name: the other player sees it in a match, and it is
            // where the name is typed, so the two sit together. Not the phone or the Windows version: of no use.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.profile_platform),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        stringResource(R.string.platform_mobile),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
        }

        SectionCard(stringResource(R.string.engine), Icons.Rounded.Memory, accent = MaterialTheme.colorScheme.secondary) {
            if (vm.engineAvailable) {
                Text(stringResource(R.string.engine_included), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    stringResource(R.string.engine_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatPill(stringResource(R.string.stat_textures), "${packs.count { it.enabled }}", Dbz.SaiyanGold, Modifier.weight(1f))
            StatPill(stringResource(R.string.stat_mods), "${mods.count { it.enabled }}", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            StatPill(stringResource(R.string.stat_stages), "${stages.size}", Dbz.KiBlue, Modifier.weight(1f))
            StatPill(stringResource(R.string.stat_songs), "${songs.size}", Dbz.ShenronGreen, Modifier.weight(1f))
        }
        }
    }

    // Both halves scroll on one page, so the shorter column does not run out before the taller one.
    Page(left = main, right = side)

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_game_data_q)) },
            text = { Text(stringResource(R.string.remove_game_data_text)) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; vm.removeGameData() }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    // 2.8 GB is a decision, not a tap: on mobile data it is the player's data plan, and it needs the free space.
    if (confirmIso) {
        AlertDialog(
            onDismissRequest = { confirmIso = false },
            title = { Text(stringResource(R.string.iso_dl)) },
            text = { Text(stringResource(R.string.iso_dl_confirm)) },
            confirmButton = { TextButton(onClick = { confirmIso = false; vm.downloadIso() }) { Text(stringResource(R.string.iso_dl_start)) } },
            dismissButton = { TextButton(onClick = { confirmIso = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Hero(compact: Boolean = false) {
    Box(Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 12.dp), contentAlignment = Alignment.CenterStart) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DragonBall(stars = 4, size = if (compact) 72.dp else 120.dp)
            Column(Modifier.padding(start = 4.dp)) {
                val gold = Brush.verticalGradient(listOf(Dbz.BallLight, Dbz.SaiyanGold, Dbz.GiOrange))
                Text(
                    "DBT3",
                    style = (TitleStyle.copy(lineHeight = TitleStyle.fontSize * 0.95f)).copy(
                        fontSize = if (compact) TitleStyle.fontSize * 0.7f else TitleStyle.fontSize,
                        lineHeight = if (compact) TitleStyle.fontSize * 0.66f else TitleStyle.fontSize * 0.95f,
                    ),
                    modifier = Modifier
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(gold, blendMode = BlendMode.SrcIn)
                        },
                )
                Text(
                    stringResource(R.string.tagline),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
