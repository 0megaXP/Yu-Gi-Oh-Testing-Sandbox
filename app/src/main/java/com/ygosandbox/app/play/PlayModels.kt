package com.ygosandbox.app.play

import com.ygoengine.AnalysisResult
import com.ygoengine.EngineDeck
import com.ygoengine.Progress
import com.ygoengine.SideResult
import com.ygosandbox.app.model.DeckAnalysis
import com.ygosandbox.app.model.SavedDeck

/** A deck the engine can use: one of My decks or a tournament list saved by an archetype search. */
data class DeckChoice(
    val key: String,
    val name: String,
    val detail: String,
    val main: List<Long>,
    val extra: List<Long>,
    val side: List<Long>,
) {
    fun toEngine() = EngineDeck(name = name, main = main, extra = extra, side = side)
}

fun deckChoices(myDecks: List<SavedDeck>, searches: List<DeckAnalysis>): List<DeckChoice> = buildList {
    myDecks.filter { it.main.isNotEmpty() }.forEach { d ->
        add(DeckChoice("my:${d.id}", d.name, "My deck · Main ${d.main.size} · Side ${d.side.size}", d.main, d.extra, d.side))
    }
    searches.forEach { s ->
        s.decks.forEachIndexed { i, d ->
            if (!d.hasList) return@forEachIndexed
            val where = listOf(d.placement, d.tournament).filter { it.isNotBlank() }.joinToString(" · ")
            add(
                DeckChoice(
                    "src:${s.id}:$i", d.name.ifBlank { s.title },
                    listOf(s.title, where, d.date).filter { it.isNotBlank() }.joinToString(" · "),
                    d.main, d.extra, d.side,
                ),
            )
        }
    }
}

enum class Depth(val label: String, val iterations: Int, val verifyIterations: Int, val hint: String) {
    QUICK("Quick", 800, 150, "about 10 s"),
    NORMAL("Normal", 2000, 300, "about 30 s"),
    DEEP("Deep", 5000, 500, "1–2 min"),
}

/** What the user set up on the Play engine screen. Kept in the ViewModel so it survives navigation. */
data class PlaySetup(
    val myDeck: String? = null,
    val opponentDeck: String? = null,
    val goingFirst: Boolean = true,
    /** Real play: the engine knows only your hand. Practice: it also sees the opponent's. */
    val real: Boolean = false,
    val myHand: List<Long> = emptyList(),
    /** Practice only; empty = a different random hand for every sample. */
    val opponentHand: List<Long> = emptyList(),
    val depth: Depth = Depth.NORMAL,
)

data class PlayState(
    val setup: PlaySetup = PlaySetup(),
    /** Non-null while the engine works. */
    val progress: Progress? = null,
    /** "Preparing the engine…" on first use (it copies the card scripts once). */
    val preparing: Boolean = false,
    val result: AnalysisResult? = null,
    /** The setup the result belongs to. */
    val resultSetup: PlaySetup? = null,
    val error: String? = null,
    // Side plan
    val sideDeck: String? = null,
    val sideOpponent: String? = null,
    val sideSimulate: Boolean = false,
    val sideProgress: Progress? = null,
    val sideResult: SideResult? = null,
    val sideError: String? = null,
)

const val HAND_SIZE = 5
