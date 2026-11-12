package com.ygosandbox.app.combos

import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Reads a combo video for free, on the phone: text recognition (ML Kit, offline) finds the card
 * names on screen (activation banners, card panels, the duel log), frame by frame.
 *
 * It sees names, not actions or the board, so it gives the order the cards were played in, as
 * "use" steps. The engine then replays that order by the real rules from the opening hand,
 * which works out what each step actually was and the end board.
 */
object FreeReader {

    class Result(
        /** Cards in the order they showed up, with when. */
        val sequence: List<Pair<Double, Long>>,
        /** Cards named on screen that none of the lists play (seen in 2+ frames). */
        val outside: List<Long>,
        val hand: List<Long>,
    )

    suspend fun read(
        frames: List<Frame>,
        cn: CardNames,
        allowed: Set<Long>,
        handTraps: Set<Long>,
        title: String,
        description: String,
        extraDeck: Set<Long>,
        onProgress: (Float) -> Unit,
    ): Result = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val pool = allowed + handTraps
        val sequence = ArrayList<Pair<Double, Long>>()
        val outsideSeen = HashMap<Long, Int>()
        var previous = emptySet<Long>()
        try {
            frames.forEachIndexed { i, f ->
                coroutineContext.ensureActive()
                onProgress(i.toFloat() / frames.size)
                val bmp = BitmapFactory.decodeFile(f.file.absolutePath) ?: return@forEachIndexed
                val text = runCatching { Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0))) }.getOrNull()
                bmp.recycle()
                text ?: return@forEachIndexed
                // every line, and each pair of lines (long names wrap in card panels)
                val lines = text.textBlocks.flatMap { b -> b.lines.map { it.text } }
                val candidates = lines + lines.zipWithNext { a, b -> "$a $b" }
                val found = LinkedHashSet<Long>()
                for (c in candidates) {
                    if (c.length < 5) continue
                    val id = cn.match(c, pool, 0.85)
                    if (id != null) found += cn.canonical(id)
                    else cn.exact(c)?.takeIf { c.length >= 10 }?.let { outsideSeen[it] = (outsideSeen[it] ?: 0) + 1 }
                }
                // a card counts when it appears, not while it stays on screen
                for (id in found) {
                    if (id in previous) continue
                    val last = sequence.lastOrNull()
                    if (last != null && last.second == id && f.seconds - last.first < 4) continue
                    sequence += f.seconds to id
                }
                previous = found
            }
        } finally {
            recognizer.close()
        }
        // hand traps shown as "what if they have it" are not part of the combo
        val combo = sequence.filter { it.second !in handTraps }
        // The opening hand: cards the title or description names, else the first Main Deck cards played.
        val named = (title.split(Regex("[,/+:|\\-–]| and | with ")) + description.lines().take(30))
            .mapNotNull { s -> s.trim().takeIf { it.length >= 5 }?.let { cn.match(it, allowed, 0.9) } }
            .map { cn.canonical(it) }.filter { it !in extraDeck }.distinct()
        val firstPlayed = combo.map { it.second }.filter { it !in extraDeck }.distinct().take(3)
        val hand = (named + firstPlayed).distinct().take(5)
        Result(combo, outsideSeen.filter { it.value >= 2 && it.key !in handTraps }.keys.toList(), hand)
    }
}
