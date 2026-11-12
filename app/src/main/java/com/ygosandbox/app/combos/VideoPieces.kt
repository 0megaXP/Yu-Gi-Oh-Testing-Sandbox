package com.ygosandbox.app.combos

import com.ygoengine.EngineDeck
import com.ygoengine.VideoLineInput
import com.ygoengine.VideoStepInput
import com.ygosandbox.app.data.CardDatabase
import com.ygosandbox.app.model.DeckAnalysis
import org.json.JSONArray

/**
 * Between video combos and the engine: which tournament list a line is replayed with, the
 * engine's input for a line, and the verified lines' combo pieces for a deck.
 */
object VideoPieces {

    /** The tournament list in [deck] that plays the most of the line's cards (null if none has a list). */
    fun deckFor(deck: DeckAnalysis, combo: VideoCombo, db: CardDatabase): EngineDeck? {
        fun canon(id: Long) = db.find(id)?.id ?: id
        val used = (combo.hand + combo.steps.mapNotNull { it.code } + combo.endBoard.map { it.code }).map(::canon).toSet()
        val best = deck.decks.filter { it.hasList }.maxByOrNull { d ->
            (d.main + d.extra + d.side).map(::canon).toSet().count { it in used }
        } ?: return null
        val all = (best.main + best.extra + best.side).distinct()
        return EngineDeck(
            name = best.name.ifBlank { deck.title },
            main = best.main, extra = best.extra, side = best.side,
            names = all.mapNotNull { id -> db.find(id)?.name?.let { id to it } }.toMap(),
        )
    }

    fun input(v: VideoCombo) = VideoLineInput(
        id = v.url,
        hand = v.hand,
        steps = v.steps.map { VideoStepInput(it.action, it.code ?: 0L, it.detail) },
        endBoard = v.fieldCodes,
    )

    /**
     * The combo pieces of every verified video line (and the played start of partly verified ones) that [cards] (a deck's Main + Extra Deck)
     * can play: each piece's starting-hand cards must all be in the deck. Null if there are none.
     */
    fun libraryFor(store: ComboStore, cards: Set<Long>, canonical: (Long) -> Long): String? {
        val deck = cards.map(canonical).toSet()
        val out = JSONArray()
        store.all().flatMap { it.videos }.mapNotNull { it.check?.library }.forEach { text ->
            val arr = runCatching { JSONArray(text) }.getOrNull() ?: return@forEach
            for (i in 0 until arr.length()) {
                val piece = arr.getJSONObject(i)
                val needs = piece.optJSONArray("needs_hand") ?: JSONArray()
                val playable = (0 until needs.length()).all { k -> canonical(needs.getJSONObject(k).optLong("code")) in deck }
                if (playable) out.put(piece)
            }
        }
        return if (out.length() == 0) null else out.toString()
    }

    /** Two piece libraries (JSON arrays) as one; either may be null. */
    fun merge(a: String?, b: String?): String? {
        if (a == null) return b
        if (b == null) return a
        val out = JSONArray(a)
        val more = JSONArray(b)
        for (i in 0 until more.length()) out.put(more.get(i))
        return out.toString()
    }
}
