/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.k3lp.runtime.K3Content

/**
 * Latin script language provider for French and English.
 *
 * Backed by an offline [Lexicon] built from the AOSP LatinIME word lists (Apache-2.0):
 *  - `suggest()` returns completions and corrections for the word being typed,
 *  - `spell()` reports whether a word is known and proposes the closest corrections.
 *
 * Nothing here touches the network and nothing is written to disk: the lexicon is read-only and the
 * personal learning (user dictionary) stays a separate, opt-in concern.
 */
class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "org.florisboard.nlp.providers.latin"
    }

    private val prefs by FlorisPreferenceStore
    private val lexicon = Lexicon(context)

    override val providerId = ProviderId

    override suspend fun create() {
        // Nothing to set up: the lexicon loads lazily on the first preload.
    }

    override suspend fun preload(subtype: Subtype) {
        // Called every time the active subtype changes. The lexicon is loaded once per provider instance
        // and kept in memory; a language switch creates a new instance through the NLP manager.
        lexicon.ensureLoaded(subtype)
        flogDebug { "Preloaded Latin provider for '${subtype.primaryLocale.languageTag()}'" }
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult = withContext(Dispatchers.Default) {
        // A private session must never even look at the word.
        if (isPrivateSession || word.isBlank()) {
            return@withContext SpellingResult.validWord()
        }
        lexicon.ensureLoaded(subtype)

        // A word carrying an apostrophe is a French elision ("l'homme", "c'est"). It is valid as a whole,
        // and its first segment is valid on its own, so we do not flag it.
        if (word.contains('\'')) {
            return@withContext SpellingResult.validWord()
        }
        // Numbers, acronyms and anything without a letter is not a spelling candidate.
        if (word.none { it.isLetter() }) {
            return@withContext SpellingResult.validWord()
        }

        val frequency = lexicon.frequencyOf(word)
        if (frequency > 0) {
            // Known word. With aggressive correction enabled, a word that exists but is very unlikely
            // (or only known capitalised) is still offered a correction.
            val aggressive = prefs.language.aggressiveCorrection.get()
            val exactCaseKnown = lexicon.contains(word)
            if (!aggressive || exactCaseKnown) {
                return@withContext SpellingResult.validWord()
            }
        }

        val corrections = lexicon.wordsNear(word, limit = maxSuggestionCount)
            .map { it.first }
            .toTypedArray()
        if (corrections.isEmpty()) {
            return@withContext SpellingResult.validWord()
        }
        // On Android 11 and lower, grammar errors are visually marked as typos due to a lack of support.
        return@withContext SpellingResult.typo(corrections)
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: K3Content,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> = withContext(Dispatchers.Default) {
        if (isPrivateSession) {
            return@withContext emptyList()
        }
        val prefix = content.composingText
        if (prefix.isBlank()) {
            return@withContext emptyList()
        }
        lexicon.ensureLoaded(subtype)

        val maxCount = minOf(maxCandidateCount, prefs.suggestion.candidateMaxCount.get())
        val autoCommit = prefs.suggestion.autoCommitEnabled.get()
        lexicon.suggestionCandidatesFor(
            prefix = prefix,
            maxCount = maxCount,
            autoCommitEnabled = autoCommit,
            sourceProvider = this@LatinLanguageProvider,
        )
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // Local learning (user dictionary growth) is not implemented yet; it will be gated by a privacy
        // preference once it exists. Today accepting a suggestion writes nothing anywhere.
        flogDebug { candidate.toString() }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        flogDebug { candidate.toString() }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        lexicon.ensureLoaded(subtype)
        return lexicon.allWords()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        lexicon.ensureLoaded(subtype)
        return lexicon.confidenceOf(lexicon.frequencyOf(word))
    }

    override suspend fun destroy() {
        // Nothing to release: the lexicon is garbage collected with the provider instance.
    }
}
