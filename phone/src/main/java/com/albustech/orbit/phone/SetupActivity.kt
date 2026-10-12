package com.albustech.orbit.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.net.toUri
import com.google.android.gms.wearable.Wearable

/**
 * The helper's only screen: what it does, whether a watch is connected, whether it may run in
 * the background (needed to answer the watch), and today's blocked count.
 */
class SetupActivity : Activity() {

    private lateinit var watch: TextView
    private lateinit var background: TextView
    private lateinit var allow: Button
    private lateinit var blocked: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun text(size: Float) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setPadding(0, dp(8), 0, dp(8))
            column.addView(this)
        }
        text(26f).setText(R.string.setup_title)
        text(16f).setText(R.string.setup_intro)
        watch = text(16f)
        background = text(16f)
        allow = Button(this).apply {
            setText(R.string.action_allow_background)
            setOnClickListener { askForBackground() }
            column.addView(this)
        }
        blocked = text(16f)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        background.setText(if (exempt) R.string.status_background_ok else R.string.status_background_needed)
        allow.isEnabled = !exempt
        blocked.text = getString(R.string.status_blocked_today, Blocking.blockedToday(this))
        Wearable.getNodeClient(this).connectedNodes.addOnCompleteListener { task ->
            val names = task.result?.joinToString { it.displayName }.orEmpty()
            watch.text = if (task.isSuccessful && names.isNotEmpty()) getString(R.string.status_watch_connected, names) else getString(R.string.status_watch_none)
        }
    }

    // Sideloaded helper whose whole job is answering the watch from the background.
    @SuppressLint("BatteryLife")
    private fun askForBackground() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())
        try {
            startActivity(direct)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
