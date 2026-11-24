/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.selection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import im.vector.app.features.timelineexport.ExportBlocks
import im.vector.app.features.timelineexport.ExportEvent
import im.vector.app.features.timelineexport.ExportPerson
import im.vector.app.features.timelineexport.ExportPngWriter
import im.vector.app.features.timelineexport.ExportPrivacy
import im.vector.app.features.timelineexport.ExportSplitPlanner
import im.vector.app.features.timelineexport.TimelineExportDraft
import im.vector.app.features.timelineexport.TimelineExportHtml
import im.vector.app.features.timelineexport.TimelineExportOptions
import im.vector.app.features.timelineexport.TimelineExportSnapshot
import im.vector.app.features.timelineexport.TimelineExportStore
import im.vector.app.features.timelineexport.exportPlainText
import im.vector.app.features.timelineexport.forPreview
import im.vector.app.features.timelineexport.hasAnimation
import im.vector.app.features.timelineexport.noticeText
import im.vector.app.features.timelineexport.withAppearance
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.profile.AnonymizationConfig
import org.matrix.android.sdk.api.session.profile.AnonymizationRule
import org.matrix.android.sdk.api.session.profile.IdentityAliases
import org.matrix.android.sdk.api.util.MatrixJsonParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TimelineExportTest {
    @org.junit.Before fun useTestMainDispatcher() {
        kotlinx.coroutines.Dispatchers.setMain(android.os.Handler(android.os.Looper.getMainLooper()).asCoroutineDispatcher())
    }

    @org.junit.After fun resetMainDispatcher() { kotlinx.coroutines.Dispatchers.resetMain() }
    private val self = "@river:example.org"
    private val other = "@fern:example.org"
    private val room = "!garden:example.org"

    private fun exportRenderer(): im.vector.app.features.timelineexport.TimelineExportRenderer {
        val context = RuntimeEnvironment.getApplication().apply { setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light) }
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val session = io.mockk.mockk<org.matrix.android.sdk.api.session.Session>(relaxed = true)
        io.mockk.every { session.fileService().getLocalFileFor(any(), any(), any(), any()) } returns null
        val active = io.mockk.mockk<im.vector.app.core.di.ActiveSessionHolder>(relaxed = true)
        io.mockk.every { active.getSafeActiveSession() } returns null
        val avatarPreferences = io.mockk.mockk<im.vector.app.features.settings.VectorPreferences>(relaxed = true) {
            io.mockk.every { avatarShape() } returns im.vector.app.features.settings.AvatarShape.CIRCLE
            io.mockk.every { peopleAvatarStyle() } returns im.vector.app.features.home.avatar.DefaultAvatarStyle.ELEMENT
            io.mockk.every { roomAvatarStyle() } returns im.vector.app.features.home.avatar.DefaultAvatarStyle.HASHTAG
            io.mockk.every { peopleColorPalette() } returns im.vector.app.core.ui.colorpicker.ColorPalette.LEGACY
            io.mockk.every { roomColorPalette() } returns im.vector.app.core.ui.colorpicker.ColorPalette.LEGACY
        }
        val identityColors = im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider(im.vector.app.core.resources.ColorProvider(context),
                avatarPreferences, io.mockk.mockk(relaxed = true), javax.inject.Provider { active })
        val colors = im.vector.app.features.home.room.detail.timeline.MessageColorProvider(im.vector.app.core.resources.ColorProvider(context), identityColors, avatarPreferences)
        val avatars = im.vector.app.features.home.AvatarRenderer(active, identityColors,
                im.vector.app.core.utils.DimensionConverter(context.resources), im.vector.app.core.resources.StringProvider(context.resources),
                avatarPreferences, im.vector.app.features.home.avatar.DefaultAvatarFactory(avatarPreferences,
                        io.mockk.mockk { io.mockk.every { enabled } returns false }), io.mockk.mockk(relaxed = true))
        val upload = im.vector.app.features.home.room.detail.timeline.helper.ContentUploadStateTrackerBinder(active, colors)
        val download = im.vector.app.features.home.room.detail.timeline.helper.ContentDownloadStateTrackerBinder(active)
        val information = io.mockk.mockk<im.vector.app.features.home.room.detail.timeline.helper.MessageInformationDataFactory>()
        io.mockk.every { information.create(any()) } answers { firstArg<im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactoryParams>().presentation!!.informationData }
        val mediaSizes = im.vector.app.features.home.room.detail.timeline.helper.TimelineMediaSizeProvider(context, context.resources, io.mockk.mockk(relaxed = true))
        val attributes = io.mockk.mockk<im.vector.app.features.home.room.detail.timeline.helper.MessageItemAttributesFactory>()
        io.mockk.every { attributes.create(any(), any(), any(), any(), any()) } answers {
            im.vector.app.features.home.room.detail.timeline.item.AbsMessageItem.Attributes(dp(30), secondArg(), avatars, colors)
        }
        val html = io.mockk.mockk<im.vector.app.features.html.EventHtmlRenderer>(relaxed = true)
        val markup = io.noties.markwon.Markwon.builder(context).usePlugin(io.noties.markwon.html.HtmlPlugin.create { plugin ->
            plugin.addHandler(im.vector.app.features.html.MxEmoticonTagHandler(active))
        }).build()
        io.mockk.every { html.render(any<String>(), *anyVararg()) } answers {
            android.text.SpannableStringBuilder(markup.toMarkdown(firstArg())).also { text ->
                secondArg<Array<out im.vector.app.features.html.EventHtmlRenderer.PostProcessor>>().forEach { it.afterRender(text) }
            }
        }
        io.mockk.every { html.setTextWithPlugins(any(), any()) } answers { firstArg<android.widget.TextView>().text = secondArg<CharSequence?>(); Unit }
        io.mockk.every { html.plugins } returns markup.plugins
        io.mockk.every { html.removeLeadingNewlineForInlineElement(any()) } answers { firstArg() }
        val textRenderer = io.mockk.mockk<im.vector.app.features.home.room.detail.timeline.render.EventTextRenderer>()
        io.mockk.every { textRenderer.render(any(), any()) } answers { firstArg() }
        val suffix = im.vector.app.features.home.room.detail.timeline.helper.MessageSuffixFormatter(
                im.vector.app.core.resources.StringProvider(context.resources), im.vector.app.core.resources.ColorProvider(context),
                im.vector.app.core.utils.DimensionConverter(context.resources), io.mockk.mockk(relaxed = true))
        val avatarSizes = io.mockk.mockk<im.vector.app.features.home.room.detail.timeline.helper.AvatarSizeProvider>()
        io.mockk.every { avatarSizes.avatarSize } returns dp(30)
        io.mockk.every { avatarSizes.leftGuideline } returns dp(38)
        io.mockk.every { avatarSizes.nonMessageGuideline(any<im.vector.app.features.home.room.detail.timeline.item.MessageInformationData>()) } returns dp(38)
        val notices = im.vector.app.features.home.room.detail.timeline.factory.NoticeItemFactory(io.mockk.mockk(relaxed = true), avatars, information, avatarSizes)
        val fallback = io.mockk.mockk<im.vector.app.features.home.room.detail.timeline.factory.DefaultItemFactory>()
        io.mockk.every { fallback.create(any<im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactoryParams>(), any()) } answers {
            throw (secondArg<Throwable?>() ?: IllegalStateException("Unexpected default timeline item"))
        }
        val images = im.vector.app.features.media.ImageContentRenderer(context, io.mockk.mockk(relaxed = true), active,
                im.vector.app.core.utils.DimensionConverter(context.resources), io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true))
        val translations = io.mockk.mockk<im.vector.app.features.translation.MessageTranslationStore>()
        io.mockk.every { translations.get(any<String>()) } returns null
        io.mockk.every { translations.get(any<org.matrix.android.sdk.api.session.room.timeline.TimelineEvent>()) } returns null
        val pills = io.mockk.mockk<im.vector.app.features.html.PillsPostProcessor.Factory> {
            io.mockk.every { create(any()) } answers { im.vector.app.features.html.PillsPostProcessor(firstArg(), context, avatars, active, io.mockk.mockk(relaxed = true), avatarPreferences) }
        }
        val rich = im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer(dagger.Lazy { html },
                im.vector.app.core.utils.DimensionConverter(context.resources), avatarPreferences, io.mockk.mockk(relaxed = true))
        val forwarded = im.vector.app.features.home.room.detail.timeline.helper.ForwardedMessagePresenter(context, dagger.Lazy { active },
                im.vector.app.core.resources.StringProvider(context.resources), identityColors, io.mockk.mockk(relaxed = true))
        val previews = im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContentBuilder(
                active, im.vector.app.core.resources.StringProvider(context.resources), avatarPreferences, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true),
                dagger.Lazy { html }, io.mockk.mockk { io.mockk.every { compress(any()) } answers { firstArg() } }, pills,
                io.mockk.mockk { io.mockk.every { create(any()) } returns textRenderer }, forwarded, translations,
                io.mockk.mockk(relaxed = true) { io.mockk.every { peekDecryptedBody(any()) } returns null },
                io.mockk.mockk(relaxed = true) { io.mockk.every { restoreEvent(any()) } returns null },
                io.mockk.mockk(relaxed = true) { io.mockk.every { fileFor(any(), any()) } returns null }, suffix,
                io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true),
                im.vector.app.core.utils.DimensionConverter(context.resources))
        val messages = im.vector.app.features.home.room.detail.timeline.factory.MessageItemFactory(
                localFilesHelper = io.mockk.mockk(relaxed = true),
                colorProvider = im.vector.app.core.resources.ColorProvider(context),
                dimensionConverter = im.vector.app.core.utils.DimensionConverter(context.resources),
                timelineMediaSizeProvider = mediaSizes,
                htmlRenderer = dagger.Lazy { html },
                htmlCompressor = io.mockk.mockk { io.mockk.every { compress(any()) } answers { firstArg() } },
                textRendererFactory = io.mockk.mockk { io.mockk.every { create(any()) } returns textRenderer },
                stringProvider = im.vector.app.core.resources.StringProvider(context.resources),
                imageContentRenderer = images,
                messageInformationDataFactory = information,
                messageItemAttributesFactory = attributes,
                contentUploadStateTrackerBinder = upload,
                contentDownloadStateTrackerBinder = download,
                defaultItemFactory = fallback,
                noticeItemFactory = notices,
                avatarSizeProvider = avatarSizes,
                pillsPostProcessorFactory = pills,
                lightweightSettingsStorage = io.mockk.mockk(relaxed = true),
                spanUtils = io.mockk.mockk { io.mockk.every { getBindingOptions(any()) } returns im.vector.app.features.home.room.detail.timeline.item.BindingOptions(false) },
                session = session,
                clock = io.mockk.mockk(relaxed = true),
                audioMessagePlaybackTracker = im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker(),
                locationPinProvider = io.mockk.mockk(relaxed = true),
                vectorPreferences = io.mockk.mockk(relaxed = true),
                preservedMediaStore = io.mockk.mockk(relaxed = true),
                urlMapProvider = io.mockk.mockk(relaxed = true),
                liveLocationShareMessageItemFactory = io.mockk.mockk(relaxed = true),
                pollItemViewStateFactory = im.vector.app.features.home.room.detail.timeline.factory.PollItemViewStateFactory(im.vector.app.core.resources.StringProvider(context.resources),
                    im.vector.app.features.home.room.detail.timeline.factory.PollOptionViewStateFactory(), io.mockk.mockk(relaxed = true)),
                processBodyOfReplyToEventUseCase = io.mockk.mockk {
                    io.mockk.every { stripExistingMxReply(any()) } answers { firstArg() }
                },
                richMessageBodyRenderer = rich,
                mediaContentRevealManager = io.mockk.mockk(relaxed = true),
                sendingMediaGate = io.mockk.mockk(relaxed = true),
                pgpDecryptor = io.mockk.mockk(relaxed = true) { io.mockk.every { peekDecryptedBody(any()) } returns null },
                messageTranslationStore = translations,
                displayableEventFormatter = io.mockk.mockk(relaxed = true),
                messageSuffixFormatter = suffix,
                codeBlockHighlighter = io.mockk.mockk(relaxed = true),
        )
        val factory = im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactory(
                messages, io.mockk.mockk(relaxed = true), notices, fallback,
                io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true),
                io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true))
        val component = io.mockk.mockk<im.vector.app.features.timelineexport.TimelineExportRenderComponent>()
        io.mockk.every { component.layouts() } returns im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayoutFactory(
                session, io.mockk.mockk(relaxed = true),
                im.vector.app.features.themes.BubbleThemeUtils(context), io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true))
        io.mockk.every { component.previews() } returns previews
        io.mockk.every { component.richBodies() } returns rich
        io.mockk.every { component.pills() } returns pills
        io.mockk.every { component.pins() } returns im.vector.app.features.home.room.detail.timeline.helper.LocationPinProvider(context,
                im.vector.app.core.utils.DimensionConverter(context.resources), avatars, identityColors)
        io.mockk.every { component.items() } returns factory
        io.mockk.every { component.mediaSizes() } returns mediaSizes
        io.mockk.every { component.avatars() } returns avatars
        io.mockk.every { component.images() } returns images
        io.mockk.every { component.topics() } returns im.vector.app.features.home.room.detail.timeline.tools.RoomTopicRenderer(active, html,
                io.mockk.mockk { io.mockk.every { compress(any()) } answers { firstArg() } }, io.mockk.mockk(relaxed = true),
                io.mockk.mockk { io.mockk.every { create(any()) } returns textRenderer }, io.mockk.mockk(relaxed = true))
        io.mockk.every { component.dates() } returns io.mockk.mockk {
            io.mockk.every { formatDayDivider(any(), any()) } answers { ExportBlocks.day(firstArg(), secondArg<java.util.TimeZone>().id) }
            io.mockk.every { formatMessageTime(any(), any(), any()) } answers {
                java.text.SimpleDateFormat(if (thirdArg()) "h:mm a" else "HH:mm", java.util.Locale.US).apply { timeZone = secondArg() }.format(java.util.Date(firstArg<Long>()))
            }
            io.mockk.every { formatFullDate(any(), any(), any()) } answers {
                java.text.SimpleDateFormat(if (thirdArg()) "EEE, d MMM yyyy h:mm a" else "EEE, d MMM yyyy HH:mm", java.util.Locale.US).apply { timeZone = secondArg() }.format(java.util.Date(firstArg<Long>()))
            }
        }
        return im.vector.app.features.timelineexport.TimelineExportRenderer(object : im.vector.app.features.timelineexport.TimelineExportRenderComponent.Factory {
            override fun create() = component
        })
    }

    private fun layout(renderer: im.vector.app.features.timelineexport.TimelineExportRenderer, context: android.content.Context,
                       snapshot: TimelineExportSnapshot, directory: File, previous: im.vector.app.features.timelineexport.TimelineExportRenderer.Document? = null): im.vector.app.features.timelineexport.TimelineExportRenderer.Document {
        val task = java.util.concurrent.FutureTask { kotlinx.coroutines.runBlocking { renderer.layout(context, snapshot, directory, previous, reuseMeasurements = previous != null) } }
        Thread(task, "export-layout-fixture").start()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        while (!task.isDone && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        return task.get(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun <T> runNative(block: suspend () -> T): T {
        val task = java.util.concurrent.FutureTask { kotlinx.coroutines.runBlocking { block() } }
        Thread(task, "export-native-fixture").start()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
        while (!task.isDone && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        return task.get(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test fun `export popup default follows preview and prefers animation`() {
        val current = TimelineExportOptions(format = "JSON", singleHtml = true)
        assertEquals("HTML", current.forPreview(html = true, animated = true).format)
        assertEquals("WEBP", current.forPreview(html = false, animated = true).format)
        assertEquals("PNG", current.forPreview(html = false, animated = false).format)
        assertTrue(current.forPreview(html = true, animated = false).singleHtml)
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `single HTML round trip retains media avatars and native archive row rendering`() {
        val context = RuntimeEnvironment.getApplication().apply { setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light) }
        val directory = File(context.cacheDir, "single-html-native-fixture").also { File(it, "assets").mkdirs() }
        val store = im.vector.app.features.timelineexport.TimelineArchiveStore(context, archiveImporter())
        var importedId: String? = null
        try {
            val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
            try { File(directory, "assets/flower.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
            val flower = File(directory, "assets/flower.png")
            flower.appendBytes(ByteArray(20_000).also { java.util.Random(1).nextBytes(it) })
            val person = ExportPerson(other, "Fern", 0xff778899.toInt(), "assets/flower.png")
            val event = ExportEvent("e-archive-flower", "m.room.message", 1000, person, "Flower", "",
                    mapOf("content" to mapOf("msgtype" to "m.image", "body" to "Flower", "url" to "assets/flower.png", "info" to mapOf("w" to 80, "h" to 60, "mimetype" to "image/png"))),
                    attachment = "assets/flower.png", attachmentMime = "image/png", isRevealedRedaction = true)
            val snapshot = TimelineExportSnapshot(self, "fixture", "Garden", "", "", "Fern", 2000, 1, listOf(event),
                    listOf(im.vector.app.features.timelineexport.ExportAsset("assets/flower.png", "image/png")),
                    TimelineExportOptions(format = "HTML", singleHtml = true, roomHeader = false, theme = "light"), roomPerson = person.copy(id = room))
            val html = im.vector.app.features.timelineexport.NativeTimelineHtmlExporter.document(snapshot,
                    listOf(im.vector.app.features.timelineexport.NativeTimelineHtmlExporter.Layout(360, "<article><span class=\"drawing i0\"></span></article>")),
                    images = mapOf("assets/flower.png" to "i0"))
            File(directory, "messages.html").writeText(html)
            val single = File(directory, "Voyage_export_fixture.html")
            runNative { TimelineExportHtml.singleFile(directory, single, listOf("messages.html", "assets/flower.png"), snapshot.assets) }
            val source = single.readText()
            assertEquals(1, "data:image/png;base64,".toRegex().findAll(source).count())
            // Inlined in the stylesheet, where it shows without JavaScript; the asset map points there instead of repeating it.
            assertTrue(org.jsoup.Jsoup.parse(source).getElementById("voyage-images")!!.data().contains(".i0{background-image:url(\"data:image/png;base64,"))
            assertTrue(source.contains("\"assets/flower.png\":\"css:0\""))
            assertFalse(source.contains("emoji-attribution"))
            importedId = runNative { store.importArchive(self, single.inputStream(), single.name) }
            val restored = store.read(self, importedId)
            assertEquals(1, restored.assets.size)
            assertNotNull(restored.roomPerson?.avatar)
            assertNotNull(restored.events.single().person.avatar)
            assertNotNull(restored.events.single().attachment)
            assertArrayEquals(flower.readBytes(), File(store.directory(self, importedId), restored.events.single().attachment!!).readBytes())
            assertTrue(restored.events.single().isRevealedRedaction)
            val renderer = exportRenderer()
            val document = layout(renderer, context, restored.copy(options = restored.options.copy(roomHeader = false)), store.directory(self, importedId))
            try {
                document.clearBoundRows()
                val adapter = im.vector.app.features.timelineexport.TimelineArchiveAdapter(document)
                val holder = adapter.onCreateViewHolder(android.widget.FrameLayout(context), adapter.getItemViewType(0))
                adapter.onBindViewHolder(holder, 0)
                holder.itemView.measure(android.view.View.MeasureSpec.makeMeasureSpec(document.logicalWidth, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
                holder.itemView.layout(0, 0, document.logicalWidth, holder.itemView.measuredHeight)
                document.images.put(restored.events.single().person.avatar!!, BitmapFactory.decodeFile(File(store.directory(self, importedId), restored.events.single().person.avatar!!).path))
                runNative { renderer.prepareAssets(document, bindRows = false); document.context.awaitMedia(listOf(holder.itemView)) }
                for (id in listOf(im.vector.app.R.id.messageThumbnailView, im.vector.app.R.id.messageAvatarImageView)) {
                    val image = holder.itemView.findViewById<android.widget.ImageView>(id)
                    assertNotNull(image)
                    assertEquals(android.view.View.VISIBLE, image.visibility)
                    val drawn = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
                    try {
                        image.drawable.setBounds(0, 0, 80, 60)
                        image.drawable.draw(android.graphics.Canvas(drawn))
                        assertEquals("Archive thumbnail/avatar must show the imported pixels", android.graphics.Color.RED, drawn.getPixel(40, 30))
                    } finally { drawn.recycle() }
                }
                val info = (document.models.first() as im.vector.app.features.home.room.detail.timeline.item.AbsMessageItem<*>).attributes.informationData
                assertTrue(info.isRevealedRedaction)
                adapter.onViewRecycled(holder)
            } finally { document.release() }
        } finally { importedId?.let { store.delete(self, it) }; directory.deleteRecursively() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `HTML reuses bitmap assets across layout sizes and embeds clipping masks`() {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "html-bitmap-reuse").also { it.mkdirs() }
        val artwork = im.vector.app.features.timelineexport.TimelineHtmlCanvas.Artwork(directory)
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        try {
            for (size in listOf(40, 80, 160)) {
                val canvas = im.vector.app.features.timelineexport.TimelineHtmlCanvas(300, 300, artwork)
                canvas.drawBitmap(bitmap, null, android.graphics.RectF(0f, 0f, size.toFloat(), size * .75f), null)
                assertTrue(canvas.html.contains("mask-image:url('data:image/png;base64,"))
                artwork.flush()
            }
            assertEquals("Changing layout size must not duplicate the source image", 1, artwork.files.size)
            val stored = BitmapFactory.decodeFile(File(directory, artwork.files.single()).path)
            try { assertEquals(80, stored.width); assertEquals(60, stored.height) } finally { stored.recycle() }
        } finally { artwork.clearReferences(); bitmap.recycle(); directory.deleteRecursively() }
    }

    @Test fun `HTML archive does not include JSON sidecars`() {
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, 0, emptyList(),
                listOf(im.vector.app.features.timelineexport.ExportAsset("assets/photo.png", "image/png")), TimelineExportOptions(format = "HTML"))
        val entries = TimelineExportHtml.archiveEntries(snapshot, listOf("messages.html"))
        assertEquals(listOf("messages.html", "assets/photo.png"), entries)
        assertEquals(listOf("export.json", "voyage.json", "assets/photo.png"),
                TimelineExportHtml.archiveEntries(snapshot.copy(options = snapshot.options.copy(format = "JSON")), emptyList()))
    }

    @Test fun `staging size ignores cached assets outside the prepared export`() {
        val context = RuntimeEnvironment.getApplication()
        val store = TimelineExportStore(context)
        val directory = File(context.cacheDir, "export-stage-space").also { File(it, "assets").mkdirs() }
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, 0, emptyList(),
                listOf(im.vector.app.features.timelineexport.ExportAsset("assets/kept.png", "image/png")), TimelineExportOptions())
        try {
            File(directory, "assets/kept.png").writeBytes(byteArrayOf(1, 2, 3))
            val before = store.stageBytes(snapshot, directory)
            File(directory, "assets/excluded.png").writeBytes(ByteArray(1024 * 1024))
            assertEquals(before, store.stageBytes(snapshot, directory))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `split planner preserves event boundaries and only cuts oversized events`() {
        assertEquals(listOf(ExportSplitPlanner.Part(0, 200), ExportSplitPlanner.Part(200, 500), ExportSplitPlanner.Part(700, 100)),
                ExportSplitPlanner.split(listOf(100, 200, 800), 800, 500))
        assertEquals(listOf(ExportSplitPlanner.Part(0, 800)), ExportSplitPlanner.split(listOf(100, 800), 800, 0))
    }

    @Test fun `unset anonymous labels use the app's localized defaults`() {
        val previous = org.matrix.android.sdk.api.session.profile.IdentityPresentation.defaultLabel
        try {
            org.matrix.android.sdk.api.session.profile.IdentityPresentation.defaultLabel = { self -> if (self) "Moi" else "Anonyme" }
            val config = AnonymizationConfig(naming = "SHARED")
            assertEquals("Moi", config.name(self, self))
            assertEquals("Anonyme", config.name(other, self))
            assertEquals("Visitor", config.copy(sharedLabel = "Visitor").name(other, self))
        } finally {
            org.matrix.android.sdk.api.session.profile.IdentityPresentation.defaultLabel = previous
        }
    }

    @Test fun `anonymous rules list people to keep or to anonymize with room overrides and custom shared labels`() {
        val config = AnonymizationConfig(rule = AnonymizationRule(enabled = true, onlyListed = false, users = setOf(other)),
                rooms = mapOf(room to AnonymizationRule(enabled = true, onlyListed = true, users = setOf(other))), naming = "SHARED", sharedLabel = "Visitor")
        assertTrue(config.includes(self))
        assertFalse(config.includes(other))
        assertTrue(config.includes(other, room))
        assertFalse(config.includes(self, room))
        assertFalse(AnonymizationConfig(rule = AnonymizationRule(enabled = false, onlyListed = true, users = setOf(other))).includes(other))
        assertFalse(AnonymizationConfig(rooms = mapOf(room to AnonymizationRule(enabled = false))).includes(self, room))
        assertEquals("Me", config.name(self, self))
        assertEquals("Visitor", config.name(other, self))
        assertEquals(IdentityAliases.name(other), IdentityAliases.name(other))
        assertEquals(2, IdentityAliases.name(other).split(' ').size)
        assertTrue(IdentityAliases.id(other).matches(Regex("@[0-9a-f]{8}:anonymized\\.local")))
        assertEquals("@lazy_dog_2:anonymized.local", IdentityAliases.id(other, "Lazy Dog 2"))
    }

    @Test fun `excluded reply target loses quotes in text html and JSON`() {
        val options = TimelineExportOptions(identity = AnonymizationConfig(rule = AnonymizationRule(enabled = true)))
        val privacy = ExportPrivacy(options, self, room)
        val content = mapOf("body" to "> <@fern:example.org> private quotation\n\nA reply", "format" to "org.matrix.custom.html",
                "formatted_body" to "<mx-reply><blockquote>private quotation</blockquote></mx-reply><p>A reply</p>",
                "m.relates_to" to mapOf("m.in_reply_to" to mapOf("event_id" to "\$outside")))
        val value = privacy.event(Event(eventId = "\$reply", senderId = other, type = "m.room.message", content = content), content, setOf("\$reply"))
        val json = MatrixJsonParser.getMoshi().adapter(Any::class.java).toJson(value)
        assertFalse(json.contains("private quotation"))
        assertFalse(json.contains(other))
        assertFalse(json.contains("m.in_reply_to"))
        assertTrue(json.contains("A reply"))
    }

    @Test fun `ordinary quoted prose is preserved when the message is not a reply`() {
        val privacy = ExportPrivacy(TimelineExportOptions(), self, room)
        val quote = "> A literary quotation\n\nA comment"
        assertEquals(quote, privacy.body(mapOf("body" to quote)))
    }

    @Test fun `plain body replaces an anonymized mention's display name`() {
        val privacy = ExportPrivacy(TimelineExportOptions(identity = AnonymizationConfig(rule = AnonymizationRule(enabled = true))), self, room)
        val body = privacy.body(mapOf("body" to "Fern Gully: are you coming? Ferns grow here.",
                "formatted_body" to "<a href=\"https://matrix.to/#/$other\">Fern Gully</a>: are you coming? Ferns grow here.",
                "m.mentions" to mapOf("user_ids" to listOf(other))))
        assertFalse(body.contains("Fern Gully"))
        assertTrue(body.startsWith(privacy.alias(other) + ":"))
        assertTrue(body.endsWith("Ferns grow here."))
    }

    @Test fun `a one-letter mention name only replaces whole words`() {
        val privacy = ExportPrivacy(TimelineExportOptions(identity = AnonymizationConfig(rule = AnonymizationRule(enabled = true))), self, room)
        val body = privacy.body(mapOf("body" to "F: Fine", "formatted_body" to "<a href=\"https://matrix.to/#/$other\">F</a>: Fine"))
        assertEquals(privacy.alias(other) + ": Fine", body)
    }

    @Test fun `HTML rejects executable content and remote images while retaining local emotes`() {
        val privacy = ExportPrivacy(TimelineExportOptions(), self, room, mapOf("mxc://example.org/emote" to "assets/emote.png"))
        val html = privacy.html("<script>alert(1)</script><img src='https://example.org/tracker' onerror='alert(2)'><img src='mxc://example.org/emote' data-mx-emoticon alt='wave'><a href='javascript:alert(3)'>bad</a>")
        assertFalse(html.contains("script"))
        assertFalse(html.contains("onerror"))
        assertFalse(html.contains("tracker"))
        assertTrue(html.contains("assets/emote.png"))
    }

    @Test fun `HTML keeps the Matrix formatting the timeline renders`() {
        val privacy = ExportPrivacy(TimelineExportOptions(), self, room)
        val html = privacy.html("<pre><code class=\"language-kotlin\">val a = 1</code></pre><font color=\"#ff0000\" data-mx-color=\"#ff0000\">red</font>" +
                "<span data-mx-bg-color=\"#00ff00\">green</span><hr><div data-mx-maths=\"x^2\">x</div>")
        assertTrue(html.contains("class=\"language-kotlin\""))
        assertTrue(html.contains("<font color=\"#ff0000\" data-mx-color=\"#ff0000\">"))
        assertTrue(html.contains("data-mx-bg-color=\"#00ff00\""))
        assertTrue(html.contains("<hr>"))
        assertTrue(html.contains("<div data-mx-maths=\"x^2\">"))
    }

    @Test fun `encrypted attachment JSON uses packaged decrypted file without encryption metadata`() {
        val privacy = ExportPrivacy(TimelineExportOptions(), self, room, mapOf("mxc://example.org/image" to "assets/image.png"))
        val clean = privacy.content(mapOf("file" to mapOf("url" to "mxc://example.org/image", "key" to mapOf("k" to "fictional-key")), "msgtype" to "m.image"))
        assertEquals("assets/image.png", clean["url"])
        assertFalse(clean.containsKey("file"))
    }

    @Test fun `omitted gallery attachments do not reuse files packaged for other events`() {
        val url = "mxc://example.org/shared"
        val privacy = ExportPrivacy(TimelineExportOptions(omitAttachments = true), self, room, mapOf(url to "assets/shared.png"))
        val content = mapOf("body" to "Gallery", "itemtypes" to listOf(
                mapOf("itemtype" to "m.image", "url" to url, "info" to mapOf("thumbnail_url" to url, "w" to 80)),
                mapOf("itemtype" to "m.video", "file" to mapOf("url" to url))))
        val clean = privacy.eventContent(Event(type = "m.room.message"), content)
        val items = (clean.getValue("itemtypes") as List<*>).filterIsInstance<Map<*, *>>()
        assertEquals(2, items.size)
        assertEquals(mapOf("w" to 80), items.first()["info"])
        assertFalse(clean.toString().contains("assets/shared.png"))
    }

    @Test fun `anonymization retains Matrix field names and empty state keys`() {
        val options = TimelineExportOptions(identity = AnonymizationConfig(rule = AnonymizationRule(enabled = true)))
        val privacy = ExportPrivacy(options, self, room)
        val content = mapOf("body" to "A message", "msgtype" to "m.text")
        val output = privacy.event(Event(eventId = "\$entry", senderId = other, type = "m.room.message", stateKey = "", content = content), content, emptySet())
        assertEquals(setOf("body", "msgtype"), (output["content"] as Map<*, *>).keys)
        assertEquals("", output["state_key"])
        assertFalse(options.identity.includes("body"))
    }

    @Test fun `reaction keys survive content sanitation`() {
        val privacy = ExportPrivacy(TimelineExportOptions(), self, room)
        val clean = privacy.content(mapOf("m.relates_to" to mapOf("key" to "yes", "event_id" to "\$target", "rel_type" to "m.annotation")))
        assertEquals("yes", (clean["m.relates_to"] as Map<*, *>)["key"])
    }

    @Test fun `PNG encoder streams strips into one valid image with correct pixels`() {
        val output = ByteArrayOutputStream()
        val first = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val last = Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        ExportPngWriter(output, 4, 3).use { writer -> writer.append(first); writer.append(last) }
        val bytes = output.toByteArray()
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(4, decoded.width)
        assertEquals(3, decoded.height)
        assertEquals(android.graphics.Color.RED, decoded.getPixel(0, 0))
        assertEquals(android.graphics.Color.BLUE, decoded.getPixel(0, 2))
        first.recycle(); last.recycle(); decoded.recycle()
    }

    @Test fun `JSON contains only prepared events`() {
        val context = RuntimeEnvironment.getApplication()
        val person = ExportPerson(IdentityAliases.id(other), "Amber Otter", 0xff778899.toInt())
        val event = ExportEvent("e123", "m.room.message", 1000, person, "A safe message", "", mapOf("type" to "m.room.message", "content" to mapOf("body" to "A safe message")), replyMissing = true)
        val snapshot = TimelineExportSnapshot(self, "draft", "<script>hostile</script>", "", "", "Me", 2000, 2, listOf(event), emptyList(), TimelineExportOptions(format = "HTML"))
        val directory = File(context.cacheDir, "export-test").also { it.mkdirs() }
        try {
            TimelineExportHtml().write(snapshot, directory)
            val json = File(directory, "export.json").readText()
            assertTrue(json.contains("room_name"))
            assertTrue(json.contains("messages"))
            assertFalse(json.contains(other))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `drafts survive store recreation and remain isolated by owner and thread`() {
        val context = RuntimeEnvironment.getApplication()
        val store = TimelineExportStore(context)
        val id = store.draftId(self, room, "\$thread")
        val draft = TimelineExportDraft(id, self, room, "\$thread", listOf("\$message"))
        try {
            store.save(draft)
            assertEquals(draft, TimelineExportStore(context).read(self, room, "\$thread"))
            assertNull(store.read(other, room, "\$thread"))
            assertNull(store.read(self, room, null))
        } finally { store.discard(self, id) }
    }

    @Test fun `notice groups do not merge across days or include non-notices`() {
        val person = ExportPerson(other, "Fern", 0)
        fun event(id: String, timestamp: Long, group: String?) = ExportEvent(id, "m.room.member", timestamp, person, "joined", "", emptyMap(), notice = group != null, group = group)
        val groups = ExportBlocks.build(listOf(event("a", 1000, "membership"), event("b", 2000, "membership"),
                event("c", 172800000, "membership"), event("d", 172800001, null)))
        assertEquals(listOf(2, 1, 1), groups.map { it.events.size })
        assertEquals("a", groups.first().groupId)
    }
    @Test fun `an older asynchronous save cannot replace a newer draft revision`() {
        val store = TimelineExportStore(RuntimeEnvironment.getApplication())
        val id = store.draftId(self, room, null)
        val newer = TimelineExportDraft(id, self, room, eventIds = listOf("\$newer"), updatedAt = 124)
        try {
            store.save(newer)
            store.save(newer.copy(eventIds = listOf("\$older"), updatedAt = 123))
            assertEquals(newer, store.read(self, room, null))
        } finally { store.discard(self, id) }
    }

    @Test fun `room identity exclusions also remove names and aliases from selected state events`() {
        val privacy = ExportPrivacy(TimelineExportOptions(hideRoomIdentity = true), self, room, roomLabel = "Exported room")
        val content = mapOf("name" to "A private room name")
        val event = Event(eventId = "\$state", roomId = room, type = "m.room.name", stateKey = "", content = content)
        assertEquals("Exported room", (privacy.event(event, content, setOf("\$state"))["content"] as Map<*, *>)["name"])
        val aliasContent = mapOf("alias" to "#garden:example.org", "alt_aliases" to listOf("#old-garden:example.org"))
        val alias = Event(type = "m.room.canonical_alias", content = aliasContent)
        assertFalse(privacy.eventContent(alias, aliasContent).containsKey("alias"))
    }

    @Test fun `native split boundaries keep date headers attached to following events`() {
        val context = RuntimeEnvironment.getApplication().apply { setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light) }
        val event = ExportEvent("e-first", "m.room.message", 1000, ExportPerson(other, "Fern", 0xff778899.toInt()), "An ordinary message", "", mapOf("content" to mapOf("msgtype" to "m.text", "body" to "An ordinary message")))
        val options = TimelineExportOptions(theme = "light", background = "#ffffff", foreground = "#222222", roomHeader = true, dates = true)
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, 1, listOf(event), emptyList(), options)
        val renderer = exportRenderer()
        val document = layout(renderer, context, snapshot, File(context.cacheDir, "native-export-test"))
        assertTrue(document.models.first() is im.vector.app.features.timelineexport.ExportRoomHeaderItem)
        assertEquals("Garden", document.boundView(0).findViewById<android.widget.TextView>(im.vector.app.R.id.roomNameTileText).text.toString())
        val boundaries = document.boundaries(document.logicalWidth)
        val date = document.rows.first { it.label == ExportBlocks.day(event.timestamp) }
        val message = document.rows.first { it.events.isNotEmpty() }
        assertFalse(boundaries.contains(date.top + date.height))
        assertTrue(boundaries.contains(message.top + message.height))
        assertTrue(document.height > message.height)
        document.release()
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `native export binds the timeline audio player and voice waveform`() {
        val context = RuntimeEnvironment.getApplication().apply { setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light) }
        val audioContent = mapOf("msgtype" to "m.audio", "body" to "Garden.ogg", "url" to "assets/audio.ogg",
                "info" to mapOf("mimetype" to "audio/ogg", "duration" to 65000, "size" to 12000))
        fun event(id: String, content: Map<String, Any>) = ExportEvent(id, "m.room.message", 1000, ExportPerson(other, "Fern", 0xff778899.toInt()), "", "",
                mapOf("content" to content), attachment = "assets/audio.ogg", attachmentMime = "audio/ogg", attachmentName = "Garden.ogg")
        val events = listOf(event("e-audio", audioContent), event("e-voice", audioContent + mapOf("org.matrix.msc3245.voice" to emptyMap<String, Any>(),
                "org.matrix.msc1767.audio" to mapOf("duration" to 65000, "waveform" to listOf(20, 400, 800, 300, 60)))))
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, 2, events, emptyList(), TimelineExportOptions(roomHeader = false, dates = false))
        val document = layout(exportRenderer(), context, snapshot, File(context.cacheDir, "export-audio-test"))
        try {
            val audio = document.boundView(0)
            val seek = audio.findViewById<android.widget.SeekBar>(im.vector.app.R.id.audioSeekBar)
            val thumb = seek.thumb
            val dot = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
            try {
                thumb.setBounds(0, 0, 20, 20)
                thumb.draw(android.graphics.Canvas(dot))
                assertEquals(android.graphics.Color.WHITE, dot.getPixel(10, 10))
                assertEquals(0, android.graphics.Color.alpha(dot.getPixel(0, 0)))
            } finally { dot.recycle() }
            assertEquals("Garden.ogg", audio.findViewById<android.widget.TextView>(im.vector.app.R.id.messageFilenameView).text.toString())
            assertEquals(android.text.format.DateUtils.formatElapsedTime(65), audio.findViewById<android.widget.TextView>(im.vector.app.R.id.audioPlaybackDuration).text.toString())
            val voice = document.boundView(1)
            assertNotNull(voice.findViewById<android.view.View>(im.vector.app.R.id.voicePlaybackWaveform))
            assertEquals(android.text.format.DateUtils.formatElapsedTime(65), voice.findViewById<android.widget.TextView>(im.vector.app.R.id.voicePlaybackTime).text.toString())
            assertTrue(document.rows.all { it.height > 0 })
            val bitmap = Bitmap.createBitmap(document.logicalWidth, document.height, Bitmap.Config.ARGB_8888)
            try {
                exportRenderer().draw(document, android.graphics.Canvas(bitmap), 0, document.logicalWidth, document.height)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue(pixels.distinct().size > 16)
            } finally { bitmap.recycle() }
        } finally { document.release() }
    }

    @Test fun `sender continuation uses the same compact timeline view without another header`() {
        val context = RuntimeEnvironment.getApplication().apply { setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light) }
        val person = ExportPerson(other, "Fern", 0xff778899.toInt())
        fun event(id: String, timestamp: Long) = ExportEvent(id, "m.room.message", timestamp, person, "A message", "",
                mapOf("content" to mapOf("msgtype" to "m.text", "body" to "A message")))
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, 2, listOf(event("e-first", 1000), event("e-second", 2000)), emptyList(),
                TimelineExportOptions(roomHeader = false, dates = false))
        val document = layout(exportRenderer(), context, snapshot, File(context.cacheDir, "export-spacing-test"))
        try {
            assertEquals(im.vector.app.R.layout.item_timeline_event_base, document.models[0].layout)
            val first = document.boundView(0)
            val second = document.boundView(1)
            assertEquals(android.view.View.VISIBLE, first.findViewById<android.view.View>(im.vector.app.R.id.messageMemberNameView).visibility)
            assertEquals(android.view.View.GONE, second.findViewById<android.view.View>(im.vector.app.R.id.messageMemberNameView).visibility)
            assertEquals(first.measuredHeight + second.measuredHeight, document.height)
            assertTrue(second.measuredHeight < first.measuredHeight)
        } finally { document.release() }
    }

    @Test fun `native export uses the timeline image file and notice item types`() {
        val context = RuntimeEnvironment.getApplication().apply { setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light) }
        val person = ExportPerson(other, "Fern", 0xff778899.toInt())
        fun message(id: String, content: Map<String, Any>, mime: String) = ExportEvent(id, "m.room.message", 1000, person, "", "", mapOf("content" to content),
                attachment = "assets/$id", attachmentMime = mime, attachmentName = content["body"] as String)
        val events = listOf(
                message("e-image", mapOf("msgtype" to "m.image", "body" to "Garden.png", "url" to "assets/e-image", "info" to mapOf("w" to 320, "h" to 180, "mimetype" to "image/png")), "image/png"),
                message("e-file", mapOf("msgtype" to "m.file", "body" to "Garden.txt", "url" to "assets/e-file", "info" to mapOf("mimetype" to "text/plain")), "text/plain"),
                ExportEvent("e-join", "m.room.member", 1001, person, "Fern joined the room", "", mapOf("state_key" to other, "content" to mapOf("membership" to "join")), notice = true))
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, 3, events, emptyList(), TimelineExportOptions(roomHeader = false, dates = false))
        val document = layout(exportRenderer(), context, snapshot, File(context.cacheDir, "export-item-types"))
        try {
            assertTrue(document.models[0] is im.vector.app.features.home.room.detail.timeline.item.MessageImageVideoItem)
            assertTrue(document.models[1] is im.vector.app.features.home.room.detail.timeline.item.MessageFileItem)
            assertTrue(document.models[2] is im.vector.app.features.home.room.detail.timeline.item.NoticeItem)
            assertTrue(document.boundView(0).findViewById<android.view.View>(im.vector.app.R.id.messageThumbnailView).height > 0)
            assertEquals("Garden.txt", document.boundView(1).findViewById<android.widget.TextView>(im.vector.app.R.id.messageFilenameView).text.toString())
            assertEquals("Fern joined the room", document.boundView(2).findViewById<android.widget.TextView>(im.vector.app.R.id.itemNoticeTextView).text.toString())
        } finally { document.release() }
    }

    @Test fun `static reactions render without an activity and keep blocked image placeholders`() {
        val context = RuntimeEnvironment.getApplication().apply { setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light) }
        val image = "mxc://example.org/garden"
        val event = ExportEvent("e-reaction", "m.room.message", 1000, ExportPerson(other, "Fern", 0xff778899.toInt()), "A message", "",
                mapOf("content" to mapOf("msgtype" to "m.text", "body" to "A message")),
                reactions = listOf(im.vector.app.features.timelineexport.ExportReaction(image, 3)), hideMediaReactions = true)
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, 1, listOf(event), emptyList(), TimelineExportOptions(roomHeader = false, dates = false))
        val document = layout(exportRenderer(), context, snapshot, File(context.cacheDir, "export-reactions-test"))
        try {
            val row = document.boundView(0)
            assertEquals("3", row.findViewById<android.widget.TextView>(im.vector.app.R.id.reactionCount).text.toString())
            assertEquals("❓", row.findViewById<android.widget.TextView>(im.vector.app.R.id.reactionText).text.toString())
        } finally { document.release() }
    }

    @Test fun `offline emotes retain the timeline line metrics`() {
        val paint = android.graphics.Paint().apply { textSize = 19f }
        val source = im.vector.app.features.html.EmoteImageSpan("garden", "mxc://example.org/garden", null, null)
        val offline = im.vector.app.features.html.EmoteImageSpan("garden", "assets/garden.png", null, null) { null }
        val sourceMetrics = android.graphics.Paint.FontMetricsInt()
        val offlineMetrics = android.graphics.Paint.FontMetricsInt()
        assertEquals(source.getSize(paint, "x", 0, 1, sourceMetrics), offline.getSize(paint, "x", 0, 1, offlineMetrics))
        assertEquals(sourceMetrics.ascent, offlineMetrics.ascent)
        assertEquals(sourceMetrics.descent, offlineMetrics.descent)
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `HTML renders native phone tablet desktop rows with selectable text and playable media`() {
        val context = RuntimeEnvironment.getApplication()
        val body = "The garden has room for everyone. ".repeat(20)
        val person = ExportPerson(other, "Fern", 0xff778899.toInt(), "assets/poster.png")
        val events = listOf(
                ExportEvent("e-text", "m.room.message", 1000, person, body, "", mapOf("content" to mapOf("msgtype" to "m.text", "body" to body))),
                ExportEvent("e-audio", "m.room.message", 2000, person, "Garden.ogg", "",
                        mapOf("content" to mapOf("msgtype" to "m.audio", "body" to "Garden.ogg", "url" to "assets/audio.ogg", "info" to mapOf("duration" to 65000))),
                        attachment = "assets/audio.ogg", attachmentMime = "audio/ogg", attachmentName = "Garden.ogg"))
        val options = TimelineExportOptions(format = "HTML", roomHeader = false)
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, events.size, events, emptyList(), options)
        val directory = File(context.cacheDir, "native-html-test").also { it.mkdirs() }
        val renderer = exportRenderer()
        try {
            val names = runNative { im.vector.app.features.timelineexport.NativeTimelineHtmlExporter(renderer).write(context, snapshot, directory) { _, _ -> } }
            val html = File(directory, "messages.html").readText()
            val dom = org.jsoup.Jsoup.parse(html)
            assertEquals(3, dom.select("main").size)
            assertTrue(html.contains("max-width:360px"))
            assertTrue(html.contains("max-width:768px"))
            assertTrue(html.contains("max-width:1200px"))
            assertTrue(html.contains("width=device-width"))
            assertTrue(dom.select(".text").text().contains("The garden has room"))
            assertTrue(dom.select("a[data-path=assets/audio.ogg]").isNotEmpty())
            assertTrue(html.contains("media.controls=true"))
            val phone = dom.select("main")[0].selectFirst("article")!!
            val desktop = dom.select("main")[2].selectFirst("article")!!
            assertTrue(phone.attr("style").substringAfter("height:").substringBefore("px").toFloat() > desktop.attr("style").substringAfter("height:").substringBefore("px").toFloat())
            assertFalse(html.contains("audio-card"))
            assertTrue(names.filter { it.endsWith(".png") }.all { File(directory, it).isFile })
            assertFalse(html.contains("assets/native-"))
            assertTrue(dom.select(".native-row .text").isNotEmpty())
            assertFalse(dom.select(".native-row svg image").isNotEmpty())
        } finally { directory.deleteRecursively() }
    }

    private fun archiveImporter(): im.vector.app.features.timelineexport.TimelineArchiveImporter {
        val colors = io.mockk.mockk<im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider>()
        io.mockk.every { colors.exportNameColor(any(), any(), any(), any(), any()) } returns 0xff778899.toInt()
        val notices = io.mockk.mockk<im.vector.app.features.home.room.detail.timeline.format.NoticeEventFormatter>()
        io.mockk.every { notices.format(any<org.matrix.android.sdk.api.session.events.model.Event>(), any(), any(), any()) } answers { "Profile updated" }
        return im.vector.app.features.timelineexport.TimelineArchiveImporter(colors, notices, RuntimeEnvironment.getApplication())
    }

    @Test fun `Element JSON imports edits reactions and event named media without server access`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "element-archive-fixture").also { it.mkdirs() }
        try {
            File(directory, "audio").mkdirs()
            File(directory, "audio/audio.ogg").writeBytes(byteArrayOf(1, 2, 3))
            File(directory, "export.json").writeText("""{"room_name":"Garden","messages":[
                {"event_id":"${'$'}original","type":"m.room.message","sender":"@fern:example.org","origin_server_ts":1000,"content":{"msgtype":"m.text","body":"Original wording"}},
                {"event_id":"${'$'}revision","type":"m.room.message","sender":"@fern:example.org","origin_server_ts":2000,"content":{"m.relates_to":{"rel_type":"m.replace","event_id":"${'$'}original"},"m.new_content":{"msgtype":"m.text","body":"Edited wording"}}},
                {"event_id":"${'$'}reaction","type":"m.reaction","sender":"@river:example.org","origin_server_ts":3000,"content":{"m.relates_to":{"rel_type":"m.annotation","event_id":"${'$'}original","key":"🌱"}}},
                {"event_id":"${'$'}audio","type":"m.room.message","sender":"@fern:example.org","origin_server_ts":4000,"content":{"msgtype":"m.audio","body":"Garden.ogg","url":"mxc://example.org/invented-audio","info":{"mimetype":"audio/ogg"}}}
            ]}""")
            val snapshot = runNative { archiveImporter().parse(directory, self) }
            assertEquals(2, snapshot.events.size)
            assertEquals("Edited wording", snapshot.events[0].body)
            assertEquals("Original wording", snapshot.events[0].history.single().body)
            assertEquals("🌱", snapshot.events[0].reactions.single().key)
            assertEquals(1, snapshot.events[0].reactions.single().count)
            val audio = snapshot.events[1]
            assertEquals("audio/ogg", audio.attachmentMime)
            assertTrue(File(directory, audio.attachment!!).readBytes().contentEquals(byteArrayOf(1, 2, 3)))
            assertTrue(audio.attachment!!.startsWith("assets/import-"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `Element JSON excludes explicitly and previously redacted reactions`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "archive-redacted-reaction-fixture").also { it.mkdirs() }
        try {
            File(directory, "export.json").writeText("""{"messages":[
                {"event_id":"${'$'}original","type":"m.room.message","sender":"@fern:example.org","content":{"msgtype":"m.text","body":"Garden"}},
                {"event_id":"${'$'}reaction","type":"m.reaction","sender":"@river:example.org","content":{"m.relates_to":{"rel_type":"m.annotation","event_id":"${'$'}original","key":"🌱"}}},
                {"event_id":"${'$'}redaction","type":"m.room.redaction","redacts":"${'$'}reaction","content":{}},
                {"event_id":"${'$'}pruned","type":"m.reaction","sender":"@fern:example.org","unsigned":{"redacted_because":{"type":"m.room.redaction"}},"content":{}}
            ]}""")
            val snapshot = runNative { archiveImporter().parse(directory, self) }
            assertTrue(snapshot.events.single().reactions.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `Element JSON does not restore redacted content or apply deleted edits`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "archive-redacted-message-fixture").also { it.mkdirs() }
        try {
            val redaction = """{"redacted_because":{"type":"m.room.redaction"}}"""
            File(directory, "export.json").writeText("""{"messages":[
                {"event_id":"deleted","type":"m.room.message","sender":"@fern:example.org","unsigned":$redaction,"content":{"msgtype":"m.text","body":"Removed body"}},
                {"event_id":"deleted-revision","type":"m.room.message","sender":"@fern:example.org","content":{"m.relates_to":{"rel_type":"m.replace","event_id":"deleted"},"m.new_content":{"msgtype":"m.text","body":"Removed revision"}}},
                {"event_id":"visible","type":"m.room.message","sender":"@fern:example.org","content":{"msgtype":"m.text","body":"Visible body"}},
                {"event_id":"pruned-edit","type":"m.room.message","sender":"@fern:example.org","unsigned":$redaction,"content":{"m.relates_to":{"rel_type":"m.replace","event_id":"visible"},"m.new_content":{"msgtype":"m.text","body":"Removed edit"}}}
            ]}""")
            val snapshot = runNative { archiveImporter().parse(directory, self) }
            assertEquals("", snapshot.events.first { it.id == "deleted" }.body)
            assertEquals("Visible body", snapshot.events.first { it.id == "visible" }.body)
            assertTrue(snapshot.events.all { it.history.isEmpty() })
            assertFalse(snapshot.events.toString().contains("Removed"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `Voyage HTML round trip preserves native presentation without JSON sidecars`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "voyage-archive-fixture").also { it.mkdirs() }
        try {
            File(directory, "assets").mkdirs()
            File(directory, "assets/emote.png").writeBytes(byteArrayOf(1, 2, 3))
            val person = ExportPerson(other, "Fern", 0xff778899.toInt(), "assets/emote.png")
            val event = ExportEvent("e-text", "m.room.message", 1000, person, "Hello <garden>", "<p>Hello &lt;garden&gt;</p>",
                    mapOf("content" to mapOf("msgtype" to "m.text", "body" to "Hello <garden>")), receipts = listOf(im.vector.app.features.timelineexport.ExportReceipt(person, 1500)))
            val source = TimelineExportSnapshot(self, "draft", "Garden", "Topic", other, "Fern", 2000, 1, listOf(event), listOf(im.vector.app.features.timelineexport.ExportAsset("assets/emote.png", "image/png")), TimelineExportOptions(bubbles = "both"))
            File(directory, "messages.html").writeText(im.vector.app.features.timelineexport.NativeTimelineHtmlExporter.document(source,
                    listOf(im.vector.app.features.timelineexport.NativeTimelineHtmlExporter.Layout(360, ""))))
            val restored = runNative { archiveImporter().parse(directory, self) }
            assertEquals(source.roomName, restored.roomName)
            assertEquals(event.body, restored.events.single().body)
            assertEquals(event.receipts.single().timestamp, restored.events.single().receipts.single().timestamp)
            assertEquals("both", restored.options.bubbles)
            assertTrue(restored.events.single().person.avatar!!.startsWith("assets/import-"))
            assertFalse(directory.listFiles()!!.any { it.extension == "json" })
        } finally { directory.deleteRecursively() }
    }

    @Suppress("DEPRECATION")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `archive media keeps declared MIME without marking static WebP animated`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "archive-media-fixture").also { File(it, "assets").mkdirs() }
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                File(directory, "assets/still.webp").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 80, it) }
            } finally { bitmap.recycle() }
            File(directory, "assets/voice.bin").writeBytes(byteArrayOf(1, 2, 3))
            val person = ExportPerson(other, "Fern", 0xff778899.toInt(), "assets/still.webp")
            val event = ExportEvent("e-audio", "m.room.message", 1000, person, "Voice", "", mapOf("content" to mapOf("msgtype" to "m.audio", "body" to "Voice")),
                    attachment = "assets/voice.bin", attachmentMime = "audio/ogg")
            val snapshot = TimelineExportSnapshot(self, "fixture", "Garden", "", "", "Fern", 2000, 1, listOf(event),
                    listOf(im.vector.app.features.timelineexport.ExportAsset("assets/still.webp", "image/webp", true),
                            im.vector.app.features.timelineexport.ExportAsset("assets/voice.bin", "audio/ogg")), TimelineExportOptions())
            File(directory, "messages.html").writeText(im.vector.app.features.timelineexport.NativeTimelineHtmlExporter.document(snapshot,
                    listOf(im.vector.app.features.timelineexport.NativeTimelineHtmlExporter.Layout(360, ""))))
            val restored = runNative { archiveImporter().parse(directory, self) }
            assertFalse(restored.assets.single { it.mime == "image/webp" }.animated)
            assertEquals("audio/ogg", restored.assets.single { it.path == restored.events.single().attachment }.mime)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `stopping an older session preserves current anonymization state`() {
        val presentation = org.matrix.android.sdk.api.session.profile.IdentityPresentation
        val context = RuntimeEnvironment.getApplication()
        try {
            presentation.initialize(context, self, "session-old")
            presentation.initialize(context, other, "session-current")
            presentation.release("session-old")
            assertEquals(other, presentation.activeAccount)
            presentation.release("session-current")
            assertNull(presentation.activeAccount)
        } finally { presentation.release("session-reopened"); presentation.release("session-current"); presentation.release("session-old") }
    }

    @Test fun `HTML and JSON archive metadata omit private editor identity rules`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "archive-private-metadata-fixture").also { it.mkdirs() }
        try {
            val options = TimelineExportOptions(identity = AnonymizationConfig(
                    rule = AnonymizationRule(enabled = true, users = setOf(other)), rooms = mapOf(room to AnonymizationRule(enabled = true, users = setOf(self)))))
            val person = ExportPerson(IdentityAliases.id(other), "Fern", 0xff778899.toInt())
            val event = ExportEvent("e-public", "m.room.message", 1000, person, "Garden", "", emptyMap())
            val snapshot = TimelineExportSnapshot(self, "private-draft", "Garden", "", "Fern", "Me", 2000, 1, listOf(event), emptyList(), options)
            val html = im.vector.app.features.timelineexport.NativeTimelineHtmlExporter.document(snapshot,
                    listOf(im.vector.app.features.timelineexport.NativeTimelineHtmlExporter.Layout(360, "")))
            TimelineExportHtml().write(snapshot, directory)
            for (text in listOf(html, File(directory, "voyage.json").readText(), File(directory, "export.json").readText())) {
                assertFalse(text.contains(self))
                assertFalse(text.contains(other))
                assertFalse(text.contains(room))
                assertFalse(text.contains("private-draft"))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun `native day headers follow the export time zone`() {
        val context = RuntimeEnvironment.getApplication()
        val providers = io.mockk.mockk<im.vector.app.core.date.DateFormatterProviders>()
        io.mockk.every { providers.provide(false).dateWithYearFormatter } returns org.threeten.bp.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val formatter = im.vector.app.core.date.VectorDateFormatter(context, io.mockk.mockk(relaxed = true), providers, io.mockk.mockk(relaxed = true))
        val time = java.time.Instant.parse("2026-01-01T10:30:00Z").toEpochMilli()
        assertEquals("2026-01-02", formatter.formatDayDivider(time, java.util.TimeZone.getTimeZone("Pacific/Kiritimati")))
        assertEquals("2026-01-01", formatter.formatDayDivider(time, java.util.TimeZone.getTimeZone("UTC")))
    }

    @Test fun `message times use the timeline formatter in the export time zone`() {
        val locale = io.mockk.mockk<im.vector.app.core.resources.LocaleProvider>()
        io.mockk.every { locale.current() } returns java.util.Locale.US
        val formatter = im.vector.app.core.date.VectorDateFormatter(RuntimeEnvironment.getApplication(), locale, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true))
        val time = java.time.Instant.parse("2026-01-01T13:05:00Z").toEpochMilli()
        assertEquals("13:05", formatter.formatMessageTime(time, java.util.TimeZone.getTimeZone("UTC"), twelveHour = false))
        assertEquals("1:05 PM", formatter.formatMessageTime(time, java.util.TimeZone.getTimeZone("UTC"), twelveHour = true))
        assertEquals("15:05", formatter.formatMessageTime(time, java.util.TimeZone.getTimeZone("Europe/Helsinki"), twelveHour = false))
    }

    @Test fun `animated room topic offers WebP only while header is visible`() {
        val source = TimelineExportSnapshot(self, "fixture", "Garden", "", "", "Me", 2000, 0, emptyList(),
                listOf(im.vector.app.features.timelineexport.ExportAsset("assets/emote.gif", "image/gif", true)),
                TimelineExportOptions(roomHeader = true), topicFormatted = "<img src=\"assets/emote.gif\" data-mx-emoticon>")
        assertTrue(source.hasAnimation())
        assertFalse(source.copy(options = source.options.copy(roomHeader = false)).hasAnimation())
    }

    @Test fun `archive extraction rejects traversal and cleans incomplete imports`() {
        val context = RuntimeEnvironment.getApplication()
        val store = im.vector.app.features.timelineexport.TimelineArchiveStore(context, archiveImporter())
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip -> zip.putNextEntry(java.util.zip.ZipEntry("../../escaped.json")); zip.write("{}".toByteArray()); zip.closeEntry() }
        val failure = runCatching { runNative { store.importArchive(self, bytes.toByteArray().inputStream(), "archive.zip") } }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(store.list(self).isEmpty())
    }

    @Test fun `Element HTML imports sanitized text and local media and records missing metadata`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "element-html-fixture").also { it.mkdirs() }
        try {
            File(directory, "images").mkdirs(); File(directory, "images/flower.png").writeBytes(byteArrayOf(1, 2, 3))
            File(directory, "messages.html").writeText("""<title>Garden</title><div class="mx_Export_EventWrapper" id="${'$'}flower"><li class="mx_EventTile"><div class="mx_SenderProfile">Fern</div><div class="mx_EventTile_body">Hello <b>garden</b><img src="https://example.org/tracker.png" alt="flower"><script>alert(1)</script></div></li></div>
                <div class="mx_Export_EventWrapper" id="${'$'}photo"><li class="mx_EventTile"><div class="mx_MImageBody mx_EventTile_content"><img class="mx_MImageBody_thumbnail" src="images/flower.png" alt="Flower"></div></li></div>""")
            val imported = runNative { archiveImporter().parse(directory, self) }
            assertEquals(2, imported.events.size)
            assertTrue(imported.events.first().html.contains("<b>garden</b>"))
            assertFalse(imported.events.first().html.contains("https://example.org/tracker"))
            assertFalse(imported.events.first().html.contains("<script>"))
            assertNotNull(imported.events[1].attachment)
            assertTrue(imported.issues.isNotEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `Voyage JSON ZIP imports persistently and removes unreferenced source files`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "archive-json-zip").also { File(it, "assets").mkdirs() }
        val archiveOwner = "@archive-reader:example.org"
        val store = im.vector.app.features.timelineexport.TimelineArchiveStore(context, archiveImporter())
        var id: String? = null
        try {
            File(directory, "assets/flower.png").writeBytes(byteArrayOf(1, 2, 3))
            val person = ExportPerson(other, "Fern", 0xff778899.toInt(), "assets/flower.png")
            val event = ExportEvent("e-local", "m.room.message", 1000, person, "A local garden", "", mapOf("event_id" to "e-local", "type" to "m.room.message", "sender" to other,
                    "content" to mapOf("msgtype" to "m.text", "body" to "assets/notes.txt", "url" to "assets/missing.png")))
            val snapshot = TimelineExportSnapshot(self, "fixture", "Garden", "", "", "Fern", 2000, 1, listOf(event),
                    listOf(im.vector.app.features.timelineexport.ExportAsset("assets/flower.png", "image/png")), TimelineExportOptions())
            TimelineExportHtml().write(snapshot, directory)
            val bytes = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(bytes).use { zip ->
                for (name in listOf("export.json", "voyage.json", "assets/flower.png")) {
                    zip.putNextEntry(java.util.zip.ZipEntry("garden/$name")); zip.write(File(directory, name).readBytes()); zip.closeEntry()
                }
                zip.putNextEntry(java.util.zip.ZipEntry("garden/assets/unreferenced.bin")); zip.write(byteArrayOf(4, 5, 6)); zip.closeEntry()
            }
            id = runNative { store.importArchive(archiveOwner, bytes.toByteArray().inputStream(), "garden.zip") }
            val imported = store.read(archiveOwner, id)
            assertEquals(archiveOwner, imported.owner)
            assertEquals("A local garden", imported.events.single().body)
            val content = imported.events.single().raw["content"] as Map<*, *>
            assertEquals("assets/notes.txt", content["body"])
            assertNull(content["url"])
            assertEquals("Garden", store.list(archiveOwner).single().name)
            assertEquals(1, store.list(archiveOwner).single().events)
            File(store.directory(archiveOwner, id), "index.json").writeText("{broken")
            assertEquals("Garden", store.list(archiveOwner).single().name)
            assertEquals(1, store.list(archiveOwner).single().events)
            assertTrue(File(store.directory(archiveOwner, id), imported.events.single().person.avatar!!).readBytes().contentEquals(byteArrayOf(1, 2, 3)))
            assertFalse(store.directory(archiveOwner, id).walkTopDown().any { it.name == "unreferenced.bin" || it.name == "export.json" })
        } finally { id?.let { store.delete(archiveOwner, it) }; directory.deleteRecursively() }
    }

    @Config(sdk = [28, 34])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `browser HTML goldens match independently drawn native timelines`() {
        val context = RuntimeEnvironment.getApplication()
        val browser = System.getenv("VOYAGE_EXPORT_BROWSER")?.let(::File)?.takeIf { it.canExecute() }
                ?: listOf(File("/usr/bin/chromium"), File("/usr/bin/chromium-browser"), File("/usr/bin/google-chrome")).firstOrNull { it.canExecute() }
                ?: File(System.getProperty("user.home"), ".cache/ms-playwright").listFiles().orEmpty().sortedByDescending { it.name }
                        .map { File(it, "chrome-linux64/chrome") }.firstOrNull { it.canExecute() }
        org.junit.Assume.assumeTrue("Install Chromium or set VOYAGE_EXPORT_BROWSER to check HTML/native golden parity", browser != null)
        val goldenRoot = File("build/outputs/timeline-html-goldens").absoluteFile.also { it.mkdirs() }
        val person = ExportPerson(other, "Fern", 0xff778899.toInt())
        val events = listOf(
                ExportEvent("e-formatted", "m.room.message", 1000, person, "Hello garden, this is a message with bold and italic text.",
                        "Hello <b>garden</b>, this is a message with <i>italic text</i>.", mapOf("content" to mapOf("msgtype" to "m.text", "body" to "Hello garden, this is a message with bold and italic text.", "format" to "org.matrix.custom.html", "formatted_body" to "Hello <b>garden</b>, this is a message with <i>italic text</i>."))),
                ExportEvent("e-reply", "m.room.message", 2000, person, "A reply with wrapping. ".repeat(5), "", mapOf("content" to mapOf("msgtype" to "m.text", "body" to "A reply with wrapping. ".repeat(5))), replyTo = "e-formatted"),
                ExportEvent("e-missing", "m.room.message", 3000, ExportPerson(self, "River", 0xff995544.toInt()), "A missing reply", "", mapOf("content" to mapOf("msgtype" to "m.text", "body" to "A missing reply")), replyMissing = true, sentByMe = true),
                ExportEvent("e-notice", "m.room.member", 4000, person, "Fern joined the room", "", mapOf("state_key" to other, "content" to mapOf("membership" to "join")), notice = true))
        val renderer = exportRenderer()
        val modes = listOf("none" to "light", "both" to "sc_dark", "start" to "sc_colored")
        for ((bubbles, theme) in modes) {
        val directory = File(goldenRoot, bubbles).also { File(it, "assets").mkdirs() }
        val image = Bitmap.createBitmap(40, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff669955.toInt()) }
        File(directory, "assets/poster.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        val emoteHtml = "<img data-mx-emoticon src=\"assets/poster.png\" alt=\":leaf:\" width=\"32\" height=\"32\">"
        val mentionHtml = "Hello <a href=\"https://matrix.to/#/@petal:example.org\">Petal</a>"
        val media = listOf(
                ExportEvent("e-pill", "m.room.message", 4100, person, "Hello Petal", mentionHtml,
                        mapOf("content" to mapOf("msgtype" to "m.text", "body" to "Hello Petal", "format" to "org.matrix.custom.html", "formatted_body" to mentionHtml))),
                ExportEvent("e-hidden-image", "m.room.message", 4150, person, "Hidden flower", "", mapOf("content" to mapOf("msgtype" to "m.image", "body" to "Hidden flower", "url" to "assets/poster.png", "info" to mapOf("w" to 40, "h" to 24, "mimetype" to "image/png"))), attachment = "assets/poster.png", attachmentMime = "image/png", mediaHidden = true),
                ExportEvent("e-decoration", "m.room.message", 4200, person, "Underline and deleted", "<u>Underline</u> and <del>deleted</del>",
                        mapOf("content" to mapOf("msgtype" to "m.text", "body" to "Underline and deleted", "format" to "org.matrix.custom.html", "formatted_body" to "<u>Underline</u> and <del>deleted</del>"))),
                ExportEvent("e-code", "m.room.message", 4500, person, "A garden() example", "A <code>garden()</code> example",
                        mapOf("content" to mapOf("msgtype" to "m.text", "body" to "A garden() example", "format" to "org.matrix.custom.html", "formatted_body" to "A <code>garden()</code> example"))),
                ExportEvent("e-emote", "m.room.message", 5000, person, ":leaf:", emoteHtml,
                        mapOf("content" to mapOf("msgtype" to "m.text", "body" to ":leaf:", "format" to "org.matrix.custom.html", "formatted_body" to emoteHtml))),
                ExportEvent("e-audio", "m.room.message", 6000, person, "Garden.ogg", "", mapOf("content" to mapOf("msgtype" to "m.audio", "body" to "Garden.ogg", "url" to "assets/audio.ogg", "info" to mapOf("duration" to 65000, "mimetype" to "audio/ogg"))), attachment = "assets/audio.ogg", attachmentMime = "audio/ogg", attachmentName = "Garden.ogg"),
                ExportEvent("e-image", "m.room.message", 7000, person, "Flower", "", mapOf("content" to mapOf("msgtype" to "m.image", "body" to "Flower", "url" to "assets/poster.png", "info" to mapOf("w" to 40, "h" to 24, "mimetype" to "image/png"))), attachment = "assets/poster.png", attachmentMime = "image/png"),
                ExportEvent("e-video", "m.room.message", 8000, person, "Garden.webm", "", mapOf("content" to mapOf("msgtype" to "m.video", "body" to "Garden.webm", "url" to "assets/video.webm", "info" to mapOf("w" to 40, "h" to 24, "thumbnail_url" to "assets/poster.png", "thumbnail_info" to mapOf("w" to 40, "h" to 24, "mimetype" to "image/png")))), attachment = "assets/video.webm", attachmentMime = "video/webm"),
                ExportEvent("e-file", "m.room.message", 9000, person, "Garden.pdf", "", mapOf("content" to mapOf("msgtype" to "m.file", "body" to "Garden.pdf", "url" to "assets/garden.pdf", "info" to mapOf("size" to 1234, "mimetype" to "application/pdf"))), attachment = "assets/garden.pdf", attachmentMime = "application/pdf", attachmentName = "Garden.pdf"),
                ExportEvent("e-link", "m.room.message", 9500, person, "https://example.org/garden", "", mapOf("content" to mapOf("msgtype" to "m.text", "body" to "https://example.org/garden")),
                        linkPreviews = listOf(im.vector.app.features.timelineexport.ExportLinkPreview("https://example.org/garden", "Garden", "Synthetic preview", "assets/poster.png", 40, 24))))
        val corpus = (events + media + listOf(events.first().copy(id = "e-revealed", timestamp = 10000, isRevealedRedaction = true))).mapIndexed { index, event -> if (index == 0) event.copy(
                reactions = listOf(im.vector.app.features.timelineexport.ExportReaction("Nice", 2)), receipts = listOf(im.vector.app.features.timelineexport.ExportReceipt(person, 1500))) else event }
        val background = if (theme == "light") "#ffffff" else "#17191c"
        val snapshot = TimelineExportSnapshot(self, "fixture", "Garden", "A room for plants", "", "River", 10000, corpus.size, corpus,
                listOf(im.vector.app.features.timelineexport.ExportAsset("assets/poster.png", "image/png")),
                TimelineExportOptions(format = "HTML", roomHeader = true, bubbles = bubbles, theme = theme, background = background,
                        foreground = if (theme == "light") "#17191c" else "#eeeeee", dates = true), topicFormatted = "A room for <b>plants</b>", mentionedPeople = listOf(ExportPerson("@petal:example.org", "Petal", 0xff887766.toInt())))
        File(directory, "assets").mkdirs()
        listOf("Regular" to "regular", "Bold" to "bold").forEach { (source, dest) ->
            context.assets.open("timeline-export/Roboto-$source.ttf").use { input -> File(directory, "assets/timeline-$dest.ttf").outputStream().use { input.copyTo(it) } }
        }
        val renderedFiles = runNative { im.vector.app.features.timelineexport.NativeTimelineHtmlExporter(renderer).write(context, snapshot, directory) { _, _ -> } }
        val single = File(directory, "standalone.html")
        runNative { TimelineExportHtml.singleFile(directory, single, TimelineExportHtml.archiveEntries(snapshot, renderedFiles), snapshot.assets) }
        for (width in listOf(360, 768, 1200)) {
            val native = layout(renderer, context, snapshot.copy(options = snapshot.options.copy(layoutWidth = width, expandAll = true)), directory)
            runNative { renderer.prepareAssets(native) }
            val expected = Bitmap.createBitmap(width, native.height, Bitmap.Config.ARGB_8888)
            try {
                renderer.draw(native, android.graphics.Canvas(expected), 0, width, native.height)
                File(directory, "native-$width.png").outputStream().use { expected.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val emitted = org.jsoup.Jsoup.parse(File(directory, "messages.html").readText()).select("main").first { it.className().contains("layout-" + listOf(360, 768, 1200).indexOf(width)) }.select("article")
                assertEquals(native.rows.size, emitted.size)
                native.rows.forEachIndexed { index, row -> assertEquals(row.height / native.density, emitted[index].attr("style").substringAfter("height:").substringBefore("px").toFloat(), .01f) }
                for (source in listOf(File(directory, "messages.html"), single)) {
                val html = source.readText().replace("</head>", "<style>nav{display:none!important}</style></head>")
                val page = File(directory, "golden-${source.nameWithoutExtension}-$width.html").also { it.writeText(html) }
                val output = File(directory, "browser-${source.nameWithoutExtension}-$width.png").also { it.delete() }
                val process = ProcessBuilder(browser!!.path, "--headless", "--no-sandbox", "--disable-gpu", "--disable-background-networking", "--no-first-run", "--hide-scrollbars", "--force-device-scale-factor=1",
                        "--run-all-compositor-stages-before-draw", "--virtual-time-budget=2000", "--window-size=$width,${maxOf(600, native.height)}", "--screenshot=${output.path}", page.toURI().toString())
                        .redirectErrorStream(true).redirectOutput(File(directory, "browser-${source.nameWithoutExtension}-$width.log")).start()
                // Headless Chromium sometimes hangs after writing the screenshot, so a written file counts as success.
                val exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                if (!exited) process.destroyForcibly().waitFor()
                assertTrue("Chromium timed out", exited || output.length() > 0)
                if (exited) assertEquals("Chromium failed", 0, process.exitValue())
                val actual = BitmapFactory.decodeFile(output.path)
                try {
                    assertEquals(width, actual.width)
                    assertTrue(actual.height >= expected.height)
                    // Allow glyph hinting differences between Skia and the browser at their edges.
                    var ink = 0; var missing = 0
                    fun contrast(a: Int, b: Int) = maxOf(kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)), kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)), kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)))
                    for (y in 0 until expected.height) for (x in 0 until width) {
                        val pixel = expected.getPixel(x, y)
                        if (contrast(pixel, android.graphics.Color.parseColor(background)) < 48) continue
                        ink++
                        var found = false
                        for (dy in -2..2) for (dx in -2..2) {
                            val px = x + dx; val py = y + dy
                            if (px in 0 until width && py in 0 until actual.height && contrast(pixel, actual.getPixel(px, py)) < 48) found = true
                        }
                        if (!found) missing++
                    }
                    assertTrue("Native/browser ${source.name} $width px golden mismatch: $missing / $ink ink pixels; artifacts in ${directory.path}", ink > 100 && missing.toDouble() / ink < .035)
                } finally { actual.recycle() }
                }
            } finally { expected.recycle(); native.release() }
        }
        }
    }

    @Config(sdk = [28, 34])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `HTML recording draws real child views on current Android`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "native-html-api-fixture").also { it.mkdirs() }
        val row = android.widget.FrameLayout(context).apply {
            addView(android.widget.TextView(context).apply { text = "Synthetic timeline content"; setTextColor(android.graphics.Color.WHITE) }, android.widget.FrameLayout.LayoutParams(300, 60))
            measure(android.view.View.MeasureSpec.makeMeasureSpec(360, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(80, android.view.View.MeasureSpec.EXACTLY))
            layout(0, 0, 360, 80)
        }
        try {
            val recording = im.vector.app.features.timelineexport.TimelineHtmlCanvas(360, 80, im.vector.app.features.timelineexport.TimelineHtmlCanvas.Artwork(directory))
            row.draw(recording)
            val drawn = org.jsoup.Jsoup.parse(recording.html.toString())
            assertTrue("Native child text must reach the HTML canvas on API ${android.os.Build.VERSION.SDK_INT}", drawn.select("span.text").isNotEmpty())
            assertTrue(drawn.text().contains("Synthetic timeline content"))
        } finally { directory.deleteRecursively() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `RTL HTML text keeps the native canvas origin`() {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "rtl-html-fixture").also { it.mkdirs() }
        val bitmap = Bitmap.createBitmap(360, 80, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 20f }
        val text = "مرحبا بالحديقة".toCharArray()
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            android.graphics.Canvas(bitmap).drawTextRun(text, 0, text.size, 0, text.size, 100f, 40f, true, paint)
            val nativeLeft = (0 until bitmap.width).first { x -> (0 until bitmap.height).any { y -> bitmap.getPixel(x, y) != android.graphics.Color.WHITE } }
            val html = im.vector.app.features.timelineexport.TimelineHtmlCanvas(360, 80, im.vector.app.features.timelineexport.TimelineHtmlCanvas.Artwork(directory))
            html.drawTextRun(text, 0, text.size, 0, text.size, 100f, 40f, true, paint)
            val span = org.jsoup.Jsoup.parse(html.html.toString()).selectFirst(".text")!!
            val origin = span.attr("style").substringAfter("matrix(").substringBefore(')').split(',')[4].toFloat()
            assertTrue(kotlin.math.abs(nativeLeft - origin) < 5f)
            assertEquals("rtl", span.attr("dir"))
            assertEquals(String(text), span.text())
        } finally { bitmap.recycle(); directory.deleteRecursively() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `HTML preserves native spoiler blur without exposing clear text in its visible DOM`() {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "spoiler-html-fixture").also { it.mkdirs() }
        val artwork = im.vector.app.features.timelineexport.TimelineHtmlCanvas.Artwork(directory)
        try {
            val canvas = im.vector.app.features.timelineexport.TimelineHtmlCanvas(360, 80, artwork)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.GRAY; textSize = 20f
                maskFilter = android.graphics.BlurMaskFilter(5f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawText("A hidden garden", 20f, 40f, paint)
            artwork.flush()
            val dom = org.jsoup.Jsoup.parse(canvas.html.toString())
            assertFalse(dom.text().contains("A hidden garden"))
            assertTrue(dom.select(".text").isEmpty())
            val drawing = dom.select("span.drawing").single()
            val path = artwork.imageClasses.entries.single { drawing.hasClass(it.value) }.key
            assertTrue(File(directory, path).isFile)
        } finally { artwork.clearReferences(); directory.deleteRecursively() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `legacy clip only canvas saves preserve framework translations`() {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "legacy-html-fixture").also { it.mkdirs() }
        try {
            val canvas = im.vector.app.features.timelineexport.TimelineHtmlCanvas(360, 80, im.vector.app.features.timelineexport.TimelineHtmlCanvas.Artwork(directory))
            canvas.translate(40f, 0f)
            val count = canvas.save(2)
            canvas.translate(10f, 0f)
            canvas.clipRect(0f, 0f, 20f, 40f)
            canvas.restoreToCount(count)
            canvas.drawText("Garden", 0f, 30f, android.graphics.Paint().apply { textSize = 20f })
            val dom = org.jsoup.Jsoup.parse(canvas.html.toString())
            val origin = dom.selectFirst(".text")!!.attr("style").substringAfter("matrix(").substringBefore(')').split(',')[4].toFloat()
            assertEquals(50f, origin, 0f)
            assertTrue(dom.selectFirst(".ink")!!.attr("style").contains("360px"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `export header uses the shared formatted topic renderer`() {
        val context = RuntimeEnvironment.getApplication()
        val snapshot = TimelineExportSnapshot(self, "topic-fixture", "Garden", "A garden topic", "", "", 1000, 0, emptyList(), emptyList(), TimelineExportOptions(),
                topicFormatted = "A <b>garden</b> topic with <a href=\"https://example.org/garden\">a link</a>")
        val directory = File(context.cacheDir, "formatted-topic-fixture").also { it.mkdirs() }
        val document = layout(exportRenderer(), context, snapshot, directory)
        try {
            val view = document.boundView(0).findViewById<android.widget.TextView>(im.vector.app.R.id.roomNameDescriptionText)
            assertTrue(view.text.toString().contains("garden topic with a link"))
            val text = view.text as android.text.Spanned
            assertTrue(text.getSpans(0, text.length, android.text.style.MetricAffectingSpan::class.java).isNotEmpty())
            assertTrue(text.getSpans(0, text.length, android.text.style.ClickableSpan::class.java).isNotEmpty())
        } finally { document.release(); directory.deleteRecursively() }
    }

    @Test fun `export gestures follow the image previewer instead of the image editor`() {
        val context = RuntimeEnvironment.getApplication()
        val person = ExportPerson(other, "Fern", 0xff778899.toInt())
        val events = (0 until 12).map { ExportEvent("e-gesture-$it", "m.room.message", it.toLong(), person, "Synthetic message", "", mapOf("content" to mapOf("msgtype" to "m.text", "body" to "Synthetic message"))) }
        val snapshot = TimelineExportSnapshot(self, "gesture-parity", "Garden", "", "", "Me", 2000, events.size, events, emptyList(), TimelineExportOptions(roomHeader = false))
        val doc = layout(exportRenderer(), context, snapshot, File(context.cacheDir, "gesture-parity"))
        val export = im.vector.app.features.timelineexport.ExportPreviewView(context)
        val preview = im.vector.app.features.attachments.preview.ZoomableImageView(context)
        val bitmap = Bitmap.createBitmap(doc.logicalWidth, doc.height, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
        fun gesture(view: android.view.View) = view.javaClass.getDeclaredField("gesture").apply { isAccessible = true }.get(view) as im.vector.app.features.attachments.ZoomPanGesture
        try {
            export.layout(0, 0, 360, 200); export.document = doc
            preview.layout(0, 0, 360, 200); preview.setImageDrawable(android.graphics.drawable.BitmapDrawable(context.resources, bitmap))
            val native = gesture(preview)
            val actual = gesture(export)
            val initial = actual.zoom * 1.4f
            native.setTransform(initial); actual.setTransform(initial)
            fun send(action: Int, centerX: Float, centerY: Float, time: Long, count: Int) {
                val pointers = Array(count) { index -> android.view.MotionEvent.PointerProperties().apply { id = index; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(count) { index -> android.view.MotionEvent.PointerCoords().apply { x = centerX + if (count == 1) 0f else if (index == 0) -80f else 80f; y = centerY; pressure = 1f; size = 1f } }
                for (view in listOf(export, preview)) android.view.MotionEvent.obtain(0, time, action, count, pointers, coords, 0, 0, 1f, 1f, 0, 0, 0, 0).also { view.onTouchEvent(it); it.recycle() }
            }
            fun compare() {
                assertEquals(native.zoom, actual.zoom, .0001f)
                assertEquals(native.panX, actual.panX, .001f)
                assertEquals(native.panY, actual.panY, .001f)
            }
            send(android.view.MotionEvent.ACTION_DOWN, 180f, 100f, 0, 1)
            send(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 180f, 100f, 10, 2)
            send(android.view.MotionEvent.ACTION_MOVE, 220f, 130f, 20, 2)
            compare()
            send(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 220f, 130f, 30, 2)
            send(android.view.MotionEvent.ACTION_UP, 300f, 160f, 40, 1)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            export.computeScroll(); compare()
            send(android.view.MotionEvent.ACTION_DOWN, 180f, 100f, 500, 1)
            send(android.view.MotionEvent.ACTION_MOVE, 1600f, 1600f, 600, 1)
            compare()
            send(android.view.MotionEvent.ACTION_CANCEL, 1600f, 1600f, 700, 1)
        } finally { export.document = null; doc.release(); bitmap.recycle() }
    }

    @Test fun `export selection is always an overflow action`() {
        val context = RuntimeEnvironment.getApplication().apply { setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light) }
        val menu = androidx.appcompat.view.menu.MenuBuilder(context)
        androidx.appcompat.view.SupportMenuInflater(context).inflate(im.vector.app.R.menu.menu_timeline_selection, menu)
        val export = menu.findItem(im.vector.app.R.id.selection_export) as androidx.appcompat.view.menu.MenuItemImpl
        assertFalse(export.requestsActionButton())
        assertFalse(export.requiresActionButton())
    }

    @Test fun `pinch snaps near fit and width while keeping intermediate zoom free`() {
        val context = RuntimeEnvironment.getApplication()
        val person = ExportPerson(other, "Fern", 0xff778899.toInt())
        val events = (0 until 12).map { ExportEvent("e-snap-$it", "m.room.message", it.toLong(), person, "Garden message", "",
                mapOf("content" to mapOf("msgtype" to "m.text", "body" to "Garden message"))) }
        val snapshot = TimelineExportSnapshot(self, "snap-fixture", "Garden", "", "", "Me", 2000, events.size, events, emptyList(), TimelineExportOptions(roomHeader = false))
        val doc = layout(exportRenderer(), context, snapshot, File(context.cacheDir, "snap-fixture"))
        val view = im.vector.app.features.timelineexport.ExportPreviewView(context)
        try {
            view.layout(0, 0, 360, 200); view.document = doc
            fun pinch(ratio: Float) {
                val pointers = Array(2) { index -> android.view.MotionEvent.PointerProperties().apply { id = index; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER } }
                fun send(action: Int, span: Float, time: Long, count: Int = 2) {
                    val coords = Array(2) { index -> android.view.MotionEvent.PointerCoords().apply { x = 180 + if (index == 0) -span / 2 else span / 2; y = 100f; pressure = 1f; size = 1f } }
                    android.view.MotionEvent.obtain(0, time, action, count, pointers, coords, 0, 0, 1f, 1f, 0, 0, 0, 0).also { view.onTouchEvent(it); it.recycle() }
                }
                send(android.view.MotionEvent.ACTION_DOWN, 200f, 0, 1)
                send(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f, 10)
                send(android.view.MotionEvent.ACTION_MOVE, 200f * ratio, 20)
                send(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f * ratio, 30)
                send(android.view.MotionEvent.ACTION_UP, 200f * ratio, 40, 1)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            }
            pinch(.97f)
            assertEquals(100, view.zoomPercent)
            val fitPercent = minOf(360f / doc.logicalWidth, 200f / doc.height) / (360f / doc.logicalWidth) * 100
            view.zoomBy(fitPercent / 100 * 1.03f)
            pinch(1f)
            assertEquals(fitPercent.toInt(), view.zoomPercent)
            assertTrue(view.visibleRange().first <= 1)
            assertTrue(view.visibleRange().second >= doc.height - 1)
            view.fitWidth(); pinch(.72f)
            assertTrue(view.zoomPercent in 71..72)
        } finally { doc.release() }
    }

    @Test fun `pinch stays below fitted size without rebound or editor overscroll`() {
        val context = RuntimeEnvironment.getApplication()
        val snapshot = TimelineExportSnapshot(self, "pinch-fixture", "Garden", "", "", "", 1000, 1,
                listOf(ExportEvent("e-canvas", "m.room.message", 1000, ExportPerson(other, "Fern", 0xff778899.toInt()), "Garden", "",
                        mapOf("content" to mapOf("msgtype" to "m.text", "body" to "Garden")))), emptyList(), TimelineExportOptions(roomHeader = false))
        val directory = File(context.cacheDir, "export-canvas-fixture").also { it.mkdirs() }
        val document = layout(exportRenderer(), context, snapshot, directory)
        val view = im.vector.app.features.timelineexport.ExportPreviewView(context)
        try {
            view.layout(0, 0, 360, 600); view.document = document
            fun single(action: Int, x: Float, time: Long) { android.view.MotionEvent.obtain(0, time, action, x, 300f, 0).also { view.onTouchEvent(it); it.recycle() } }
            fun two(action: Int, span: Float, time: Long) {
                val pointers = Array(2) { index -> android.view.MotionEvent.PointerProperties().apply { id = index; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(2) { index -> android.view.MotionEvent.PointerCoords().apply { x = 180 + if (index == 0) -span / 2 else span / 2; y = 300f; pressure = 1f; size = 1f } }
                android.view.MotionEvent.obtain(0, time, action, 2, pointers, coords, 0, 0, 1f, 1f, 0, 0, 0, 0).also { view.onTouchEvent(it); it.recycle() }
            }
            single(android.view.MotionEvent.ACTION_DOWN, 60f, 0)
            two(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 240f, 10)
            two(android.view.MotionEvent.ACTION_MOVE, 120f, 20)
            two(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 120f, 30)
            single(android.view.MotionEvent.ACTION_UP, 120f, 40)
            val zoom = view.zoomPercent
            assertTrue(zoom in 49..51)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            assertEquals(zoom, view.zoomPercent)
            single(android.view.MotionEvent.ACTION_DOWN, 100f, 600)
            single(android.view.MotionEvent.ACTION_MOVE, 300f, 700)
            single(android.view.MotionEvent.ACTION_CANCEL, 300f, 800)
            val gesture = view.javaClass.getDeclaredField("gesture").apply { isAccessible = true }.get(view) as im.vector.app.features.attachments.ZoomPanGesture
            assertEquals(0f, gesture.panX, 0f)
            assertEquals(0f, gesture.panY, 0f)
        } finally { view.document = null; document.release(); directory.deleteRecursively() }
    }

    @Test fun `HTML defaults omit dates and responsive wrapper escapes room names`() {
        val options = TimelineExportOptions()
        assertFalse(options.dates)
        val snapshot = TimelineExportSnapshot(self, "draft", "<script>hostile</script>", "", "", "Me", 2000, 0, emptyList(), emptyList(), options)
        val html = im.vector.app.features.timelineexport.NativeTimelineHtmlExporter.document(snapshot,
                listOf(im.vector.app.features.timelineexport.NativeTimelineHtmlExporter.Layout(360, "")))
        assertTrue(org.jsoup.Jsoup.parse(html).select("nav, input").isEmpty())
        assertFalse(html.contains("max-width:45%"))
        assertFalse(html.contains("<script>hostile</script>"))
        assertTrue(html.contains("&lt;script&gt;hostile&lt;/script&gt;"))
    }

    @Test fun `shared renderer theme resolves injected colors without changing the app theme`() {
        val context = RuntimeEnvironment.getApplication()
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, 0, emptyList(), emptyList(),
                TimelineExportOptions(roomHeader = false, theme = "sc_light", accentName = "red"))
        val document = layout(exportRenderer(), context, snapshot, File(context.cacheDir, "render-scope-test"))
        val colors = im.vector.app.core.resources.ColorProvider(context)
        val attr = im.vector.lib.ui.styles.R.attr.vctr_content_secondary
        val before = colors.getColorFromAttribute(attr)
        try {
            im.vector.app.features.home.room.detail.timeline.render.TimelineRenderingScope.render(document.context) {
                assertEquals(document.context.resolveColor(attr), colors.getColorFromAttribute(attr))
                assertTrue(im.vector.app.features.themes.ThemeUtils.isLightTheme(context))
            }
            assertEquals(before, colors.getColorFromAttribute(attr))
            assertNull(im.vector.app.features.home.room.detail.timeline.render.TimelineRenderingScope.current())
        } finally { document.release() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `native HTML retains grouped notices and opens the timeline's sheets from its rows`() {
        val context = RuntimeEnvironment.getApplication()
        val person = ExportPerson(other, "Fern", 0xff778899.toInt())
        val notices = (1..2).map { ExportEvent("e-notice-$it", "m.room.member", 1000, person, "Fern updated their profile", "",
                mapOf("state_key" to person.id, "content" to mapOf("membership" to "join")), notice = true, group = "m.room.member") }
        val history = listOf("Earlier wording" to 500L, "Current wording" to 1500L).map { (body, ts) ->
            im.vector.app.features.timelineexport.ExportRevision(ts, body, "", mapOf("content" to mapOf("msgtype" to "m.text", "body" to body)))
        }
        val reader = ExportPerson("@moss:example.org", "Moss", 0xff336699.toInt())
        val events = notices + ExportEvent("e-edited", "m.room.message", 2000, person, "Current wording", "",
                mapOf("content" to mapOf("msgtype" to "m.text", "body" to "Current wording")), history = history,
                receipts = listOf(im.vector.app.features.timelineexport.ExportReceipt(reader, 2500)))
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, events.size, events, emptyList(), TimelineExportOptions(roomHeader = false))
        val directory = File(context.cacheDir, "native-html-groups").also { it.mkdirs() }
        try {
            runNative { im.vector.app.features.timelineexport.NativeTimelineHtmlExporter(exportRenderer()).write(context, snapshot, directory) { _, _ -> } }
            val html = org.jsoup.Jsoup.parse(File(directory, "messages.html").readText())
            assertEquals(3, html.select("section.group").size)
            assertTrue(html.select(".group .expanded").text().contains("Fern updated their profile"))
            assertEquals(0, html.select("details").size)
            // Groups toggle through a checkbox, which works with JavaScript disabled.
            html.select("section.group").forEach { group ->
                val toggle = group.child(0)
                assertEquals("checkbox", toggle.attr("type"))
                assertEquals(2, group.select("label[for=${toggle.id()}]").size)
            }
            // One sheet each, outside the per-width layouts, opened from every layout's row.
            val edits = html.select("body > div.panel[data-panel=edits:e-edited]")
            assertEquals(1, edits.size)
            assertEquals("Message Edits", edits.select(".panel-title").text())
            val edited = html.select("article[data-event=e-edited]")
            assertTrue(edited.text().contains("Current wording"))
            assertEquals(3, edited.select("a.hotspot[data-panel=edits:e-edited]").size)
            // The fragment opens the sheet through :target when no script runs.
            assertTrue(edited.select("a.hotspot[data-panel=edits:e-edited]").all { it.attr("href") == "#" + edits.single().id() })
            val revisions = edits.select("article").toList().filter { it.text().contains("wording") }
            assertEquals(2, revisions.size)
            // Newest first, marked up against the version before it; the oldest has nothing to diff.
            assertTrue(revisions.first().text().contains("Current") && revisions.first().text().contains("Earlier"))
            assertTrue(revisions.last().text().contains("Earlier wording") && !revisions.last().text().contains("Current"))
            assertTrue(edits.select("article").none { it.hasAttr("data-event") })
            val receipts = html.select("body > div.panel[data-panel^=receipts:]")
            assertEquals(1, receipts.size)
            assertEquals("Seen by", receipts.select(".panel-title").text())
            assertTrue(receipts.text().contains("Moss"))
            assertEquals(3, html.select("a.hotspot[data-panel=${receipts.attr("data-panel")}]").size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `appearance preview reuses measured rows and binds only on demand`() {
        val context = RuntimeEnvironment.getApplication()
        val event = ExportEvent("e-first", "m.room.message", 1000, ExportPerson(other, "Fern", 0xff778899.toInt()), "A message", "",
                mapOf("content" to mapOf("msgtype" to "m.text", "body" to "A message")))
        val options = TimelineExportOptions(roomHeader = false)
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, 1, listOf(event), emptyList(), options)
        val renderer = exportRenderer()
        val directory = File(context.cacheDir, "realtime-export-test")
        val first = layout(renderer, context, snapshot, directory)
        val next = layout(renderer, context, snapshot.copy(options = options.copy(accentName = "red")), directory, first)
        try {
            assertEquals(first.rows.map { it.height }, next.rows.map { it.height })
            assertEquals(0, next.cachedRowCount())
            next.boundView(0)
            assertEquals(1, next.cachedRowCount())
        } finally { first.release(); next.release() }
    }

    @Test fun `staging includes only packaged assets and keeps the editable draft`() {
        val context = RuntimeEnvironment.getApplication()
        val store = TimelineExportStore(context)
        val draft = TimelineExportDraft("stage-fixture", self, room, eventIds = listOf("e-first"))
        store.save(draft)
        val source = store.directory(self, draft.id)
        File(source, "assets").mkdirs()
        File(source, "assets/kept.png").writeText("fixture")
        File(source, "assets/excluded.png").writeText("excluded fixture")
        val snapshot = TimelineExportSnapshot(self, draft.id, "Garden", "", "", "Me", 2000, 0, emptyList(),
                listOf(im.vector.app.features.timelineexport.ExportAsset("assets/kept.png", "image/png")), draft.options)
        val staged = store.stage(snapshot, source, "staged-fixture")
        try {
            assertTrue(File(store.directory(self, staged), "assets/kept.png").isFile)
            assertFalse(File(store.directory(self, staged), "assets/excluded.png").exists())
            assertFalse(File(store.directory(self, staged), "draft.json").exists())
            assertTrue(File(source, "draft.json").isFile)
        } finally { store.discard(self, staged); store.discard(self, draft.id) }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `scrolled media uses native request delivery including EXIF and video posters`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "scrolled-native-media").also { File(it, "assets").mkdirs() }
        val rotated = File(directory, "assets/rotated.jpg")
        val poster = File(directory, "assets/poster.png")
        val source = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        rotated.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        poster.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        source.recycle()
        androidx.exifinterface.media.ExifInterface(rotated.path).apply {
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val person = ExportPerson(other, "Fern", 0xff778899.toInt())
        fun event(id: String, content: Map<String, Any>, attachment: String? = null, mime: String? = null) = ExportEvent(id, "m.room.message", 1000, person, "", "",
                mapOf("content" to content), attachment = attachment, attachmentMime = mime)
        val events = listOf(
                event("e-rotated", mapOf("msgtype" to "m.image", "body" to "Rotated.jpg", "url" to "assets/rotated.jpg",
                        "info" to mapOf("mimetype" to "image/jpeg", "w" to 20, "h" to 40)), "assets/rotated.jpg", "image/jpeg"),
                event("e-video", mapOf("msgtype" to "m.video", "body" to "Garden.webm", "url" to "assets/video.webm",
                        "info" to mapOf("mimetype" to "video/webm", "w" to 40, "h" to 20, "thumbnail_url" to "assets/poster.png",
                                "thumbnail_info" to mapOf("mimetype" to "image/png", "w" to 40, "h" to 20))), "assets/video.webm", "video/webm")) +
                (1..40).map { event("e-text-$it", mapOf("msgtype" to "m.text", "body" to "A garden message")) }
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, events.size, events,
                listOf(im.vector.app.features.timelineexport.ExportAsset("assets/rotated.jpg", "image/jpeg"), im.vector.app.features.timelineexport.ExportAsset("assets/poster.png", "image/png")),
                TimelineExportOptions(roomHeader = false))
        val renderer = exportRenderer()
        val document = layout(renderer, context, snapshot, directory)
        try {
            runNative { renderer.prepareAssets(document, top = 0, bottom = document.rows[2].top) }
            val image = document.boundView(0).findViewById<android.widget.ImageView>(im.vector.app.R.id.messageThumbnailView)
            assertTrue(image.drawable.intrinsicHeight > image.drawable.intrinsicWidth)
            val video = document.boundView(1).findViewById<android.widget.ImageView>(im.vector.app.R.id.messageThumbnailView)
            assertTrue(video.drawable.intrinsicWidth > video.drawable.intrinsicHeight)
            val pixels = Bitmap.createBitmap(video.width, video.height, Bitmap.Config.ARGB_8888)
            try {
                video.draw(android.graphics.Canvas(pixels))
                assertEquals(android.graphics.Color.RED, pixels.getPixel(pixels.width / 2, pixels.height / 2))
            } finally { pixels.recycle() }
        } finally { document.release(); directory.deleteRecursively() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `export media decoder applies EXIF rotation like timeline Glide decoding`() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "rotated-fixture.jpg")
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        androidx.exifinterface.media.ExifInterface(file.path).apply {
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val task = java.util.concurrent.FutureTask { im.vector.app.features.media.ImageContentRenderer.decodeExportBitmap(context, file, 100) }
        Thread(task).start()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        while (!task.isDone && System.nanoTime() < deadline) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(1) }
        val decoded = task.get(1, java.util.concurrent.TimeUnit.SECONDS)!!
        try { assertTrue(decoded.height > decoded.width) } finally { decoded.recycle(); file.delete() }
    }

    @Test fun `frozen mentions use shared pills with profiles outside the selection`() {
        val context = RuntimeEnvironment.getApplication()
        val html = "Hello <a href=\"https://matrix.to/#/@petal:example.org\">old label</a>"
        val event = ExportEvent("e-mention", "m.room.message", 1000, ExportPerson(other, "Fern", 0xff778899.toInt()), "Hello Petal", html,
                mapOf("content" to mapOf("msgtype" to "m.text", "body" to "Hello Petal", "format" to "org.matrix.custom.html", "formatted_body" to html)))
        val snapshot = TimelineExportSnapshot(self, "mention-fixture", "Garden", "", "", "Me", 2000, 1, listOf(event), emptyList(), TimelineExportOptions(roomHeader = false),
                mentionedPeople = listOf(ExportPerson("@petal:example.org", "Petal", 0xff445566.toInt())))
        val doc = layout(exportRenderer(), context, snapshot, File(context.cacheDir, "mention-fixture"))
        try {
            val text = (doc.models.single() as im.vector.app.features.home.room.detail.timeline.item.MessageTextItem).message!!.charSequence as android.text.Spanned
            val pill = text.getSpans(0, text.length, im.vector.app.features.html.PillImageSpan::class.java).single()
            assertEquals("Petal", pill.matrixItem.displayName)
            assertEquals("@petal:example.org", pill.matrixItem.id)
            assertFalse(text.toString().contains("old label"))
        } finally { doc.release() }
    }

    @Test fun `cancelled media requests do not cancel export layout`() {
        val context = RuntimeEnvironment.getApplication()
        val snapshot = TimelineExportSnapshot(self, "cancelled-media-fixture", "Garden", "", "", "Me", 2000, 0, emptyList(), emptyList(), TimelineExportOptions(roomHeader = false))
        val doc = layout(exportRenderer(), context, snapshot, File(context.cacheDir, "cancelled-media-fixture"))
        try {
            val view = android.widget.ImageView(context)
            val completion = kotlinx.coroutines.CompletableDeferred<Unit>()
            doc.context.mediaStarted(view, completion)
            completion.cancel()
            runNative { doc.context.awaitMedia(listOf(view)); assertTrue(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.isActive) }
        } finally { doc.release() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `hidden native media retains rounded corners and its eye icon`() {
        val context = RuntimeEnvironment.getApplication()
        val event = ExportEvent("e-hidden-image", "m.room.message", 1000, ExportPerson(other, "Fern", 0xff778899.toInt()), "Flower", "",
                mapOf("content" to mapOf("msgtype" to "m.image", "body" to "Flower", "url" to "assets/flower.png", "info" to mapOf("w" to 80, "h" to 60, "mimetype" to "image/png"))),
                attachment = "assets/flower.png", attachmentMime = "image/png", mediaHidden = true)
        val snapshot = TimelineExportSnapshot(self, "hidden-image-fixture", "Garden", "", "", "Me", 2000, 1, listOf(event), emptyList(), TimelineExportOptions(roomHeader = false))
        val renderer = exportRenderer()
        val doc = layout(renderer, context, snapshot, File(context.cacheDir, "hidden-image-fixture"))
        val bitmap = Bitmap.createBitmap(doc.logicalWidth, doc.height, Bitmap.Config.ARGB_8888)
        try {
            val root = doc.boundView(0) as android.view.ViewGroup
            val show = root.findViewById<android.widget.TextView>(im.vector.app.R.id.messageMediaShowButton)
            assertEquals(android.view.View.VISIBLE, show.visibility)
            assertNotNull(androidx.core.widget.TextViewCompat.getCompoundDrawablesRelative(show)[0])
            val image = root.findViewById<android.widget.ImageView>(im.vector.app.R.id.messageThumbnailView)
            val bounds = android.graphics.Rect(0, 0, image.width, image.height)
            root.offsetDescendantRectToMyCoords(image, bounds)
            renderer.draw(doc, android.graphics.Canvas(bitmap), 0, doc.logicalWidth, doc.height)
            assertEquals(android.graphics.Color.parseColor(snapshot.options.background), bitmap.getPixel(bounds.left, bounds.top))
            assertNotEquals(bitmap.getPixel(bounds.left, bounds.top), bitmap.getPixel(bounds.centerX(), bounds.centerY()))
        } finally { bitmap.recycle(); doc.release() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `native image corners survive loading and rebinding`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "native-image-corners-fixture").also { File(it, "assets").mkdirs() }
        val picture = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        try { File(directory, "assets/picture.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { picture.recycle() }
        try {
            for (bubbles in listOf("none", "both")) {
                val event = ExportEvent("e-corners", "m.room.message", 1000, ExportPerson(other, "Fern", 0xff778899.toInt()), "Picture", "",
                        mapOf("content" to mapOf("msgtype" to "m.image", "body" to "Picture", "url" to "assets/picture.png", "info" to mapOf("w" to 80, "h" to 60, "mimetype" to "image/png"))),
                        attachment = "assets/picture.png", attachmentMime = "image/png")
                val snapshot = TimelineExportSnapshot(self, "native-corners-fixture", "Garden", "", "", "Me", 2000, 1, listOf(event),
                        listOf(im.vector.app.features.timelineexport.ExportAsset("assets/picture.png", "image/png")), TimelineExportOptions(roomHeader = false, bubbles = bubbles))
                val renderer = exportRenderer()
                val doc = layout(renderer, context, snapshot, directory)
                try {
                    runNative { renderer.prepareAssets(doc) }
                    val root = doc.boundView(0) as android.view.ViewGroup
                    val image = root.findViewById<android.widget.ImageView>(im.vector.app.R.id.messageThumbnailView)
                    val bounds = android.graphics.Rect(0, 0, image.width, image.height)
                    root.offsetDescendantRectToMyCoords(image, bounds)
                    val bitmap = Bitmap.createBitmap(doc.logicalWidth, doc.height, Bitmap.Config.ARGB_8888)
                    try {
                        renderer.draw(doc, android.graphics.Canvas(bitmap), 0, doc.logicalWidth, doc.height)
                        assertEquals(android.graphics.Color.RED, bitmap.getPixel(bounds.centerX(), bounds.centerY()))
                        assertNotEquals("Image corner must be clipped for $bubbles", android.graphics.Color.RED, bitmap.getPixel(bounds.left, bounds.top))
                        assertNotEquals(android.graphics.Color.RED, bitmap.getPixel(bounds.right - 1, bounds.bottom - 1))
                    } finally { bitmap.recycle() }
                    doc.clearBoundRows()
                    val rebound = doc.boundView(0).findViewById<android.widget.ImageView>(im.vector.app.R.id.messageThumbnailView)
                    val reboundBitmap = Bitmap.createBitmap(rebound.width, rebound.height, Bitmap.Config.ARGB_8888)
                    try { rebound.draw(android.graphics.Canvas(reboundBitmap)); assertTrue("Rebound image corner must remain clipped", android.graphics.Color.alpha(reboundBitmap.getPixel(0, 0)) <= 8) } finally { reboundBitmap.recycle() }
                } finally { doc.release() }
            }
        } finally { directory.deleteRecursively() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `image replies use the shared native thumbnail preview while the source is offscreen`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "native-media-reply-fixture").also { File(it, "assets").mkdirs() }
        val source = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        try { File(directory, "assets/picture.png").outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { source.recycle() }
        val person = ExportPerson(other, "Fern", 0xff778899.toInt())
        val image = ExportEvent("e-image", "m.room.message", 1000, person, "Picture", "", mapOf("content" to mapOf("msgtype" to "m.image", "body" to "Picture", "url" to "assets/picture.png", "info" to mapOf("w" to 80, "h" to 60, "mimetype" to "image/png"))), attachment = "assets/picture.png", attachmentMime = "image/png")
        val reply = ExportEvent("e-reply", "m.room.message", 2000, person, "A reply", "", mapOf("content" to mapOf("msgtype" to "m.text", "body" to "A reply")), replyTo = image.id)
        val snapshot = TimelineExportSnapshot(self, "native-media-reply", "Garden", "", "", "Me", 3000, 2, listOf(image, reply),
                listOf(im.vector.app.features.timelineexport.ExportAsset("assets/picture.png", "image/png")), TimelineExportOptions(roomHeader = false))
        val renderer = exportRenderer()
        val doc = layout(renderer, context, snapshot, directory)
        try {
            doc.clearBoundRows()
            val row = doc.rows.last()
            runNative { renderer.prepareAssets(doc, top = row.top, bottom = row.top + row.height) }
            val header = doc.boundView(1).findViewById<im.vector.app.features.home.room.detail.timeline.reply.InReplyToView>(im.vector.app.R.id.inReplyToContainer)
            val thumbnail = header.findViewById<android.widget.ImageView>(im.vector.app.R.id.messagePreviewThumbnail)
            assertEquals(android.view.View.VISIBLE, thumbnail.visibility)
            val pixels = Bitmap.createBitmap(thumbnail.width, thumbnail.height, Bitmap.Config.ARGB_8888)
            try { thumbnail.draw(android.graphics.Canvas(pixels)); assertEquals(android.graphics.Color.RED, pixels.getPixel(pixels.width / 2, pixels.height / 2)) } finally { pixels.recycle() }
            val name = doc.boundView(1).findViewById<android.widget.TextView>(im.vector.app.R.id.messageMemberNameView)
            if (name.visibility == android.view.View.VISIBLE) assertEquals(person.color, name.currentTextColor)
        } finally { doc.release(); directory.deleteRecursively() }
    }

    @Test fun `notice reply headers keep the shared notice text style`() {
        val context = RuntimeEnvironment.getApplication()
        val person = ExportPerson(other, "Fern", 0xff778899.toInt())
        val notice = ExportEvent("e-notice", "m.room.member", 1000, person, "Fern unbanned Petal", "", mapOf("state_key" to "@petal:example.org", "content" to mapOf("membership" to "leave")), notice = true)
        val reply = ExportEvent("e-reply", "m.room.message", 2000, person, "A reply", "", mapOf("content" to mapOf("msgtype" to "m.text", "body" to "A reply")), replyTo = notice.id)
        val snapshot = TimelineExportSnapshot(self, "notice-reply-fixture", "Garden", "", "", "Me", 3000, 2, listOf(notice, reply), emptyList(), TimelineExportOptions(roomHeader = false))
        val doc = layout(exportRenderer(), context, snapshot, File(context.cacheDir, "notice-reply-fixture"))
        try {
            val header = doc.boundView(1).findViewById<im.vector.app.features.home.room.detail.timeline.reply.InReplyToView>(im.vector.app.R.id.inReplyToContainer)
            val text = header.findViewById<android.widget.TextView>(im.vector.app.R.id.messagePreviewText)
            assertEquals(doc.context.resolveColor(im.vector.lib.ui.styles.R.attr.vctr_content_secondary), text.currentTextColor)
            assertEquals(notice.body, text.text.toString())
        } finally { doc.release() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `native media rounds bitmap canvas corners on modern Android`() {
        val context = RuntimeEnvironment.getApplication()
        val view = im.vector.app.core.ui.views.RoundedCornerImageView(context)
        view.scaleType = android.widget.ImageView.ScaleType.FIT_XY
        view.setImageDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.RED))
        view.setCornerRadius(16f)
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(64, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(48, android.view.View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 64, 48)
        val image = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        try {
            view.draw(android.graphics.Canvas(image))
            assertEquals(0, android.graphics.Color.alpha(image.getPixel(0, 0)))
            assertEquals(0, android.graphics.Color.alpha(image.getPixel(63, 47)))
            assertEquals(android.graphics.Color.RED, image.getPixel(32, 24))
        } finally { image.recycle() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `header toggle retains cached avatars before binding new rows`() {
        val context = RuntimeEnvironment.getApplication()
        val event = ExportEvent("e-avatar", "m.room.message", 1000, ExportPerson(other, "Fern", 0xff778899.toInt(), "assets/avatar.png"),
                "A message", "", mapOf("content" to mapOf("msgtype" to "m.text", "body" to "A message")))
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, 1, listOf(event), emptyList(), TimelineExportOptions(roomHeader = true))
        val directory = File(context.cacheDir, "avatar-header-test")
        val renderer = exportRenderer()
        val first = layout(renderer, context, snapshot, directory)
        val avatar = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        first.images.put("assets/avatar.png", avatar)
        val next = runNative { renderer.layout(context, snapshot.copy(options = snapshot.options.copy(roomHeader = false)), directory, first, false) }
        try {
            val view = next.boundView(0).findViewById<android.widget.ImageView>(im.vector.app.R.id.messageAvatarImageView)
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(image))
                assertEquals(android.graphics.Color.RED, image.getPixel(image.width / 2, image.height / 2))
            } finally { image.recycle() }
        } finally { first.release(); next.release() }
    }

    @Config(sdk = [28, 34])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `animated images stickers and video posters match timeline outlines on every sampled frame`() {
        val context = RuntimeEnvironment.getApplication()
        val diagnostics = java.util.Collections.synchronizedList(mutableListOf<String>())
        val trace = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { if (t != null) diagnostics.add(message + ": " + t.toString()) }
        }
        timber.log.Timber.plant(trace)
        val directory = File(context.cacheDir, "timeline-outline-parity-fixture").also { File(it, "assets").mkdirs() }
        val sources = mapOf(
                "gif" to "R0lGODlhUAA8AIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACAAAACwAAAAAUAA8AAAIcQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyBDihxJsqTJkyhTqlzJsqXLlzBjypxJs6bNmzhz6tzJs6fPn0CDCh1KtKjRo0iTKl3KtKnTp1CjSp1KtarVq1izat3KtavXr2DDvgwIACH5BAEIAAEALAAAAABQADwAgQAA/wAAAAAAAAAAAAhxAAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWLGDNq3Mixo8ePIEOKHEmypMmTKFOqXMmypcuXMGPKnEmzps2bOHPq3Mmzp8+fQIMKHUq0qNGjSJMqXcq0qdOnUKNKnUq1qtWrWLNq3cq1q9evYMO+DAgAOw==",
                "apng" to "iVBORw0KGgoAAAANSUhEUgAAAFAAAAA8CAIAAAB+RarbAAAACGFjVEwAAAACAAAAAPONk3AAAAAaZmNUTAAAAAAAAABQAAAAPAAAAAAAAAAAAAIAGQAAur7TjgAAAF1JREFUeJztz4ENABAAwDD8/zNfkNR6wTb3+Mt6HXBbw7qGdQ3rGtY1rGtY17CuYV3DuoZ1Desa1jWsa1jXsK5hXcO6hnUN6xrWNaxrWNewrmFdw7qGdQ3rGtY1rDu8HAF3Sm8ilAAAABpmY1RMAAAAAQAAAFAAAAA8AAAAAAAAAAAAAgAZAAAhzTlaAAAAZGZkQVQAAAACeJztzwENACEQwLDj/XsGF3wyWgXbmtnzku/vgNsM1xmuM1xnuM5wneE6w3WG6wzXGa4zXGe4znCd4TrDdYbrDNcZrjNcZ7jOcJ3hOsN1husM1xmuM1xnuM5w3QG6HgF3wzdpbwAAAABJRU5ErkJggg==")
        sources.forEach { (extension, bytes) -> File(directory, "assets/moving.$extension").writeBytes(android.util.Base64.decode(bytes, android.util.Base64.DEFAULT)) }
        val poster = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        try { File(directory, "assets/poster.png").outputStream().use { poster.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { poster.recycle() }
        try {
            for (bubbles in listOf("none", "start", "both")) for (kind in listOf("gif", "apng", "video", "sticker")) {
                val path = if (kind == "video") "assets/poster.png" else "assets/moving.${if (kind == "sticker") "gif" else kind}"
                val mime = if (kind == "video") "video/webm" else "image/${if (kind == "sticker") "gif" else kind}"
                val content = mapOf("msgtype" to if (kind == "video") "m.video" else "m.image", "body" to "Synthetic media", "url" to if (kind == "video") "assets/movie.webm" else path,
                        "info" to mapOf("w" to 80, "h" to 60, "mimetype" to mime, "thumbnail_url" to path, "thumbnail_info" to mapOf("w" to 80, "h" to 60, "mimetype" to "image/png")))
                val event = ExportEvent("e-media", if (kind == "sticker") "m.sticker" else "m.room.message", 1000, ExportPerson(other, "Fern", 0xff778899.toInt()), "Synthetic media", "",
                        mapOf("content" to content), attachment = if (kind == "video") "assets/movie.webm" else path, attachmentMime = mime)
                val snapshot = TimelineExportSnapshot(self, "outline-parity", "Garden", "", "", "Me", 2000, 1, listOf(event),
                        listOf(im.vector.app.features.timelineexport.ExportAsset(path, if (kind == "video") "image/png" else mime, kind != "video")), TimelineExportOptions(roomHeader = false, bubbles = bubbles))
                val renderer = exportRenderer()
                val doc = layout(renderer, context, snapshot, directory)
                try {
                    for (time in listOf(0, 90, 170)) {
                        runNative { renderer.prepareAssets(doc, timeMs = time) }
                        val image = doc.boundView(0).findViewById<android.widget.ImageView>(im.vector.app.R.id.messageThumbnailView)
                        val outline = android.graphics.Outline().also { image.outlineProvider.getOutline(image, it) }
                        val bounds = android.graphics.Rect()
                        assertTrue(outline.getRect(bounds))
                        val expected = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                        val actual = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                        fun hardware(bitmap: Bitmap) = object : android.graphics.Canvas(bitmap) { override fun isHardwareAccelerated() = true }
                        try {
                            val nativeCanvas = hardware(expected)
                            nativeCanvas.clipPath(android.graphics.Path().apply { addRoundRect(android.graphics.RectF(bounds), outline.radius, outline.radius, android.graphics.Path.Direction.CW) })
                            image.draw(nativeCanvas)
                            im.vector.app.features.home.room.detail.timeline.render.TimelineRenderingScope.draw(doc.context) { image.draw(hardware(actual)) }
                            val reference = IntArray(image.width * image.height).also { expected.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
                            val exported = IntArray(reference.size).also { actual.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
                            assertTrue("Native outline mismatch kind=$kind bubbles=$bubbles frame=$time api=${android.os.Build.VERSION.SDK_INT}", reference.contentEquals(exported))
                            val expectedColor = if (kind == "video" || time != 90) android.graphics.Color.RED else android.graphics.Color.BLUE
                            assertEquals("Media frame must be loaded for $kind at $time API=${android.os.Build.VERSION.SDK_INT} drawable=${image.drawable.javaClass.simpleName} cached=${doc.bitmap(path)?.let { it.getPixel(it.width / 2, it.height / 2) }} diagnostics=$diagnostics", expectedColor, actual.getPixel(image.width / 2, image.height / 2))
                        } finally { expected.recycle(); actual.recycle() }
                    }
                } finally { doc.release() }
            }
        } finally { timber.log.Timber.uproot(trace); directory.deleteRecursively() }
    }

    @Config(sdk = [28, 34])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `gallery exports match the native timeline clipping on a hardware preview canvas`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "gallery-native-parity").also { File(it, "assets").mkdirs() }
        val image = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        try { File(directory, "assets/tile.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { image.recycle() }
        val item = mapOf("itemtype" to "m.image", "body" to "Tile", "url" to "assets/tile.png", "info" to mapOf("w" to 80, "h" to 60, "mimetype" to "image/png"))
        val event = ExportEvent("e-gallery", "m.room.message", 1000, ExportPerson(other, "Fern", 0xff778899.toInt()), "Photos", "",
                mapOf("content" to mapOf("msgtype" to "m.gallery", "body" to "Photos", "itemtypes" to listOf(item, item))),
                extraAttachments = listOf(im.vector.app.features.timelineexport.ExportAttachment("assets/tile.png", "image/png", "Tile", false), im.vector.app.features.timelineexport.ExportAttachment("assets/tile.png", "image/png", "Tile", false)))
        val snapshot = TimelineExportSnapshot(self, "gallery-native-parity", "Garden", "", "", "Me", 2000, 1, listOf(event),
                listOf(im.vector.app.features.timelineexport.ExportAsset("assets/tile.png", "image/png")), TimelineExportOptions(roomHeader = false))
        val renderer = exportRenderer()
        val doc = layout(renderer, context, snapshot, directory)
        try {
            runNative { renderer.prepareAssets(doc) }
            val grid = doc.boundView(0).findViewById<im.vector.app.core.ui.views.GalleryGridLayout>(im.vector.app.R.id.messageGalleryGrid)
            val outline = android.graphics.Outline().also { grid.outlineProvider.getOutline(grid, it) }
            val bounds = android.graphics.Rect(); assertTrue(outline.getRect(bounds))
            val native = Bitmap.createBitmap(grid.width, grid.height, Bitmap.Config.ARGB_8888)
            val exported = Bitmap.createBitmap(grid.width, grid.height, Bitmap.Config.ARGB_8888)
            fun hardware(bitmap: Bitmap) = object : android.graphics.Canvas(bitmap) { override fun isHardwareAccelerated() = true }
            try {
                val reference = hardware(native)
                reference.clipPath(android.graphics.Path().apply { addRoundRect(android.graphics.RectF(bounds), outline.radius, outline.radius, android.graphics.Path.Direction.CW) })
                grid.draw(reference)
                im.vector.app.features.home.room.detail.timeline.render.TimelineRenderingScope.draw(doc.context) { grid.draw(hardware(exported)) }
                val expected = IntArray(grid.width * grid.height).also { native.getPixels(it, 0, grid.width, 0, 0, grid.width, grid.height) }
                val actual = IntArray(expected.size).also { exported.getPixels(it, 0, grid.width, 0, 0, grid.width, grid.height) }
                assertTrue(expected.contentEquals(actual))
                assertEquals(android.graphics.Color.RED, exported.getPixel(grid.width / 4, grid.height / 2))
            } finally { native.recycle(); exported.recycle() }
        } finally { doc.release(); directory.deleteRecursively() }
    }

    @Config(sdk = [28, 34])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `HTML records native vector pictures used by inline artwork`() {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "html-picture-parity").also { it.mkdirs() }
        val picture = android.graphics.Picture()
        picture.beginRecording(32, 24).drawColor(android.graphics.Color.RED); picture.endRecording()
        val artwork = im.vector.app.features.timelineexport.TimelineHtmlCanvas.Artwork(directory)
        try {
            val canvas = im.vector.app.features.timelineexport.TimelineHtmlCanvas(64, 48, artwork)
            canvas.drawPicture(picture, android.graphics.Rect(0, 0, 64, 48))
            artwork.flush()
            assertEquals(1, artwork.files.size)
            val bitmap = BitmapFactory.decodeFile(File(directory, artwork.files.single()).path)
            try { assertEquals(android.graphics.Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) } finally { bitmap.recycle() }
        } finally { artwork.clearReferences(); directory.deleteRecursively() }
    }

    @Test fun `video play icon resolves the chosen export accent`() {
        val context = RuntimeEnvironment.getApplication()
        val event = ExportEvent("e-video", "m.room.message", 1000, ExportPerson(other, "Fern", 0xff778899.toInt()), "", "",
                mapOf("content" to mapOf("msgtype" to "m.video", "body" to "Garden.webm", "url" to "assets/video.webm",
                        "info" to mapOf("w" to 320, "h" to 180, "mimetype" to "video/webm"))), attachment = "assets/video.webm", attachmentMime = "video/webm")
        val options = TimelineExportOptions(roomHeader = false, accentName = "red")
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, 1, listOf(event), emptyList(), options)
        val document = layout(exportRenderer(), context, snapshot, File(context.cacheDir, "video-accent-test"))
        try {
            val view = document.boundView(0).findViewById<android.widget.ImageView>(im.vector.app.R.id.messageMediaPlayView)
            val tint = androidx.core.widget.ImageViewCompat.getImageTintList(view)!!.defaultColor
            val expected = android.util.TypedValue().also { document.context.theme.resolveAttribute(im.vector.lib.ui.styles.R.attr.vctr_accent, it, true) }.data
            assertEquals(expected, tint)
            assertNotEquals(android.graphics.Color.parseColor("#0DBD8B"), tint)
        } finally { document.release() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `portrait and landscape photo avatars occupy the same timeline bounds`() {
        val context = RuntimeEnvironment.getApplication()
        fun event(id: String, userId: String, avatar: String) = ExportEvent(id, "m.room.message", 1000, ExportPerson(userId, "Fern", 0xff778899.toInt(), avatar),
                "A message", "", mapOf("content" to mapOf("msgtype" to "m.text", "body" to "A message")))
        val events = listOf(event("e-portrait", other, "assets/portrait.png"), event("e-landscape", self, "assets/landscape.png"))
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, 2, events, emptyList(), TimelineExportOptions(roomHeader = false))
        val document = layout(exportRenderer(), context, snapshot, File(context.cacheDir, "avatar-bounds-test"))
        val portrait = Bitmap.createBitmap(12, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val landscape = Bitmap.createBitmap(48, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        try {
            document.images.put("assets/portrait.png", portrait)
            document.images.put("assets/landscape.png", landscape)
            document.invalidateImages()
            fun coverage(index: Int): Int {
                val avatar = document.boundView(index).findViewById<android.widget.ImageView>(im.vector.app.R.id.messageAvatarImageView)
                val image = Bitmap.createBitmap(avatar.width, avatar.height, Bitmap.Config.ARGB_8888)
                return try {
                    avatar.draw(android.graphics.Canvas(image))
                    val pixels = IntArray(image.width * image.height)
                    image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                    val covered = pixels.count { android.graphics.Color.alpha(it) > 128 }
                    assertTrue(covered > image.width * image.height * .65f)
                    covered
                } finally { image.recycle() }
            }
            assertEquals(coverage(0), coverage(1))
        } finally { document.release() }
    }

    @Test fun `anonymized colors use the original MXID palette and ignore custom profile colors`() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = io.mockk.mockk<im.vector.app.features.settings.VectorPreferences>(relaxed = true)
        io.mockk.every { preferences.peopleColorPalette() } returns im.vector.app.core.ui.colorpicker.ColorPalette.LEGACY
        val colors = im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider(
                im.vector.app.core.resources.ColorProvider(context), preferences, io.mockk.mockk(relaxed = true), javax.inject.Provider { io.mockk.mockk(relaxed = true) })
        val item = org.matrix.android.sdk.api.util.MatrixItem.UserItem(other, "Fern",
                colorPreference = org.matrix.android.sdk.api.session.profile.ColorPreference("#ff0000", "#ff0000"))
        val palette = im.vector.app.core.ui.colorpicker.ColorPalette.LEGACY
        val expected = androidx.core.content.ContextCompat.getColor(context, palette.colorFor(other, false))
        assertEquals(expected, colors.exportNameColor(item, false, true, palette.name))
        assertEquals(expected, colors.exportNameColor(item.copy(displayName = "Different alias"), false, true, palette.name))
        assertEquals(expected, colors.exportNameColor(item, false, true, palette.name, true))
        assertEquals(expected, colors.exportNameColor(item, false, true, palette.name, false))
        assertEquals(android.graphics.Color.RED, colors.exportNameColor(item, false, false, palette.name, true))
        assertEquals(expected, colors.exportNameColor(item, false, false, palette.name, false))
        assertNotEquals(android.graphics.Color.RED, expected)
    }

    @Test fun `hidden animated assets do not offer animated WebP`() {
        val animated = im.vector.app.features.timelineexport.ExportAsset("assets/garden.gif", "image/gif", animated = true)
        val event = ExportEvent("e-image", "m.room.message", 1000, ExportPerson(other, "Fern", 0xff778899.toInt()), "", "", emptyMap(),
                attachment = animated.path, attachmentMime = animated.mime, mediaHidden = true)
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, 1, listOf(event), listOf(animated), TimelineExportOptions())
        assertFalse(snapshot.hasAnimation())
        val headerOnly = snapshot.copy(roomPerson = ExportPerson(room, "Garden", 0xff778899.toInt(), animated.path),
                events = emptyList(), options = snapshot.options.copy(roomHeader = false))
        assertFalse(headerOnly.hasAnimation())
        assertTrue(snapshot.copy(events = listOf(event.copy(mediaHidden = false))).hasAnimation())
    }

    @Test fun `ordinary quotes in a reply without a Matrix fallback remain intact`() {
        val content = mapOf("body" to "> A quote I wrote\n\nA comment", "m.relates_to" to mapOf("m.in_reply_to" to mapOf("event_id" to "\$outside")))
        assertEquals(content["body"], ExportPrivacy(TimelineExportOptions(), self, room).body(content))
    }

    @Test fun `export notices describe ACL and power changes using supplied identities`() {
        val sessions = io.mockk.mockk<im.vector.app.ActiveSessionDataSource>(relaxed = true)
        io.mockk.every { sessions.currentValue } returns null
        val formatter = im.vector.app.features.home.room.detail.timeline.format.NoticeEventFormatter(
                sessions, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true),
                io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true),
                im.vector.app.core.resources.StringProvider(RuntimeEnvironment.getApplication().resources))
        val acl = Event(eventId = "\$acl", senderId = other, type = "m.room.server_acl", stateKey = "",
                content = mapOf("allow" to listOf("*"), "deny" to emptyList<String>(), "allow_ip_literals" to false))
        val notice = formatter.format(acl, "Amber Otter", false).toString()
        assertTrue(notice.contains("Amber Otter"))
        assertFalse(notice.contains("m.room.server_acl"))
        val powers = Event(eventId = "\$powers", senderId = other, type = "m.room.power_levels", stateKey = "",
                content = mapOf("users" to mapOf(self to 50)), prevContent = mapOf("users" to mapOf(self to 0)))
        val powerNotice = formatter.format(powers, "Amber Otter", false, mapOf(self to "Me")).toString()
        assertTrue(powerNotice.contains("Me"))
        assertFalse(powerNotice.contains(self))
    }

    @Test fun `JSON emoji assets and integers survive a snapshot round trip`() {
        val context = RuntimeEnvironment.getApplication()
        val event = ExportEvent("e-first", "m.room.message", 1000, ExportPerson(other, "Fern", 0xff778899.toInt()), "Hello 🙂", "",
                mapOf("type" to "m.room.message", "origin_server_ts" to 1000L, "content" to mapOf("body" to "Hello 🙂")))
        val original = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, 1, listOf(event), emptyList(), TimelineExportOptions(),
                emojiImages = mapOf("🙂" to "assets/emoji.png"))
        val adapter = MatrixJsonParser.getMoshi().adapter(TimelineExportSnapshot::class.java)
        val snapshot = adapter.fromJson(adapter.toJson(original))!!
        val directory = File(context.cacheDir, "export-emoji-test").also { it.mkdirs() }
        try {
            TimelineExportHtml().write(snapshot, directory)
            assertTrue(File(directory, "voyage.json").readText().contains("assets/emoji.png"))
            val json = File(directory, "export.json").readText()
            assertTrue(json.contains("\"origin_server_ts\": 1000"))
            assertFalse(json.contains("1000.0"))
            assertFalse(File(directory, "emoji-attribution.txt").exists())
            assertFalse(File(directory, "voyage.json").readText().contains("emoji_attribution"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `export bubble choices use the global preference entries and values`() {
        val context = RuntimeEnvironment.getApplication().apply { setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Dark) }
        val rows = android.widget.LinearLayout(context)
        var selected = "start"
        im.vector.app.features.timelineexport.ExportControls(context, rows).preferenceChoice(
                im.vector.lib.strings.CommonStrings.settings_sc_bubble_style_title,
                im.vector.app.R.array.sc_bubble_style_entries, im.vector.app.R.array.sc_bubble_style_values, selected) { selected = it }
        rows.getChildAt(0).performClick()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val entries = context.resources.getStringArray(im.vector.app.R.array.sc_bubble_style_entries)
        val values = context.resources.getStringArray(im.vector.app.R.array.sc_bubble_style_values)
        assertEquals(entries.toList(), (0 until dialog.listView.count).map { dialog.listView.adapter.getItem(it).toString() })
        assertEquals(2, dialog.listView.checkedItemPosition)
        dialog.listView.performItemClick(null, 1, 1)
        assertEquals(values[1], selected)
    }

    @Test fun `export bubble appearance is frozen independently of global preferences`() {
        val context = RuntimeEnvironment.getApplication()
        val global = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val keys = im.vector.app.features.themes.BubbleThemeUtils
        global.edit().putString(keys.BUBBLE_STYLE_KEY, "both").putString(keys.BUBBLE_ROUNDNESS_KEY, "r2")
                .putBoolean(keys.BUBBLE_TAIL_KEY, false).putBoolean(keys.BUBBLE_TINT_KEY, false).commit()
        try {
            val options = TimelineExportOptions(bubbles = "start", bubbleCorners = "r1", bubbleTail = true, bubbleTint = true, codePalette = "NORD")
            val exportContext = im.vector.app.features.timelineexport.TimelineExportContext(context, options, emptyMap(), emptyMap(),
                    { null }, io.mockk.mockk(relaxed = true), File(context.cacheDir, "bubble-appearance"))
            val bubbles = im.vector.app.features.themes.BubbleThemeUtils(exportContext)
            assertEquals("start", bubbles.getBubbleStyle())
            assertEquals(im.vector.app.features.themes.r1ScBubbleAppearance, bubbles.getBubbleAppearance())
            assertTrue(bubbles.isOutgoingTintEnabled())
            assertEquals(im.vector.app.features.html.highlight.CodePalette.NORD, exportContext.codePalette)
            assertEquals("both", global.getString(keys.BUBBLE_STYLE_KEY, null))
            assertEquals("r2", global.getString(keys.BUBBLE_ROUNDNESS_KEY, null))
            assertFalse(global.getBoolean(keys.BUBBLE_TAIL_KEY, true))
            assertFalse(global.getBoolean(keys.BUBBLE_TINT_KEY, true))
        } finally {
            global.edit().remove(keys.BUBBLE_STYLE_KEY).remove(keys.BUBBLE_ROUNDNESS_KEY)
                    .remove(keys.BUBBLE_TAIL_KEY).remove(keys.BUBBLE_TINT_KEY).commit()
        }
    }

    @Test fun `appearance reset includes bubble and avatar settings and preserves timeline settings`() {
        val defaults = TimelineExportOptions(bubbles = "both", bubbleCorners = "r2", bubbleTail = false, bubbleTint = false,
                avatarShape = "HEXAGON", codePalette = "NORD", showProfileColors = false, roomHeader = true)
        val options = TimelineExportOptions(roomHeader = false, timestamps = false, dates = true)
        val reset = options.withAppearance(defaults)
        assertEquals(defaults.bubbles, reset.bubbles)
        assertEquals(defaults.bubbleCorners, reset.bubbleCorners)
        assertEquals(defaults.bubbleTail, reset.bubbleTail)
        assertEquals(defaults.bubbleTint, reset.bubbleTint)
        assertEquals(defaults.avatarShape, reset.avatarShape)
        assertEquals(defaults.codePalette, reset.codePalette)
        assertEquals(defaults.showProfileColors, reset.showProfileColors)
        assertFalse(reset.roomHeader)
        assertFalse(reset.timestamps)
        assertTrue(reset.dates)
        val adapter = MatrixJsonParser.getMoshi().adapter(TimelineExportOptions::class.java)
        assertEquals(reset, adapter.fromJson(adapter.toJson(reset)))
    }

    @Config(sdk = [21, 28])
    @Test fun `editor tools remain compact and leave space for the timeline`() {
        val context = RuntimeEnvironment.getApplication().apply { setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Black) }
        val editor = im.vector.app.features.timelineexport.ExportEditorView(context)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        assertFalse(editor.panelVisible)
        editor.selectPanel(1, true, animate = false)
        repeat(30) { editor.panelContent.addView(android.widget.TextView(context).apply { text = "Example preference"; minimumHeight = dp(56) }) }
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
                ((view as? android.view.ViewGroup)?.let { group -> (0 until group.childCount).flatMap { descendants(group.getChildAt(it)) } } ?: emptyList())
        val tools = descendants(editor).filterIsInstance<com.google.android.material.floatingactionbutton.FloatingActionButton>()
        assertEquals(4, tools.size)
        assertEquals(listOf(im.vector.lib.strings.CommonStrings.export_appearance, im.vector.lib.strings.CommonStrings.settings_category_timeline,
                im.vector.lib.strings.CommonStrings.export_privacy, im.vector.lib.strings.CommonStrings.export_save).map { context.getString(it) },
                tools.map { it.contentDescription.toString() })
        fun center(view: android.view.View): Int {
            var x = view.width / 2
            var current: android.view.View? = view
            while (current != null && current !== editor) { x += current.left; current = current.parent as? android.view.View }
            return x
        }
        for (width in listOf(320, 768)) {
            editor.measure(android.view.View.MeasureSpec.makeMeasureSpec(dp(width), android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(dp(800), android.view.View.MeasureSpec.EXACTLY))
            editor.layout(0, 0, editor.measuredWidth, editor.measuredHeight)
            val positions = tools.map(::center)
            assertTrue(positions.zipWithNext().all { (first, next) -> next - first in dp(64)..dp(76) })
            assertTrue(positions.first() >= dp(32))
            assertTrue(positions.last() <= dp(width - 32))
            assertTrue(editor.previewContainer.height >= dp(120))
        }
        val previous = editor.previewContainer.height
        editor.selectPanel(1, false, animate = false)
        editor.measure(android.view.View.MeasureSpec.makeMeasureSpec(dp(768), android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(dp(800), android.view.View.MeasureSpec.EXACTLY))
        editor.layout(0, 0, editor.measuredWidth, editor.measuredHeight)
        assertTrue(editor.previewContainer.height > previous)
        var selected = -1
        editor.onPanelSelected = { selected = it }
        tools[1].performClick()
        assertEquals(0, selected)
    }

    @Test fun `rebuilding settings retains expanded sections`() {
        val context = RuntimeEnvironment.getApplication().apply { setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Black) }
        val rows = android.widget.LinearLayout(context)
        val expanded = mutableMapOf<Int, Boolean>()
        val title = im.vector.lib.strings.CommonStrings.settings_sc_bubble_appearance
        im.vector.app.features.timelineexport.ExportControls(context, rows, expanded).section(title) { it.text("Example preference") }
        rows.getChildAt(0).performClick()
        rows.removeAllViews()
        im.vector.app.features.timelineexport.ExportControls(context, rows, expanded).section(title) { it.text("Example preference") }
        val body = rows.getChildAt(1) as android.view.ViewGroup
        assertEquals(android.view.View.VISIBLE, body.visibility)
        assertEquals(1, body.childCount)
    }

    @Config(sdk = [21, 28])
    @Suppress("DEPRECATION")
    @Test fun `HTML preview serves only export files without granting file URL access`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "preview-fixture").also { it.mkdirs() }
        val asset = File(directory, "assets/example.txt").also { it.parentFile!!.mkdirs(); it.writeText("Example asset") }
        val outside = File(context.cacheDir, "not-in-export.txt").also { it.writeText("Private fixture") }
        val web = im.vector.app.features.timelineexport.ExportHtmlPreviewView(context)
        try {
            web.load(directory, "<p>Example preview</p>", listOf("assets/example.txt", "../not-in-export.txt"))
            // Layoutlib's compile-time WebView stub omits getSettings().
            val settings = android.webkit.WebView::class.java.getMethod("getSettings").invoke(web) as android.webkit.WebSettings
            assertFalse(settings.allowFileAccess)
            assertFalse(settings.allowContentAccess)
            assertFalse(settings.loadWithOverviewMode)
            assertTrue(org.robolectric.Shadows.shadowOf(web).lastLoadedUrl.startsWith("https://voyage-export.invalid/messages.html?revision="))
            val client = org.robolectric.Shadows.shadowOf(web).webViewClient
            assertEquals("<p>Example preview</p>", client.shouldInterceptRequest(web, "https://voyage-export.invalid/messages.html?revision=1")!!.data.bufferedReader().use { it.readText() })
            assertEquals(asset.readText(), client.shouldInterceptRequest(web, "https://voyage-export.invalid/assets/example.txt")!!.data.bufferedReader().use { it.readText() })
            assertTrue(client.shouldInterceptRequest(web, "https://voyage-export.invalid/../not-in-export.txt")!!.data.read() == -1)
            assertTrue(client.shouldInterceptRequest(web, "https://example.org/tracker")!!.data.read() == -1)
            assertTrue(client.shouldOverrideUrlLoading(web, "file:///not-in-export.txt"))
            assertTrue(client.shouldOverrideUrlLoading(web, "https://example.org/"))
            assertFalse(client.shouldOverrideUrlLoading(web, "https://voyage-export.invalid/#event"))
        } finally { web.destroy(); directory.deleteRecursively(); outside.delete() }
    }

    @Test fun `preview keeps its zoom and scroll position when the settings panel resizes`() {
        val context = RuntimeEnvironment.getApplication()
        val person = ExportPerson(other, "Fern", 0xff778899.toInt())
        val events = (0..15).map { index -> ExportEvent("event-$index", "m.room.message", 1000L + index, person, "Example message", "",
                mapOf("content" to mapOf("msgtype" to "m.text", "body" to "Example message"))) }
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, events.size, events, emptyList(), TimelineExportOptions())
        val renderer = exportRenderer()
        val directory = File(context.cacheDir, "preview-resize").also { it.mkdirs() }
        val doc = layout(renderer, context, snapshot, directory)
        val preview = im.vector.app.features.timelineexport.ExportPreviewView(context)
        try {
            preview.layout(0, 0, 360, 600)
            preview.document = doc
            preview.zoomBy(1.25f)
            assertTrue(preview.zoomPercent in 124..125)
            val top = preview.visibleRange().first
            preview.layout(0, 0, 360, 320)
            assertTrue(preview.zoomPercent in 124..125)
            assertTrue(kotlin.math.abs(top - preview.visibleRange().first) <= 2)
        } finally { preview.document = null; doc.release(); directory.deleteRecursively() }
    }

    @Test fun `custom reaction emotes survive notice serialization without object replacement characters`() {
        val source = android.text.SpannableStringBuilder("Reacted with: \uFFFC and \uFFFC")
        val first = source.indexOf('\uFFFC')
        val second = source.lastIndexOf('\uFFFC')
        source.setSpan(im.vector.app.features.html.EmoteImageSpan("wave", "mxc://example.org/wave", null, null), first, first + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        source.setSpan(im.vector.app.features.html.EmoteImageSpan("", "mxc://example.org/spark", null, null), second, second + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val exported = kotlinx.coroutines.runBlocking { im.vector.app.features.timelineexport.exportNoticeText(source, "Image") { url ->
            if (url.endsWith("wave")) "assets/wave.png" else "assets/spark.png"
        } }
        assertFalse(exported.first.contains('\uFFFC'))
        assertEquals(exported.first, source.exportPlainText("Image"))
        assertEquals(2, exported.second.size)
        val event = ExportEvent("notice", "m.reaction", 1000, ExportPerson(other, "Fern", 0xff778899.toInt()), exported.first, "", emptyMap(), notice = true, noticeSpans = exported.second)
        val adapter = MatrixJsonParser.getMoshi().adapter(ExportEvent::class.java)
        val restored = adapter.fromJson(adapter.toJson(event))!!
        val rendered = restored.noticeText(emptyMap(), false) { null } as android.text.Spanned
        assertEquals(2, rendered.getSpans(0, rendered.length, im.vector.app.features.html.EmoteImageSpan::class.java).size)
        assertTrue(restored.noticeSpans.all { it.start >= 0 && it.end <= restored.body.length })
        val hidden = kotlinx.coroutines.runBlocking { im.vector.app.features.timelineexport.exportNoticeText(source, "Image") { null } }
        assertFalse(hidden.first.contains('\uFFFC'))
        assertTrue(hidden.second.isEmpty())
    }

    @Test fun `missing exported replies use the same notice style as unavailable replies`() {
        val context = RuntimeEnvironment.getApplication().apply { setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Dark) }
        val reply = im.vector.app.features.home.room.detail.timeline.reply.InReplyToView(context)
        reply.renderStaticStatus(context.getString(im.vector.lib.strings.CommonStrings.export_message_not_exported))
        val preview = reply.findViewById<im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewView>(im.vector.app.R.id.replyPreview)
        val text = preview.textView.text as android.text.Spanned
        assertTrue(text.getSpans(0, text.length, android.text.style.StyleSpan::class.java).any { it.style == android.graphics.Typeface.ITALIC })
        assertEquals(im.vector.app.features.themes.ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary), preview.textView.currentTextColor)
        assertEquals(android.view.View.GONE, reply.findViewById<android.view.View>(im.vector.app.R.id.replyMemberNameView).visibility)
    }

    @Test fun `six hundred event exports reuse bounded native views and bind the correct row when scrolling back`() {
        val context = RuntimeEnvironment.getApplication()
        val person = ExportPerson(other, "Fern", 0xff778899.toInt())
        val events = (0 until 600).map { index ->
            val body = "Synthetic export message $index"
            ExportEvent("event-$index", "m.room.message", 1000L + index, person, body, "", mapOf("content" to mapOf("msgtype" to "m.text", "body" to body)))
        }
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, events.size, events, emptyList(), TimelineExportOptions(roomHeader = false))
        val renderer = exportRenderer()
        val directory = File(context.cacheDir, "large-view-fixture").also { it.mkdirs() }
        val document = runNative { renderer.layout(context, snapshot, directory) }
        try {
            assertEquals(600, document.rows.size)
            assertTrue(document.cachedRowCount() <= 32)
            assertTrue(document.createdViews <= 36)
            assertTrue(document.reusedViews >= 564)
            val first = document.boundView(0)
            assertEquals(events.first().body, first.findViewById<android.widget.TextView>(im.vector.app.R.id.messageTextView).text.toString())
            val last = document.boundView(599)
            assertEquals(events.last().body, last.findViewById<android.widget.TextView>(im.vector.app.R.id.messageTextView).text.toString())
        } finally { document.release(); directory.deleteRecursively() }
    }

    @Test fun `queued PNG exports pass five hundred events with monotonic preparation rendering and publishing progress`() {
        val context = RuntimeEnvironment.getApplication()
        val person = ExportPerson(other, "Fern", 0xff778899.toInt())
        val events = (0 until 625).map { index -> ExportEvent("event-$index", "m.room.message", 1000L + index, person, "Synthetic message $index", "",
                mapOf("content" to mapOf("msgtype" to "m.text", "body" to "Synthetic message $index"))) }
        val options = TimelineExportOptions(roomHeader = false, width = 1080)
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, events.size, events, emptyList(), options)
        val store = TimelineExportStore(context)
        val directory = File(context.cacheDir, "large-png-fixture").also { it.mkdirs() }
        val id = store.stage(snapshot, directory, "large-png-fixture")
        val renderer = exportRenderer()
        val worker = im.vector.app.features.timelineexport.TimelineExportWorker(context, store, renderer, im.vector.app.features.timelineexport.NativeTimelineHtmlExporter(renderer))
        val session = io.mockk.mockk<org.matrix.android.sdk.api.session.Session> { io.mockk.every { myUserId } returns self }
        val progress = mutableListOf<Int>()
        var outputs = emptyList<android.net.Uri>()
        try {
            outputs = runNative { worker.run(session, im.vector.app.features.timelineexport.ExportJobState(id, "Voyage_export_fixture")) { current, total ->
                assertEquals(100, total)
                progress.add(current)
            } }
            assertEquals(1, outputs.size)
            assertEquals(100, progress.last())
            assertTrue(progress.zipWithNext().all { (before, after) -> after > before })
            assertTrue(progress.any { it in 1..25 })
            assertTrue(progress.any { it in 26..89 })
            val file = File(outputs.single().path!!)
            assertTrue(file.length() > 24)
            java.io.DataInputStream(file.inputStream()).use { input ->
                val signature = ByteArray(8).also { input.readFully(it) }
                assertEquals(137.toByte(), signature[0])
                input.readInt(); input.readInt()
                assertEquals(1080, input.readInt())
                assertTrue(input.readInt() > 1000)
            }
        } finally { outputs.forEach { File(it.path!!).delete() }; store.discard(self, id); directory.deleteRecursively() }
    }

    @Test fun `offline custom-emote-only messages keep the timeline big-font rule`() {
        val context = RuntimeEnvironment.getApplication()
        val person = ExportPerson(other, "Fern", 0xff778899.toInt())
        fun message(id: String, html: String, body: String) = ExportEvent(id, "m.room.message", 1000, person, body, html,
                mapOf("content" to mapOf("msgtype" to "m.text", "body" to body, "format" to "org.matrix.custom.html", "formatted_body" to html)))
        val events = listOf(
                message("online", "<img data-mx-emoticon src=\"mxc://example.org/wave\" alt=\":wave:\">", ":wave:"),
                message("offline", "<img data-mx-emoticon src=\"assets/wave.png\" alt=\":wave:\">", ":wave:"),
                message("conventional", "<img width=\"32px\" src=\"assets/wave.png\" alt=\":wave:\">", ":wave:"),
                message("mixed", "Hello <img data-mx-emoticon src=\"assets/wave.png\" alt=\":wave:\">", "Hello :wave:"))
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, events.size, events,
                listOf(im.vector.app.features.timelineexport.ExportAsset("assets/wave.png", "image/png")), TimelineExportOptions(roomHeader = false))
        val directory = File(context.cacheDir, "big-emote-fixture").also { it.mkdirs() }
        val document = layout(exportRenderer(), context, snapshot, directory)
        try {
            val messages = document.models.filterIsInstance<im.vector.app.features.home.room.detail.timeline.item.MessageTextItem>()
            assertEquals(listOf(true, true, true, false), messages.map { it.useBigFont })
            val online = document.boundView(0).findViewById<android.widget.TextView>(im.vector.app.R.id.messageTextView)
            val offline = document.boundView(1).findViewById<android.widget.TextView>(im.vector.app.R.id.messageTextView)
            assertEquals(online.textSize, offline.textSize, .01f)
            val rendered = offline.text as android.text.Spanned
            assertEquals(1, rendered.getSpans(0, rendered.length, im.vector.app.features.html.EmoteImageSpan::class.java).size)
        } finally { document.release(); directory.deleteRecursively() }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun `animation decoding retains the requested frame when it stops reading early`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "animation-frame-fixture").also { it.mkdirs() }
        val path = "assets/avatar.gif"
        File(directory, path).also { it.parentFile!!.mkdirs() }.writeBytes(android.util.Base64.decode(
                "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", android.util.Base64.DEFAULT))
        val snapshot = TimelineExportSnapshot(self, "draft", "Garden", "", "", "Me", 2000, 0, emptyList(),
                listOf(im.vector.app.features.timelineexport.ExportAsset(path, "image/gif", true)), TimelineExportOptions(),
                roomPerson = ExportPerson(room, "Garden", 0xff778899.toInt(), path))
        val renderer = exportRenderer()
        val document = layout(renderer, context, snapshot, directory)
        try {
            runNative { renderer.prepareAssets(document, timeMs = 50) }
            assertNotNull(document.bitmap(path))
            assertFalse(document.bitmap(path)!!.isRecycled)
            assertTrue(document.failedImages.isEmpty())
        } finally { document.release(); directory.deleteRecursively() }
    }
}
