package com.bytarch.dbt3.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bytarch.dbt3.AppViewModel
import com.bytarch.dbt3.OnlinePhase
import com.bytarch.dbt3.R
import com.bytarch.dbt3.ui.components.HudHeader
import com.bytarch.dbt3.ui.components.SectionCard
import com.bytarch.dbt3.ui.components.stageBackdrop
import com.bytarch.dbt3.ui.theme.Dbz

/** The launcher's online screen: find an opponent in the queue, or make a room and give out its code. The looking
 * happens here; the game opens only when the other player is found and the two machines have a path to each other
 * (MatchLobby, AppViewModel). */
@Composable
fun OnlineScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state by vm.online.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val idle = state.phase == OnlinePhase.IDLE
    var code by remember { mutableStateOf("") }

    Column(
        Modifier.stageBackdrop(R.drawable.battlepass_bg, 0.9f)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HudHeader(stringResource(R.string.play_online), stringResource(R.string.online_subtitle), onBack)

        // what the service can see right now, before anything is pressed: whether anybody is about
        Text(
            stringResource(R.string.online_population_full, stats.online, stats.queued, stats.playing),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard(stringResource(R.string.online_find), icon = Icons.Rounded.Bolt) {
            Text(
                stringResource(R.string.online_find_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = { vm.onlineFind() }, enabled = idle) {
                Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.online_find_button))
            }
        }

        SectionCard(stringResource(R.string.online_create), icon = Icons.Rounded.Add) {
            Text(
                stringResource(R.string.online_create_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = { vm.onlineHost() }, enabled = idle) {
                Icon(Icons.Rounded.Public, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.online_create_button))
            }
        }

        SectionCard(stringResource(R.string.online_join), icon = Icons.Rounded.SportsEsports) {
            Text(
                stringResource(R.string.online_join_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { typed -> code = typed.uppercase().filter { it.isLetterOrDigit() }.take(6) },
                    label = { Text(stringResource(R.string.online_code)) },
                    singleLine = true,
                    enabled = idle,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { vm.onlineJoin(code) }, enabled = idle && code.isNotEmpty()) {
                    Text(stringResource(R.string.online_join_button))
                }
            }
        }

        if (!idle) {
            SectionCard(
                when (state.phase) {
                    OnlinePhase.WORKING -> stringResource(R.string.online_working)
                    OnlinePhase.SEARCHING -> stringResource(R.string.online_searching, state.waiting, state.queued)
                    OnlinePhase.HOSTING -> stringResource(R.string.online_waiting)
                    OnlinePhase.CONNECTING -> stringResource(R.string.online_connecting)
                    else -> stringResource(R.string.online_failed)
                },
                icon = Icons.Rounded.Public,
                accent = if (state.phase == OnlinePhase.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            ) {
                if (state.phase == OnlinePhase.HOSTING && state.code.isNotEmpty()) {
                    Text(
                        state.code,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = Dbz.SaiyanGold,
                    )
                    Text(
                        stringResource(R.string.online_code_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.peer.isNotEmpty()) Text(stringResource(R.string.online_peer, state.peer))
                // the other player, as the service knows them: their record is public, and this is their public id
                val peerRecord = state.peerRecord
                if (peerRecord != null) {
                    Text(
                        stringResource(R.string.online_peer_record, peerRecord.first, peerRecord.second),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = Dbz.ShenronGreen,
                    )
                }
                if (state.peerId.isNotEmpty()) {
                    Text(
                        stringResource(R.string.online_peer_id, state.peerId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.error.isNotEmpty()) Text(state.error)
                if (state.phase != OnlinePhase.CONNECTING) {
                    TextButton(onClick = { vm.onlineCancel() }) { Text(stringResource(R.string.online_cancel)) }
                }
            }
        }
    }
}
