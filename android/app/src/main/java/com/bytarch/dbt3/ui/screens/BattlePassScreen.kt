package com.bytarch.dbt3.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Texture
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bytarch.dbt3.AppViewModel
import com.bytarch.dbt3.R
import com.bytarch.dbt3.data.EngineSettings
import com.bytarch.dbt3.data.ExtraItem
import com.bytarch.dbt3.data.ExtraKind
import com.bytarch.dbt3.data.FileMod
import com.bytarch.dbt3.data.TexturePack
import com.bytarch.dbt3.data.formatBytes
import com.bytarch.dbt3.ui.components.BattlePassPreview
import com.bytarch.dbt3.ui.components.EmptyState
import com.bytarch.dbt3.ui.components.HudHeader
import com.bytarch.dbt3.ui.components.SectionCard
import com.bytarch.dbt3.ui.theme.Dbz

/**
 * Everything that changes how the game looks and plays, in one place: texture packs, file mods, stages and songs.
 *
 * They are one thing to a player (what the game looks like) and were two screens; the four tabs are the four kinds.
 */
@Composable
fun BattlePassScreen(vm: AppViewModel, onBack: () -> Unit) {
    val packs by vm.texturePacks.collectAsStateWithLifecycle()
    val settings by vm.engineSettings.collectAsStateWithLifecycle()
    val mods by vm.fileMods.collectAsStateWithLifecycle()
    val stages by vm.stages.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importMod(uris)
    }
    val pickPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importTexturePack(uri)
    }
    var deleteMod by remember { mutableStateOf<FileMod?>(null) }
    var deleteExtra by remember { mutableStateOf<Pair<ExtraKind, ExtraItem>?>(null) }
    var deletePack by remember { mutableStateOf<TexturePack?>(null) }
    var rename by remember { mutableStateOf<Pair<ExtraKind, ExtraItem>?>(null) }
    var downloading by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.battlepass_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        // the wash that keeps the panels and the text readable over it
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xE60A0B12), Color(0xB30A0B12))),
            ),
        )
        Column(Modifier.fillMaxSize()) {
            HudHeader(
                stringResource(R.string.battlepass_title),
                stringResource(R.string.battlepass_sub),
                onBack,
            )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HudChip(stringResource(R.string.tab_packs), packs.size, tab == 0) { tab = 0 }
            HudChip(stringResource(R.string.tab_files), mods.size, tab == 1) { tab = 1 }
            HudChip(stringResource(R.string.tab_stages), stages.size, tab == 2) { tab = 2 }
            HudChip(stringResource(R.string.tab_songs), songs.size, tab == 3) { tab = 3 }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { BattlePassPreview() }
            when (tab) {
                0 -> {
                    item {
                        SectionCard(stringResource(R.string.textures_use), Icons.Rounded.Texture) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.textures_use_text),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = (settings[EngineSettings.TEXTURE_PACK] ?: 1) != 0,
                                    onCheckedChange = { vm.setting(EngineSettings.TEXTURE_PACK, if (it) 1 else 0) },
                                )
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { pickPack.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.large,
                            ) {
                                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.add_pack))
                            }
                            OutlinedButton(
                                onClick = { downloading = true },
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.large,
                            ) {
                                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.tex_dl_button))
                            }
                        }
                    }
                    if (packs.isEmpty()) {
                        item {
                            EmptyState(
                                Icons.Rounded.Texture,
                                stringResource(R.string.textures_empty),
                                stringResource(R.string.textures_empty_text),
                            )
                        }
                    }
                    items(packs.size) { i ->
                        val p = packs[i]
                        PackCard(p, onToggle = { vm.setPackEnabled(p, it) }, onDelete = { deletePack = p })
                    }
                }
                1 -> {
                    if (mods.isEmpty()) {
                        item {
                            EmptyState(
                                Icons.Rounded.Add, stringResource(R.string.mods_empty),
                                stringResource(R.string.mods_empty_text),
                            )
                        }
                    }
                    itemsIndexed(mods, key = { _, m -> m.id }) { i, m ->
                        ModCard(
                            m, first = i == 0, last = i == mods.lastIndex,
                            onToggle = { vm.setModEnabled(m, it) },
                            onUp = { vm.moveMod(m, -1) }, onDown = { vm.moveMod(m, 1) },
                            onDelete = { deleteMod = m },
                        )
                    }
                }
                else -> {
                    val kind = if (tab == 2) ExtraKind.STAGE else ExtraKind.SONG
                    val list = if (tab == 2) stages else songs
                    if (list.isEmpty()) {
                        item {
                            if (kind == ExtraKind.STAGE) {
                                EmptyState(
                                    Icons.Rounded.Landscape, stringResource(R.string.stages_empty),
                                    stringResource(R.string.stages_empty_text),
                                )
                            } else {
                                EmptyState(
                                    Icons.Rounded.MusicNote, stringResource(R.string.songs_empty),
                                    stringResource(R.string.songs_empty_text),
                                )
                            }
                        }
                    }
                    itemsIndexed(list, key = { _, it -> it.file.path }) { _, item ->
                        ExtraCard(
                            item, kind,
                            onRename = { rename = kind to item },
                            onDelete = { deleteExtra = kind to item },
                        )
                    }
                }
            }
        }
        }
    }

    if (downloading) TextureDownloadDialog(vm) { downloading = false }

    deleteMod?.let { m ->
        ConfirmDelete(stringResource(R.string.delete_mod_q, m.name), onConfirm = { vm.deleteMod(m) }, onDismiss = { deleteMod = null })
    }
    deleteExtra?.let { (kind, item) ->
        ConfirmDelete(
            stringResource(R.string.delete_q, item.displayName),
            onConfirm = { vm.deleteExtra(kind, item) },
            onDismiss = { deleteExtra = null },
        )
    }
    deletePack?.let { p ->
        ConfirmDelete(
            stringResource(R.string.delete_q, p.name),
            onConfirm = { vm.deletePack(p) },
            onDismiss = { deletePack = null },
        )
    }
    rename?.let { (kind, item) ->
        var text by remember(item) { mutableStateOf(item.displayName) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text(stringResource(R.string.name_in_game)) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(40) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.name_in_game_hint)) },
                )
            },
            confirmButton = { TextButton(onClick = { rename = null; vm.renameExtra(kind, item, text) }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ConfirmDelete(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PackCard(p: TexturePack, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (p.enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.pack_info, p.textures, formatBytes(p.bytes)) +
                        if (p.enabled) "" else " · " + stringResource(R.string.pack_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = p.enabled, onCheckedChange = onToggle)
            Spacer(Modifier.size(4.dp))
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun ModCard(
    m: FileMod, first: Boolean, last: Boolean,
    onToggle: (Boolean) -> Unit, onUp: () -> Unit, onDown: () -> Unit, onDelete: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (m.enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.mod_info, m.files, formatBytes(m.bytes)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = m.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete)) }
            Column {
                IconButton(onClick = onUp, enabled = !first) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.move_up)) }
                IconButton(onClick = onDown, enabled = !last) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.move_down)) }
            }
        }
    }
}

@Composable
private fun ExtraCard(item: ExtraItem, kind: ExtraKind, onRename: () -> Unit, onDelete: () -> Unit) {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (kind == ExtraKind.STAGE) Icons.Rounded.Landscape else Icons.Rounded.MusicNote, null,
                tint = if (kind == ExtraKind.STAGE) Dbz.KiBlue else Dbz.ShenronGreen,
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.file.name} · ${formatBytes(item.bytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRename) { Icon(Icons.Rounded.Edit, stringResource(R.string.rename)) }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete)) }
        }
    }
}
