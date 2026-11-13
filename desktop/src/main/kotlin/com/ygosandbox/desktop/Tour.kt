package com.ygosandbox.desktop

import androidx.compose.ui.awt.ComposeWindow
import com.ygosandbox.app.AppViewModel
import com.ygosandbox.app.DbStatus
import com.ygosandbox.app.Screen
import com.ygosandbox.app.model.GameFormat
import com.ygosandbox.app.model.SourceDeck
import com.ygosandbox.app.play.DuelMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO

/**
 * Walks through the main screens and saves a screenshot of each (used on CI to check the desktop
 * layout). Decks come from the .ydk files in YGO_TOUR_DECKS.
 */
class Tour(private val vm: AppViewModel, private val window: ComposeWindow, private val out: File) {
    private val robot = Robot()

    suspend fun run() {
        out.mkdirs()
        withTimeoutOrNull(240_000) { vm.ui.first { it.dbStatus == DbStatus.READY } }
        delay(1500)
        shot("01_menu")

        val ydks = System.getenv("YGO_TOUR_DECKS")?.let(::File)?.listFiles { f -> f.name.endsWith(".ydk") }?.sorted().orEmpty()
        ydks.forEach { f -> vm.importDeck(parseYdk(f), GameFormat.TCG) }
        delay(500)
        val decks = vm.ui.value.myDecks
        vm.open(Screen.MyDecks); shot("02_my_decks", 4000)
        decks.firstOrNull()?.let { d -> vm.open(Screen.DeckEditor(d.id)); shot("03_deck_editor", 9000); vm.back() }
        vm.back()
        vm.open(Screen.Cards); shot("04_cards", 5000); vm.back()
        vm.open(Screen.Analysis); shot("05_analysis"); 
        decks.firstOrNull()?.let { d -> vm.open(Screen.AnalysisDeck(d.id)); shot("06_analysis_deck", 6000); vm.back() }
        vm.back()
        vm.open(Screen.Play); shot("07_play"); vm.back()
        vm.open(Screen.Settings); shot("08_settings"); vm.back()

        vm.open(Screen.Duel); shot("09_duel_setup")
        if (decks.size >= 2) {
            vm.updateDuelSetup {
                it.copy(mode = DuelMode.BOT_VS_BOT, deck1 = "my:${decks[0].id}", deck2 = "my:${decks[1].id}", delayMs = 300)
            }
            vm.startDuel()
            shot("10_duel_a", 25_000)
            shot("11_duel_b", 25_000)
            vm.pauseDuel(true)
            shot("12_duel_paused", 3000)
            vm.stopDuel()
        }
    }

    private suspend fun shot(name: String, wait: Long = 2500) {
        delay(wait)
        val b = window.bounds
        val img = robot.createScreenCapture(Rectangle(b.x, b.y, b.width, b.height))
        ImageIO.write(img, "png", File(out, "$name.png"))
        println("tour: $name")
    }

    private fun parseYdk(f: File): SourceDeck {
        val parts = mutableMapOf("main" to mutableListOf<Long>(), "extra" to mutableListOf(), "side" to mutableListOf())
        var part = "main"
        f.readLines().forEach { raw ->
            val l = raw.trim()
            when {
                l == "#main" -> part = "main"
                l == "#extra" -> part = "extra"
                l == "!side" -> part = "side"
                else -> l.toLongOrNull()?.let { parts.getValue(part).add(it) }
            }
        }
        return SourceDeck(
            f.nameWithoutExtension, "", "", "", "", "", true,
            parts.getValue("main"), parts.getValue("extra"), parts.getValue("side"),
        )
    }
}
