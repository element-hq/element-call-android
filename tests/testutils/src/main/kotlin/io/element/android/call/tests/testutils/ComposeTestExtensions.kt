/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.call.tests.testutils

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

/** Click the clickable node showing the string [res]. */
fun AndroidComposeUiTest<ComponentActivity>.clickOn(@StringRes res: Int) {
    onNode(hasText(activity!!.getString(res)) and hasClickAction()).performClick()
}

/** Click the node described by the string [res]: an icon button, whose label is its content description. */
fun AndroidComposeUiTest<ComponentActivity>.clickOnContentDescription(@StringRes res: Int) {
    onNodeWithContentDescription(activity!!.getString(res)).performClick()
}

fun AndroidComposeUiTest<ComponentActivity>.assertNodeWithTextIsDisplayed(@StringRes res: Int) {
    onNodeWithText(activity!!.getString(res)).assertIsDisplayed()
}

fun AndroidComposeUiTest<ComponentActivity>.assertNoNodeWithText(@StringRes res: Int) {
    onNodeWithText(activity!!.getString(res)).assertDoesNotExist()
}

fun AndroidComposeUiTest<ComponentActivity>.assertNodeWithContentDescriptionIsDisplayed(@StringRes res: Int) {
    onNodeWithContentDescription(activity!!.getString(res)).assertIsDisplayed()
}

fun AndroidComposeUiTest<ComponentActivity>.assertNoNodeWithContentDescription(@StringRes res: Int) {
    onNodeWithContentDescription(activity!!.getString(res)).assertDoesNotExist()
}
