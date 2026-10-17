package com.bytarch.dbt3.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
import com.bytarch.dbt3.data.UpdateState
import com.bytarch.dbt3.data.formatBytes
import com.bytarch.dbt3.ui.components.HudPanel
import com.bytarch.dbt3.ui.components.HudShape
import com.bytarch.dbt3.ui.components.stageGlow
import com.bytarch.dbt3.ui.theme.Dbz

private val Panel = Color(0xE60D0F1A)
private val Steel = Color(0xFF1B2130)
private val Line = Color(0x59FFD54F)
private val Scarlet = Color(0xFFC8102E)
private val Violet = Color(0xFF8B3BD9)
private val Muted = Color(0xFFB9C0D0)

/** The lobby: the game's art across the middle, a HUD around it that never covers the stage, and three one-tap
 * things on top - play, the battle pass, the settings. Everything else hangs off the rail and the tabs.
 *
 * The screen is three bands - top, middle, bottom - and the middle takes whatever is left, so no two parts of the
 * HUD can end up on top of each other. It is laid out for the shortest case that has to work, a phone in landscape
 * (about 800x360dp), not for the size of the window it happens to be given. */
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
    val update by vm.update.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var codeDialog by remember { mutableStateOf(false) }
    var codeInput by remember { mutableStateOf("") }
    var codeNote by remember { mutableIntStateOf(0) }

    val installed = install is InstallState.Installed
    val dataFraction = when (val s = install) {
        is InstallState.Installed -> 1f
        is InstallState.Working -> s.progress.coerceIn(0f, 1f)
        else -> 0f
    }
    val dataLabel = when (val s = install) {
        is InstallState.Installed -> stringResource(R.string.hud_data_ready)
        is InstallState.Working -> stringResource(R.string.hud_data_working, (s.progress * 100).toInt())
        is InstallState.Failed -> stringResource(R.string.hud_data_failed)
        InstallState.NotInstalled -> stringResource(R.string.hud_data_missing)
    }
    val content = mods.size + packs.size + stages.size + songs.size
    val modsOn = mods.count { it.enabled }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // a phone in landscape is about 360dp tall; on anything shorter the cards tighten rather than overlap
        val tight = maxHeight < 380.dp

        Box(Modifier.fillMaxSize().stageGlow(Dbz.GiOrangeDeep))

        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {

            // ---------------------------------------------------------------- who you are, and the system
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                ProfileChip(
                    name = playerName,
                    installed = installed,
                    fraction = dataFraction,
                    label = stringResource(R.string.hud_gauge, (dataFraction * 100).toInt()),
                    modifier = Modifier.weight(1f),
                    onClick = { codeDialog = true },
                )
                Spacer(Modifier.width(8.dp))
                UpdateChip(update, onCheck = { vm.checkForUpdate(true) })
                Spacer(Modifier.width(8.dp))
                RailIcon(Icons.Rounded.SportsEsports, Dbz.KiBlue, R.string.nav_controls, onOpenControls)
                Spacer(Modifier.width(8.dp))
                RailIcon(Icons.Rounded.Settings, Dbz.SaiyanGold, R.string.nav_settings, onOpenSettings)
            }

            Spacer(Modifier.height(8.dp))

            // ---------------------------------------------------------------- the middle: rail, stage, cards
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(1f))
                    RailIcon(Icons.Rounded.Album, Dbz.SaiyanGold, R.string.nav_battlepass, onOpenBattlePass)
                    RailIcon(Icons.Rounded.LockOpen, Dbz.ShenronGreen, R.string.nav_cheats, onOpenCheats)
                    RailIcon(Icons.Rounded.Memory, Violet, R.string.nav_controls, onOpenControls)
                    Spacer(Modifier.weight(1f))
                }
                // the stage: the art shows through here, and nothing draws on top of it
                Box(Modifier.weight(1f).fillMaxSize())
                Column(
                    Modifier.width(if (tight) 186.dp else 212.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PassCard(content, modsOn, mods.size, onOpenBattlePass)
                    DataCard(install, dataLabel, onOpenSettings)
                    CountsCard(mods.size, packs.size, stages.size, songs.size)
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---------------------------------------------------------------- the tabs, and the one big control
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                HudTab(stringResource(R.string.hud_tab_content), true, onOpenBattlePass)
                Spacer(Modifier.width(4.dp))
                HudTab(stringResource(R.string.hud_tab_cheats), false, onOpenCheats)
                Spacer(Modifier.weight(1f))
                PlayButton(installed, dataLabel, vm::play)
                Spacer(Modifier.weight(1f))
                HudTab(stringResource(R.string.hud_tab_settings), false, onOpenSettings)
                Spacer(Modifier.width(4.dp))
                HudTab(stringResource(R.string.hud_tab_controls), false, onOpenControls)
            }
        }

        // Another device's record: a dialog, so the lobby keeps the stage clear.
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
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.profile_code_short, code), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                            Text(stringResource(R.string.profile_copy))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = { codeInput = it },
                            label = { Text(stringResource(R.string.profile_code_in)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (codeNote != 0) {
                            Text(
                                stringResource(if (codeNote > 0) R.string.profile_taken else R.string.profile_bad_code),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (codeNote > 0) Dbz.ShenronGreen else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        codeNote = if (vm.setRecoveryCode(codeInput)) {
                            codeInput = ""
                            1
                        } else {
                            -1
                        }
                    }) { Text(stringResource(R.string.profile_use_code)) }
                },
                dismissButton = {
                    TextButton(onClick = { codeDialog = false }) { Text(stringResource(R.string.close)) }
                },
            )
        }
    }
}

/** The player, cut on the slant the way the mock's panels are: avatar, name, and how ready the game data is. */
@Composable
private fun ProfileChip(
    name: String,
    installed: Boolean,
    fraction: Float,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    HudPanel(
        shape = HudShape(0f, 0.06f),
        modifier = modifier,
        color = Panel,
        border = Line,
        padding = PaddingValues(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Steel)
                    .border(1.5.dp, Dbz.SaiyanGold, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Person, stringResource(R.string.profile_name), Modifier.size(20.dp), tint = Dbz.SaiyanGold)
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium.copy(shadow = Shadow(Color.Black, Offset(1f, 1f))),
                        fontWeight = FontWeight.Black,
                        fontStyle = FontStyle.Italic,
                        color = Dbz.SaiyanGold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(3.dp))
                            .background(if (installed) Scarlet else Steel)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            stringResource(if (installed) R.string.hud_badge_iso else R.string.hud_badge_setup),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.width(96.dp).height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Steel),
                    ) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxSize().background(Dbz.SaiyanGold))
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (installed) Dbz.ShenronGreen else Muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The build in hand and whether there is a newer one. Tapping it looks again. */
@Composable
private fun UpdateChip(update: UpdateState, onCheck: () -> Unit) {
    val (label, tint) = when (val u = update) {
        is UpdateState.Available -> stringResource(R.string.hud_update_ready, u.info.version) to Dbz.StarRed
        is UpdateState.Downloading -> stringResource(R.string.hud_update_ready, u.info.version) to Dbz.KiBlue
        is UpdateState.NeedPermission -> stringResource(R.string.hud_update_ready, u.info.version) to Dbz.StarRed
        UpdateState.Checking -> stringResource(R.string.hud_checking) to Muted
        else -> stringResource(R.string.hud_up_to_date) to Dbz.ShenronGreen
    }
    HudPanel(
        shape = HudShape(0.08f, 0f),
        color = Panel,
        border = Line,
        padding = PaddingValues(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        onClick = onCheck,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.hud_build), style = MaterialTheme.typography.labelSmall, color = Muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(tint))
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
            }
        }
    }
}

/** The battle pass, the way a season banner looks: what the season holds and how much of it is on. */
@Composable
private fun PassCard(count: Int, on: Int, total: Int, onClick: () -> Unit) {
    HudPanel(
        shape = RoundedCornerShape(10.dp),
        color = Panel,
        border = Line,
        onClick = onClick,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.hud_library).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFCE93D8),
                    )
                    Text(
                        stringResource(R.string.battlepass_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        fontStyle = FontStyle.Italic,
                        color = Dbz.SaiyanGold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(R.string.hud_items, count),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF241600),
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Dbz.SaiyanGold)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Steel),
            ) {
                Box(
                    Modifier.fillMaxWidth(if (total == 0) 0f else on.toFloat() / total)
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(Color(0xFFBA68C8), Dbz.SaiyanGold))),
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                stringResource(R.string.hud_mods_on, on, total),
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                maxLines = 1,
            )
        }
    }
}

/** The game's own data: the one card that says whether the game can start at all. */
@Composable
private fun DataCard(install: InstallState, label: String, onClick: () -> Unit) {
    HudPanel(shape = RoundedCornerShape(10.dp), color = Panel, border = Line, onClick = onClick) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.game_data),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = Dbz.SaiyanGold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(label, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            when (install) {
                is InstallState.Working -> LinearProgressIndicator(
                    progress = { install.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Dbz.SaiyanGold,
                    trackColor = Steel,
                )
                is InstallState.Installed -> Text(
                    stringResource(R.string.hud_data_files, install.files, formatBytes(install.bytes)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Dbz.ShenronGreen,
                    maxLines = 1,
                )
                is InstallState.Failed -> Text(
                    install.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = Dbz.StarRed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                InstallState.NotInstalled -> Text(
                    stringResource(R.string.game_data_missing),
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** What is in the library, in one line each, so the lobby says something without being read. */
@Composable
private fun CountsCard(mods: Int, packs: Int, stages: Int, songs: Int) {
    HudPanel(shape = RoundedCornerShape(10.dp), color = Panel, border = Color(0x33FFD54F)) {
        Column {
            Text(
                "${mods} ${stringResource(R.string.stat_mods)}   ·   ${packs} ${stringResource(R.string.stat_textures)}",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${stages} ${stringResource(R.string.stat_stages)}   ·   ${songs} ${stringResource(R.string.stat_songs)}",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                maxLines = 1,
            )
        }
    }
}

/** The one control that starts the game. Its shape is the mock's: a point on either side. */
@Composable
private fun PlayButton(installed: Boolean, dataLabel: String, onClick: () -> Unit) {
    HudPanel(
        shape = HudShape(0.14f, 0.14f),
        modifier = Modifier.width(216.dp),
        brush = Brush.horizontalGradient(listOf(Scarlet, Dbz.GiOrange, Dbz.SaiyanGold)),
        border = Dbz.BallLight,
        borderWidth = 2f,
        padding = PaddingValues(horizontal = 18.dp, vertical = 7.dp),
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.PlayArrow,
                stringResource(R.string.play),
                Modifier.size(22.dp),
                tint = if (installed) Color.White else Color.White.copy(alpha = 0.45f),
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                        stringResource(R.string.play),
                    style = MaterialTheme.typography.titleMedium.copy(shadow = Shadow(Color.Black, Offset(1f, 1f))),
                    fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic,
                    color = if (installed) Color.White else Color.White.copy(alpha = 0.45f),
                )
                Text(
                    dataLabel.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
        }
    }
}

/** One square on the rail. 44dp, so a thumb finds it without looking. */
@Composable
private fun RailIcon(icon: ImageVector, tint: Color, label: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, stringResource(label), Modifier.size(20.dp), tint = tint)
    }
}

/** A word along the bottom, with the rule under the page you are on. */
@Composable
private fun HudTab(label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = if (active) Dbz.SaiyanGold else Muted,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        Box(Modifier.width(30.dp).height(2.dp).background(if (active) Dbz.SaiyanGold else Color.Transparent))
    }
}