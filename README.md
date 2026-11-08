# YGO Sandbox

An Android app for testing and learning Yu-Gi-Oh! decks from real tournament results.

## Features

- **Card database**: the full card list from the [YGOPRODeck API](https://ygoprodeck.com/api-guide/),
  downloaded once, kept on the phone, and refreshed automatically when YGOPRODeck publishes a new
  version (checked once a day). Browse by name or by archetype.
- **Archetype detection**, in three steps:
  1. YGOPRODeck tags the card with the archetype, the archetype is a whole word in its name, or its
     text cites the archetype in quotes (`add 1 "Branded" card`);
  2. a card named in the text of at least 2 cards from step 1 (many Branded cards name
     "Fallen of Albaz", so Albaz is Branded);
  3. a card whose text names a card from step 1 or 2 (Ecclesia names "Fallen of Albaz", so it's
     Branded too).

  Decks are matched only on step 1 cards, so a shared card like "Fallen of Albaz" can't make a
  Sky Striker list look like Despia. Steps 2 and 3 decide which played cards count as engine.
- **My decks**: create, rename, edit and delete your own decks. Add cards by name or archetype;
  Extra Deck monsters go to the Extra Deck automatically, and a Side button adds to the Side Deck.
  Each card has −/+ and a button to move a copy between Main/Extra and Side. The editor checks deck
  sizes (Main 40–60, Extra and Side up to 15), the 3-copy limit and the TCG or OCG banlist. Decks are
  saved on the phone (mydecks.json) after every change. Deck lists show as a grid of card pictures
  (one per copy) by default; the button at the top switches to the list view.
- **Archetype search**: pick one or more archetypes, a format (TCG / OCG) and a time span:
  - *Recent*: last 2 weeks, or since the last banlist if that came out less than 2 weeks ago
  - *This banlist*: since the last Forbidden & Limited list
  - *All time*: no limit; decks from older formats are marked

  The app reads YGOPRODeck's tournament decks, keeps those running at least 2 cards of every chosen
  archetype (extra archetypes are fine) and none of the ignored ones, and splits the cards they play into:
  - **Engine** (cards of the chosen archetypes) and **Non-engine** (everything else)
  - each into **Main**, **Side**, **Mix** and **Niche**

  Inside each category cards are sorted by type: Monsters, Spells, Traps, Fusion, Synchro, Xyz, Link.
  Results are saved in the deck list, together with the full card list of every tournament deck used.
  Open a deck in the Decks tab to see its list and import it into My decks. Tap a card for its details; use the arrows for the previous or next one.
- **In-app updates** from this repository's GitHub releases.

### How cards are sorted

| Category | Rule |
|---|---|
| Niche | played in fewer than 25% of the matching decks |
| Main | otherwise, at least 80% of the decks that play it have it in Main/Extra (even if they also side copies) |
| Side | otherwise, at least 80% of the decks that play it have it in the Side |
| Mix | everything else: some lists main it, others side it |

Decks that run a card in both Main and Side are shown as a separate "Both" percentage.

The thresholds are in `DeckSearch.kt` (`NICHE_BELOW`, `MAIN_SHARE`, `MIN_COPIES`).

## Builds and updates

Every push runs the **Build APK** workflow. Builds on `main` become a GitHub release `v0.0.N`
(N = the workflow run number). The app checks the latest release at start and shows an
**Update** banner. Because the repository is private, add a read-only GitHub token under
**Settings → App version** (fine-grained token, this repository only, *Contents: Read*).

## Banlist dates

Built-in dates live in `data/Banlists.kt`. When a new list comes out, add its effective date
under **Settings → Banlist dates** (or in the code).

## Project layout

```
app/src/main/java/com/ygosandbox/app/
  MainActivity.kt        entry point
  AppViewModel.kt        app state, navigation, actions
  data/CardDatabase.kt   card download, storage, archetype matching
  data/DeckSearch.kt     YGOPRODeck deck lookup and card statistics
  data/DeckStore.kt      saved results (decks.json)
  data/Banlists.kt       banlist dates and format periods
  update/Updater.kt      in-app updater (GitHub releases)
  ui/                    Jetpack Compose screens
```
