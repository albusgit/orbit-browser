package com.albustech.orbit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.albustech.orbit.R

/**
 * The bezel's mode, visible with the chrome: a dark glass capsule near the bottom with one
 * segment per mode, the current one a white pill. This is how modes switch: a tap on the page
 * brings the chrome back.
 */
@Composable
fun BoxScope.BezelModeBar(
    geometry: RoundGeometry,
    labels: List<String>,
    icons: List<Int>,
    selected: Int,
    visible: Boolean,
    onSelect: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center).offset(y = (geometry.diameterDp / 2f * 0.6f).dp),
    ) {
        Row(
            Modifier
                .width((geometry.diameterDp * 0.72f).dp)
                .height(42.dp)
                .clip(RoundedCornerShape(50))
                .background(GlassFill)
                .border(0.5.dp, GlassEdge, RoundedCornerShape(50))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                val tint = if (on) Color.Black else colors.onSurface.copy(alpha = 0.85f)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(if (on) Color.White else Color.Transparent)
                        .selectable(selected = on, role = Role.RadioButton) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(painterResource(icons[i]), contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                    Text(label, fontSize = 9.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Link mode (design 2c): a counter pill at the top and one explicit "Open link" button.
 * Taps elsewhere only bring back the chrome, so links can't open by accident.
 */
@Composable
fun BoxScope.LinkModeControls(
    geometry: RoundGeometry,
    counter: String?,
    hasLink: Boolean,
    onOpen: () -> Unit,
    onOptions: () -> Unit,
) {
    if (counter != null) {
        Text(
            counter,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(50))
                .background(GlassFill)
                .border(0.5.dp, GlassEdge, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 5.dp),
        )
    }
    val widthDp = (geometry.diameterDp * 0.76f)
    Button(
        onClick = onOpen,
        onLongClick = onOptions,
        enabled = hasLink,
        modifier = Modifier
            .align(Alignment.Center)
            .offset(y = (geometry.diameterDp / 2f * 0.36f).dp)
            .width(widthDp.dp)
            .padding(horizontal = 2.dp),
        icon = { Icon(painterResource(R.drawable.ic_forward), contentDescription = null) },
        secondaryLabel = { Text(androidx.compose.ui.res.stringResource(R.string.hold_for_options), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        label = { Text(androidx.compose.ui.res.stringResource(R.string.open_link), maxLines = 1) },
    )
}

/** Transparent layer that turns any tap into "show the chrome" and a hold into the ring. */
@Composable
fun TapToShowChrome(onTap: () -> Unit, onHold: () -> Unit) {
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onTap = { onTap() },
                onLongPress = { onHold() },
            )
        },
    )
}
