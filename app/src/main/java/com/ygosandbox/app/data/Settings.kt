package com.ygosandbox.app.data

import android.content.Context

/** Small key-value settings. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Read-only GitHub token so the updater can see releases of the private repository. */
    var githubToken: String
        get() = prefs.getString("github_token", "") ?: ""
        set(v) = prefs.edit().putString("github_token", v.trim()).apply()

    /** Deck lists as a grid of card pictures (default) or as a list with copy counts. */
    var deckGrid: Boolean
        get() = prefs.getBoolean("deck_grid", true)
        set(v) = prefs.edit().putBoolean("deck_grid", v).apply()

    var cardDbVersion: String
        get() = prefs.getString("card_db_version", "") ?: ""
        set(v) = prefs.edit().putString("card_db_version", v).apply()

    var cardDbCheckedAt: Long
        get() = prefs.getLong("card_db_checked", 0)
        set(v) = prefs.edit().putLong("card_db_checked", v).apply()

    var cardDbDownloadedAt: Long
        get() = prefs.getLong("card_db_downloaded", 0)
        set(v) = prefs.edit().putLong("card_db_downloaded", v).apply()

    /** Extra banlist dates (yyyy-MM-dd) added by hand, per format. */
    fun customBanlists(format: String): List<String> =
        (prefs.getString("banlists_$format", "") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun setCustomBanlists(format: String, dates: List<String>) =
        prefs.edit().putString("banlists_$format", dates.distinct().sorted().joinToString(",")).apply()
}
