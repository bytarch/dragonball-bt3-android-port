package com.bytarch.dbt3.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bytarch.dbt3.AppViewModel
import com.bytarch.dbt3.R
import com.bytarch.dbt3.data.InstallState
import com.bytarch.dbt3.data.formatBytes
import com.bytarch.dbt3.ui.components.DragonBall
import com.bytarch.dbt3.ui.components.SlantedBox
import com.bytarch.dbt3.ui.components.StatPill
import com.bytarch.dbt3.ui.theme.Dbz

private val Scarlet = Color(0xFFC8102E)
private val Crimson = Color(0xFF7A0B1E)
private val Steel = Color(0xFF1C2230)
private val Ink = Color(0xFF0B0A10)

/**
 * The lobby, the way a mobile game opens: who you are at the top, what you have in the middle, one big control at the
 * bottom. It does not scroll — the phone is held in landscape and everything fits, and a home screen that scrolls is
 * a home screen where Play can be off the screen.
 *
 * Every control here does something real: Play starts the game, the banners go to the battle pass and the update
 * check, the left card is the game's own data, and the grid is the way into the rest of the app. There is no currency,
 * no store and no friends list, because there is no such thing here.
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
    var codeDialog by remember { mutableStateOf(false) }
    var codeInput by remember { mutableStateOf("") }
    var codeNote by remember { mutableIntStateOf(0) }
    val installed = install is InstallState.Installed

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // a short landscape window (about 360dp of height) is the case to design for: the columns sit side by side
        val roomy = maxHeight > 420.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
            // ---- top: the player, and the two banners that hang off it
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                SlantedBox(Steel, modifier = Modifier.weight(1f), onClick = onOpenSettings) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DragonBall(stars = 3, size = if (roomy) 30.dp else 24.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                playerName.ifBlank { stringResource(R.string.profile_name) },
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(R.string.profile_code_short, code),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        SlantedBox(Steel, skew = 0.14f, borderWidth = 2f, onClick = { clipboard.setText(AnnotatedString(code)) }) {
                            Text(stringResource(R.string.profile_copy), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.width(6.dp))
                        SlantedBox(Steel, skew = 0.14f, borderWidth = 2f, onClick = { codeDialog = true }) {
                            Text(stringResource(R.string.profile_code_in_short), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
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

            Spacer(Modifier.height(10.dp))

            // ---- the middle: the way into everything else, and what you have
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        GridButton(Modifier.weight(1f), Icons.Rounded.Extension, R.string.battlepass_title, onOpenBattlePass)
                        GridButton(Modifier.weight(1f), Icons.Rounded.SportsEsports, R.string.nav_controls, onOpenControls, skew = 0.14f)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        GridButton(Modifier.weight(1f), Icons.Rounded.LockOpen, R.string.nav_cheats, onOpenCheats)
                        GridButton(Modifier.weight(1f), Icons.Rounded.Settings, R.string.nav_settings, onOpenSettings, skew = 0.14f)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        GridButton(Modifier.weight(1f), Icons.Rounded.Memory, R.string.hud_save_log, { vm.exportLog() })
                        GridButton(Modifier.weight(1f), Icons.Rounded.AddToHomeScreen, R.string.shortcut_add, { vm.createPlayShortcut() }, skew = 0.14f)
                    }
                }
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        StatPill(stringResource(R.string.stat_textures), "${packs.count { it.enabled }}", Dbz.SaiyanGold, Modifier.weight(1f))
                        StatPill(stringResource(R.string.stat_mods), "${mods.count { it.enabled }}", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        StatPill(stringResource(R.string.stat_stages), "${stages.size}", Dbz.KiBlue, Modifier.weight(1f))
                        StatPill(stringResource(R.string.stat_songs), "${songs.size}", Dbz.ShenronGreen, Modifier.weight(1f))
                    }
                    // the game's own data is Settings' business; all the lobby says about it is whether Play can happen
                    SlantedBox(Steel, modifier = Modifier.fillMaxWidth(), skew = 0.14f, borderWidth = 2f, onClick = onOpenSettings) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Album, null, Modifier.size(18.dp), tint = if (installed) Dbz.ShenronGreen else Dbz.StarRed)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(if (installed) R.string.game_data_ok_short else R.string.game_data_missing),
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ---- the one control that is bigger than the rest, at the bottom where the thumb is
            SlantedBox(
                Scarlet,
                modifier = Modifier.fillMaxWidth().height(if (roomy) 66.dp else 56.dp),
                borderWidth = 3f,
                padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                onClick = vm::play,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow, null, Modifier.size(if (roomy) 30.dp else 26.dp),
                        tint = if (installed) Color.White else Color.White.copy(alpha = 0.45f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.play).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (installed) Color.White else Color.White.copy(alpha = 0.45f),
                        fontWeight = FontWeight.Black,
                        fontStyle = FontStyle.Italic,
                    )
                    if (!installed) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.msg_install_first),
                            style = MaterialTheme.typography.labelMedium,
                            color = Dbz.SaiyanGold,
                        )
                    }
                }
            }
        }
    }

    // Another device's record: a dialog, so the lobby keeps the space for the things that are always on it.
    if (codeDialog) {
        AlertDialog(
            onDismissRequest = { codeDialog = false },
            title = { Text(stringResource(R.string.profile_recovery)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.profile_recovery_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it.trim().lowercase() },
                        label = { Text(stringResource(R.string.profile_code_in)) },
                        singleLine = true,
                    )
                    if (codeNote != 0) {
                        Text(
                            stringResource(codeNote),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (codeNote < 0) MaterialTheme.colorScheme.error else Dbz.ShenronGreen,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    codeNote = if (vm.setRecoveryCode(codeInput)) {
                        codeInput = ""
                        R.string.profile_taken
                    } else {
                        R.string.profile_bad_code
                    }
                }) { Text(stringResource(R.string.profile_use_code)) }
            },
            dismissButton = { TextButton(onClick = { codeDialog = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun GridButton(
    modifier: Modifier,
    icon: ImageVector,
    label: Int,
    onClick: () -> Unit,
    skew: Float = 0.18f,
) {
    SlantedBox(Steel, modifier = modifier.height(46.dp), skew = skew, borderWidth = 2f, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = Dbz.SaiyanGold)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
