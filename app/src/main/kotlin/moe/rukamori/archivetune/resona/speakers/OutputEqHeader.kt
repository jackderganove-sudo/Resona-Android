/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.speakers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.R

/** Top of the EQ screen (round 12): "Applies to: <device>", "Same for all devices", "Use default EQ". */
@Composable
fun OutputEqHeader(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val output by OutputEq.output.collectAsStateWithLifecycle()
    val state by OutputEq.state.collectAsStateWithLifecycle()
    val key = OutputEqRules.key(output)
    val hasOwn = OutputEqRules.hasOwn(state, key)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    painter = painterResource(if (state.sameForAll) R.drawable.volume_up else output.icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val target = if (state.sameForAll) stringResource(R.string.resona_eq_all_devices) else OutputEq.displayName(context, output)
                    Text(
                        text = stringResource(R.string.resona_eq_applies_to, target),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!state.sameForAll) {
                        Text(
                            text = stringResource(if (hasOwn) R.string.resona_eq_own else R.string.resona_eq_default_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.resona_eq_same_for_all),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.sameForAll, onCheckedChange = { OutputEq.setSameForAll(it) })
            }
            if (hasOwn) {
                TextButton(onClick = { OutputEq.useStandard() }) {
                    Text(text = stringResource(R.string.resona_eq_use_default), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
