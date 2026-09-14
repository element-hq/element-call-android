/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The stacks alive in this process, so that the Android components the system instantiates - the
 * foreground service and the notification receiver - can find the controller behind the running call
 * without a dependency-injection framework.
 *
 * One stack per Matrix session; a host with several sessions has several. There is at most one call
 * running across all of them, which is what [active] returns.
 */
internal object ElementCallStackRegistry {
    private val stacks = CopyOnWriteArrayList<ElementCallStack>()

    fun register(stack: ElementCallStack) {
        stacks.add(stack)
    }

    fun unregister(stack: ElementCallStack) {
        stacks.remove(stack)
    }

    /**
     * The stack whose controller has a running call, else the most recently built one, else null when
     * no stack exists (the service was started by a process the host has not rebuilt yet).
     */
    fun active(): ElementCallStack? {
        return stacks.lastOrNull { it.controller.state.value != null } ?: stacks.lastOrNull()
    }
}
