/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.matrixcli.di

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
import org.matrix.android.sdk.api.session.widgets.WidgetService
import org.matrix.android.sdk.api.session.widgets.WidgetURLFormatter
import org.matrix.android.sdk.internal.crypto.DefaultCryptoService
import org.matrix.android.sdk.internal.crypto.store.IMXCommonCryptoStore
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.store.db.sql.SqlCryptoStore
import org.matrix.android.sdk.internal.platform.BackgroundTaskScheduler
import org.matrix.android.sdk.internal.platform.CoroutineBackgroundTaskScheduler
import org.matrix.android.sdk.internal.session.content.ContentUriResolver
import org.matrix.android.sdk.internal.session.content.ImageExifTagRemover
import org.matrix.android.sdk.internal.session.content.ThumbnailExtractor
import org.matrix.android.sdk.internal.session.identity.DefaultIdentityService
import org.matrix.android.sdk.internal.session.integrationmanager.DefaultIntegrationManagerService
import org.matrix.android.sdk.internal.session.integrationmanager.IntegrationManager
import org.matrix.android.sdk.internal.session.media.LinkPreviewPrefetcher
import org.matrix.android.sdk.internal.session.media.WebUrlPattern
import org.matrix.android.sdk.internal.session.room.DefaultRoomService
import org.matrix.android.sdk.internal.session.room.send.VideoMetadataExtractor
import org.matrix.android.sdk.internal.session.room.send.pills.TextPillsUtils
import org.matrix.android.sdk.internal.session.room.threads.DefaultThreadsService
import org.matrix.android.sdk.internal.session.room.threads.ThreadsServiceFactory
import org.matrix.android.sdk.internal.session.user.DefaultUserService
import org.matrix.android.sdk.internal.session.widgets.DefaultWidgetService
import org.matrix.android.sdk.internal.session.widgets.DefaultWidgetURLFormatter
import org.matrix.android.sdk.internal.session.workmanager.WorkManagerConfig

/**
 * The desktop answer to AndroidSessionModule: the same list of bindings, with the plain-JVM
 * implementation where there is one and a stub where the feature is android-only.
 */
@Module
internal abstract class DesktopSessionModule {

    @Binds
    abstract fun bindBackgroundTaskScheduler(scheduler: CoroutineBackgroundTaskScheduler): BackgroundTaskScheduler

    @Binds
    abstract fun bindWorkManagerConfig(config: DesktopWorkManagerConfig): WorkManagerConfig

    @Binds
    abstract fun bindCryptoService(service: DefaultCryptoService): CryptoService

    @Binds
    abstract fun bindCryptoStore(store: SqlCryptoStore): IMXCryptoStore

    @Binds
    abstract fun bindCommonCryptoStore(store: SqlCryptoStore): IMXCommonCryptoStore

    @Binds
    abstract fun bindThreadsServiceFactory(factory: DefaultThreadsService.Factory): ThreadsServiceFactory

    @Binds
    abstract fun bindRoomService(service: DefaultRoomService): RoomService

    @Binds
    abstract fun bindUserService(service: DefaultUserService): UserService

    // The widget/identity/integration-manager stacks are shared: only android's WebView-backed JS
    // bridge (WidgetPostAPIMediator) stays behind, and nothing here asks for it.
    @Binds
    abstract fun bindWidgetService(service: DefaultWidgetService): WidgetService

    @Binds
    abstract fun bindWidgetUrlFormatter(formatter: DefaultWidgetURLFormatter): WidgetURLFormatter

    @Binds
    @IntoSet
    abstract fun bindWidgetUrlFormatterAsSessionLifecycleObserver(formatter: DefaultWidgetURLFormatter): SessionLifecycleObserver

    @Binds
    abstract fun bindIdentityService(service: DefaultIdentityService): IdentityService

    @Binds
    @IntoSet
    abstract fun bindIdentityServiceAsSessionLifecycleObserver(service: DefaultIdentityService): SessionLifecycleObserver

    @Binds
    abstract fun bindIntegrationManagerService(service: DefaultIntegrationManagerService): IntegrationManagerService

    @Binds
    @IntoSet
    abstract fun bindIntegrationManager(manager: IntegrationManager): SessionLifecycleObserver

    @Binds
    abstract fun bindThumbnailExtractor(extractor: DesktopThumbnailExtractor): ThumbnailExtractor

    @Binds
    abstract fun bindImageExifTagRemover(remover: DesktopImageExifTagRemover): ImageExifTagRemover

    @Binds
    abstract fun bindContentUriResolver(resolver: DesktopContentUriResolver): ContentUriResolver

    @Binds
    abstract fun bindVideoMetadataExtractor(extractor: DesktopVideoMetadataExtractor): VideoMetadataExtractor

    @Binds
    abstract fun bindTextPillsUtils(utils: DesktopTextPillsUtils): TextPillsUtils

    @Binds
    abstract fun bindLinkPreviewPrefetcher(prefetcher: DesktopLinkPreviewPrefetcher): LinkPreviewPrefetcher

    @Binds
    abstract fun bindWebUrlPattern(pattern: DesktopWebUrlPattern): WebUrlPattern

    @Binds
    abstract fun bindSyncService(service: DesktopSyncService): SyncService

    @Binds
    abstract fun bindFileService(service: DesktopFileService): FileService
}
