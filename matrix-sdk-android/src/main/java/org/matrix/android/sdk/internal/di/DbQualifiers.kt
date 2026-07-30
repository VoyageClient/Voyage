/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
internal annotation class AuthDatabase

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
internal annotation class GlobalDatabase

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
internal annotation class SessionDatabase

/**
 * Dispatcher for read-only session-database work (room-list and room-summary mapping). Separate from the
 * [SessionDatabase] write thread so a UI read isn't queued behind a sync response's one big transaction;
 * WAL gives it a consistent committed snapshot meanwhile. Never start a transaction or a write on it.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
internal annotation class SessionDatabaseRead

/**
 * Read-only dispatcher reserved for timeline seeding and rebuilding. Kept apart from
 * [SessionDatabaseRead] because a room open holds its thread for a couple of hundred ms, which would
 * otherwise delay the room summary the timeline itself is waiting on to render.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
internal annotation class SessionDatabaseTimeline

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
internal annotation class CryptoDatabase

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
internal annotation class IdentityDatabase

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
internal annotation class ContentScannerDatabase
