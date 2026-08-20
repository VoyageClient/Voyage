/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session

import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import org.matrix.android.sdk.api.session.SessionLifecycleObserver
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.file.FileService
import org.matrix.android.sdk.api.session.identity.IdentityService
import org.matrix.android.sdk.api.session.integrationmanager.IntegrationManagerService
import org.matrix.android.sdk.api.session.room.RoomService
import org.matrix.android.sdk.api.session.sync.SyncService
import org.matrix.android.sdk.api.session.user.UserService
import org.matrix.android.sdk.api.session.widgets.WidgetPostAPIMediator
import org.matrix.android.sdk.api.session.widgets.WidgetService
import org.matrix.android.sdk.api.session.widgets.WidgetURLFormatter
import org.matrix.android.sdk.internal.crypto.AndroidCryptoService
import org.matrix.android.sdk.internal.crypto.store.IMXCommonCryptoStore
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.store.db.sql.AndroidCryptoStore
import org.matrix.android.sdk.internal.platform.BackgroundTaskScheduler
import org.matrix.android.sdk.internal.platform.WorkManagerTaskScheduler
import org.matrix.android.sdk.internal.session.content.AndroidContentUriResolver
import org.matrix.android.sdk.internal.session.content.AndroidImageExifTagRemover
import org.matrix.android.sdk.internal.session.content.AndroidThumbnailExtractor
import org.matrix.android.sdk.internal.session.content.ContentUriResolver
import org.matrix.android.sdk.internal.session.content.ImageExifTagRemover
import org.matrix.android.sdk.internal.session.content.ThumbnailExtractor
import org.matrix.android.sdk.internal.session.identity.DefaultIdentityService
import org.matrix.android.sdk.internal.session.integrationmanager.DefaultIntegrationManagerService
import org.matrix.android.sdk.internal.session.integrationmanager.IntegrationManager
import org.matrix.android.sdk.internal.session.media.AndroidWebUrlPattern
import org.matrix.android.sdk.internal.session.media.LinkPreviewPrefetcher
import org.matrix.android.sdk.internal.session.media.UrlPreviewBundler
import org.matrix.android.sdk.internal.session.media.WebUrlPattern
import org.matrix.android.sdk.internal.session.room.AndroidRoomService
import org.matrix.android.sdk.internal.session.room.send.AndroidVideoMetadataExtractor
import org.matrix.android.sdk.internal.session.room.send.VideoMetadataExtractor
import org.matrix.android.sdk.internal.session.room.send.pills.AndroidTextPillsUtils
import org.matrix.android.sdk.internal.session.room.send.pills.TextPillsUtils
import org.matrix.android.sdk.internal.session.room.threads.AndroidThreadsService
import org.matrix.android.sdk.internal.session.room.threads.ThreadsServiceFactory
import org.matrix.android.sdk.internal.session.sync.DefaultSyncService
import org.matrix.android.sdk.internal.session.user.AndroidUserService
import org.matrix.android.sdk.internal.session.widgets.DefaultWidgetPostAPIMediator
import org.matrix.android.sdk.internal.session.widgets.DefaultWidgetService
import org.matrix.android.sdk.internal.session.widgets.DefaultWidgetURLFormatter
import org.matrix.android.sdk.internal.session.workmanager.DefaultWorkManagerConfig
import org.matrix.android.sdk.internal.session.workmanager.WorkManagerConfig

/**
 * Everything a session graph needs that only the android platform can supply. It is the shopping
 * list for any other platform: whatever is bound here has to be bound there too, by an impl of the
 * same core interface.
 */
@Module
internal abstract class AndroidSessionModule {

    @Binds
    abstract fun bindBackgroundTaskScheduler(scheduler: WorkManagerTaskScheduler): BackgroundTaskScheduler

    @Binds
    abstract fun bindWorkManagerConfig(config: DefaultWorkManagerConfig): WorkManagerConfig

    @Binds
    abstract fun bindCryptoService(service: AndroidCryptoService): CryptoService

    @Binds
    abstract fun bindCryptoStore(store: AndroidCryptoStore): IMXCryptoStore

    @Binds
    abstract fun bindCommonCryptoStore(store: AndroidCryptoStore): IMXCommonCryptoStore

    @Binds
    abstract fun bindThreadsServiceFactory(factory: AndroidThreadsService.Factory): ThreadsServiceFactory

    @Binds
    abstract fun bindRoomService(service: AndroidRoomService): RoomService

    // Sync runs on a dedicated android thread (SyncThread); another platform needs its own loop.
    @Binds
    abstract fun bindSyncService(service: DefaultSyncService): SyncService

    @Binds
    abstract fun bindWidgetService(service: DefaultWidgetService): WidgetService

    @Binds
    abstract fun bindWidgetPostAPIMediator(mediator: DefaultWidgetPostAPIMediator): WidgetPostAPIMediator

    @Binds
    abstract fun bindWidgetUrlBuilder(formatter: DefaultWidgetURLFormatter): WidgetURLFormatter

    @Binds
    abstract fun bindUserService(service: AndroidUserService): UserService

    @Binds
    abstract fun bindFileService(service: DefaultFileService): FileService

    @Binds
    abstract fun bindThumbnailExtractor(extractor: AndroidThumbnailExtractor): ThumbnailExtractor

    @Binds
    abstract fun bindImageExifTagRemover(remover: AndroidImageExifTagRemover): ImageExifTagRemover

    @Binds
    abstract fun bindContentUriResolver(resolver: AndroidContentUriResolver): ContentUriResolver

    @Binds
    abstract fun bindVideoMetadataExtractor(extractor: AndroidVideoMetadataExtractor): VideoMetadataExtractor

    @Binds
    abstract fun bindTextPillsUtils(utils: AndroidTextPillsUtils): TextPillsUtils

    @Binds
    abstract fun bindLinkPreviewPrefetcher(bundler: UrlPreviewBundler): LinkPreviewPrefetcher

    @Binds
    abstract fun bindWebUrlPattern(pattern: AndroidWebUrlPattern): WebUrlPattern

    @Binds
    abstract fun bindIntegrationManagerService(service: DefaultIntegrationManagerService): IntegrationManagerService

    @Binds
    @IntoSet
    abstract fun bindIntegrationManager(manager: IntegrationManager): SessionLifecycleObserver

    @Binds
    @IntoSet
    abstract fun bindWidgetUrlFormatter(formatter: DefaultWidgetURLFormatter): SessionLifecycleObserver

    @Binds
    abstract fun bindIdentityService(service: DefaultIdentityService): IdentityService

    @Binds
    @IntoSet
    abstract fun bindIdentityServiceAsSessionLifecycleObserver(service: DefaultIdentityService): SessionLifecycleObserver
}
