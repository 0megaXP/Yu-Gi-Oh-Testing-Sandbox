package com.ygosandbox.app.analysis

import com.ygosandbox.app.model.Card
import com.ygosandbox.app.model.Role

/**
 * Guesses a card's roles from its text. These are rough rules and the player is expected to
 * correct them per deck — a card's real role depends on the deck it's in.
 */
object RoleGuesser {
    fun guess(card: Card): Set<Role> {
        if (card.isExtraDeck) return emptySet()
        val t = card.desc.lowercase()
        val roles = LinkedHashSet<Role>()
        val isSpell = card.type.contains("Spell")
        val isTrap = card.type.contains("Trap")
        val continuous = card.race == "Continuous"
        val quickPlay = card.race == "Quick-Play"

        val fromHandSelf = "discard this card" in t || "send this card from your hand to the gy" in t ||
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
            "monsters your opponent controls", "cards your opponent controls",
        ).any { it in t } && ("destroy" in t || "banish" in t || "negate" in t || "shuffle" in t || "return" in t || "tribute" in t)
        if (breaker && !continuous) roles += Role.BREAKER

        // Interruptions (negates or removal you hold up)
        val removes = "negate" in t || "destroy" in t || "banish" in t || "return it to the hand" in t
        if ((isTrap && !continuous && removes) || (quickPlay && "negate" in t)) roles += Role.INTERRUPTION

        // Floodgates and enders
        if ((continuous && ("cannot" in t || "neither player" in t || "negate" in t)) ||
            (card.isMonster && ("your opponent cannot" in t || "neither player can" in t) && !fromHandSelf)
        ) roles += Role.ENDER

        if (roles.isEmpty()) {
            val searches = ("add 1" in t || "add up to" in t) && "from your deck" in t
            val summonsFromDeck = "special summon" in t && "from your deck" in t
            val fusionFromDeck = "fusion summon" in t && "deck" in t
            val normalSummonable = !card.isMonster || "cannot be normal summoned" !in t
            val selfSummon = "special summon this card" in t || "you can special summon this card" in t

            when {
                (searches || summonsFromDeck || fusionFromDeck) && normalSummonable && (card.isMonster || isSpell) ->
                    roles += Role.STARTER
                card.isMonster && selfSummon -> roles += Role.EXTENDER
                card.isMonster && "cannot be normal summoned" in t -> roles += Role.BRICK
            }
        }
        return roles
    }
}
