package com.bytarch.dbt3.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bytarch.dbt3.BootState
import com.bytarch.dbt3.BuildConfig
import com.bytarch.dbt3.R
import com.bytarch.dbt3.StepState
import com.bytarch.dbt3.ui.theme.Dbz
import com.bytarch.dbt3.ui.theme.TitleStyle

/**
 * What a player sees between tapping the icon and the home screen: the artwork, the build, and each check as it
 * lands. The same list on every launch is what makes it read like a game's loading step instead of a wait.
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
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.90f))),
            ),
        )
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.app_name), style = TitleStyle, color = Dbz.SaiyanGold)
            Text(
                stringResource(R.string.boot_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(26.dp))

            boot.steps.forEach { step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    statusIcon(step.state)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            step.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (step.state == StepState.Waiting)
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (step.detail.isNotEmpty()) {
                            Text(
                                step.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.size(12.dp))
            }

            Spacer(Modifier.weight(1f))
            val finished = boot.steps.count { it.state != StepState.Waiting && it.state != StepState.Working }
            LinearProgressIndicator(
                progress = { if (boot.steps.isEmpty()) 0f else finished.toFloat() / boot.steps.size },
                modifier = Modifier.fillMaxWidth(),
                color = Dbz.SaiyanGold,
            )
            Text(
                stringResource(R.string.boot_please_wait),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun statusIcon(state: StepState) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    when (state) {
        StepState.Waiting -> Icon(Icons.Rounded.RadioButtonUnchecked, null, Modifier.size(18.dp), tint = dim)
        StepState.Working -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Dbz.SaiyanGold)
        StepState.Ok -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = Dbz.ShenronGreen)
        StepState.Warn -> Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp), tint = Dbz.SaiyanGold)
        StepState.Fail -> Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
    }
}
