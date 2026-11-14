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
- **Analysis** (test): for one of your decks, each Main Deck card gets roles (Starter, Searcher, Extender,
  Hand trap, Endboard, Board breaker, Brick), guessed from its text and editable per deck, and its
  once-per-turn rule (hard, soft or none) read from the text. The app shows the exact odds
  (hypergeometric) of opening 1–5 playable cards and 1–5 hand traps, for 5 cards going first or 6 going
  second. A second column counts several copies of the same hard once-per-turn card as one. Logic in
  `analysis/`.
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
- **Combos from videos** (test): the *Combos* tab of an archetype search result.
  - *Find combo videos* searches YouTube for "<archetype> combo". It keeps only titles that name the archetype, skips pack openings, tier lists and Shorts, and drops videos older than the chosen period (default: since the last banlist).
  - **Two ways to read:**
    - **Free reading (default):** reads what the video says and shows, on the phone. The source is chosen in order:
      - **A duel log on screen,** if there is one.
      - **Else the narration** (YouTube captions): cards said with an action word ("activate", "normal summon", "search"…), in the order said. Misheard names are matched against the deck lists, and short names ("Castellan") count too. Lines about what could happen ("if they have Ash…, instead…") are skipped, and the hand comes from lines like "we start with X and Y".
      - **Else card names on screen** (ML Kit text recognition, offline). Only lines that are just a name count, like banners and card titles, not effect text or explanations mentioning other cards. A frame showing many names at once is skipped, and a card counts again only after it has left the screen.
      - Then the engine's check replays the steps by the rules from the opening hand, working out what each did and the end board.
      - The opening hand comes from the title and description, or else the first cards played.
      - It works best with EDOPro and Master Duel videos.
      - On engine-made test lines reduced to names, 7 of 16 verified, against 12 with Claude's full reading.
    - **Duel logs:** videos that show a duel log (Duelingbook, Dueling Nexus…) are read from it. A line with an action word and a card gives the real action, and a "Player:" prefix gives who did it. Otherwise the cards come in the order their names appeared.
    - **Claude reading:** reads every action, the hand and the end board, also from commentary and real tables. It uses your API key (paid).
  - For each video you tick, the app downloads it at up to 720p, keeps one frame per screen change, and reads it in the chosen way. The English captions and the description help when YouTube has them. The video is deleted afterwards; only the combo is kept.
  - **Deck-list check:** a video is left out as soon as its combo plays a card that none of the result's tournament lists play. Reading stops at that point, so the rest of the video isn't paid for. Cards that are only held in hand and hand traps the opponent shows don't count. A card that can't be identified also drops the video, because the check can't be done. Left-out videos are listed with the reason.
  - Videos ending on mostly the same field cards (60%) count as one line. Lines are sorted by how many videos show them, each with its end board, opening hand, interruptions, choke points and the steps. Tap a step's time to open the video there.
  - **Checked by the engine:** each kept line is replayed by the real rules from its opening hand, with the result's tournament list that plays most of its cards. A line is verified when most of its steps can be played and the replay reaches most of its end board; the tab shows the result per line, and *Check with the engine* redoes lines read before this existed.
  - **Used by the engine:** verified lines are split into sub-combos (pieces). The Play engine, the combo tree and the duel bots use them, together with the reference boards' pieces, for any deck that holds a piece's opening cards.
  - Needs an Anthropic API key, entered in the tab and kept only on the phone. The model can be changed there too (default `claude-sonnet-5-5`).
  - YouTube search and downloads use [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) on the phone's own connection. When YouTube changes its site, searches can fail until the app updates the library. Saved per result in `combos/`.
- **Replays from videos** (test): the *Replays* tab of an archetype search result.
  - *Find match videos* searches YouTube for "<archetype> replay": feature matches, tournament rounds and ranked replays of 5 to 90 minutes, since the last banlist (or the last 6 months).
  - Each match is read like a combo video, but more sparsely: a frame every 2 seconds, at most 360 kept. Both players are tracked through every game, and the commentary is used when YouTube has captions.
  - The side playing the archetype is found from the cards each player uses. The match is left out as soon as that side plays a card none of the result's lists play, or if neither side can be told apart.
  - Each match shows the result vs the opponent's deck, and every game with who went first and who won. Every turn has a one-line summary, the end board and the interruptions used (yours highlighted), plus turning points, side deck changes and takeaways.
  - At the top, the record across the read matches: going first and second, and per opponent.
  - **Free or Claude reading,** with the same switch as Combos.
    - **Free with a duel log naming both players** (Duelingbook and the like): the side playing the archetype, turns, games and results come from the log, and the deck-list rule applies to that side.
    - **Free without such a log:** a timeline of the cards shown, in order, with a link to each moment.
    - **Claude reading** uses the same API key as the Combos tab. A 20-minute match costs roughly what 300 images do.
- **YGO Omega replays, for every deck** (test): in *Settings*, set Omega's replay folder and press *Analyze replays* (or pick files).
  - **What is read:** every new replay is read exactly from the file, for every deck, both players and every turn. For each turn it records:
    - the line played and the end board;
    - going first or second;
    - the opponent's interruptions and what they answered;
    - whether it was played under the opponent's Mulcharmy (Fuwalos, Purulia) or Droll & Lock Bird;
    - how it went: the game's result, how many activations the opponent had next turn, and whether the player lost then. Together these give a performance from 0 to 100.
  - **Combo or interaction:** every activation in a turn is tagged from what happened in the replay:
    - **combo:** it builds the player's board (summons, searches, placements, or it only touches their own cards, like Enlisted removing their own Seneschal from the other field);
    - **response:** it answers the opponent, or acts in the opponent's turn without building anything (Ash, Dominus Impulse, Droll);
    - **removal:** it hits the opponent's cards and gains nothing.

    An effect that both removes and builds (Enlisted banishing a monster, then moving to the other field so another Angelechy can be summoned) counts as combo. The engine learns only from the combo steps. What a deck uses to stop others is kept separately ("Stops opponents with" in Settings), and match summaries list the answers and removals apart from the combo. Lines in the Play engine and the combo tree mark the engine's own answers to the opponent as "Answer:".
  - **Both players' information:** a replay of a played game keeps both decks and the game as each player saw it, so both opening hands and every draw are known. The app merges the two views.
  - **What the engine learns:**
    - It replays the opening turn of whoever went first (you or the opponent) with that player's exact deck. Verified lines keep their combo pieces, tagged with the Mulcharmy / Droll condition and weighted by performance, and the move policy learns from them.
    - Those pieces are used by the Play engine, the combo tree and the duel bots for any deck that can play them.
    - **Why each choice:** at each choice in those lines (what a card searched, discarded, summoned or placed), the engine tries every other option with its best continuation and compares. A choice where the player's pick comes out on top is understood, with the reason in plain words (what it ends with that the next best option doesn't, and what the chosen card does next). Example: "Seneschal: its effect Special Summons Enlisted".
    - **Not understood yet:** where the engine scores another option higher, the choice is listed as not understood yet, per archetype in Settings. If players made it at least twice, the Play engine, the combo tree and the duel bots follow it unless their search finds something clearly better.
    - Lines replayed before this are explained on the next *Analyze replays*.
  - **Per archetype:**
    - **In Settings:** games and record going first and second, average activations, the cards where the deck is stopped most (and how often it lost after), Mulcharmy / Droll turns and the best line.
    - **In the Replays tab:** the same summary for that deck, and its Omega games.
  - **Bots use it:** in the duel simulator, each bot knows from these games where the other deck is usually stopped and how many plays it has. It uses an interruption on such a choke point even at a small cost, and stops the first play outright when its hand traps and Set interruptions cover the plays that deck usually has left.
  - Stored in `replaydb.json`, one database for all decks.
- **Play engine** (test): finds the best plays for an opening hand against a real deck.
  - Pick your deck and the opponent's deck from My decks or any tournament list saved by an Archetype search.
  - Choose going first or second, your 5-card hand (pick or random) and a search depth.
  - **Practice** sees both hands; leave theirs empty for a random one per sample. **Real play** knows only your hand and samples theirs from their list.
  - The engine plays the real card rules (ygopro-core with the EDOPro scripts). It shows the top lines with their steps, the end board, what that board can stop against this opponent, and the opponent's strongest reply found when they play their turn against it.
  - Searches can be stopped early and keep what they found. Cards EDOPro can't play yet are listed.
- **Combo tree** (test): on the Play engine screen, *Combo tree vs hand traps*.
  - For your hand going first, the engine shows the main line, then for each hand trap the opponent is likely to have where it hurts that line most and how to continue from there, with the board you still reach.
  - The main line is also split into **parts** (sub-combos): what each part needs (cards from your hand, what earlier parts left, the Normal Summon), what it gives the next parts, and which hand traps can stop it. The part each hand trap hurts most is highlighted.
  - The hand traps come from the opponent's list if you picked one (non-engine cards that interrupt from the hand, Main Deck copies first), otherwise the most played: Ash, Veiler, Imperm, Ogre, Droll, Nibiru, Fuwalos, Ghost Belle.
- **Reference end boards** (test): from a deck's Analysis page.
  - The engine plays 12 (quick) or 30 (thorough) random opening hands going first, with no interaction from the opponent, and keeps the best board it finds for each.
  - The screen shows the median and best board value, the typical end-board cards (share of boards with each card), and every board with its hand and line.
  - Play engine lines then show where their board sits against these (e.g. "better than 80% of this deck's reference boards").
  - **Combo pieces:** the lines are split into sub-combos and merged across hands, each with what it needs, what it gives and which of the most played hand traps can stop it (and from which step). Starters come first, then the most common pieces. A reference built with an older version needs a rebuild to show them.
  - **Used by the engine:** once a deck has reference boards, its pieces steer the search in the Play engine, the combo tree and that deck's bot in the duel simulator. The search first tries plans that chain pieces the current cards allow, recombining pieces from different hands. After an interruption, the plan is rebuilt from what is left. This helps most with quick searches, such as the Normal bot.
  - Saved per card list (`refs/`), so editing the deck asks for a new reference.
- **Learned move policy** (test): the engine learns which options strong lines choose and tries those first in every search. This covers the Play engine, the combo tree and the duel bots.
  - **What teaches it:** building reference boards for any deck adds that deck's best lines. Verified video lines from the Combos tab add more and count double.
  - **One policy for all decks:** it's kept on the phone (`policy.json`) and keeps learning.
  - **Measured on the desktop**, 12 new hands per deck at a quick search budget. One policy trained on 16 hands each of Angelechy and Elfnote found a better board on every hand of both decks:

    | Deck | Average board |
    |---|---|
    | Elfnote | 0.807 → 0.894 |
    | Angelechy | 0.803 → 0.850 |

    Details in the engine's docs/api.md.
- **Side plan** (test): from a deck's Analysis page, pick an opponent deck to get what to side in and out, going first and going second, with the reason for each card. *Simulate their boards* first plays several of their opening turns and rates your board breakers against what they actually build.
- **Duel simulator** (test): a full duel with the real card rules, in two modes: **You vs bot** or **Bot vs bot**.
  - Pick both decks, who goes first and the bot level:
    - *Easy*: simple priorities
    - *Normal* / *Strong*: plans each turn by searching ahead, then follows the plan
  - A delay between bot moves (0–3 s) keeps the game easy to follow, with pause, one-move step and a "Hurry" button while a bot thinks.
  - The board shows both fields (opponent mirrored), the Extra Monster Zones, hands, LP, Deck/Extra/GY/banished counts (tap to see the cards) and a duel log.
  - On your turn, the panel at the bottom lists every legal action or answer. Zone placement and other choices that never matter are made for you.
  - In bot vs bot you can show both hands and set cards.
  - The bot sees every card, including your hand.
- **Background work:** long jobs keep running when you leave the app or turn the screen off.
  - **Which jobs:** reading combo and match videos, checking lines with the engine, archetype searches, Play engine, combo tree, side plan and reference boards.
  - **Notification:** a notification shows the progress and has a Stop button. On Android 13 and later, the app asks once to show notifications; the work runs either way.
  - **Also covered:** the app update and card database downloads. An update downloaded while the phone is locked installs when you come back to the app, because Android only opens the installer while the app is on screen.
  - **Staying awake:** the service keeps the CPU and Wi-Fi awake, and video downloads resume after a dropped connection. The first time a long job starts, Android asks once whether the app may run without battery restrictions; say yes, or some phones' battery savers still stop it.
  - **When it still stops:** closing the app from the recent apps list stops engine jobs. Video reading carries on.
  - **Interrupted runs:**
    - **The next open tells you:** if Android closes the app mid-job or it crashes, the Combos and Replays tabs say so when you next open the app, with the stage it reached (and the error for a crash).
    - **Reading resumes:** a video that didn't finish keeps its frames and the batches already read. Reading it again continues where it stopped, so nothing is downloaded or paid for twice.
  - **Off the main thread:** all reading work runs off the app's main thread. Matching card names there used to freeze the app for seconds per batch, and Android silently kills a frozen app in the background.
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

### The engine submodule

The engine lives in its own repository, [Yu-Gi-Oh-Engine](https://github.com/0megaXP/Yu-Gi-Oh-Engine), included here as the `engine/` submodule (Gradle module `:ygoe`). That repository is private, so the workflow needs a repository secret **`ENGINE_TOKEN`**:

1. On GitHub: Settings → Developer settings → Fine-grained tokens → Generate new token.
2. Repository access: only *Yu-Gi-Oh-Engine*. Permissions: *Contents: Read-only*.
3. In this repository: Settings → Secrets and variables → Actions → New repository secret, name `ENGINE_TOKEN`.

The build also fetches the rules core, card scripts and card database the engine pins (from GitHub) and packs them into the APK. This adds about 25 MB.

For a local build, run `git submodule update --init`. Android Studio needs git, python3 and the NDK (it installs the NDK on first build).

To use a newer engine version, run `git -C engine pull origin main` and commit the submodule change.

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
  play/PlayModels.kt     Play engine / side plan state and deck choices
  play/DuelModels.kt     Duel simulator settings and state (game loop in AppViewModel)
  data/ReferenceStore.kt saved reference end boards, one file per card list
  ui/                    Jetpack Compose screens (Play engine + side plan: ui/PlayScreens.kt, duel: ui/DuelScreen.kt)
engine/                  the play engine (submodule, Gradle module :ygoe)
```
