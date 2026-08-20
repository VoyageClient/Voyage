/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.util.system

import dagger.Binds
import dagger.Module
import org.matrix.android.sdk.api.securestorage.SecureStorageService
import org.matrix.android.sdk.internal.platform.KeystoreSecureStorage
import org.matrix.android.sdk.internal.platform.SecureStorage
import org.matrix.android.sdk.internal.securestorage.DefaultSecureStorageService

// Secret storage on android goes through the Keystore; other platforms bind their own SecureStorage.
@Module
internal abstract class AndroidSystemModule {

    @Binds
    abstract fun bindSecureStorageService(service: DefaultSecureStorageService): SecureStorageService

    @Binds
    abstract fun bindSecureStorage(storage: KeystoreSecureStorage): SecureStorage
}
