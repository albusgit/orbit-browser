package com.albustech.orbit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.albustech.orbit.R

/**
 * Start screen, laid out inside the inscribed square: voice (primary), keyboard, and a
 * "continue" chip for the last page. Bookmarks quick-launch joins it in Phase 4.
 */
@Composable
fun HomeScreen(
    geometry: RoundGeometry,
    lastHost: String?,
    onSpeak: () -> Unit,
    onType: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.size(geometry.squareDp.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = onSpeak,
                    modifier = Modifier.size(IconButtonDefaults.LargeButtonSize),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = stringResource(R.string.action_voice),
                        modifier = Modifier.size(IconButtonDefaults.LargeIconSize),
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = onType,
                    modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_keyboard),
                        contentDescription = stringResource(R.string.action_keyboard),
                    )
                }
            }
            if (lastHost != null) {
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = lastHost,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
