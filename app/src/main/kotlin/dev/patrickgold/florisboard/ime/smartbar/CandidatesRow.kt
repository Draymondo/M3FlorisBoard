/*
 * Copyright (C) 2024-2026 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.smartbar

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.keyboard3.interaction.LocalInteractionController
import dev.patrickgold.florisboard.ime.nlp.ClipboardSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.subtypeManager
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText
import kotlin.time.Duration

val CandidatesRowScrollbarHeight = 2.dp

/** One of the three fixed slots of the suggestion strip (left, middle, right). */
private data class CandidateSlot(val candidate: SuggestionCandidate, val isPrimary: Boolean)

/**
 * Samsung-style strip: three equal slots, centered text, no separators. When a word is being typed the layout is
 * `[typed word] [best suggestion, highlighted] [second suggestion]`, otherwise the candidates are shown in order.
 */
private fun buildSlots(candidates: List<SuggestionCandidate>, typedWord: String?): List<CandidateSlot?> {
    if (candidates.isEmpty()) return listOf(null, null, null)
    if (typedWord == null) {
        return List(3) { n -> candidates.getOrNull(n)?.let { CandidateSlot(it, isPrimary = false) } }
    }
    val best = candidates[0]
    val rest = candidates.drop(1)
    val left: SuggestionCandidate?
    val right: SuggestionCandidate?
    if (best.text.toString().equals(typedWord, ignoreCase = true)) {
        // The typed word is already the best suggestion: no need to show it twice.
        left = rest.getOrNull(0)
        right = rest.getOrNull(1)
    } else {
        left = WordSuggestionCandidate(text = typedWord, isEligibleForUserRemoval = false)
        right = rest.getOrNull(0)
    }
    return listOf(
        left?.let { CandidateSlot(it, isPrimary = false) },
        CandidateSlot(best, isPrimary = true),
        right?.let { CandidateSlot(it, isPrimary = false) },
    )
}

@Composable
fun CandidatesRow(modifier: Modifier = Modifier) {
    val imeController = LocalImeController.current
    val interactionController = LocalInteractionController.current
    val context = LocalContext.current
    val nlpManager by context.nlpManager()
    val subtypeManager by context.subtypeManager()

    val candidates by nlpManager.activeCandidatesFlow.collectAsState()
    val timingOptions by interactionController.activeTimingOptions.collectAsState()

    val typedWord = imeController.snapshotState().content.compositionText
        ?.toString()
        ?.takeIf { it.isNotBlank() }
    val slots = buildSlots(candidates, typedWord)

    SnyggRow(
        elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
    ) {
        for (slot in slots) {
            if (slot == null) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight())
                continue
            }
            CandidateItem(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
                candidate = slot.candidate,
                isPrimary = slot.isPrimary,
                onClick = {
                    imeController.updateStateBlocking {
                        emitCandidate(slot.candidate)
                    }
                },
                onLongPress = {
                    if (slot.candidate.isEligibleForUserRemoval) {
                        nlpManager.removeSuggestion(subtypeManager.activeSubtype, slot.candidate)
                    } else {
                        false
                    }
                },
                longPressTimeout = timingOptions.longPressTimeout,
            )
        }
    }
}

@Composable
private fun CandidateItem(
    candidate: SuggestionCandidate,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = { },
    onLongPress: () -> Boolean = { false },
    longPressTimeout: Duration,
) {
    var isPressed by remember { mutableStateOf(false) }
    // The pointer handler below is created once, so it must always call the latest callbacks.
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    val elementName = if (candidate is ClipboardSuggestionCandidate) {
        FlorisImeUi.SmartbarCandidateClip
    } else {
        FlorisImeUi.SmartbarCandidateWord
    }.elementName
    val attributes = mapOf("auto-commit" to if (candidate.isEligibleForAutoCommit) 1 else 0)
    val selector = if (isPressed) SnyggSelector.PRESSED else SnyggSelector.NONE
    // FOCUS is (ab)used as the "best suggestion" marker so the theme can highlight it in blue.
    val textSelector = if (isPrimary && !isPressed) SnyggSelector.FOCUS else selector

    SnyggRow(
        elementName = elementName,
        attributes = attributes,
        selector = selector,
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    isPressed = true
                    if (down.pressed != down.previousPressed) down.consume()
                    var upOrCancel: PointerInputChange? = null
                    try {
                        upOrCancel = withTimeout(longPressTimeout.inWholeMilliseconds) {
                            waitForUpOrCancellation()
                        }
                        upOrCancel?.let { if (it.pressed != it.previousPressed) it.consume() }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        if (currentOnLongPress()) {
                            upOrCancel = null
                            isPressed = false
                        }
                        waitForUpOrCancellation()?.let { if (it.pressed != it.previousPressed) it.consume() }
                    }
                    if (upOrCancel != null) {
                        currentOnClick()
                    }
                    isPressed = false
                }
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (candidate.icon != null) {
            SnyggBox(
                elementName = "$elementName-icon",
                attributes = attributes,
                selector = selector,
            ) {
                SnyggIcon(imageVector = candidate.icon!!)
            }
        }
        SnyggColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnyggText(
                elementName = "$elementName-text",
                attributes = attributes,
                selector = textSelector,
                text = candidate.text.toString(),
            )
            if (candidate.secondaryText != null) {
                SnyggText(
                    elementName = "$elementName-secondary-text",
                    attributes = attributes,
                    selector = selector,
                    text = candidate.secondaryText!!.toString(),
                )
            }
        }
    }
}
