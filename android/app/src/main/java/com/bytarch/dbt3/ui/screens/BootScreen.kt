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
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
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
import com.bytarch.dbt3.ui.theme.Dbz
import com.bytarch.dbt3.ui.theme.TitleStyle

/**
 * What a player sees between tapping the icon and the lobby: the artwork, the build, and each check as it lands.
 *
 * Laid out for the phone in landscape (about 800 x 360 dp): the title and the bar on the left, the checks on the
 * right, because a single column of five rows in a short window is a list that runs off the bottom.
 */
@Composable
fun BootScreen(boot: BootState) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.boot_backdrop),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color(0xF00A0B12), Color(0xA60A0B12))),
            ),
        )
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 14.dp)) {
            val short = maxHeight < 400.dp
            val titleSize = if (short) TitleStyle.fontSize * 0.8f else TitleStyle.fontSize
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                // left: who is loading, and how far along
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.Center) {
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
                    Spacer(Modifier.height(if (short) 14.dp else 22.dp))
                    val finished = boot.steps.count { it.state != StepState.Waiting && it.state != StepState.Working }
                    LinearProgressIndicator(
                        progress = { if (boot.steps.isEmpty()) 0f else finished.toFloat() / boot.steps.size },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Dbz.SaiyanGold,
                    )
                    Spacer(Modifier.height(if (short) 8.dp else 12.dp))
                    Text(
                        stringResource(R.string.boot_please_wait),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // right: the checks, each one as it lands
                Column(
                    Modifier
                        .weight(1.15f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0D1018).copy(alpha = 0.72f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (short) 6.dp else 10.dp, Alignment.CenterVertically),
                ) {
                    boot.steps.forEach { step ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusIcon(step.state)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    step.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (step.state == StepState.Waiting)
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    else Color.White,
                                    maxLines = 1,
                                )
                                if (step.detail.isNotEmpty()) {
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
        }
    }
}

@Composable
private fun StatusIcon(state: StepState) {
    when (state) {
        StepState.Waiting -> Icon(
            Icons.Rounded.RadioButtonUnchecked, null, Modifier.size(18.dp),
            tint = Color.White.copy(alpha = 0.22f),
        )
        StepState.Working -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Dbz.SaiyanGold)
        StepState.Ok -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = Dbz.ShenronGreen)
        StepState.Warn -> Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp), tint = Dbz.SaiyanGold)
        StepState.Fail -> Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
    }
}
