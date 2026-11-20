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

package dev.patrickgold.florisboard.app.settings.privacy

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.app.components.ScrollableScreenColumn
import dev.patrickgold.florisboard.app.components.SegmentedListColumn
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import org.florisboard.lib.compose.stringRes

/**
 * Privacy screen: the user-facing side of [dev.patrickgold.florisboard.ime.core.SensitiveFieldGuard].
 *
 * The incognito mode selector deliberately lives here and only here. It used to be duplicated on the Typing
 * screen, which made the same setting appear in two places with two different explanations.
 */
@Composable
fun PrivacyScreen() {
    val prefs by FlorisPreferenceStore
    val navController = LocalNavController.current

    FlorisScreenNg(
        title = stringRes(R.string.settings__privacy__title),
        previewFieldVisible = true,
    ) { contentPadding ->
        ScrollableScreenColumn(contentPadding) {
            SegmentedListColumn(title = stringRes(R.string.pref__privacy__guard_group__title)) {
                val count = 1
                SwitchPreference(
                    prefs.privacy.guardSensitiveFields,
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    icon = Icons.Outlined.Lock,
                    title = stringRes(R.string.pref__privacy__guard_sensitive_fields__label),
                    summary = stringRes(R.string.pref__privacy__guard_sensitive_fields__summary),
                )
            }

            SegmentedListColumn(title = stringRes(R.string.pref__privacy__incognito_group__title)) {
                val count = 1
                Preference(
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    title = stringRes(R.string.pref__suggestion__incognito_mode__label),
                    summary = stringRes(R.string.pref__privacy__incognito_summary),
                    onClick = { navController.navigate(Routes.Settings.Typing) },
                )
            }

            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                text = stringRes(R.string.pref__privacy__explanation),
            )
        }
    }
}
