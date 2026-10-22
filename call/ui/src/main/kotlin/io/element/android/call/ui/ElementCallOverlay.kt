/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import android.app.ActivityOptions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.ui.theme.ElementCallStyle
import io.element.android.call.ui.theme.ElementCallTheme
import io.element.android.call.ui.util.scaffoldScrollableContentInsets

/**
 * Draws a minimized call above the host's content as a bar, and opens [ElementCallActivity] when the call
 * is maximized. This is spec rule R9: the minimized UI is the component's, where it sits is the host's.
 *
 * The full screen is never drawn here. It is its own Activity, on top of the host's, so that it alone can show
 * over the lock screen and shrink into picture-in-picture over the host's Activity.
 *
 * @param controller the call to draw, from `ElementCallStack.controller`, or null before there is one. Compose
 * the overlay even then: switching between the overlay and bare content would rebuild the host's content.
 * @param modifier applied at the root, around the host's content and the bar over it.
 * @param style the host's design system, or null for the defaults.
 * @param content the host's own content, composed underneath with the bar's height consumed from its
 * top window inset, so a scaffold below slides down rather than jumping when the bar appears.
 */
@Composable
fun ElementCallOverlay(
    controller: ElementCallController?,
    modifier: Modifier = Modifier,
    style: ElementCallStyle? = null,
    content: @Composable (Modifier) -> Unit,
) {
    val current = controller?.state?.collectAsState()?.value
    val isInPictureInPicture = controller?.isInPictureInPicture?.collectAsState()?.value == true
    val isBarVisible = current != null && !current.isMaximized
    var barBounds by remember { mutableStateOf<IntRect?>(null) }
    var isOpeningFromBar by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        val statusBarTopPadding = if (LocalInspectionMode.current) {
            24.dp
        } else {
            scaffoldScrollableContentInsets.asPaddingValues().calculateTopPadding()
        }
        // Animated in step with the bar so the content below slides rather than jumping. Keyed on the
        // call so a call starting docked does not slide.
        val topWindowInset by key(current != null) {
            animateDpAsState(
                targetValue = if (isBarVisible) statusBarTopPadding + MINIMIZED_CALL_BAR_HEIGHT else 0.dp,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1.dp),
                label = "call-bar-insets-animation",
            )
        }

        if (controller != null && current != null) {
            ElementCallTheme(style = style) {
                AnimatedVisibility(
                    visible = isBarVisible,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    ElementCallMinimizedBar(
                        call = current,
                        onToggleMicrophone = { controller.setMicrophoneMuted(!current.isMicrophoneMuted) },
                        onHangUp = controller::hangUp,
                        onClick = {
                            isOpeningFromBar = true
                            controller.setMaximized(true)
                        },
                        modifier = Modifier.onGloballyPositioned { barBounds = it.boundsInWindow().roundToIntRect() },
                    )
                }
            }
        }

        // Always in this one place: this is the host's whole navigation tree, and composing it anywhere
        // else would lose every screen's state and re-run every presenter.
        Box(propagateMinConstraints = true) {
            content(Modifier.consumeWindowInsets(PaddingValues(top = topWindowInset)))
        }
    }
    if (controller != null) {
        LaunchedEffect(controller, current == null) {
            if (current == null) forgetCallSpotlightMemory(controller)
        }
    }
    if (controller != null && current != null) {
        MinimizedCall(controller = controller, current = current)
        OpenCallScreen(
            shouldOpen = current.isMaximized && !isInPictureInPicture,
            fromBounds = barBounds?.takeIf { isOpeningFromBar },
            onOpen = { isOpeningFromBar = false },
        )
    }
}

/** What the call still needs from this window while the call screen is closed. */
@Composable
private fun MinimizedCall(controller: ElementCallController, current: ElementCallSnapshot) {
    val spotlightMemory = rememberCallSpotlightMemory(controller)
    CallLayoutEffects(controller = controller, current = current, spotlightMemory = spotlightMemory)
    if (!current.isMaximized) {
        UnmountedStageWindow(controller = controller, current = current, spotlightMemory = spotlightMemory, isStageMounted = false)
        rememberCallPermissions(controller = controller, current = current)
    }
}

/**
 * Opens the call screen whenever the call is maximized while this window is in front: from the bar, and
 * for a call started from the host. Only while resumed, so a call maximized from the lock screen or the
 * notification, which opens the screen itself, is not opened a second time from behind it.
 */
@Composable
private fun OpenCallScreen(shouldOpen: Boolean, fromBounds: IntRect?, onOpen: () -> Unit) {
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    val view = LocalView.current
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentFromBounds by rememberUpdatedState(fromBounds)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(shouldOpen && isResumed) {
        if (!shouldOpen || !isResumed) return@LaunchedEffect
        // The call screen grows out of the bar that was tapped.
        val options = currentFromBounds?.let { ActivityOptions.makeScaleUpAnimation(view, it.left, it.top, it.width, it.height) }
        ElementCallActivity.start(context, options?.toBundle())
        currentOnOpen()
    }
}
