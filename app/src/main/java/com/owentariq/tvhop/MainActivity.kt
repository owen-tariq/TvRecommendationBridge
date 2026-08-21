package com.owentariq.tvhop

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Setup screen: pick the player app, turn the service on, and prove the whole
 * pipeline works without having to go hunting for a card on the home screen.
 */
class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var serviceStatus: TextView
    private lateinit var testStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serviceStatus = findViewById(R.id.service_status)
        testStatus = findViewById(R.id.test_status)

        setupTargetSelector()

        findViewById<Button>(R.id.button_accessibility).setOnClickListener {
            // Some third-party launchers block this intent for apps without a
            // vendor permission and throw instead of opening the screen.
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.accessibility_unavailable, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.button_test).setOnClickListener { runSelfTest() }
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
    }

    private fun setupTargetSelector() {
        val group = findViewById<RadioGroup>(R.id.target_group)
        when (Prefs.getTarget(this)) {
            TargetApp.NUVIO -> findViewById<RadioButton>(R.id.radio_nuvio).isChecked = true
            TargetApp.STREMIO -> findViewById<RadioButton>(R.id.radio_stremio).isChecked = true
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val target = if (checkedId == R.id.radio_stremio) TargetApp.STREMIO else TargetApp.NUVIO
            Prefs.setTarget(this, target)
        }
    }

    private fun refreshServiceStatus() {
        val enabled = isServiceEnabled()
        serviceStatus.setText(
            if (enabled) R.string.service_on else R.string.service_off
        )
    }

    /**
     * Resolves a known title and opens it, so the user can confirm network
     * access and the player hand-off in one press — no launcher card needed.
     */
    private fun runSelfTest() {
        testStatus.setText(R.string.test_running)
        val target = Prefs.getTarget(this)

        worker.execute {
            val meta = MetaResolver.resolve(SELF_TEST_TITLE)
            runOnUiThread {
                if (meta == null) {
                    testStatus.setText(R.string.test_lookup_failed)
                    return@runOnUiThread
                }
                when (val result = TargetLauncher.open(this, target, meta)) {
                    is TargetLauncher.Result.Opened ->
                        testStatus.text = getString(R.string.test_opened, meta.name, meta.id)
                    is TargetLauncher.Result.TargetNotInstalled ->
                        testStatus.text =
                            getString(R.string.test_target_missing, target.name.lowercase())
                    is TargetLauncher.Result.Failed ->
                        testStatus.text = getString(R.string.test_failed, result.error)
                }
            }
        }
    }

    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${CardClickAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        for (entry in splitter) {
            if (entry.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    private companion object {
        const val SELF_TEST_TITLE = "Dune: Part Two"
    }
}
