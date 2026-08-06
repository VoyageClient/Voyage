/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.admin

import dagger.Binds
import dagger.Module
import dagger.Provides
import org.matrix.android.sdk.api.session.admin.AdminService
import retrofit2.Retrofit

@Module
internal abstract class AdminModule {

    @Module
    companion object {
        @Provides
        @JvmStatic
        fun providesAdminAPI(retrofit: Retrofit): AdminAPI {
            return retrofit.create(AdminAPI::class.java)
        }
    }

    @Binds
    abstract fun bindAdminService(service: DefaultAdminService): AdminService

    @Binds
    abstract fun bindGetServerAdminStatusTask(task: DefaultGetServerAdminStatusTask): GetServerAdminStatusTask
}
