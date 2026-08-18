/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.di

import javax.inject.Qualifier

/**
 * The client used to fetch arbitrary web pages, for the link previews we generate ourselves.
 * It resolves public addresses only, and is never given any credential.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
internal annotation class LinkPreview
