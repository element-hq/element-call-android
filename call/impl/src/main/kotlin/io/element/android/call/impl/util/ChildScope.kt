/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.plus

/**
 * A child scope of this one: its own [SupervisorJob] under the parent's job, the given dispatcher and a
 * name, so that cancelling the parent cancels the child and a failure in the child stays there.
 *
 * Copied from Element X's `libraries/core`, minus its special case for `TestScope`: tests here pass a
 * scope they control instead.
 */
internal fun CoroutineScope.childScope(
    dispatcher: CoroutineDispatcher,
    name: String,
): CoroutineScope {
    val supervisorJob = SupervisorJob(parent = coroutineContext.job)
    return this + dispatcher + supervisorJob + CoroutineName(name)
}
