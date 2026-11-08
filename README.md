# YGO Sandbox

An Android app for testing and learning Yu-Gi-Oh! decks from real tournament results.

## Features

- **Card database**: the full card list from the [YGOPRODeck API](https://ygoprodeck.com/api-guide/),
  downloaded once, kept on the phone, and refreshed automatically when YGOPRODeck publishes a new
  version (checked once a day). Browse by name or by archetype.
- **Archetype detection**: a card belongs to an archetype when YGOPRODeck tags it with it, when the
  archetype name appears as a whole word in the card's name, or when the card's text cites the
  archetype in quotes (e.g. `add 1 "Branded" card`).
- **Decks**: pick one or more archetypes, a format (TCG / OCG) and a time span:
  - *Recent*: last 2 weeks, or since the last banlist if that came out less than 2 weeks ago
  - *This banlist*: since the last Forbidden & Limited list
  - *All time*: no limit; decks from older formats are marked

  The app reads YGOPRODeck's tournament decks, keeps those running at least 2 cards of every chosen
  archetype (extra archetypes are fine), and splits the cards they play into:
  - **Engine** (cards of the chosen archetypes) and **Non-engine** (everything else)
  - each into **Main**, **Side**, **Mix** and **Niche**

  Results are saved in the deck list. Tap a card for its details; swipe left/right for the next one.
- **In-app updates** from this repository's GitHub releases.

### How cards are sorted

| Category | Rule |
|---|---|
| Niche | played in fewer than 25% of the matching decks |
| Main | otherwise, at least 80% of the decks that play it have it in Main/Extra |
| Side | otherwise, at least 80% of the decks that play it have it in the Side |
| Mix | everything else (split between Main and Side) |

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
