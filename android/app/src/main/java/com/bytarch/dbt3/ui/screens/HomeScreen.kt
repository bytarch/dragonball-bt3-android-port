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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bytarch.dbt3.AppViewModel
import com.bytarch.dbt3.R
import com.bytarch.dbt3.data.InstallState
import com.bytarch.dbt3.ui.components.DragonBall
import com.bytarch.dbt3.ui.components.SectionCard
import com.bytarch.dbt3.ui.components.SlantedBox
import com.bytarch.dbt3.ui.components.StatPill
import com.bytarch.dbt3.ui.theme.Dbz

private val Scarlet = Color(0xFFC8102E)
private val Crimson = Color(0xFF7A0B1E)
private val Steel = Color(0xFF1C2230)
private val Ink = Color(0xFF0B0A10)

/**
 * The lobby: the way a game lays out a main menu, with every control here doing something real.
 *
 * The angled banners are the battle pass and the update check; the left panel is the game's own data (install, the
 * ISO, remove); the right stack is the way into the rest of the app; the big red one is Play. Nothing on this screen
 * is decoration pretending to be a feature: there is no currency, no store and no friends list, because there is no
 * such thing here.
 */
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenBattlePass: () -> Unit,
    onOpenControls: () -> Unit,
    onOpenCheats: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val install by vm.install.collectAsStateWithLifecycle()
    val packs by vm.texturePacks.collectAsStateWithLifecycle()
    val mods by vm.fileMods.collectAsStateWithLifecycle()
    val stages by vm.stages.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val playerName by vm.playerName.collectAsStateWithLifecycle()
    val code by vm.recoveryCode.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var codeInput by remember { mutableStateOf("") }
    var codeNote by remember { mutableIntStateOf(0) } // 0 none; a string resource, negative meaning it is an error
    val pickIso = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.installFromIso(uri)
    }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmIso by remember { mutableStateOf(false) }
    val installed = install is InstallState.Installed

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- top: who is playing, and the two banners
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SlantedBox(Steel, onClick = onOpenSettings) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DragonBall(stars = 3, size = 26.dp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            playerName.ifBlank { stringResource(R.string.profile_name) },
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                        )
                        Text(
                            stringResource(R.string.profile_platform),
                            style = MaterialTheme.typography.labelSmall,
                            color = Dbz.SaiyanGold,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            SlantedBox(Scarlet, skew = 0.14f, onClick = onOpenBattlePass) {
                Column {
                    Text(
                        stringResource(R.string.battlepass_title).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontStyle = FontStyle.Italic,
                    )
                    Text(
                        stringResource(R.string.bp_coming_soon),
                        style = MaterialTheme.typography.labelSmall,
                        color = Dbz.SaiyanGold,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            SlantedBox(Steel, skew = 0.14f, borderWidth = 2f, onClick = { vm.checkForUpdate(true) }) {
                Icon(Icons.Rounded.SystemUpdateAlt, stringResource(R.string.hud_check_update), Modifier.size(20.dp), tint = Dbz.SaiyanGold)
            }
        }

        // ---- the game's own data: everything the left panel does is about being able to play
        SectionCard(stringResource(R.string.game_data), Icons.Rounded.Album, accent = if (installed) Dbz.ShenronGreen else Dbz.StarRed) {
            when (val s = install) {
                InstallState.NotInstalled -> {
                    Text(
                        stringResource(R.string.game_data_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SlantedBox(Crimson, onClick = { pickIso.launch(arrayOf("*/*")) }) {
                        Text(stringResource(R.string.pick_iso), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.size(8.dp))
                    SlantedBox(Scarlet, onClick = { confirmIso = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Download, null, Modifier.size(18.dp), tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.iso_dl), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.size(6.dp))
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
                        Icon(Icons.Rounded.Close, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.cancel))
                    }
                }
                is InstallState.Installed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Dbz.ShenronGreen)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(if (s.modified) R.string.game_data_ok_mod else R.string.game_data_ok, s.files, formatBytes(s.bytes)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (s.modified) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp), tint = Dbz.SaiyanGold)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                stringResource(R.string.game_data_modified),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    SlantedBox(Crimson, borderWidth = 2f, onClick = { confirmRemove = true }) {
                        Text(stringResource(R.string.remove_game_data), color = Color.White)
                    }
                }
                is InstallState.Failed -> {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.size(8.dp))
                        Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickIso.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.pick_other_iso)) }
                        TextButton(onClick = vm::dismissInstallError) { Text(stringResource(R.string.close)) }
                    }
                }
            }
        }

        // ---- the right-hand stack in the mock: the way into everything else in the app
        SectionCard(stringResource(R.string.hud_quick), Icons.Rounded.Extension, accent = Dbz.SaiyanGold) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SlantedBox(Steel, modifier = Modifier.weight(1f), onClick = onOpenBattlePass) { HudLabel(Icons.Rounded.Extension, R.string.battlepass_title) }
                SlantedBox(Steel, modifier = Modifier.weight(1f), skew = 0.14f, onClick = onOpenControls) { HudLabel(Icons.Rounded.SportsEsports, R.string.nav_controls) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SlantedBox(Steel, modifier = Modifier.weight(1f), onClick = onOpenCheats) { HudLabel(Icons.Rounded.LockOpen, R.string.nav_cheats) }
                SlantedBox(Steel, modifier = Modifier.weight(1f), skew = 0.14f, onClick = onOpenSettings) { HudLabel(Icons.Rounded.Settings, R.string.nav_settings) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SlantedBox(
                    Steel, modifier = Modifier.weight(1f), borderWidth = 2f,
                    onClick = { vm.exportLog() },
                ) { HudLabel(Icons.Rounded.Memory, R.string.hud_save_log) }
                SlantedBox(
                    Steel, modifier = Modifier.weight(1f), skew = 0.14f, borderWidth = 2f,
                    onClick = { vm.createPlayShortcut() },
                ) { HudLabel(Icons.Rounded.AddToHomeScreen, R.string.shortcut_add) }
            }
        }

        // ---- what is installed, in numbers
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatPill(stringResource(R.string.stat_textures), "${packs.count { it.enabled }}", Dbz.SaiyanGold, Modifier.weight(1f))
            StatPill(stringResource(R.string.stat_mods), "${mods.count { it.enabled }}", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            StatPill(stringResource(R.string.stat_stages), "${stages.size}", Dbz.KiBlue, Modifier.weight(1f))
            StatPill(stringResource(R.string.stat_songs), "${songs.size}", Dbz.ShenronGreen, Modifier.weight(1f))
        }

        // ---- the profile, still the same: the name that goes into the match, and the code that carries the record
        SectionCard(stringResource(R.string.profile), Icons.Rounded.Person) {
            OutlinedTextField(
                value = playerName,
                onValueChange = vm::setPlayerName,
                label = { Text(stringResource(R.string.profile_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.profile_recovery),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                    Text(stringResource(R.string.profile_copy))
                }
                Spacer(Modifier.width(10.dp))
                Text(code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.profile_recovery_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
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
            OutlinedButton(
                onClick = {
                    codeNote = if (vm.setRecoveryCode(codeInput)) {
                        codeInput = ""
                        R.string.profile_taken
                    } else {
                        R.string.profile_bad_code
                    }
                },
            ) { Text(stringResource(R.string.profile_use_code)) }
        }

        // ---- play, the one control that is bigger than the rest
        SlantedBox(
            Scarlet,
            modifier = Modifier.fillMaxWidth().height(74.dp),
            borderWidth = 3f,
            padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            onClick = vm::play,
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.PlayArrow, null, Modifier.size(34.dp),
                        tint = if (installed) Color.White else Color.White.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.play).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (installed) Color.White else Color.White.copy(alpha = 0.4f),
                        fontWeight = FontWeight.Black,
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
        }
        if (!installed) {
            Text(
                stringResource(R.string.msg_install_first),
                style = MaterialTheme.typography.bodySmall,
                color = Dbz.SaiyanGold,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard(stringResource(R.string.engine), Icons.Rounded.Memory, accent = MaterialTheme.colorScheme.secondary) {
            Text(
                stringResource(if (vm.engineAvailable) R.string.engine_included else R.string.engine_missing),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

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
private fun HudLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = Dbz.SaiyanGold)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
        )
    }
}
