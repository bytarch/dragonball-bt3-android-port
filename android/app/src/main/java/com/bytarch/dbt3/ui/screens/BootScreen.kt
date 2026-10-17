package com.bytarch.dbt3.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytarch.dbt3.BootState
import com.bytarch.dbt3.BuildConfig
import com.bytarch.dbt3.R
import com.bytarch.dbt3.StepState
import com.bytarch.dbt3.ui.components.stageGlow
import com.bytarch.dbt3.ui.theme.Dbz
import com.bytarch.dbt3.ui.theme.TitleStyle

/**
 * What a player sees between tapping the icon and the lobby: the artwork, the build, the bar along the bottom, and
 * each check as it happens.
 *
 * The list only carries what has already started: a check that has not begun says nothing, and a line with no
 * detail to give draws no detail. Laid out for the phone in landscape (about 800 x 360 dp), which is why the bar is
 * across the bottom rather than under the list.
 */
@Composable
fun BootScreen(boot: BootState) {
    Box(Modifier.fillMaxSize().stageGlow(Dbz.BallAmber)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            val short = maxHeight < 400.dp
            val total = boot.steps.size
            val started = boot.steps.count { it.state != StepState.Waiting }
            val finished = boot.steps.count { it.state != StepState.Waiting && it.state != StepState.Working }
            val working = boot.steps.firstOrNull { it.state == StepState.Working }

            Column(Modifier.fillMaxSize()) {
                // top: the name and the build, and the checks that have begun
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                        val titleSize = if (short) TitleStyle.fontSize * 0.8f else TitleStyle.fontSize
                        Text(
                            stringResource(R.string.app_name),
                            style = TitleStyle.copy(fontSize = titleSize, lineHeight = titleSize * 0.95f),
                            color = Dbz.SaiyanGold,
                        )
                        Text(
                            stringResource(R.string.boot_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(
                        Modifier
                            .weight(1.2f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0D1018).copy(alpha = 0.72f))
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(if (short) 8.dp else 12.dp, Alignment.CenterVertically),
                    ) {
                        // only the checks that have started: a spinner while one runs, its result once it lands
                        boot.steps.forEach { step ->
                            if (step.state == StepState.Waiting) return@forEach
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusIcon(step.state)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        step.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White,
                                        maxLines = 1,
                                    )
                                    // a line with nothing to say draws nothing
                                    if (step.state != StepState.Working && step.detail.isNotEmpty()) {
                                        Text(
                                            step.detail,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // bottom: the bar, and what it is doing
                Spacer(Modifier.height(if (short) 8.dp else 14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        working?.label ?: stringResource(R.string.boot_please_wait),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.boot_count, finished, total),
                        style = MaterialTheme.typography.labelLarge,
                        color = Dbz.SaiyanGold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else finished.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = Dbz.SaiyanGold,
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(state: StepState) {
    when (state) {
        StepState.Waiting -> Unit // never drawn: the row is not there yet
        StepState.Working -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Dbz.SaiyanGold)
        StepState.Ok -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(20.dp), tint = Dbz.ShenronGreen)
        StepState.Warn -> Icon(Icons.Rounded.Warning, null, Modifier.size(20.dp), tint = Dbz.SaiyanGold)
        StepState.Fail -> Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
    }
}
