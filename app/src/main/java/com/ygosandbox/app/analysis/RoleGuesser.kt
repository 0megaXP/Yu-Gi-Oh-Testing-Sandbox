package com.ygosandbox.app.analysis

import com.ygosandbox.app.model.Card
import com.ygosandbox.app.model.Opt
import com.ygosandbox.app.model.Role

/**
 * Guesses a card's roles and once-per-turn rule from its text. The roles are rough and the
 * player is expected to correct them per deck — a card's real role depends on the deck it's in.
 */
object RoleGuesser {
    private val QUOTED_NAME = Regex("\"[^\"]*\"")

    /**
     * Hard once per turn: "You can only use this effect of "X" once per turn", "You can only
     * activate 1 "X" per turn" — limited by card name, so extra copies can't use it again.
     * Soft once per turn: "Once per turn" without that — each copy can use it.
     */
    fun opt(card: Card): Opt {
        // Blank out quoted names first, so dots in names like "A.I." don't end the sentence early.
        val t = QUOTED_NAME.replace(card.desc.lowercase(), "\"n\"")
        val hard = Regex("you can only (use|activate|special summon|normal summon|apply)[^.]*(once per turn|per turn)").containsMatchIn(t) ||
            Regex("you can only use (each|1|one)[^.]*per turn").containsMatchIn(t)
        return when {
            hard -> Opt.HARD
            "once per turn" in t -> Opt.SOFT
            else -> Opt.NONE
        }
    }

    fun guess(card: Card): Set<Role> {
        if (card.isExtraDeck) return emptySet()
        val t = card.desc.lowercase()
        val roles = LinkedHashSet<Role>()
        val isSpell = card.type.contains("Spell")
        val isTrap = card.type.contains("Trap")
        val continuous = card.race == "Continuous"

        val fromHandSelf = "discard this card" in t || "send this card from your hand" in t ||
            "reveal this card in your hand" in t || "banish this card from your hand" in t
        val onOpponentsTurn = "(quick effect)" in t || "during either player's turn" in t ||
            "during your opponent's turn" in t || "when your opponent" in t || "if your opponent" in t
        val trapFromHand = isTrap && "activate this card from your hand" in t

        // Hand traps
        if (card.isMonster && fromHandSelf && onOpponentsTurn) roles += Role.HAND_TRAP
        if (trapFromHand) roles += Role.HAND_TRAP

        // Board breakers
        val breaker = listOf(
            "destroy all", "banish all", "return all", "all face-up monsters your opponent controls",
            "to your opponent's field", "your opponent controls more", "shuffle all",
            "monsters your opponent controls", "cards your opponent controls", "your opponent currently controls",
        ).any { it in t } && ("destroy" in t || "banish" in t || "negate" in t || "shuffle" in t || "return" in t || "tribute" in t)
        if ((breaker && !continuous) || ("take control of" in t && !continuous)) roles += Role.BREAKER

        // Endboard: floodgates, continuous locks, set traps that negate or remove
        val removes = "negate" in t || "destroy" in t || "banish" in t || "return it to the hand" in t
        if ((continuous && ("cannot" in t || "neither player" in t || "negate" in t || "can only control" in t)) ||
            (card.isMonster && ("your opponent cannot" in t || "neither player can" in t) && !fromHandSelf) ||
            (isTrap && !continuous && removes && !trapFromHand)
        ) roles += Role.ENDBOARD

        if (Role.HAND_TRAP !in roles) {
            val searches = ("add 1" in t || "add up to" in t || "add it to your hand" in t) && "from your deck" in t
            val summonsFromDeck = "special summon" in t && "from your deck" in t
            val fusionFromDeck = "fusion summon" in t && "deck" in t
            val normalSummonable = !card.isMonster || "cannot be normal summoned" !in t
            val selfSummon = "special summon this card" in t || "you can special summon this card" in t

            if (searches) roles += Role.SEARCHER
            if ((summonsFromDeck || fusionFromDeck || (searches && card.isMonster)) && normalSummonable && (card.isMonster || isSpell)) {
                roles += Role.STARTER
            }
            if (Role.STARTER !in roles && card.isMonster && selfSummon) roles += Role.EXTENDER
            if (roles.isEmpty() && card.isMonster && "cannot be normal summoned" in t) roles += Role.BRICK
        }
        return roles
    }
}
