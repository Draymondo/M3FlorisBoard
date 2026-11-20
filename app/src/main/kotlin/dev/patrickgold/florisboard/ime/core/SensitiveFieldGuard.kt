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

package dev.patrickgold.florisboard.ime.core

import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.keyboard3.ImeState

/**
 * Central gatekeeper for every feature which may observe, learn from or leak what the user types.
 *
 * Two independent conditions put the keyboard into a *private session*:
 *
 *  1. the user explicitly enabled incognito mode (see [ImeState.flags] / `IncognitoMode`), or
 *  2. the currently focused editor is a sensitive field: password field, or an editor which opted out of
 *     personalised learning with `IME_FLAG_NO_PERSONALIZED_LEARNING`.
 *
 * In a private session no suggestion, no learning, no clipboard suggestion and no AI feature may run.
 * Before this guard existed, incognito mode was only a visual indicator and suggestion providers were
 * always called with `isPrivateSession = false`.
 */
object SensitiveFieldGuard {
    /**
     * Returns true if the given editor attributes describe a field the user expects to be private,
     * regardless of the global incognito setting.
     */
    fun isSensitiveInputAttributes(attributes: InputAttributes): Boolean {
        // Any password variation (text, visible, web, number) is a hard stop.
        return when (attributes.variation) {
            InputAttributes.Variation.PASSWORD,
            InputAttributes.Variation.VISIBLE_PASSWORD,
            InputAttributes.Variation.WEB_PASSWORD,
            -> true
            else -> attributes.flagTextNoSuggestions
        }
    }

    /**
     * Returns true if the given editor info describes a sensitive field.
     */
    fun isSensitiveEditorInfo(info: FlorisEditorInfo): Boolean {
        // Editors which explicitly opt out of personalised learning must never be observed.
        if (info.imeOptions.flagNoPersonalizedLearning) {
            return true
        }
        return isSensitiveInputAttributes(info.inputAttributes)
    }

    /**
     * Returns true if the given input method state must be treated as a private session.
     */
    fun isPrivateSession(state: ImeState): Boolean {
        if (state.flags.isIncognitoMode || state.flags.isGuardedSensitiveField) {
            return true
        }
        // Always inspect the editor info directly as well. The flag is derived from it, but suggestion
        // requests are triggered from resetContent(), which can run before the flags of the new state have
        // been written. Relying on the flag alone would leak the first suggestion of a password field.
        return isSensitiveEditorInfo(state.editor.info)
    }

    /**
     * Returns true if the given input method state describes a sensitive field while the user did not
     * explicitly ask for incognito mode. Used to show an unobtrusive hint in the UI.
     */
    fun isImplicitlyPrivate(state: ImeState): Boolean {
        return state.flags.isGuardedSensitiveField ||
            (!state.flags.isIncognitoMode && isSensitiveEditorInfo(state.editor.info))
    }

    /**
     * Same as [isPrivateSession] but honours the user preference: when the guard is disabled, only an
     * explicit incognito session is treated as private.
     */
    fun isPrivateSession(state: ImeState, guardEnabled: Boolean): Boolean {
        if (!guardEnabled) {
            return state.flags.isIncognitoMode
        }
        return isPrivateSession(state)
    }
}
