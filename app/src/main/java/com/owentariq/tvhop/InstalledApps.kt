package com.owentariq.tvhop

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * The names of apps installed on this device.
 *
 * Used as a veto: "Netflix", "Plex" and "Tubi" are things you launch, not
 * things to look up on a metadata service. Several of them also happen to
 * match real titles, so without this a press on an app tile could send you to
 * a film instead of the app.
 */
object InstalledApps {

    private const val TAG = "InstalledApps"
    private const val TTL_MS = 10 * 60 * 1000L

    @Volatile private var labels: Set<String> = emptySet()
    @Volatile private var loadedAt = 0L

    fun isAppName(context: Context, value: String): Boolean {
        val needle = value.trim().lowercase()
        if (needle.isEmpty()) return false
        return needle in labels(context)
    }

    @Synchronized
    private fun labels(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (labels.isNotEmpty() && now - loadedAt < TTL_MS) return labels

        val packageManager = context.packageManager
        val found = HashSet<String>()

        for (category in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            try {
                for (resolved in packageManager.queryIntentActivities(intent, 0)) {
                    val label = resolved.loadLabel(packageManager)?.toString()?.trim()?.lowercase()
                    if (!label.isNullOrEmpty()) found.add(label)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not list apps for $category", e)
            }
        }

        labels = found
        loadedAt = now
        Log.d(TAG, "Cached ${found.size} app names")
        return found
    }
}
