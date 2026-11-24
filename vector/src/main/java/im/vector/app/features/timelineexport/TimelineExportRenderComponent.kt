/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import dagger.Module
import dagger.Subcomponent
import dagger.hilt.InstallIn
import dagger.hilt.android.scopes.ActivityScoped
import dagger.hilt.components.SingletonComponent
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactory
import im.vector.app.features.home.room.detail.timeline.helper.LocationPinProvider
import im.vector.app.features.home.room.detail.timeline.helper.MessageInformationDataFactory
import im.vector.app.features.home.room.detail.timeline.helper.TimelineMediaSizeProvider
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContentBuilder
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayoutFactory
import im.vector.app.features.home.room.detail.timeline.tools.RoomTopicRenderer
import im.vector.app.features.html.PillsPostProcessor
import im.vector.app.features.location.UrlMapProvider
import im.vector.app.features.media.ImageContentRenderer

@ActivityScoped
@Subcomponent
interface TimelineExportRenderComponent {
    fun previews(): MessagePreviewContentBuilder
    fun richBodies(): RichMessageBodyRenderer
    fun pills(): PillsPostProcessor.Factory
    fun pins(): LocationPinProvider
    fun topics(): RoomTopicRenderer
    fun dates(): VectorDateFormatter
    fun layouts(): TimelineMessageLayoutFactory
    fun items(): TimelineItemFactory
    fun avatars(): AvatarRenderer
    fun maps(): UrlMapProvider
    fun mediaSizes(): TimelineMediaSizeProvider
    fun images(): ImageContentRenderer
    fun information(): MessageInformationDataFactory

    @Subcomponent.Factory
    interface Factory {
        fun create(): TimelineExportRenderComponent
    }
}

@Module(subcomponents = [TimelineExportRenderComponent::class])
@InstallIn(SingletonComponent::class)
object TimelineExportRenderingModule
