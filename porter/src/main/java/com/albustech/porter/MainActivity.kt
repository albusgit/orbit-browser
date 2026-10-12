package com.albustech.porter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleShare(intent)
        setContent { PorterTheme { PorterScreen(porter) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Files shared or opened from another app go into the queue. */
    private fun handleShare(intent: Intent) {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) porter.enqueue(uris)
    }
}

private val TimeoutChoices = listOf(0 to "Off", 5 * 60_000 to "5 min", 10 * 60_000 to "10 min", 30 * 60_000 to "30 min")

@Composable
internal fun PorterScreen(porter: Porter) {
    val state by porter.state.collectAsStateWithLifecycle()
    val running = state.phase != Phase.Off

    var host by remember { mutableStateOf(state.host) }
    var port by remember { mutableStateOf(if (state.port > 0) state.port.toString() else "") }
    // Discovery and port moves update the fields.
    LaunchedEffect(state.host, state.port) {
        if (state.host.isNotEmpty()) host = state.host
        if (state.port > 0) port = state.port.toString()
    }
    var timeout by remember { mutableIntStateOf(porter.prefs.timeoutMs) }
    var showPair by remember { mutableStateOf(!porter.prefs.paired) }

    val pickApks = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { porter.enqueue(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { porter.enqueue(it) }
    // Typed `install` and `push` pick their files here, then queue them with the typed options.
    var typed by remember { mutableStateOf<AdbCommand?>(null) }
    val pickTyped = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        when (val c = typed) {
            is AdbCommand.Install -> porter.enqueue(uris, installOptions = c.options.ifEmpty { listOf("-r") })
            is AdbCommand.Push -> porter.enqueue(uris, remoteDir = c.remoteDir, push = true)
            else -> Unit
        }
        if (uris.isNotEmpty()) porter.echo("Queued ${uris.size} file(s): see Files above.\n")
        typed = null
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Porter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                StatusPill(state.phase)
            }

            Section {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host, onValueChange = { host = it.trim() }, enabled = !running,
                        label = { Text("Watch IP") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, enabled = !running,
                        label = { Text("Port") }, singleLine = true, modifier = Modifier.width(96.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Text(
                    "Watch: Developer options → Wireless debugging. Use the IP and port shown there.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val portNumber = port.toIntOrNull() ?: 0
                if (running) {
                    Button(
                        onClick = { porter.stop() }, modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) { Text("Stop port", fontSize = 16.sp) }
                } else {
                    Button(
                        onClick = { porter.start(host, portNumber) },
                        enabled = host.isNotEmpty() && portNumber in 1..65535,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Start port", fontSize = 16.sp) }
                }
                if (state.message.isNotEmpty()) {
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!running) {
                    TextButton(onClick = { porter.discover() }, modifier = Modifier.align(Alignment.End)) { Text("Find watch") }
                }
            }

            Section {
                Text("Watch screen timeout while porting", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TimeoutChoices.forEachIndexed { i, (ms, label) ->
                        SegmentedButton(
                            selected = timeout == ms,
                            onClick = { timeout = ms; porter.prefs.timeoutMs = ms },
                            enabled = !running,
                            shape = SegmentedButtonDefaults.itemShape(i, TimeoutChoices.size),
                        ) { Text(label) }
                    }
                }
                Text(
                    "Set when the port starts, put back when it stops.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Files", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    if (state.transfers.any { it.status == Status.Done || it.status == Status.Failed }) {
                        TextButton(onClick = { porter.clearFinished() }) { Text("Clear") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { pickApks.launch(arrayOf("application/vnd.android.package-archive")) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Install APK") }
                    FilledTonalButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                        Text("Send file")
                    }
                }
                Text(
                    if (running) "Sent files go to the watch's Download folder."
                    else "Files you pick wait here until the port starts.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.transfers.forEach { TransferRow(it) }
            }

            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pair this phone", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showPair = !showPair }) { Text(if (showPair) "Hide" else "Show") }
                }
                AnimatedVisibility(showPair) {
                    PairForm(host, state.pairing, state.pairStatus, onPair = { h, p, code -> porter.pair(h, p, code) })
                }
            }

            Section {
                CommandBox(
                    console = state.console,
                    connected = state.phase == Phase.Connected,
                    onRun = { line ->
                        val cmd = AdbCommand.parse(line)
                        porter.runCommand(line, cmd)
                        when (cmd) {
                            is AdbCommand.Install -> { typed = cmd; pickTyped.launch(arrayOf("application/vnd.android.package-archive")) }
                            is AdbCommand.Push -> { typed = cmd; pickTyped.launch(arrayOf("*/*")) }
                            else -> Unit
                        }
                    },
                    onStop = porter::stopCommand,
                    onClear = porter::clearConsole,
                )
            }

            if (state.log.isNotEmpty()) {
                Section {
                    Text("Log", style = MaterialTheme.typography.titleSmall)
                    Text(
                        state.log.takeLast(8).joinToString("\n"),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PairForm(host: String, pairing: Boolean, status: String, onPair: (String, Int, String) -> Unit) {
    var pairPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Only needed once. On the watch, open Wireless debugging → Pair new device, then enter the " +
                "pairing port and code it shows (the IP above stays the same).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = pairPort, onValueChange = { pairPort = it.filter(Char::isDigit).take(5) },
                label = { Text("Pairing port") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("Code") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        }
        FilledTonalButton(
            onClick = { onPair(host, pairPort.toInt(), code) },
            enabled = !pairing && host.isNotEmpty() && (pairPort.toIntOrNull() ?: 0) in 1..65535 && code.length == 6,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (pairing) "Pairing…" else "Pair") }
        if (status.isNotEmpty()) {
            Text(
                status, style = MaterialTheme.typography.bodySmall,
                color = when {
                    pairing -> MaterialTheme.colorScheme.onSurfaceVariant
                    status.startsWith("Paired") -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

@Composable
private fun CommandBox(console: Console, connected: Boolean, onRun: (String) -> Unit, onStop: () -> Unit, onClear: () -> Unit) {
    var line by remember { mutableStateOf("") }
    val run = { if (line.isNotBlank()) { onRun(line.trim()); line = "" } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Commands", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { onRun("help") }) { Text("Help") }
        if (console.output.isNotEmpty() && !console.running) TextButton(onClick = onClear) { Text("Clear") }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = line, onValueChange = { line = it.replace("\n", "") },
            placeholder = { Text("adb shell pm list packages", fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { if (!console.running) run() }),
        )
        if (console.running) {
            FilledTonalButton(onClick = onStop) { Text("Stop") }
        } else {
            FilledTonalButton(onClick = run, enabled = line.isNotBlank()) { Text("Run") }
        }
    }
    if (!connected) {
        Text(
            "Commands run once the port is connected. Type them as you would after adb.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (console.output.isNotEmpty()) {
        val scroll = rememberScrollState()
        // Follow new output, like a terminal.
        LaunchedEffect(console.output.length) { scroll.scrollTo(scroll.maxValue) }
        SelectionContainer {
            Text(
                console.output.trimEnd('\n'),
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.small)
                    .verticalScroll(scroll)
                    .padding(10.dp),
            )
        }
    }
}

@Composable
private fun TransferRow(t: Transfer) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            val label = when (t.status) {
                Status.Waiting -> "Waiting"
                Status.Sending -> if (t.size > 0) "${(t.sent * 100 / t.size).coerceIn(0, 100)}%" else "Sending"
                Status.Done -> if (t.isApk) "Installed" else "Sent"
                Status.Failed -> "Failed"
            }
            val color = when (t.status) {
                Status.Done -> MaterialTheme.colorScheme.primary
                Status.Failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
        when (t.status) {
            Status.Sending ->
                if (t.size > 0) LinearProgressIndicator(progress = { t.sent.toFloat() / t.size }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> Unit
        }
    }
}

@Composable
private fun StatusPill(phase: Phase) {
    val (label, color) = when (phase) {
        Phase.Off -> "Off" to MaterialTheme.colorScheme.outline
        Phase.Connecting, Phase.Reconnecting -> "Connecting" to Color(0xFFE0A030)
        Phase.Connected -> "Connected" to Color(0xFF3DBE6E)
    }
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
internal fun PorterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors: ColorScheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF8AB4FF))
        else -> lightColorScheme(primary = Color(0xFF2F5FD0))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
