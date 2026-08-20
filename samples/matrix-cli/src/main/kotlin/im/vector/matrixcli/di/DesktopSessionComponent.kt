/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.matrixcli.di

import dagger.BindsInstance
import dagger.Component
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.internal.crypto.CryptoModule
import org.matrix.android.sdk.internal.federation.FederationModule
import org.matrix.android.sdk.internal.network.RequestModule
import org.matrix.android.sdk.internal.session.SessionComponent
import org.matrix.android.sdk.internal.session.SessionModule
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.account.AccountModule
import org.matrix.android.sdk.internal.session.admin.AdminModule
import org.matrix.android.sdk.internal.session.cache.CacheModule
import org.matrix.android.sdk.internal.session.call.CallModule
import org.matrix.android.sdk.internal.session.content.ContentModule
import org.matrix.android.sdk.internal.session.contentscanner.ContentScannerModule
import org.matrix.android.sdk.internal.session.filter.FilterModule
import org.matrix.android.sdk.internal.session.homeserver.HomeServerCapabilitiesModule
import org.matrix.android.sdk.internal.session.identity.IdentityModule
import org.matrix.android.sdk.internal.session.integrationmanager.IntegrationManagerModule
import org.matrix.android.sdk.internal.session.media.MediaModule
import org.matrix.android.sdk.internal.session.openid.OpenIdModule
import org.matrix.android.sdk.internal.session.presence.di.PresenceModule
import org.matrix.android.sdk.internal.session.profile.ProfileModule
import org.matrix.android.sdk.internal.session.pushers.PushersModule
import org.matrix.android.sdk.internal.session.room.RoomModule
import org.matrix.android.sdk.internal.session.room.redaction.RedactedContentModule
import org.matrix.android.sdk.internal.session.search.SearchModule
import org.matrix.android.sdk.internal.session.signout.SignOutModule
import org.matrix.android.sdk.internal.session.space.SpaceModule
import org.matrix.android.sdk.internal.session.sync.SyncModule
import org.matrix.android.sdk.internal.session.terms.TermsModule
import org.matrix.android.sdk.internal.session.thirdparty.ThirdPartyModule
import org.matrix.android.sdk.internal.session.user.UserModule
import org.matrix.android.sdk.internal.session.user.accountdata.AccountDataModule
import org.matrix.android.sdk.internal.session.widgets.WidgetModule
import org.matrix.android.sdk.internal.util.system.SystemModule
import org.matrix.android.sdk.internal.worker.BackgroundTaskBodiesModule

@Component(
        dependencies = [DesktopMatrixComponent::class],
        modules = [
            SessionModule::class,
            DesktopSessionModule::class,
            BackgroundTaskBodiesModule::class,
            RoomModule::class,
            SyncModule::class,
            HomeServerCapabilitiesModule::class,
            SignOutModule::class,
            UserModule::class,
            FilterModule::class,
            ContentModule::class,
            CacheModule::class,
            MediaModule::class,
            CryptoModule::class,
            SystemModule::class,
            PushersModule::class,
            OpenIdModule::class,
            WidgetModule::class,
            IntegrationManagerModule::class,
            IdentityModule::class,
            TermsModule::class,
            AccountDataModule::class,
            ProfileModule::class,
            AccountModule::class,
            FederationModule::class,
            AdminModule::class,
            RedactedContentModule::class,
            CallModule::class,
            ContentScannerModule::class,
            SearchModule::class,
            ThirdPartyModule::class,
            SpaceModule::class,
            PresenceModule::class,
            RequestModule::class,
        ]
)
@SessionScope
internal interface DesktopSessionComponent : SessionComponent {

    @Component.Factory
    interface Factory {
        fun create(
                matrixComponent: DesktopMatrixComponent,
                @BindsInstance sessionParams: SessionParams
        ): DesktopSessionComponent
    }
}
