package com.ygosandbox.app.analysis

import com.ygosandbox.app.data.CardDatabase
import com.ygosandbox.app.model.Card
import com.ygosandbox.app.model.Role
import com.ygosandbox.app.model.SavedDeck
import kotlin.random.Random

/** Going first draws 5 cards; going second draws 6. */
enum class Turn(val label: String, val handSize: Int) { FIRST("Going first", 5), SECOND("Going second", 6) }

data class CardScore(
    val cardId: Long,
    val card: Card?,
    val copies: Int,
    val roles: Set<Role>,
    /** True when the roles were set by hand rather than guessed. */
    val rolesByHand: Boolean,
    /** 0..100, 50 = neutral. */
    val score: Map<Turn, Int>,
    /** How much drawing the card changes the average hand quality (−1..1). */
    val impact: Map<Turn, Float>,
    val reasons: List<String>,
)

data class TurnStats(
    /** Hands with a starter, or two extenders. */
    val playable: Float,
    /** Hands with two starters, or a starter and an extender. */
    val strong: Float,
    /** Hands with at least one hand trap or interruption. */
    val interaction: Float,
    /** Hands with at least one board breaker (matters going second). */
    val breaker: Float,
    /** Hands with two or more bricks. */
    val bricked: Float,
    /** Average hand quality, 0..1. */
    val quality: Float,
)

data class AnalysisReport(
    val mainCount: Int,
    val stats: Map<Turn, TurnStats>,
    val cards: List<CardScore>,
    val extraDeck: List<Card>,
    val hands: Int,
)

/**
 * Rates cards inside a deck by simulating opening hands. A card's score is how much better (or
 * worse) the average hand is when that card is in it, so the same card can rate differently in
 * different decks and going first or second.
 */
object DeckAnalyzer {
    const val HANDS = 20_000

    fun rolesOf(deck: SavedDeck, card: Card?, id: Long): Pair<Set<Role>, Boolean> {
        deck.roles[id]?.let { return it to true }
        return (card?.let(RoleGuesser::guess) ?: emptySet()) to false
    }

    fun analyze(deck: SavedDeck, db: CardDatabase): AnalysisReport {
        val main = deck.main
        val distinct = main.distinct()
        val roleOf = distinct.associateWith { id -> rolesOf(deck, db.find(id), id) }
        val index = distinct.withIndex().associate { (i, id) -> id to i }
        val deckIdx = main.map { index.getValue(it) }.toIntArray()
        val roleArr = distinct.map { roleOf.getValue(it).first }

        val stats = HashMap<Turn, TurnStats>()
        val impacts = HashMap<Turn, FloatArray>()
        val rnd = Random(42) // fixed seed: the same deck always gives the same numbers

        for (turn in Turn.entries) {
            val n = distinct.size
            val withSum = DoubleArray(n); val withCount = IntArray(n)
            var total = 0.0
            var playable = 0; var strong = 0; var interaction = 0; var breaker = 0; var bricked = 0
            val hands = if (main.size >= turn.handSize) HANDS else 0
            val pool = deckIdx.copyOf()
            val inHand = BooleanArray(n)
            repeat(hands) {
                // Partial Fisher–Yates: the first handSize entries become the hand.
                for (i in 0 until turn.handSize) {
                    val j = i + rnd.nextInt(pool.size - i)
                    val tmp = pool[i]; pool[i] = pool[j]; pool[j] = tmp
                }
                var s = 0; var e = 0; var h = 0; var it = 0; var b = 0; var f = 0; var k = 0
                java.util.Arrays.fill(inHand, false)
                for (i in 0 until turn.handSize) {
                    val c = pool[i]
                    inHand[c] = true
                    val r = roleArr[c]
                    if (Role.STARTER in r) s++
                    if (Role.EXTENDER in r) e++
                    if (Role.HAND_TRAP in r) h++
                    if (Role.INTERRUPTION in r) it++
                    if (Role.BREAKER in r) b++
                    if (Role.ENDER in r) f++
                    if (Role.BRICK in r) k++
                }
                val q = quality(turn, s, e, h, it, b, f, k)
                total += q
                if (s >= 1 || e >= 2) playable++
                if (s >= 2 || (s >= 1 && e >= 1)) strong++
                if (h + it >= 1) interaction++
                if (b >= 1) breaker++
                if (k >= 2) bricked++
                for (c in 0 until n) if (inHand[c]) { withSum[c] += q; withCount[c]++ }
            }
            val hn = hands.coerceAtLeast(1).toFloat()
            stats[turn] = TurnStats(
                playable / hn, strong / hn, interaction / hn, breaker / hn, bricked / hn, (total / hn).toFloat(),
            )
            impacts[turn] = FloatArray(n) { c ->
                val wc = withCount[c]
                val oc = hands - wc
                if (wc == 0 || oc == 0) 0f
                else ((withSum[c] / wc) - ((total - withSum[c]) / oc)).toFloat()
            }
        }

        val inDeck = (deck.main + deck.extra + deck.side).map { db.find(it)?.id ?: it }.toSet()
        val cards = distinct.mapIndexed { c, id ->
            val card = db.find(id)
            val (roles, byHand) = roleOf.getValue(id)
            val reasons = ArrayList<String>()
            var synergy = 0
            if (card != null) {
                val named = db.namedCards(card)
                val have = named.filter { it.id in inDeck && it.id != card.id }
                val missing = named.filter { it.id !in inDeck && it.id != card.id }
                if (have.isNotEmpty()) {
                    reasons += "Works with " + have.take(2).joinToString(", ") { it.name }
                    synergy += 4
                }
                if (missing.isNotEmpty() && have.isEmpty()) {
                    reasons += "Needs " + missing.take(2).joinToString(", ") { it.name } + " (not in deck)"
                    synergy -= 8
                }
            }
            val score = Turn.entries.associateWith { t ->
                (50 + impacts.getValue(t)[c] * 160 + synergy).toInt().coerceIn(0, 100)
            }
            val i1 = impacts.getValue(Turn.FIRST)[c]
            val i2 = impacts.getValue(Turn.SECOND)[c]
            when {
                i2 - i1 > 0.06 -> reasons.add(0, "Better going second")
                i1 - i2 > 0.06 -> reasons.add(0, "Better going first")
            }
            if (i1 < -0.03 && i2 < -0.03) reasons.add(0, "Makes hands worse when drawn")
            if (roles.isEmpty()) reasons += "No role: tap to set one"
            CardScore(
                cardId = id, card = card, copies = main.count { it == id }, roles = roles, rolesByHand = byHand,
                score = score, impact = mapOf(Turn.FIRST to i1, Turn.SECOND to i2), reasons = reasons,
            )
        }
        return AnalysisReport(
            mainCount = main.size,
            stats = stats,
            cards = cards,
            extraDeck = deck.extra.distinct().mapNotNull { db.find(it) },
            hands = if (main.size >= Turn.FIRST.handSize) HANDS else 0,
        )
    }

    /**
     * How good an opening hand is, 0..1. Going first values a combo and something to hold up on
     * the opponent's turn; going second values hand traps and board breakers more, and floodgates
     * and set traps barely at all.
     */
    private fun quality(t: Turn, s: Int, e: Int, h: Int, i: Int, b: Int, f: Int, k: Int): Float {
        val combo = if (s >= 1 || e >= 2) 1f else 0f
        val strong = if (s >= 2 || (s >= 1 && e >= 1)) 1f else 0f
        val q = when (t) {
            Turn.FIRST -> 0.55f * combo + 0.15f * strong + 0.08f * minOf(h + i, 2) +
                0.10f * minOf(f, 1) - 0.10f * k
            Turn.SECOND -> 0.35f * combo + 0.08f * strong + 0.12f * minOf(h, 3) + 0.17f * minOf(b, 2) +
                0.03f * minOf(i, 1) - 0.10f * k
        }
        return q.coerceIn(0f, 1f)
    }
}
