package com.bytarch.dbt3.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalUriHandler
import com.bytarch.dbt3.data.REPO_URL
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bytarch.dbt3.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bytarch.dbt3.AppViewModel
import com.bytarch.dbt3.BuildConfig
import com.bytarch.dbt3.data.EngineSettings
import com.bytarch.dbt3.data.Renderer
import com.bytarch.dbt3.ui.components.Page
import com.bytarch.dbt3.ui.components.ScreenTitle
import com.bytarch.dbt3.ui.components.SectionCard
import com.bytarch.dbt3.ui.theme.Dbz
import com.bytarch.dbt3.ui.theme.ThemeMode
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.bytarch.dbt3.data.InstallState
import com.bytarch.dbt3.ui.components.GameDataCard
import com.bytarch.dbt3.ui.components.ScreenHeader

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val install by vm.install.collectAsStateWithLifecycle()
    val pickIso = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.installFromIso(uri)
    }
    var confirmIso by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val s by vm.engineSettings.collectAsStateWithLifecycle()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    fun get(k: String, d: Int) = s[k] ?: d

    // Landscape is a short screen and these are seven cards: video, effects and audio on the left, performance,
    // the driver and log, and appearance with About on the right. One column below the width for it.
    Page(
        title = { ScreenHeader(stringResource(R.string.settings_title), onBack, stringResource(R.string.settings_sub)) },
        left = {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard(
            stringResource(R.string.game_data),
            Icons.Rounded.Album,
            accent = if (install is InstallState.Installed) Dbz.ShenronGreen else MaterialTheme.colorScheme.error,
        ) {
            GameDataCard(
                install = install,
                onPick = { pickIso.launch(arrayOf("*/*")) },
                onDownload = { confirmIso = true },
                onRemove = { confirmRemove = true },
                onCancel = vm::cancelInstall,
                onDismissError = vm::dismissInstallError,
            )
        }
        SectionCard(stringResource(R.string.video), Icons.Rounded.Tv) {
            val steps = EngineSettings.SCALE_STEPS
            val q = get(EngineSettings.SCALE4, 0).takeIf { it > 0 } ?: (get(EngineSettings.SCALE, EngineSettings.SCALE_DEFAULT) * 4)
            val at = steps.indices.minByOrNull { kotlin.math.abs(steps[it] - q) } ?: 4
            StepSlider(
                label = stringResource(R.string.resolution),
                format = { i -> steps[i].let { "${if (it % 4 == 0) (it / 4).toString() else (it / 4.0).toString()}x · ${128 * it}×${112 * it}" } },
                value = at, range = 0..steps.lastIndex,
                onChange = { i ->
                    vm.setting(EngineSettings.SCALE4, steps[i])
                    vm.setting(EngineSettings.SCALE, (steps[i] + 2) / 4)
                },
            )
            Text(
                stringResource(R.string.resolution_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.screen_filter), style = MaterialTheme.typography.titleSmall)
            ChipRow(
                options = listOf(
                    stringResource(R.string.filter_bilinear) to 0, stringResource(R.string.filter_sharp) to 1, "FXAA" to 2,
                    "AMD FSR 1" to 3, "Snapdragon GSR" to 4,
                ),
                selected = get(EngineSettings.FILTER, 0),
                onSelect = { vm.setting(EngineSettings.FILTER, it) },
            )
            Text(
                stringResource(R.string.screen_filter_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.aspect), style = MaterialTheme.typography.titleSmall)
            ChipRow(
                options = listOf(
                    stringResource(R.string.aspect_screen) to 0, "4:3" to 1333, "16:10" to 1600, "16:9" to 1778, "20:9" to 2222,
                    "21:9" to 2389, "32:9" to 3556,
                ),
                selected = get(EngineSettings.ASPECT, EngineSettings.ASPECT_DEFAULT),
                onSelect = { vm.setting(EngineSettings.ASPECT, it) },
            )
            Text(
                stringResource(R.string.aspect_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(stringResource(R.string.show_fps), stringResource(R.string.show_fps_text), get(EngineSettings.METER, 0) != 0) {
                vm.setting(EngineSettings.METER, if (it) 1 else 0)
            }
        }

        SectionCard(stringResource(R.string.ps2_effects), Icons.Rounded.AutoAwesome, accent = Dbz.SaiyanGold) {
            val fxOff = get(EngineSettings.FX_OFF, 0)
            listOf(
                Triple(1, R.string.fx_outline, R.string.fx_outline_text),
                Triple(2, R.string.fx_see, R.string.fx_see_text),
                Triple(4, R.string.fx_depth, R.string.fx_depth_text),
                Triple(8, R.string.fx_glow, R.string.fx_glow_text),
                Triple(16, R.string.fx_blur, R.string.fx_blur_text),
            ).forEach { (bit, title, sub) ->
                ToggleRow(stringResource(title), stringResource(sub), fxOff and bit == 0) { on ->
                    vm.setting(EngineSettings.FX_OFF, if (on) fxOff and bit.inv() else fxOff or bit)
                }
            }
            val glow = get(EngineSettings.GLOW, EngineSettings.GLOW_DEFAULT)
            StepSlider(stringResource(R.string.glow_strength), { "${it * 10}%" }, glow / 10, 0..20) { vm.setting(EngineSettings.GLOW, it * 10) }
        }

        SectionCard(stringResource(R.string.audio), Icons.AutoMirrored.Rounded.VolumeUp, accent = Dbz.ShenronGreen) {
            val music = get(EngineSettings.MUSIC, 100)
            StepSlider(stringResource(R.string.music), { "${it * 10}%" }, music / 10, 0..20) { vm.setting(EngineSettings.MUSIC, it * 10) }
            val fx = get(EngineSettings.EFFECTS, 100)
            StepSlider(stringResource(R.string.sfx), { "${it * 10}%" }, fx / 10, 0..20) { vm.setting(EngineSettings.EFFECTS, it * 10) }
        }

    }
        },
        right = {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard(stringResource(R.string.performance), Icons.Rounded.Speed, accent = MaterialTheme.colorScheme.secondary) {
            val gpu by vm.gpuName.collectAsStateWithLifecycle()
            val choice = get(EngineSettings.RENDERER, Renderer.AUTO).coerceIn(0, 2)
            Text(stringResource(R.string.renderer), style = MaterialTheme.typography.titleSmall)
            ChipRow(
                options = listOf(
                    stringResource(R.string.renderer_auto) to Renderer.AUTO, "Vulkan" to Renderer.VULKAN, "OpenGL ES" to Renderer.OPENGL,
                ),
                selected = choice,
                onSelect = { vm.setRenderer(it) },
            )
            val picks = if (Renderer.gpuApi(choice, gpu) == 1) "OpenGL ES" else "Vulkan"
            Text(
                stringResource(R.string.renderer_now, gpu.ifEmpty { "?" }, picks),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.renderer_text),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.fps60), style = MaterialTheme.typography.titleSmall)
            ChipRow(
                options = listOf(stringResource(R.string.off) to 0, "60" to 1, "120" to 2),
                selected = get(EngineSettings.INTERP, 0).coerceIn(0, 2),
                onSelect = { vm.setting(EngineSettings.INTERP, it) },
            )
            Text(
                stringResource(R.string.fps60_text),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.present_mode), style = MaterialTheme.typography.titleSmall)
            ChipRow(
                options = listOf(
                    stringResource(R.string.present_auto) to 0, "FIFO (VSync)" to 1, "Mailbox" to 2, "Immediate" to 3,
                ),
                selected = get(EngineSettings.PRESENT, 0).coerceIn(0, 3),
                onSelect = { vm.setting(EngineSettings.PRESENT, it) },
            )
            Text(
                stringResource(R.string.present_mode_text),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        GpuDriverCard(vm)
        LogCard(vm)

        SectionCard(stringResource(R.string.appearance), Icons.Rounded.Palette, accent = MaterialTheme.colorScheme.tertiary) {
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
            ChipRow(
                options = listOf(stringResource(R.string.theme_dark) to ThemeMode.DARK.ordinal, stringResource(R.string.theme_light) to ThemeMode.LIGHT.ordinal, stringResource(R.string.theme_system) to ThemeMode.SYSTEM.ordinal),
                selected = ui.theme.ordinal,
                onSelect = { vm.setTheme(ThemeMode.entries[it]) },
            )
            if (vm.canPickLanguage) {
                Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
                var lang by remember { mutableStateOf(vm.language()) }
                val langs = listOf("", "en", "pt")
                ChipRow(
                    options = listOf(stringResource(R.string.lang_system) to 0, "English" to 1, "Português" to 2),
                    selected = langs.indexOfFirst { it.isNotEmpty() && lang.startsWith(it) }.coerceAtLeast(0),
                    onSelect = { lang = langs[it]; vm.setLanguage(langs[it]) },
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ToggleRow(stringResource(R.string.material_you), stringResource(R.string.material_you_text), ui.dynamicColor) {
                    vm.setDynamicColor(it)
                }
            }
        }

        SectionCard(stringResource(R.string.about), Icons.Rounded.Info, accent = MaterialTheme.colorScheme.outline) {
            Text("DBT3 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.about_author), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.about_text),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val uri = LocalUriHandler.current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { runCatching { uri.openUri(REPO_URL) } }) {
                    Icon(Icons.Rounded.Code, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.github_repo))
                }
                OutlinedButton(onClick = { vm.checkForUpdate(manual = true) }) {
                    Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.check_updates))
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.game_folder), style = MaterialTheme.typography.titleSmall)
            Text(vm.paths.root.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
        },
    )

    if (confirmIso) {
        AlertDialog(
            onDismissRequest = { confirmIso = false },
            title = { Text(stringResource(R.string.iso_dl)) },
            text = { Text(stringResource(R.string.iso_dl_confirm)) },
            confirmButton = { TextButton(onClick = { confirmIso = false; vm.downloadIso() }) { Text(stringResource(R.string.iso_dl_start)) } },
            dismissButton = { TextButton(onClick = { confirmIso = false }) { Text(stringResource(R.string.cancel)) } },
        )
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
}

@Composable
internal fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A slider over whole steps that writes only when the finger lifts. */
@Composable
internal fun StepSlider(label: String, format: (Int) -> String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var drag by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                format(drag.roundToInt()),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = drag,
            onValueChange = { drag = it },
            onValueChangeFinished = { onChange(drag.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

@Composable
internal fun ChipRow(options: List<Pair<String, Int>>, selected: Int, onSelect: (Int) -> Unit) {
    // the nearest option counts as selected (an aspect written by the engine may be in between)
    val best = options.minByOrNull { kotlin.math.abs(it.second - selected) }?.second
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        options.forEach { (label, v) ->
            FilterChip(selected = v == best, onClick = { onSelect(v) }, label = { Text(label) })
        }
    }
}
