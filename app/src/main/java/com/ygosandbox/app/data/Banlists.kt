package com.ygosandbox.app.data

import com.ygosandbox.app.model.GameFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dates when a new Forbidden & Limited list took effect. The built-in dates can be extended in
 * Settings when Konami publishes a new list before the app is updated.
 */
object Banlists {
    private val BUILT_IN_TCG = listOf(
        "2026-02-02", "2026-05-18", "2026-09-21", "2026-10-19",
    )
    private val BUILT_IN_OCG = listOf(
        "2026-01-01", "2026-04-01", "2026-07-01", "2026-10-01",
    )

    fun builtIn(format: GameFormat): List<LocalDate> =
        (if (format == GameFormat.TCG) BUILT_IN_TCG else BUILT_IN_OCG).map(LocalDate::parse)

    /** All known list dates for a format, oldest first, ignoring dates in the future. */
    fun dates(format: GameFormat, custom: List<String>, today: LocalDate = LocalDate.now()): List<LocalDate> =
        (builtIn(format) + custom.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() })
            .filter { !it.isAfter(today) }
            .distinct()
            .sorted()

    fun latest(format: GameFormat, custom: List<String>, today: LocalDate = LocalDate.now()): LocalDate? =
        dates(format, custom, today).lastOrNull()

    private val pretty = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    fun pretty(d: LocalDate): String = d.format(pretty)

    /** Names the banlist period a deck date falls in. */
    fun periodOf(date: LocalDate?, all: List<LocalDate>): Pair<String, Boolean> {
        if (date == null) return "Unknown date" to false
        val latest = all.lastOrNull() ?: return "Current format" to true
        if (!date.isBefore(latest)) return "Current format" to true
        val start = all.lastOrNull { !it.isAfter(date) }
        return if (start == null) "Older format (before ${pretty(all.first())})" to false
        else "Older format (${pretty(start)} list)" to false
    }
}
