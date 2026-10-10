package com.albustech.orbit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TimeTextDefaults
import com.albustech.orbit.R
import androidx.wear.compose.material3.curvedText

/** The time at the top, off pages (launcher, lists, menus). */
@Composable
fun TitleTimeText() {
    TimeText()
}

/** On a page: a dark glass pill at the top with the time over a padlock and the site. */
@Composable
fun PagePill(host: String) {
    val colors = MaterialTheme.colorScheme
    val time = TimeTextDefaults.rememberTimeSource(TimeTextDefaults.timeFormat()).currentTime()
    Box(Modifier.fillMaxSize().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 150.dp)
                .clip(RoundedCornerShape(50))
                .background(GlassFill)
                .border(0.5.dp, GlassEdge, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(time, fontSize = 10.sp, color = colors.onSurfaceVariant, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(9.dp))
                Spacer(Modifier.width(3.dp))
                Text(host, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A short label curved along the bottom edge (bezel mode, "4 / 12", text size), shown for a
 * moment after something changes.
 */
@Composable
fun BottomCurvedLabel(text: String?, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onPrimaryContainer
    val background = MaterialTheme.colorScheme.primaryContainer
    AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        CurvedLayout(modifier = Modifier.fillMaxSize().padding(2.dp), anchor = 90f) {
            curvedText(
                text = text.orEmpty(),
                color = color,
                background = background,
                fontSize = 13.sp,
                maxSweepAngle = 90f,
                overflow = TextOverflow.Ellipsis,
                modifier = CurvedModifier.padding(radial = 3.dp, angular = 8.dp),
                angularDirection = androidx.wear.compose.foundation.CurvedDirection.Angular.CounterClockwise,
            )
        }
    }
}

/** A static label curved along the bottom edge (the launcher's highlighted slot or hint). */
@Composable
fun BottomCurvedText(text: String?, maxSweepAngle: Float = 150f) {
    if (text.isNullOrEmpty()) return
    val color = MaterialTheme.colorScheme.onSurface
    CurvedLayout(modifier = Modifier.fillMaxSize().padding(6.dp), anchor = 90f) {
        curvedText(
            text = text,
            color = color,
            fontSize = 13.sp,
            maxSweepAngle = maxSweepAngle,
            overflow = TextOverflow.Ellipsis,
            angularDirection = androidx.wear.compose.foundation.CurvedDirection.Angular.CounterClockwise,
        )
    }
}

/** Muted curved text along the top, used on the ambient screen. */
@Composable
fun TopCurvedText(text: String, color: Color, maxSweepAngle: Float = 140f) {
    CurvedLayout(modifier = Modifier.fillMaxSize().padding(4.dp), anchor = 270f) {
        curvedText(text, color = color, maxSweepAngle = maxSweepAngle, overflow = TextOverflow.Ellipsis)
    }
}
