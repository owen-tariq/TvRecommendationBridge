package com.owentariq.tvhop

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small in-app ring buffer of what the service actually saw.
 *
 * This exists so diagnosing a bad match doesn't require ADB. The setup screen
 * renders these lines, so the user can read them straight off the TV.
 */
object DebugLog {

    private const val CAPACITY = 40
    private val entries = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(line: String) {
        entries.addLast("${stamp.format(Date())}  $line")
        while (entries.size > CAPACITY) entries.removeFirst()
    }

    /** Newest first, for display. */
    @Synchronized
    fun recent(limit: Int = CAPACITY): List<String> =
        entries.toList().asReversed().take(limit)

    @Synchronized
    fun clear() = entries.clear()
}
