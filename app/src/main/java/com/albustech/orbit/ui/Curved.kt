package com.albustech.orbit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.curvedText
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.material3.timeTextSeparator

/** Time at the top, with the page title curving along the edge beside it. */
@Composable
fun TitleTimeText(title: String?) {
    val t = title?.trim()?.takeIf { it.isNotEmpty() }
    TimeText(maxSweepAngle = if (t != null) 150f else 70f) { time ->
        if (t != null) {
            curvedText(t, maxSweepAngle = 100f, overflow = TextOverflow.Ellipsis)
            timeTextSeparator()
        }
        timeTextCurvedText(time)
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

/** Muted curved text along the top, used on the ambient screen. */
@Composable
fun TopCurvedText(text: String, color: Color) {
    CurvedLayout(modifier = Modifier.fillMaxSize().padding(4.dp), anchor = 270f) {
        curvedText(text, color = color, maxSweepAngle = 140f, overflow = TextOverflow.Ellipsis)
    }
}
