package com.ygosandbox.app.model

/** One card from the YGOPRODeck database, trimmed to what the app shows. */
data class Card(
    val id: Long,
    val name: String,
    val type: String,          // e.g. "Effect Monster", "Spell Card"
    val frameType: String,     // e.g. "effect", "fusion", "spell"
    val desc: String,
    val race: String,          // monster type, or spell/trap property ("Quick-Play")
    val attribute: String,
    val atk: Int?,
    val def: Int?,
    val level: Int?,           // level, rank (xyz) or link rating
    val scale: Int?,
    val archetype: String,
    val banTcg: String,        // "", "Forbidden", "Limited", "Semi-Limited"
    val banOcg: String,
    /** Every passcode this card is known by (alternate artworks have their own). */
    val altIds: List<Long>,
) {
    val isExtraDeck: Boolean
        get() = frameType.startsWith("fusion") || frameType.startsWith("synchro") ||
            frameType.startsWith("xyz") || frameType.startsWith("link")
    val isMonster: Boolean get() = type.contains("Monster")
    val isLink: Boolean get() = frameType.startsWith("link")
    val isXyz: Boolean get() = frameType.startsWith("xyz")

    /** Sorting group: Main Deck monsters, Spells, Traps, then Extra Deck by summon type. */
    val group: CardGroup
        get() = when {
            frameType.startsWith("fusion") -> CardGroup.FUSION
            frameType.startsWith("synchro") -> CardGroup.SYNCHRO
            frameType.startsWith("xyz") -> CardGroup.XYZ
            frameType.startsWith("link") -> CardGroup.LINK
            frameType == "spell" || type.contains("Spell") -> CardGroup.SPELL
            frameType == "trap" || type.contains("Trap") -> CardGroup.TRAP
            else -> CardGroup.MONSTER
        }

    fun imageUrl(small: Boolean = false) =
        if (small) "https://images.ygoprodeck.com/images/cards_small/$id.jpg"
        else "https://images.ygoprodeck.com/images/cards/$id.jpg"
}

enum class CardGroup(val label: String) {
    MONSTER("Monsters"), SPELL("Spells"), TRAP("Traps"),
    FUSION("Fusion"), SYNCHRO("Synchro"), XYZ("Xyz"), LINK("Link"), UNKNOWN("Other"),
}

enum class GameFormat(val label: String) { TCG("TCG"), OCG("OCG") }

enum class TimeSpan(val label: String, val help: String) {
    RECENT("Recent", "Last 2 weeks (or since the last banlist, if that is more recent)"),
    THIS_BANLIST("This banlist", "Since the last Forbidden & Limited list"),
    ALL_TIME("All time", "No date limit; older formats are marked"),
}

/** Where a card is played: the four sub-categories of a deck analysis. */
enum class Usage(val label: String, val help: String) {
    MAIN("Main", "In the Main or Extra Deck of most lists that play it"),
    SIDE("Side", "In the Side Deck of most lists that play it"),
    MIX("Mix", "Commonly played, but some lists main it and others side it"),
    NICHE("Niche", "Played in only a few lists"),
}

/** How one card shows up across the analysed decks. */
data class CardStat(
    val cardId: Long,
    val name: String,
    val engine: Boolean,
    val usage: Usage,
    /** Share of analysed decks with the card in Main or Extra Deck (0..1). */
    val mainRate: Float,
    /** Share of analysed decks with the card in the Side Deck (0..1). */
    val sideRate: Float,
    /** Share of analysed decks with the card in both Main/Extra and Side (0..1). */
    val bothRate: Float = 0f,
    /** Share of analysed decks that play the card anywhere (0..1). */
    val anyRate: Float,
    /** Average copies in Main/Extra among decks that main it. */
    val avgMain: Float,
    /** Average copies in Side among decks that side it. */
    val avgSide: Float,
    val extraDeck: Boolean,
)

/** A tournament deck that went into the analysis. */
data class SourceDeck(
    val name: String,
    val date: String,         // yyyy-MM-dd, or "" when unknown
    val tournament: String,
    val placement: String,
    val url: String,
    /** Which banlist period the deck belongs to, e.g. "Current format" or "Format of 2 Feb 2026". */
    val formatLabel: String,
    val currentFormat: Boolean,
    /** The deck's card list, downloaded with the search (one entry per copy). Empty for old saves. */
    val main: List<Long> = emptyList(),
    val extra: List<Long> = emptyList(),
    val side: List<Long> = emptyList(),
) {
    val hasList: Boolean get() = main.isNotEmpty() || extra.isNotEmpty()
}

/** A saved search result in the Decks list. */
data class DeckAnalysis(
    val id: String,
    val archetypes: List<String>,
    /** Archetypes the decks must not play. */
    val excluded: List<String> = emptyList(),
    val format: GameFormat,
    val span: TimeSpan,
    val createdAt: Long,
    /** Earliest deck date included (yyyy-MM-dd), "" for all time. */
    val since: String,
    val decksScanned: Int,
    val cards: List<CardStat>,
    val decks: List<SourceDeck>,
) {
    val title: String get() = archetypes.joinToString(" + ")
    val excludedLabel: String get() = if (excluded.isEmpty()) "" else "without " + excluded.joinToString(", ")
}

// ---------------- User decks ----------------

enum class DeckPart(val label: String, val min: Int, val max: Int) {
    MAIN("Main Deck", 40, 60), EXTRA("Extra Deck", 0, 15), SIDE("Side Deck", 0, 15),
}

/** A deck the user builds and saves. Card lists hold one entry per copy. */
data class SavedDeck(
    val id: String,
    val name: String,
    val format: GameFormat,
    val main: List<Long>,
    val extra: List<Long>,
    val side: List<Long>,
    val createdAt: Long,
    val updatedAt: Long,
    /** Roles set by hand for the Analysis feature; cards not listed use the automatic guess. */
    val roles: Map<Long, Set<Role>> = emptyMap(),
) {
    fun part(p: DeckPart): List<Long> = when (p) {
        DeckPart.MAIN -> main
        DeckPart.EXTRA -> extra
        DeckPart.SIDE -> side
    }

    fun withPart(p: DeckPart, cards: List<Long>): SavedDeck = when (p) {
        DeckPart.MAIN -> copy(main = cards)
        DeckPart.EXTRA -> copy(extra = cards)
        DeckPart.SIDE -> copy(side = cards)
    }

    /** Copies of a card across Main, Extra and Side. */
    fun copiesOf(id: Long): Int = main.count { it == id } + extra.count { it == id } + side.count { it == id }
}

/** What a card does for a deck, used by the Analysis feature. A card can have several. */
enum class Role(val label: String, val short: String) {
    STARTER("Starter", "Starts a combo on its own"),
    SEARCHER("Searcher", "Adds a card from the Deck to the hand"),
    EXTENDER("Extender", "Adds to a combo, needs a starter"),
    HAND_TRAP("Hand trap", "Disrupts the opponent from the hand"),
    ENDBOARD("Endboard", "Stays on the field after your combo: floodgates, set traps"),
    BREAKER("Board breaker", "Clears the opponent's board going second"),
    BRICK("Brick", "Bad to draw");

    companion object {
        /** Reads saved names, including ones from older versions. */
        fun parse(name: String): Role? = when (name) {
            "ENDER", "INTERRUPTION" -> ENDBOARD
            else -> entries.firstOrNull { it.name == name }
        }
    }
}

/** How often a card's effects can be used. */
enum class Opt(val label: String, val short: String) {
    HARD("Hard once per turn", "Hard OPT"),
    SOFT("Soft once per turn", "Soft OPT"),
    NONE("No once per turn", "No OPT"),
}

/** Highest number of copies the banlist allows. */
fun banLimit(status: String): Int = when (status.lowercase()) {
    "forbidden", "banned" -> 0
    "limited" -> 1
    "semi-limited" -> 2
    else -> 3
}
