package com.albustech.orbit.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.albustech.orbit.R
import com.albustech.orbit.data.Suggestions
import kotlin.math.cos
import kotlin.math.sin

/**
 * The radial menu for a long-pressed (or focused) link: four actions around the centre at
 * north, east, south and west, with the link itself in the middle. The buttons sit on a ring
 * well inside the screen, clear of the bezel; tapping anywhere else closes the menu.
 */
@Composable
fun LinkMenu(
    geometry: RoundGeometry,
    url: String,
    title: String?,
    bookmarked: Boolean,
    onOpen: () -> Unit,
    onOpenOnPhone: () -> Unit,
    onBookmark: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ringDp = geometry.diameterDp / 2f * RING_FRACTION
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width((geometry.squareDp * 0.62f).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title?.takeIf { it.isNotBlank() } ?: Suggestions.displayUrl(url),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                Suggestions.siteKey(url) ?: url,
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RadialButton(-90f, ringDp, R.drawable.ic_open, stringResource(R.string.action_open), onOpen, primary = true)
        RadialButton(0f, ringDp, R.drawable.ic_phone, stringResource(R.string.action_open_on_phone), onOpenOnPhone)
        RadialButton(
            90f,
            ringDp,
            if (bookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
            stringResource(R.string.action_bookmark),
            onBookmark,
        )
        RadialButton(180f, ringDp, R.drawable.ic_copy, stringResource(R.string.action_copy_link), onCopy)
    }
}

@Composable
private fun RadialButton(
    angleDeg: Float,
    radiusDp: Float,
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val a = Math.toRadians(angleDeg.toDouble())
    val modifier = Modifier
        .offset(x = (radiusDp * cos(a)).toFloat().dp, y = (radiusDp * sin(a)).toFloat().dp)
        .size(IconButtonDefaults.DefaultButtonSize)
    if (primary) {
        FilledIconButton(onClick = onClick, modifier = modifier) { Icon(painterResource(icon), contentDescription = label) }
    } else {
        FilledTonalIconButton(onClick = onClick, modifier = modifier) { Icon(painterResource(icon), contentDescription = label) }
    }
}

/** Button centres sit at this fraction of the radius: far from the rim, clear of the centre text. */
private const val RING_FRACTION = 0.6f
