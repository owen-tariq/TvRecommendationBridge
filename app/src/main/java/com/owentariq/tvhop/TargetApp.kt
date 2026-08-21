package com.owentariq.tvhop

import android.content.Context

/** The player app a recommendation should be handed off to. */
enum class TargetApp(val id: String) {
    NUVIO("nuvio"),
    STREMIO("stremio");

    companion object {
        fun fromId(value: String?): TargetApp =
            entries.firstOrNull { it.id == value } ?: NUVIO
    }
}

object Prefs {

    private const val FILE = "tvhop_prefs"
    private const val KEY_TARGET = "target_app"

    fun getTarget(context: Context): TargetApp =
        TargetApp.fromId(prefs(context).getString(KEY_TARGET, null))

    fun setTarget(context: Context, target: TargetApp) {
        prefs(context).edit().putString(KEY_TARGET, target.id).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
