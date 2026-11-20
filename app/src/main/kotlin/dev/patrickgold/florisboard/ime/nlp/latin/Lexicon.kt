/*
 * Copyright (C) 2025 The M3Keyboard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import android.content.Context
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.devtools.flogWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText

/**
 * Offline lexicon for the Latin script languages supported by the keyboard.
 *
 * Data comes from the AOSP LatinIME dictionaries (Apache-2.0), redistributed through
 * `Helium314/aosp-dictionaries`. Two compact JSON maps are bundled as assets:
 *
 *  - `ime/dict/lexicon_fr.json`  (French, ~87k words)
 *  - `ime/dict/lexicon_en.json`  (English, ~60k words)
 *
 * Format: `{ "word": frequency }`, frequency in the range 0..255, sorted by descending frequency.
 *
 * ## Why everything is precomputed at load time
 *
 * A first implementation folded accents on every lookup and cost 100–550 ms per keystroke. All derived
 * structures are therefore built once, off the main thread, when the subtype changes:
 *
 *  - sortedFolded : folded forms sorted alphabetically, giving prefix ranges in O(log n + k) through a
 *    binary search,
 *  - [byFirstCharAndLength] : folded forms grouped by first letter and length, so an edit-distance scan
 *    only compares a few thousand candidates instead of the whole lexicon,
 *  - [frequencyOfFolded] / [originalOfFolded] : ranking and proper spelling.
 *
 * A keystroke then costs one binary search plus a bounded scan, with no per-entry allocation.
 */
class Lexicon(private val context: Context) {
    companion object {
        private const val ASSET_FR = "ime/dict/lexicon_fr.json"
        private const val ASSET_EN = "ime/dict/lexicon_en.json"

        /** Maximum edit distance we are willing to correct. 2 is the usual sweet spot for typos. */
        const val MAX_EDIT_DISTANCE = 2

        /**
         * A word at or above this frequency is common enough that a user typing it exactly needs no
         * completion. Rarer words are still expanded.
         */
        private const val COMMON_WORD_THRESHOLD = 60

        /** Below this length an edit-distance scan matches too many candidates to be useful. */
        private const val MIN_LENGTH_FOR_EDIT_SEARCH = 3

        /** Hard cap on corrections examined per keystroke, so low-end devices stay responsive. */
        private const val MAX_DISTANCE_CANDIDATES = 400

        /** How many entries of a prefix range are inspected when picking the most frequent ones. */
        private const val MAX_PREFIX_RANGE_SCAN = 1500

        /**
         * Confidence weight of a correction relative to a completion of the same frequency, and how much
         * each extra edit decays it. Calibrated so `exellent` answers `excellent` and not `exercent`.
         */
        private const val CORRECTION_WEIGHT = 0.7
        private const val EDIT_DECAY = 0.40

        /** Weight applied to capitalised candidates when the user is typing in lowercase. */
        private const val PROPER_NOUN_PENALTY = 0.45

        /**
         * Accent folding map. French users very often type without accents ("ecole", "developpement"), so
         * every lookup is done on the folded form and the properly accented word is proposed back.
         */
        private val ACCENT_FOLD: Map<Char, String> = mapOf(
            'à' to "a", 'â' to "a", 'ä' to "a", 'á' to "a", 'ã' to "a", 'å' to "a",
            'ç' to "c",
            'é' to "e", 'è' to "e", 'ê' to "e", 'ë' to "e",
            'í' to "i", 'ì' to "i", 'î' to "i", 'ï' to "i",
            'ñ' to "n",
            'ó' to "o", 'ò' to "o", 'ô' to "o", 'ö' to "o", 'õ' to "o",
            'ú' to "u", 'ù' to "u", 'û' to "u", 'ü' to "u",
            'ý' to "y", 'ÿ' to "y",
            'œ' to "oe", 'æ' to "ae",
        )

        /** Folds accents and lowercases, so `École` and `ecole` compare equal. */
        fun fold(text: String): String {
            val lowered = text.lowercase()
            var needsFold = false
            for (char in lowered) {
                if (ACCENT_FOLD.containsKey(char)) {
                    needsFold = true
                    break
                }
            }
            if (!needsFold) return lowered
            val builder = StringBuilder(lowered.length + 2)
            for (char in lowered) {
                val replacement = ACCENT_FOLD[char]
                if (replacement != null) builder.append(replacement) else builder.append(char)
            }
            return builder.toString()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    /**
     * Language for which the indexes are currently built, or null if nothing is loaded yet.
     *
     * A single provider instance serves every subtype, so this must be compared on each preload: switching
     * from French to English has to rebuild the indexes, otherwise English input would be checked against
     * the French dictionary.
     */
    @Volatile
    private var loadedLanguage: String? = null

    /** Folded forms, sorted alphabetically. */
    private var sortedFolded: Array<String> = emptyArray()

    /** Folded form -> frequency, for ranking. */
    private var frequencyOfFolded: Map<String, Int> = emptyMap()

    /** Folded form -> the properly spelled word. */
    private var originalOfFolded: Map<String, String> = emptyMap()

    /**
     * (first folded letter, folded length) -> folded forms in descending frequency order.
     *
     * A typo almost never changes the first letter, so restricting the edit-distance scan to one initial
     * plus a few lengths turns an 87k-word scan into at most a few thousand comparisons.
     */
    private var byFirstCharAndLength: Map<Pair<Char, Int>, Array<String>> = emptyMap()

    val isLoaded: Boolean get() = loadedLanguage != null

    /**
     * Loads the lexicon for the given subtype if it is not loaded yet. Safe to call repeatedly.
     */
    suspend fun ensureLoaded(subtype: Subtype) = withContext(Dispatchers.IO) {
        val language = subtype.primaryLocale.language
        if (loadedLanguage == language) return@withContext
        synchronized(lock) {
            if (loadedLanguage == language) return@synchronized
            val asset = assetFor(subtype)
            if (asset == null) {
                flogDebug { "No lexicon available for language '$language'" }
                loadedLanguage = language
                return@synchronized
            }
            // Never let a missing or malformed asset crash the keyboard: a broken dictionary must degrade
            // to "no suggestion", not to a force close.
            val parsed = try {
                val raw = context.assets.readText(asset)
                json.decodeFromString(MapSerializer(String.serializer(), Int.serializer()), raw)
            } catch (e: Throwable) {
                flogWarning { "Failed to load lexicon '$asset': ${e.message}" }
                emptyMap()
            }
            buildIndexes(parsed)
            loadedLanguage = language
            flogDebug { "Lexicon loaded from $asset with ${parsed.size} words for '$language'" }
        }
    }

    private fun assetFor(subtype: Subtype): String? = when (subtype.primaryLocale.language) {
        "fr" -> ASSET_FR
        "en" -> ASSET_EN
        else -> null
    }

    private fun buildIndexes(words: Map<String, Int>) {
        // 1. Fold once. The source map is frequency-ordered, so iteration order is our ranking order.
        val foldedToFrequency = LinkedHashMap<String, Int>(words.size)
        val foldedToOriginal = HashMap<String, String>(words.size)
        for ((word, frequency) in words) {
            val folded = fold(word)
            if (folded.isEmpty()) continue
            // Several spellings can fold together; keep the most frequent one.
            val previous = foldedToFrequency[folded]
            if (previous == null || frequency > previous) {
                foldedToFrequency[folded] = frequency
                foldedToOriginal[folded] = word
            }
        }

        // 2. Alphabetical array for prefix ranges.
        sortedFolded = foldedToFrequency.keys.toTypedArray()
        sortedFolded.sort()

        // 3. Buckets by (first letter, length), frequency order preserved by the insertion order.
        val buckets = HashMap<Pair<Char, Int>, MutableList<String>>(512)
        for (folded in foldedToFrequency.keys) {
            buckets.getOrPut(folded[0] to folded.length) { ArrayList() }.add(folded)
        }

        frequencyOfFolded = foldedToFrequency
        originalOfFolded = foldedToOriginal
        byFirstCharAndLength = buckets.mapValues { (_, list) -> list.toTypedArray() }
    }

    /** Returns the real word for a folded form, or the folded form itself as a fallback. */
    private fun originalOf(folded: String): String = originalOfFolded[folded] ?: folded

    /** Frequency of a word as typed, accents and case ignored. */
    fun frequencyOf(word: String): Int = frequencyOfFolded[fold(word)] ?: 0

    /** True if the word exists in the lexicon, accents and case ignored. */
    fun contains(word: String): Boolean = frequencyOfFolded.containsKey(fold(word))

    /** Every known word, most frequent first. Used once by the glide typing classifier. */
    fun allWords(): List<String> = frequencyOfFolded.keys.map { originalOf(it) }

    /** Index of the first element >= [target] in the sorted folded array. */
    private fun lowerBound(target: String): Int {
        var low = 0
        var high = sortedFolded.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sortedFolded[mid] < target) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * Returns the top [limit] completions of [prefix], most frequent first, by binary searching the
     * alphabetical array for the block of folded forms starting with the prefix.
     */
    fun wordsWithPrefix(prefix: String, limit: Int): List<String> {
        return wordsWithPrefixFold(prefix, limit).map { originalOf(it.first) }
    }

    /** Prefix completions as (folded form, frequency), most frequent first. */
    private fun wordsWithPrefixFold(prefix: String, limit: Int): List<Pair<String, Int>> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val foldedPrefix = fold(prefix)
        if (foldedPrefix.isEmpty()) return emptyList()
        var index = lowerBound(foldedPrefix)
        val matches = ArrayList<Pair<String, Int>>(minOf(limit * 4, 64))
        var scanned = 0
        while (index < sortedFolded.size && scanned < MAX_PREFIX_RANGE_SCAN) {
            val folded = sortedFolded[index]
            if (!folded.startsWith(foldedPrefix)) break
            matches.add(folded to (frequencyOfFolded[folded] ?: 0))
            index++
            scanned++
        }
        return matches.asSequence()
            .sortedByDescending { it.second }
            .take(limit)
            .toList()
    }

    /**
     * Returns the words within [maxDistance] edits of [word], closest first then most frequent.
     */
    fun wordsNear(word: String, maxDistance: Int = MAX_EDIT_DISTANCE, limit: Int = 8): List<Pair<String, Int>> {
        if (word.length < 2) return emptyList()
        val folded = fold(word)
        if (folded.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<String, Int>>()
        for (candidateLength in (folded.length - maxDistance)..(folded.length + maxDistance)) {
            val bucket = byFirstCharAndLength[folded[0] to candidateLength] ?: continue
            for (candidate in bucket) {
                val distance = boundedLevenshtein(folded, candidate, maxDistance)
                if (distance <= maxDistance) {
                    scored.add(candidate to distance)
                }
            }
        }
        return scored.asSequence()
            .sortedWith(
                compareBy<Pair<String, Int>> { it.second }
                    .thenByDescending { frequencyOfFolded[it.first] ?: 0 }
                    .thenBy { it.first }
            )
            .take(limit)
            .map { originalOf(it.first) to it.second }
            .toList()
    }

    /**
     * Builds the candidate list for [prefix].
     *
     * Ranking calibrated against the bundled lexicons:
     *  - a common word typed exactly yields nothing, so `merci` and `salut` get no junk candidates,
     *  - the same word typed without its accents yields only the correct spelling: `ecole` -> `école`,
     *    `ca` -> `ça`, `etre` -> `être`,
     *  - a partial word yields completions: `bonjou` -> `bonjour`,
     *  - a typo yields corrections ranked by frequency, edit distance and length proximity:
     *    `exellent` -> `excellent`, `developement` -> `développement`, `recieve` -> `receive`.
     *
     * @return pairs of (word, confidence) sorted best-first, capped at [maxCount].
     */
    fun candidatesFor(prefix: String, maxCount: Int): List<Pair<String, Double>> {
        if (prefix.isBlank() || maxCount <= 0) return emptyList()
        val folded = fold(prefix)
        if (folded.isEmpty()) return emptyList()

        val properForm = originalOfFolded[folded]
        val knownFrequency = frequencyOfFolded[folded] ?: 0

        // Rule 1: the user typed a common word correctly. Nothing to suggest.
        if (properForm != null && properForm == prefix && knownFrequency >= COMMON_WORD_THRESHOLD) {
            return emptyList()
        }

        // Rule 2: same word, but accents or case differ. Only the proper spelling is worth showing.
        if (properForm != null && properForm != prefix) {
            return listOf(properForm to confidenceOf(knownFrequency))
        }

        val best = LinkedHashMap<String, Double>()

        // Proper nouns (Thierry, Wichita) crowd out real corrections. When the user types in lowercase,
        // a capitalised candidate is demoted unless it is clearly the better match.
        val prefixStartsLowercase = prefix.firstOrNull()?.isLowerCase() == true

        fun offer(word: String, score: Double) {
            if (score <= 0.0 || word == prefix) return
            val adjusted = if (prefixStartsLowercase && word.firstOrNull()?.isUpperCase() == true) {
                score * PROPER_NOUN_PENALTY
            } else {
                score
            }
            val previous = best[word]
            if (previous == null || adjusted > previous) best[word] = adjusted
        }

        // Completions of what is being typed.
        for ((matchedFolded, frequency) in wordsWithPrefixFold(prefix, maxCount * 4)) {
            offer(originalOf(matchedFolded), normalizedFrequency(frequency))
        }

        // Corrections, only once the input is long enough for the scan to be meaningful.
        if (folded.length >= MIN_LENGTH_FOR_EDIT_SEARCH) {
            var budget = MAX_DISTANCE_CANDIDATES
            val initial = folded[0]
            for (candidateLength in (folded.length - MAX_EDIT_DISTANCE)..(folded.length + MAX_EDIT_DISTANCE)) {
                if (budget <= 0) break
                val bucket = byFirstCharAndLength[initial to candidateLength] ?: continue
                for (candidate in bucket) {
                    if (budget <= 0) break
                    val distance = boundedLevenshtein(folded, candidate, MAX_EDIT_DISTANCE)
                    if (distance in 1..MAX_EDIT_DISTANCE) {
                        val lengthPenalty = 1.0 / (1 + kotlin.math.abs(candidate.length - folded.length))
                        var score = normalizedFrequency(frequencyOfFolded[candidate] ?: 0) * CORRECTION_WEIGHT
                        repeat(distance - 1) { score *= EDIT_DECAY }
                        offer(originalOf(candidate), score * lengthPenalty)
                        budget--
                    }
                }
            }
        }

        return best.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(maxCount)
            .map { (word, score) -> word to confidenceOf((score * 255).toInt()) }
    }

    /** Raw 0..255 frequency mapped to 0..1, with a floor so rare words still rank. */
    private fun normalizedFrequency(frequency: Int): Double = maxOf(1, frequency) / 255.0

    private fun boundedLevenshtein(a: String, b: String, maxDistance: Int): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n
        var previous = IntArray(m + 1) { it }
        var current = IntArray(m + 1)
        for (i in 1..n) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost,
                )
                if (current[j] < rowMin) rowMin = current[j]
            }
            // Every remaining row can only grow, so stop once the whole row is already too far.
            if (rowMin > maxDistance) return maxDistance + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[m]
    }

    /**
     * Maps a raw 0..255 dictionary frequency onto a 0..1 confidence. The curve is deliberately gentle so
     * that several plausible candidates coexist instead of a single dominant one.
     */
    fun confidenceOf(frequency: Int): Double = (frequency.coerceIn(0, 255) / 255.0) * 0.8 + 0.2

    /**
     * Returns suggestion candidates ready to be shown in the Smartbar.
     */
    fun suggestionCandidatesFor(
        prefix: String,
        maxCount: Int,
        autoCommitEnabled: Boolean,
        sourceProvider: SuggestionProvider,
    ): List<SuggestionCandidate> {
        return candidatesFor(prefix, maxCount).mapIndexed { index, (word, confidence) ->
            WordSuggestionCandidate(
                text = word,
                secondaryText = null,
                confidence = confidence,
                // Only the first candidate may be auto-committed, only when the user asked for it, and only
                // when it actually differs from what is already typed.
                isEligibleForAutoCommit = autoCommitEnabled &&
                    index == 0 &&
                    !word.equals(prefix, ignoreCase = true),
                sourceProvider = sourceProvider,
            )
        }
    }
}
