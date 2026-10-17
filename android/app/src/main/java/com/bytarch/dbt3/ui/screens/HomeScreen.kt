package com.bytarch.dbt3.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.bytarch.dbt3.ui.components.stageBackdrop
import com.bytarch.dbt3.ui.theme.Dbz

private val Panel = Color(0xF00B0D18)
private val Steel = Color(0xFF161B29)
private val Line = Color(0x59FFD54F)
private val Scarlet = Color(0xFFC8102E)
private val Violet = Color(0xFF8B3BD9)
private val Muted = Color(0xFFB9C0D0)

/** The lobby: the game runs in the middle of the screen with a HUD around it, the way a fighting game does on a
 * phone. Everything here is one tap from the three things that matter - play the game, the battle pass, the settings -
 * and the rest of the app hangs off the rail on the left and the tabs along the bottom. Nothing scrolls: the phone is
 * held in landscape, so the stage has to stay clear. */
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

    Box(Modifier.fillMaxSize()) {
        // the stage: our own art, dimmed so the HUD on top of it still reads
        Box(Modifier.fillMaxSize().stageBackdrop(R.drawable.home_backdrop, 0.58f))

        // ---------------- top HUD: who you are, and the two system controls
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HudPanel(
                shape = HudShape(0f, 0.10f),
                modifier = Modifier.weight(1f),
                border = Line,
                padding = PaddingValues(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                onClick = { codeDialog = true },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Steel)
                            .border(2.dp, Dbz.SaiyanGold, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Person, stringResource(R.string.profile_name), Modifier.size(22.dp), tint = Dbz.SaiyanGold)
                    }
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                playerName,
                                style = MaterialTheme.typography.titleLarge.copy(shadow = Shadow(Color.Black, Offset(1.5f, 1.5f))),
                                fontWeight = FontWeight.Black,
                                fontStyle = FontStyle.Italic,
                                color = Dbz.SaiyanGold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier.clip(HudShape(0f, 0.18f))
                                    .background(if (installed) Scarlet else Steel)
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    stringResource(if (installed) R.string.hud_badge_iso else R.string.hud_badge_setup),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.width(104.dp).height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Steel)
                                    .border(1.dp, Line, RoundedCornerShape(3.dp)),
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(dataFraction).fillMaxHeight()
                                        .background(Brush.horizontalGradient(listOf(Dbz.SaiyanGold, Scarlet))),
                                )
                            }
                            Spacer(Modifier.width(7.dp))
                            Text(
                                stringResource(R.string.hud_gauge, (dataFraction * 100).toInt()),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (installed) Dbz.ShenronGreen else Muted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            BuildWidget(update) { vm.checkForUpdate(true) }
            Spacer(Modifier.width(6.dp))
            RailIcon(Icons.Rounded.SportsEsports, Dbz.KiBlue, R.string.nav_controls, onOpenControls)
            Spacer(Modifier.width(6.dp))
            RailIcon(Icons.Rounded.Settings, Dbz.SaiyanGold, R.string.nav_settings, onOpenSettings)
        }

        // ---------------- left rail: the way into everything else
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RailIcon(Icons.Rounded.Album, Dbz.SaiyanGold, R.string.nav_battlepass, onOpenBattlePass)
            RailIcon(Icons.Rounded.LockOpen, Dbz.ShenronGreen, R.string.nav_cheats, onOpenCheats)
            RailIcon(Icons.Rounded.Memory, Violet, R.string.nav_controls, onOpenControls)
        }

        // ---------------- right column: what is installed, and how much of it is on
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 66.dp, end = 10.dp).width(206.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HudPanel(
                shape = HudShape(0.10f, 0f),
                border = Color(0x8CFFD54F),
                brush = Brush.horizontalGradient(listOf(Color(0xE63B0F63), Color(0xE60B0D18), Color(0xE64A2A05))),
                onClick = onOpenBattlePass,
            ) {
                Column {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.hud_library),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
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
                        Box(
                            Modifier.clip(HudShape(0f, 0.18f))
                                .background(Dbz.SaiyanGold)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                stringResource(R.string.hud_items, content),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF241600),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth().height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0x66231F35))
                            .border(1.dp, Color(0x4DBA68C8), RoundedCornerShape(3.dp)),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(if (mods.isEmpty()) 0f else modsOn.toFloat() / mods.size)
                                .fillMaxHeight()
                                .background(Brush.horizontalGradient(listOf(Color(0xFFBA68C8), Dbz.SaiyanGold))),
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(R.string.hud_mods_on, modsOn, mods.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        maxLines = 1,
                    )
                }
            }
            HudPanel(shape = HudShape(0.10f, 0f), border = Color(0x8CFFD54F), onClick = onOpenSettings) {
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
                        Icon(Icons.Rounded.SystemUpdateAlt, null, Modifier.size(14.dp), tint = Muted)
                    }
                    Spacer(Modifier.height(3.dp))
                    when (val s = install) {
                        is InstallState.Working -> {
                            LinearProgressIndicator(
                                progress = { s.progress },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = Dbz.SaiyanGold,
                                trackColor = Steel,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(s.detail, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1)
                        }
                        is InstallState.Installed -> Text(
                            stringResource(R.string.hud_data_files, s.files, formatBytes(s.bytes)),
                            style = MaterialTheme.typography.labelSmall,
                            color = Dbz.ShenronGreen,
                            maxLines = 1,
                        )
                        is InstallState.Failed -> Text(
                            stringResource(R.string.hud_data_failed),
                            style = MaterialTheme.typography.labelSmall,
                            color = Dbz.StarRed,
                            maxLines = 1,
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
            HudPanel(shape = HudShape(0.10f, 0f), border = Color(0x40FFD54F)) {
                Column {
                    Text(
                        stringResource(R.string.hud_library),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Dbz.SaiyanGold,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${mods.size} ${stringResource(R.string.stat_mods)} · ${packs.size} ${stringResource(R.string.stat_textures)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        maxLines = 1,
                    )
                    Text(
                        "${stages.size} ${stringResource(R.string.stat_stages)} · ${songs.size} ${stringResource(R.string.stat_songs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        maxLines = 1,
                    )
                }
            }
        }

        // ---------------- bottom HUD: the tabs, and the one control that starts the game
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.92f)),
                    ),
                )
                .padding(bottom = 6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HudTab(stringResource(R.string.hud_tab_content), true, onOpenBattlePass)
                Spacer(Modifier.width(20.dp))
                HudTab(stringResource(R.string.hud_tab_cheats), false, onOpenCheats)
                Spacer(Modifier.weight(1f))
                HudTab(stringResource(R.string.hud_tab_settings), false, onOpenSettings)
                Spacer(Modifier.width(20.dp))
                HudTab(stringResource(R.string.hud_tab_controls), false, onOpenControls)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HudPanel(
                        shape = HudShape(0f, 0.16f),
                        color = Color(0xF20B0D18),
                        border = Color(0x99FFD54F),
                        padding = PaddingValues(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                        onClick = onOpenSettings,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.clip(HudShape(0f, 0.18f))
                                    .background(if (installed) Scarlet else Steel)
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    stringResource(R.string.hud_data),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                dataLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = if (installed) Dbz.SaiyanGold else Muted,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(5.dp))
                            Icon(Icons.Rounded.SystemUpdateAlt, null, Modifier.size(12.dp), tint = Line)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    HudPanel(
                        shape = HudShape(0.16f, 0.16f),
                        modifier = Modifier.width(232.dp),
                        brush = Brush.horizontalGradient(listOf(Scarlet, Dbz.GiOrange, Dbz.SaiyanGold)),
                        border = Dbz.BallLight,
                        borderWidth = 3f,
                        padding = PaddingValues(vertical = 9.dp),
                        onClick = vm::play,
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                stringResource(R.string.play),
                                Modifier.size(22.dp),
                                tint = if (installed) Color.White else Color.White.copy(alpha = 0.45f),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.hud_battle),
                                style = MaterialTheme.typography.headlineSmall.copy(shadow = Shadow(Color.Black, Offset(1.5f, 1.5f))),
                                fontWeight = FontWeight.Black,
                                fontStyle = FontStyle.Italic,
                                color = if (installed) Color.White else Color.White.copy(alpha = 0.45f),
                            )
                        }
                    }
                }
            }
        }

        // Another device's record: a dialog, so the HUD keeps the stage clear.
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

/** The build the app is on, and whether there is a newer one. Tapping it checks again. */
@Composable
private fun BuildWidget(update: UpdateState, onCheck: () -> Unit) {
    val (label, tint) = when (val u = update) {
        is UpdateState.Available -> stringResource(R.string.hud_update_ready, u.info.version) to Dbz.StarRed
        is UpdateState.Downloading -> stringResource(R.string.hud_update_ready, u.info.version) to Dbz.KiBlue
        is UpdateState.NeedPermission -> stringResource(R.string.hud_update_ready, u.info.version) to Dbz.StarRed
        UpdateState.Checking -> stringResource(R.string.hud_checking) to Muted
        else -> stringResource(R.string.hud_up_to_date) to Dbz.ShenronGreen
    }
    HudPanel(
        shape = HudShape(0.10f, 0f),
        border = Line,
        padding = PaddingValues(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        onClick = onCheck,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Text(
                stringResource(R.string.hud_build),
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(tint))
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
            }
        }
    }
}

/** One square on the rail. 44dp so a thumb finds it without looking. */
@Composable
private fun RailIcon(icon: ImageVector, tint: Color, label: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .border(1.5.dp, tint.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, stringResource(label), Modifier.size(20.dp), tint = tint)
    }
}

/** A word along the bottom, with the rule under the one that is the page you are on. */
@Composable
private fun HudTab(label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Black,
            color = if (active) Dbz.SaiyanGold else Muted,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier.width(34.dp).height(2.dp)
                .background(if (active) Dbz.SaiyanGold else Color.Transparent),
        )
    }
}