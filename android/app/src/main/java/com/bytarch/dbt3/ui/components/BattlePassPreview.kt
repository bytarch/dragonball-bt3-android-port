package com.bytarch.dbt3.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytarch.dbt3.R
import com.bytarch.dbt3.ui.theme.Dbz

// The pass's own colours: the magenta and yellow of the mock, not the app's.
private val Magenta = Color(0xFFE6007E)
private val Yellow = Color(0xFFFFD400)
private val Cyan = Color(0xFF2BE8C8)
private val Ink = Color(0xFF0B0714)
private val Panel = Color(0xFF1B1030)

/** A box cut on the slant, the way the mock's panels are: the shape leans, the text inside does not. [onClick] makes
 * it a control (the lobby's banners and buttons are the same shape). */
@Composable
fun SlantedBox(
    color: Color,
    modifier: Modifier = Modifier,
    skew: Float = -0.16f,
    border: Color = Ink,
    borderWidth: Float = 3f,
    padding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 7.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .graphicsLayer { transformMatrix = Matrix().apply { setSkew(skew, 0f) } }
            .background(color)
            .border(borderWidth.dp, border, RoundedCornerShape(2.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding)
            .graphicsLayer { transformMatrix = Matrix().apply { setSkew(-skew, 0f) } },
    ) {
        content()
    }
}

/** The battle pass as it will be: a season, eight tiers of rewards and a premium pass. None of it is here yet, and
    every part of it says so. The one thing that works is the list under it. */
@Composable
fun BattlePassPreview(modifier: Modifier = Modifier) {
    val tiers = remember { List(8) { it + 1 } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // the season header and its gauge: nothing is filled in, because nothing has been earned
        Row(verticalAlignment = Alignment.CenterVertically) {
            SlantedBox(Magenta) {
                Text(
                    stringResource(R.string.bp_season),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic,
                )
            }
            Spacer(Modifier.weight(1f))
            SlantedBox(Yellow, skew = 0.16f, borderWidth = 2f) {
                Text(
                    stringResource(R.string.bp_coming_soon),
                    style = MaterialTheme.typography.labelLarge,
                    color = Ink,
                    fontWeight = FontWeight.Black,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Panel)
                .border(2.dp, Ink, RoundedCornerShape(3.dp)),
        ) {
            SlantedBox(
                Magenta,
                Modifier.fillMaxWidth(0.12f).height(18.dp),
                skew = 0f,
                borderWidth = 0f,
                padding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {}
        }
        Text(
            stringResource(R.string.bp_season_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // what the middle of the pass will hold
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Panel.copy(alpha = 0.55f))
                .border(3.dp, Ink, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Star, null, Modifier.size(40.dp), tint = Yellow.copy(alpha = 0.8f))
            Text(
                stringResource(R.string.bp_center_placeholder),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 64.dp),
            )
            Column(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalAlignment = Alignment.End) {
                SlantedBox(Yellow, skew = 0.1f, borderWidth = 2f, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.bp_requires_premium), style = MaterialTheme.typography.labelMedium, color = Ink, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                SlantedBox(Yellow, skew = 0.1f, borderWidth = 2f, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.bp_unlocks_tier), style = MaterialTheme.typography.labelMedium, color = Ink, fontWeight = FontWeight.Bold)
                }
            }
        }

        // the eight tiers, each one locked but for the first
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tiers) { tier ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(104.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(78.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (tier == 1) Panel else Color(0xFF140C24))
                            .border(if (tier == 5) 3.dp else 2.dp, if (tier == 5) Magenta else Ink, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (tier != 1) {
                            Icon(Icons.Rounded.Lock, null, Modifier.size(22.dp), tint = Color(0xFFE5484D))
                        } else {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .background(Cyan),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    SlantedBox(
                        if (tier == 5) Magenta else Color(0xFF2A1B45),
                        skew = -0.12f,
                        borderWidth = 2f,
                        padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            stringResource(R.string.bp_tier, tier),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
            }
        }

        // the premium pass, with nothing to buy yet
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(132.dp)
                    .height(168.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(3.dp, Ink, RoundedCornerShape(4.dp)),
            ) {
                Image(
                    painter = painterResource(R.drawable.battlepass_bg),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(124.dp),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Ink)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text("--", style = MaterialTheme.typography.labelLarge, color = Yellow, fontWeight = FontWeight.Black)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SlantedBox(Magenta, borderWidth = 3f) {
                    Text(
                        stringResource(R.string.bp_buy_premium),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                    )
                }
                Text(
                    stringResource(R.string.bp_premium_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            stringResource(R.string.bp_below_note),
            style = MaterialTheme.typography.bodySmall,
            color = Dbz.SaiyanGold,
            textAlign = TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
