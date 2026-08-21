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
 * ## Why focus is tracked as well as clicks
 *
 * Tapping a card on the Google TV home screen does not produce a click event
 * carrying the title. The launcher just navigates to Google TV's own detail
 * page, and the only clicks that arrive with useful text are the buttons on
 * that next screen ("Watch trailer", "Buy"). Acting on those means reacting
 * one screen too late.
 *
 * So the currently focused card is remembered as the D-pad moves over it
 * ([lastFocused]), and when a press lands with no usable title of its own —
 * or with a button label instead — that remembered card is used. Focus events
 * are cheap: they only ever update a field, never hit the network.
 */
class CardClickAccessibilityService : AccessibilityService() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** The card the D-pad was sitting on most recently, and when. */
    @Volatile private var lastFocused: CardInfo? = null
    @Volatile private var lastFocusedAt = 0L

    @Volatile private var lastHandledTitle: String? = null
    @Volatile private var lastHandledAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in WATCHED_PACKAGES) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> rememberFocus(event)

            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event)
        }
    }

    /** Cheap: parse and store, never resolve. */
    private fun rememberFocus(event: AccessibilityEvent) {
        val card = TitleExtractor.extract(candidatesOf(event)) ?: return
        lastFocused = card
        lastFocusedAt = SystemClock.elapsedRealtime()
        Log.v(TAG, "Focus on \"${card.title}\" (${card.year ?: "no year"})")
    }

    private fun handleClick(event: AccessibilityEvent) {
        val candidates = candidatesOf(event)
        Log.d(TAG, "Click strings: " + candidates.filterNotNull().joinToString(" ⟪|⟫ "))

        val clicked = TitleExtractor.extract(candidates)
        val focused = lastFocused.takeIf {
            SystemClock.elapsedRealtime() - lastFocusedAt < FOCUS_TTL_MS
        }

        // A press on a card usually carries nothing useful, and a press on a
        // detail-page button carries the button's label. Either way the
        // focused card is the better answer.
        val card = when {
            clicked == null -> focused
            TitleExtractor.isAffordance(clicked.title) -> focused ?: clicked
            else -> clicked
        } ?: run {
            Log.d(TAG, "Click with no usable title and no recent focus — ignoring")
            return
        }

        // The launcher can emit several events for one press.
        val now = SystemClock.elapsedRealtime()
        if (card.title == lastHandledTitle && now - lastHandledAt < DEBOUNCE_MS) return
        lastHandledTitle = card.title
        lastHandledAt = now

        val target = Prefs.getTarget(this)
        Log.d(TAG, "Opening \"${card.title}\" year=${card.year} type=${card.typeHint} -> $target")
        worker.execute { handle(card, target) }
    }

    /** Card strings in descending order of trustworthiness. */
    private fun candidatesOf(event: AccessibilityEvent): List<CharSequence?> {
        val candidates = ArrayList<CharSequence?>()
        // The node's own label beats the event's, and contentDescription is
        // where launchers stuff synopses — so it goes last.
        event.source?.let { candidates.add(it.text) }
        candidates.addAll(event.text)
        event.source?.let { candidates.add(it.contentDescription) }
        candidates.add(event.contentDescription)
        return candidates
    }

    private fun handle(card: CardInfo, target: TargetApp) {
        val meta = MetaResolver.resolve(card)
        if (meta == null) {
            Log.i(TAG, "No confident match for \"${card.title}\"")
            toast(getString(R.string.toast_not_identified, card.title))
            return
        }

        Log.d(TAG, "Resolved \"${card.title}\" -> ${meta.id} (${meta.type})")
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

        /** How long a remembered card stays valid after focus moved to it. */
        private const val FOCUS_TTL_MS = 60_000L

        /**
         * The TV launchers, plus Google TV's detail screen — which is where a
         * card tap lands, and where "Watch trailer" / "Buy" live.
         */
        private val WATCHED_PACKAGES = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcher",
            "com.google.android.videos"
        )
    }
}
