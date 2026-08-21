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
            // Google TV's detail page is the authoritative source: by the time
            // it's on screen, Google has already resolved which title you
            // picked and rendered its name. Reading it beats guessing from the
            // card, whose accessibility label is only layout scaffolding.
            // Any window change might be a detail page opening. Which package
            // hosts it varies — Google TV for most titles, other surfaces for
            // buy-only ones — so the reader decides by looking at the page
            // itself rather than trusting the package name.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                rememberFocus(event)
                scanForDetailPage()
            }

            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> rememberFocus(event)

            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event, packageName)
        }
    }

    private var prefetch: Runnable? = null

    private fun rememberFocus(event: AccessibilityEvent) {
        val candidates = candidatesOf(event)
        val card = TitleExtractor.extract(candidates) ?: return
        if (card.title == lastFocused?.title) return

        lastFocused = card
        lastFocusedAt = SystemClock.elapsedRealtime()
        Log.v(TAG, "Focus on \"${card.title}\" (${card.year ?: "no year"})")
        DebugLog.add(
            "focus: \"${card.title}\"" +
                (card.year?.let { " ($it)" } ?: "") +
                (card.typeHint?.let { " [$it]" } ?: "") +
                "  ⟨raw: ${candidates.filterNotNull().joinToString(" | ").take(160)}⟩"
        )

        // Resolve ahead of the press. Cards you skim past are debounced away;
        // anything you pause on is already looked up and cached by the time
        // you hit select, so opening feels instant instead of waiting on the
        // network mid-press.
        prefetch?.let { main.removeCallbacks(it) }
        val task = Runnable {
            worker.execute {
                if (lastFocused?.title == card.title) MetaResolver.resolve(card)
            }
        }
        prefetch = task
        main.postDelayed(task, PREFETCH_DELAY_MS)
    }

    private val pendingScans = ArrayList<Runnable>()

    /**
     * A window opened. It may be a detail page, and it may still be drawing —
     * artwork and buttons often land after the first frame — so it's checked a
     * few times and the first confident read wins.
     *
     * When it succeeds the hand-off happens on its own; no second press.
     */
    private fun scanForDetailPage() {
        pendingScans.forEach { main.removeCallbacks(it) }
        pendingScans.clear()

        for (delay in SCAN_DELAYS_MS) {
            val task = Runnable { attemptDetailScan() }
            pendingScans.add(task)
            main.postDelayed(task, delay)
        }
    }

    private fun attemptDetailScan() {
        val scan = DetailPageReader.read(rootInActiveWindow)
        val card = scan.card
        if (card == null) {
            // Only worth reporting once the page claimed to be a detail page;
            // otherwise every home-screen redraw would spam the log.
            if (scan.isDetailPage) {
                DebugLog.add("detail: ${scan.diagnostics}")
                Log.d(TAG, scan.diagnostics)
            }
            return
        }

        if (card.title == lastHandledTitle &&
            SystemClock.elapsedRealtime() - lastHandledAt < DEBOUNCE_MS
        ) return

        // Found it — cancel the remaining retries.
        pendingScans.forEach { main.removeCallbacks(it) }
        pendingScans.clear()

        lastHandledTitle = card.title
        lastHandledAt = SystemClock.elapsedRealtime()

        DebugLog.add("detail: ${scan.diagnostics}")
        val target = Prefs.getTarget(this)
        DebugLog.add("open:   \"${card.title}\" (auto, from detail page) → $target")
        Log.d(TAG, "Auto-opening \"${card.title}\" from detail page -> $target")
        worker.execute { handle(card, target) }
    }

    private fun handleClick(event: AccessibilityEvent, packageName: String) {
        val candidates = candidatesOf(event)
        Log.d(TAG, "Click strings: " + candidates.filterNotNull().joinToString(" ⟪|⟫ "))

        DebugLog.add("click:  ⟨raw: " + candidates.filterNotNull().joinToString(" | ").take(160) + "⟩")

        val clicked = TitleExtractor.extract(candidates)
        val focused = lastFocused.takeIf {
            SystemClock.elapsedRealtime() - lastFocusedAt < FOCUS_TTL_MS
        }

        // On the home screen a press that carries no title is left alone: the
        // detail page is about to open and will say authoritatively what was
        // picked. Falling back to a remembered card here is what made the app
        // appear stuck on one film — a stale memory got reused for every tap.
        val onDetailPage = packageName == GOOGLE_TV_PACKAGE
        val card = when {
            clicked != null && !TitleExtractor.isAffordance(clicked.title) -> clicked
            onDetailPage -> focused
            else -> null
        } ?: run {
            Log.d(TAG, "Click carried no title; leaving it to the detail page")
            DebugLog.add("click:  no title — waiting for the detail page")
            return
        }

        // The launcher can emit several events for one press.
        val now = SystemClock.elapsedRealtime()
        if (card.title == lastHandledTitle && now - lastHandledAt < DEBOUNCE_MS) return
        lastHandledTitle = card.title
        lastHandledAt = now

        val target = Prefs.getTarget(this)
        Log.d(TAG, "Opening \"${card.title}\" year=${card.year} type=${card.typeHint} -> $target")
        DebugLog.add("open:   using \"${card.title}\" → $target")
        worker.execute { handle(card, target) }
    }

    /**
     * Card strings in descending order of trustworthiness.
     *
     * The clicked node is usually a grid cell whose own label is layout
     * scaffolding ("Column 1") — the title lives in its children, on the
     * poster's content description or a caption view. So the node's subtree is
     * walked rather than just reading the node itself.
     */
    private fun candidatesOf(event: AccessibilityEvent): List<CharSequence?> {
        val candidates = ArrayList<CharSequence?>()
        val source = event.source

        source?.let { candidates.add(it.text) }
        source?.let { collectSubtree(it, 0, candidates) }
        candidates.addAll(event.text)
        source?.let { candidates.add(it.contentDescription) }
        candidates.add(event.contentDescription)

        // If the cell itself carried nothing but scaffolding, try one level up:
        // some launchers put the label on the wrapper around the cell.
        val anythingUsable = candidates.any { text ->
            val value = text?.toString()?.trim()
            !value.isNullOrEmpty() &&
                !TitleExtractor.isStructural(value) &&
                !TitleExtractor.isAffordance(value)
        }
        if (!anythingUsable) {
            source?.parent?.let { parent ->
                candidates.add(parent.text)
                candidates.add(parent.contentDescription)
                collectSubtree(parent, 1, candidates)
            }
        }

        return candidates
    }

    /** Depth- and count-limited, so a deep launcher hierarchy can't stall the UI thread. */
    private fun collectSubtree(
        node: android.view.accessibility.AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<CharSequence?>
    ) {
        if (depth >= MAX_TREE_DEPTH || out.size >= MAX_TREE_NODES) return
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            out.add(child.text)
            out.add(child.contentDescription)
            collectSubtree(child, depth + 1, out)
            if (out.size >= MAX_TREE_NODES) return
        }
    }

    private fun handle(card: CardInfo, target: TargetApp) {
        val targetName = getString(
            if (target == TargetApp.STREMIO) R.string.target_stremio else R.string.target_nuvio
        )

        // Say what's happening. Without this the TV just sits there for a
        // moment and it's impossible to tell whether anything was detected.
        // Skipped when the answer is already cached, since there's no wait.
        if (!MetaResolver.isCached(card)) {
            toast(getString(R.string.toast_looking_up, card.title))
        }

        val meta = MetaResolver.resolve(card)
        if (meta == null) {
            Log.i(TAG, "No confident match for \"${card.title}\"")
            toast(getString(R.string.toast_not_identified, card.title))
            return
        }

        Log.d(TAG, "Resolved \"${card.title}\" -> ${meta.id} (${meta.type})")
        when (val result = TargetLauncher.open(this, target, meta)) {
            is TargetLauncher.Result.Opened -> {
                // Name the match, with its year, so a wrong pick is obvious
                // immediately rather than after the wrong page loads.
                val matched = meta.name + (meta.year?.let { " ($it)" } ?: "")
                toast(getString(R.string.toast_opening, matched, targetName))
            }
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

        /**
         * How long focus must settle on a card before it's looked up. Long
         * enough that scrolling a row doesn't fire a request per card, short
         * enough that the answer is ready before a deliberate press.
         */
        private const val PREFETCH_DELAY_MS = 450L

        /** Bounds on the node-tree walk, so a deep hierarchy can't stall things. */
        private const val MAX_TREE_DEPTH = 4
        private const val MAX_TREE_NODES = 40

        /**
         * How long a remembered card stays usable. This was 60s, which meant a
         * card parsed a minute ago got reused for every later click — the app
         * appeared to be "stuck" on one film. A press follows its own focus
         * within a couple of seconds, so the window can be tight.
         */
        private const val FOCUS_TTL_MS = 6_000L

        /**
         * When to look at a newly opened window. Several attempts, because a
         * detail page's buttons and title don't all arrive in the first frame
         * — especially buy-only titles, whose purchase actions load late.
         */
        private val SCAN_DELAYS_MS = longArrayOf(700L, 1500L, 2600L)

        private const val GOOGLE_TV_PACKAGE = "com.google.android.videos"

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
