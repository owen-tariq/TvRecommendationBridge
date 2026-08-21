package com.owentariq.tvhop

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Watches the TV launcher for a click on a recommendation card, works out
 * which title it was, and hands it to the chosen player app.
 *
 * The service is scoped to the launcher packages in
 * `res/xml/accessibility_service_config.xml`, so it never receives events from
 * anything else the user does on the device.
 */
class CardClickAccessibilityService : AccessibilityService() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var lastTitle: String? = null
    @Volatile private var lastHandledAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName !in LAUNCHER_PACKAGES) return

        val candidates = ArrayList<CharSequence?>(event.text)
        candidates.add(event.contentDescription)
        // The clicked node itself often carries a cleaner label than the event.
        // (Nodes are reclaimed by the framework; explicit recycle() is
        // deprecated and a no-op on current releases.)
        event.source?.let { node ->
            candidates.add(node.text)
            candidates.add(node.contentDescription)
        }

        val title = TitleExtractor.extract(candidates) ?: return

        // The launcher can emit several events for one press.
        val now = SystemClock.elapsedRealtime()
        if (title == lastTitle && now - lastHandledAt < DEBOUNCE_MS) return
        lastTitle = title
        lastHandledAt = now

        val target = Prefs.getTarget(this)
        Log.d(TAG, "Clicked \"$title\" -> $target")
        worker.execute { handle(title, target) }
    }

    private fun handle(title: String, target: TargetApp) {
        val meta = MetaResolver.resolve(title)
        if (meta == null) {
            Log.i(TAG, "No match for \"$title\"")
            toast(getString(R.string.toast_not_identified, title))
            return
        }

        Log.d(TAG, "Resolved \"$title\" -> ${meta.id} (${meta.type})")
        when (val result = TargetLauncher.open(this, target, meta)) {
            is TargetLauncher.Result.Opened -> Unit
            is TargetLauncher.Result.TargetNotInstalled ->
                toast(getString(R.string.toast_target_missing, target.name.lowercase()))
            is TargetLauncher.Result.Failed ->
                toast(getString(R.string.toast_open_failed, result.error))
        }
    }

    private fun toast(message: String) {
        main.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "TvHopService"
        private const val DEBOUNCE_MS = 2500L

        /** Google TV launcher, plus the older Android TV one. */
        private val LAUNCHER_PACKAGES = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcher"
        )
    }
}
