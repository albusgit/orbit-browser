package com.albustech.orbit.claude.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.albustech.orbit.claude.ClaudeController
import com.albustech.orbit.claude.ClaudeUrls
import com.albustech.orbit.claude.R
import com.albustech.orbit.claude.input.Bezel

/** What the screen asks of the Activity. */
interface ScreenActions {
    fun openOnPhone(url: String)
    fun finishApp()
}

/** Height the chat buttons take at the bottom, which the page pads its conversation by (CSS px = dp). */
val ChatBarHeightDp = 84.dp

/**
 * The interface layer: claude.ai fills the circle, and the watch's own controls sit over it.
 *
 *  - In a chat, claude.ai's message box gives way to three round buttons along the bottom edge:
 *    type, speak (stop while Claude answers) and the menu. The bezel scrolls the conversation.
 *  - On sign-in pages nothing covers the form.
 *  - A window the page opens (Google's sign-in) covers the chat, with its site and a close
 *    button on top, until it closes itself or you close it.
 *  - The rim shows a moving arc while a page loads or Claude is answering.
 */
@Composable
fun ClaudeScreen(c: ClaudeController, bezel: Bezel, actions: ScreenActions) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val entry = rememberMessageEntry { c.send(it) }
    val popup = c.popup
    val url = c.url
    val inChat = popup == null && ClaudeUrls.isChat(url) && c.page.native
    val signingIn = ClaudeUrls.isSignIn(url) || (url != null && !ClaudeUrls.isClaude(url))

    bezel.enabled = !menuOpen
    LaunchedEffect(menuOpen, popup) { if (!menuOpen) rootFocus.requestFocus() }

    BackHandler {
        when {
            menuOpen -> menuOpen = false
            !c.back() -> actions.finishApp()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent { bezel.onRotary(it.verticalScrollPixels) }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        // Two surfaces never overlap: the chat hides while a popup is up.
        AndroidView(
            factory = { c.view.also { (it.parent as? ViewGroup)?.removeView(it) } },
            update = { it.visibility = if (popup == null) View.VISIBLE else View.INVISIBLE },
            modifier = Modifier.fillMaxSize(),
        )

        if (inChat) {
            ChatBar(
                busy = c.page.busy,
                onType = entry::type,
                onSpeak = entry::speak,
                onStop = c::stop,
                onMenu = { menuOpen = true },
            )
        } else if (popup == null && !signingIn && c.loadError == null) {
            // Off the chat (the chats list, settings): just the way to the menu.
            Box(Modifier.fillMaxSize().padding(bottom = 10.dp), contentAlignment = Alignment.BottomCenter) {
                RoundButton(R.drawable.ic_more, stringResource(R.string.action_menu), size = 36.dp, onClick = { menuOpen = true })
            }
        }

        if (popup != null) PopupLayer(c, popup)

        RimArc(visible = (popup?.loading ?: c.loading) || (inChat && c.page.busy))

        c.loadError?.let { ErrorPane(onRetry = c::reload) }

        if (menuOpen) {
            Menu(
                onNewChat = { menuOpen = false; c.load(ClaudeUrls.HOME) },
                onChats = { menuOpen = false; c.load(ClaudeUrls.CHATS) },
                onReload = { menuOpen = false; c.reload() },
                onOpenOnPhone = { menuOpen = false; actions.openOnPhone(url ?: ClaudeUrls.HOME) },
                onSignOut = { menuOpen = false; c.load(ClaudeUrls.LOGOUT) },
            )
        }
    }
}

// ---------------------------------------------------------------------- chat

@Composable
private fun ChatBar(busy: Boolean, onType: () -> Unit, onSpeak: () -> Unit, onStop: () -> Unit, onMenu: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // A scrim so the last lines of an answer fade under the buttons instead of fighting them.
        Box(
            Modifier
                .fillMaxWidth()
                .height(ChatBarHeightDp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
        )
        // Small buttons ride higher than the big one so all three stay inside the circle.
        Row(
            Modifier.padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RoundButton(R.drawable.ic_keyboard, stringResource(R.string.action_type), size = 36.dp, onClick = onType)
            if (busy) {
                RoundButton(R.drawable.ic_stop, stringResource(R.string.action_stop), size = 52.dp, primary = true, onClick = onStop)
            } else {
                RoundButton(R.drawable.ic_mic, stringResource(R.string.action_speak), size = 52.dp, primary = true, onClick = onSpeak)
            }
            RoundButton(R.drawable.ic_more, stringResource(R.string.action_menu), size = 36.dp, onClick = onMenu)
        }
    }
}

@Composable
private fun RoundButton(@DrawableRes icon: Int, label: String, size: Dp, onClick: () -> Unit, primary: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (primary) colors.primary else GlassFill)
            .border(0.5.dp, if (primary) Color.Transparent else GlassEdge, CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = label,
            tint = if (primary) colors.onPrimary else colors.onSurface,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

// ---------------------------------------------------------------------- popup

/** The page's window over the chat: its site on top (where you're signing in) and a close button. */
@Composable
private fun PopupLayer(c: ClaudeController, popup: ClaudeController.Popup) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c.popupView.also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier
                    .widthIn(max = 150.dp)
                    .clip(RoundedCornerShape(50))
                    .background(GlassFill)
                    .border(0.5.dp, GlassEdge, RoundedCornerShape(50))
                    .clickable(onClickLabel = stringResource(R.string.action_close), role = Role.Button, onClick = c::closePopup)
                    .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close), tint = colors.onSurface, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(9.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    ClaudeUrls.host(popup.url) ?: "",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- status

/** A short arc running around the rim while something is in progress. */
@Composable
private fun RimArc(visible: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        val turn by rememberInfiniteTransition(label = "rim").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
            label = "turn",
        )
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            val stroke = 3.dp.toPx()
            drawArc(
                color = color,
                startAngle = turn - 90f,
                sweepAngle = 70f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun ErrorPane(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.error_load),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = onRetry, icon = { Icon(painterResource(R.drawable.ic_reload), contentDescription = null) }) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

// ---------------------------------------------------------------------- menu

@Composable
private fun Menu(
    onNewChat: () -> Unit,
    onChats: () -> Unit,
    onReload: () -> Unit,
    onOpenOnPhone: () -> Unit,
    onSignOut: () -> Unit,
) {
    ClaudeList {
        item { MenuButton(R.drawable.ic_add, stringResource(R.string.menu_new_chat), onNewChat, primary = true) }
        item { MenuButton(R.drawable.ic_history, stringResource(R.string.menu_chats), onChats) }
        item { MenuButton(R.drawable.ic_reload, stringResource(R.string.menu_reload), onReload) }
        item { MenuButton(R.drawable.ic_phone, stringResource(R.string.menu_open_on_phone), onOpenOnPhone) }
        item { MenuButton(R.drawable.ic_logout, stringResource(R.string.menu_sign_out), onSignOut) }
    }
}
