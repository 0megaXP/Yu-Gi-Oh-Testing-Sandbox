package com.ygosandbox.app.analysis

import com.ygosandbox.app.data.CardDatabase
import com.ygosandbox.app.model.Card
import com.ygosandbox.app.model.Opt
import com.ygosandbox.app.model.Role
import com.ygosandbox.app.model.SavedDeck

/** One distinct Main Deck card with what the analysis knows about it. */
data class CardInfo(
    val cardId: Long,
    val card: Card?,
    val copies: Int,
    val roles: Set<Role>,
    /** True when the roles were set by hand rather than guessed. */
    val rolesByHand: Boolean,
    val opt: Opt,
)

/** Chances for k = 1..5 cards of a kind in the opening hand. */
data class OddsRow(
    val k: Int,
    val exactly: Double,
    val atLeast: Double,
    /** At least k, counting several copies of the same hard once-per-turn card as one. */
    val atLeastUsable: Double,
)

data class OddsTable(val cardsInDeck: Int, val rows: List<OddsRow>)

/** Exact opening-hand odds (hypergeometric), no simulation. */
object Odds {

    fun cards(deck: SavedDeck, db: CardDatabase): List<CardInfo> =
        deck.main.groupingBy { it }.eachCount().map { (id, n) ->
            val card = db.find(id)
            val manual = deck.roles[id]
            CardInfo(
                cardId = id, card = card, copies = n,
                roles = manual ?: card?.let(RoleGuesser::guess).orEmpty(),
                rolesByHand = manual != null,
                opt = card?.let(RoleGuesser::opt) ?: Opt.NONE,
            )
        }

    /**
     * Odds of drawing 1..5 cards that have any of [roles], in a hand of [handSize] from a deck of
     * [deckSize] cards.
     */
    fun table(cards: List<CardInfo>, roles: Set<Role>, deckSize: Int, handSize: Int): OddsTable {
        val groups = cards.filter { c -> c.roles.any { it in roles } }
        val k = groups.sumOf { it.copies }
        val dist = distribution(groups, deckSize, handSize, usable = false)
        val usable = distribution(groups, deckSize, handSize, usable = true)
        val rows = (1..5).map { n ->
            OddsRow(
                k = n,
                exactly = dist.getOrElse(n) { 0.0 },
                atLeast = dist.drop(n).sum(),
                atLeastUsable = usable.drop(n).sum(),
            )
        }
        return OddsTable(k, rows)
    }

    /**
     * Probability of each count 0..handSize. With [usable], a hard once-per-turn card counts at
     * most once however many copies are drawn. Works card by card: for every way to draw x copies
     * of each matching card, the rest of the hand comes from the other cards.
     */
    private fun distribution(groups: List<CardInfo>, deckSize: Int, handSize: Int, usable: Boolean): List<Double> {
        val result = DoubleArray(handSize + 1)
        if (deckSize < handSize || handSize <= 0) return result.toList()
        val matching = groups.sumOf { it.copies }
        val others = deckSize - matching
        // ways[j][c] = number of ways to pick j matching cards that count as c
        var ways = Array(handSize + 1) { DoubleArray(handSize + 1) }
        ways[0][0] = 1.0
        for (g in groups) {
            val next = Array(handSize + 1) { DoubleArray(handSize + 1) }
            for (j in 0..handSize) for (c in 0..handSize) {
                val w = ways[j][c]
                if (w == 0.0) continue
                for (x in 0..minOf(g.copies, handSize - j)) {
                    val counts = if (usable && g.opt == Opt.HARD) minOf(x, 1) else x
                    next[j + x][c + counts] += w * choose(g.copies, x)
                }
            }
            ways = next
        }
        val total = choose(deckSize, handSize)
        for (j in 0..handSize) for (c in 0..handSize) {
            val w = ways[j][c]
            if (w != 0.0) result[c] += w * choose(others, handSize - j) / total
        }
        return result.toList()
    }

    private fun choose(n: Int, k: Int): Double {
        if (k < 0 || k > n) return 0.0
        var r = 1.0
        for (i in 1..minOf(k, n - k)) r = r * (n - minOf(k, n - k) + i) / i
        return r
    }
}
