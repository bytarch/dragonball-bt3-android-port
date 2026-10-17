package com.bytarch.dbt3.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bytarch.dbt3.AppViewModel
import com.bytarch.dbt3.R
import com.bytarch.dbt3.data.BattleRecord
import com.bytarch.dbt3.ui.components.EmptyState
import com.bytarch.dbt3.ui.components.HudHeader
import com.bytarch.dbt3.ui.components.StatPill
import com.bytarch.dbt3.ui.components.stageBackdrop
import com.bytarch.dbt3.ui.theme.Dbz
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What this device's player has done online: the count at the top, every battle below it. The lines come straight
 *  from the engine's bt3_battles.txt (the place the match report is written), newest first. */
@Composable
fun RecordScreen(vm: AppViewModel, onBack: () -> Unit) {
    val record by vm.record.collectAsStateWithLifecycle()
    val stamp = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }
    val won = record.count { it.won == true }
    val lost = record.count { it.won == false }
    val drawn = record.size - won - lost

    LazyColumn(
        modifier = Modifier.fillMaxSize().stageBackdrop(R.drawable.boot_backdrop, 0.88f),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            HudHeader(stringResource(R.string.record_title), stringResource(R.string.record_sub), onBack)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatPill(stringResource(R.string.record_played), record.size.toString(), Dbz.KiBlue, Modifier.weight(1f))
                StatPill(stringResource(R.string.record_won), won.toString(), Dbz.ShenronGreen, Modifier.weight(1f))
                StatPill(stringResource(R.string.record_lost), lost.toString(), Dbz.StarRed, Modifier.weight(1f))
                StatPill(stringResource(R.string.record_draws), drawn.toString(), Dbz.SaiyanGold, Modifier.weight(1f))
            }
        }
        if (record.isEmpty()) {
            item {
                EmptyState(
                    Icons.Rounded.EmojiEvents,
                    stringResource(R.string.record_empty),
                    stringResource(R.string.record_empty_text),
                )
            }
        } else {
            items(record, key = { "${it.at}|${it.mode}|${it.winner}|${it.me}" }) { r ->
                RecordRow(r) { stamp.format(Date(r.at)) }
            }
        }
    }
}

/** One battle: how it went on the left, when and why under it. A draw is not a win or a loss, and a line written
 *  before the record said which side this copy played says neither. */
@Composable
private fun RecordRow(r: BattleRecord, whenText: () -> String) {
    val (label, tint) = when {
        r.drew -> stringResource(R.string.record_result_draw) to Dbz.SaiyanGold
        r.won == true -> stringResource(R.string.record_result_win) to Dbz.ShenronGreen
        r.won == false -> stringResource(R.string.record_result_loss) to Dbz.StarRed
        else -> stringResource(R.string.record_result_unknown) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val why = when {
        r.ko -> stringResource(R.string.record_ko)
        r.timeUp -> stringResource(R.string.record_time_up)
        else -> stringResource(R.string.record_reason_other, r.reason)
    }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = tint,
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(whenText(), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                Text(
                    why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}