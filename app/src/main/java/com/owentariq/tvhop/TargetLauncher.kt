package com.owentariq.tvhop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Opens a resolved title in the chosen player app.
 *
 * The two apps are reached in completely different ways:
 *
 *  - **Stremio** publishes a documented `stremio://` URL scheme, so it gets a
 *    plain ACTION_VIEW on `stremio:///detail/{type}/{id}`.
 *
 *  - **Nuvio** publishes no URL scheme at all. What it does have is an
 *    exported MainActivity that reads `contentId` / `contentType` extras —
 *    the same path its own Android TV "Continue watching" channel uses to
 *    deep-link back into itself. We address that activity explicitly.
 */
object TargetLauncher {

    private const val TAG = "TargetLauncher"

    const val NUVIO_PACKAGE = "com.nuvio.tv"
    private const val NUVIO_ACTIVITY = "com.nuvio.tv.MainActivity"

    sealed class Result {
        object Opened : Result()
        object TargetNotInstalled : Result()
        data class Failed(val error: String) : Result()
    }

    fun open(context: Context, target: TargetApp, meta: Meta): Result {
        val intent = when (target) {
            TargetApp.NUVIO -> nuvioIntent(meta)
            TargetApp.STREMIO -> stremioIntent(context, meta)
        }

        if (intent.resolveActivity(context.packageManager) == null) {
            Log.w(TAG, "Nothing on the device can handle $target for ${meta.id}")
            return Result.TargetNotInstalled
        }

        return try {
            context.startActivity(intent)
            Result.Opened
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open $target", e)
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun nuvioIntent(meta: Meta): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(NUVIO_PACKAGE, NUVIO_ACTIVITY)
            putExtra("contentId", meta.id)
            putExtra("contentType", normalizeType(meta.type))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    private fun stremioIntent(context: Context, meta: Meta): Intent {
        val type = normalizeType(meta.type)
        // For a movie the video id equals the meta id, and supplying it makes
        // Stremio open the detail page with the stream list already up. For a
        // series we leave it off so the user lands on the episode list.
        val path = if (type == "movie") {
            "stremio:///detail/movie/${meta.id}/${meta.id}"
        } else {
            "stremio:///detail/$type/${meta.id}"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(path))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // If exactly one installed app claims the scheme, address it directly
        // so the TV never shows a disambiguation dialog.
        val handlers = context.packageManager.queryIntentActivities(intent, 0)
        if (handlers.size == 1) {
            intent.setPackage(handlers.first().activityInfo.packageName)
        }
        return intent
    }

    private fun normalizeType(type: String): String =
        when (type.lowercase()) {
            "series", "tv", "show" -> "series"
            else -> "movie"
        }

    fun isInstalled(context: Context, packageName: String): Boolean =
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
}
