/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.StrictMode
import android.util.Log
import android.view.Gravity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.recyclerview.widget.SnapHelper
import com.airbnb.epoxy.Carousel
import com.airbnb.epoxy.EpoxyAsyncUtil
import com.airbnb.epoxy.EpoxyController
import com.airbnb.mvrx.Mavericks
import com.gabrielittner.threetenbp.LazyThreeTen
import com.github.rubensousa.gravitysnaphelper.GravitySnapHelper
import dagger.hilt.android.HiltAndroidApp
import im.vector.app.config.Config
import im.vector.app.core.debug.LeakDetector
import im.vector.app.core.dex.MultiDexLoader
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.glide.GlideMemoryTrimmer
import im.vector.app.core.pushers.FcmHelper
import im.vector.app.core.resources.BuildMeta
import im.vector.app.core.session.EnsureSessionSyncingUseCase
import im.vector.app.core.session.HomeserverMirrorRefresher
import im.vector.app.features.configuration.VectorConfiguration
import im.vector.app.features.invite.InvitesAcceptor
import im.vector.app.features.lifecycle.VectorActivityLifecycleCallbacks
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.pin.PinLocker
import im.vector.app.features.popup.PopupAlertManager
import im.vector.app.features.rageshake.VectorFileLogger
import im.vector.app.features.rageshake.VectorUncaughtExceptionHandler
import im.vector.app.features.settings.VectorLocale
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeUtils
import im.vector.app.features.version.VersionProvider
import org.maplibre.android.MapLibre
import org.matrix.android.sdk.api.Matrix
import org.matrix.android.sdk.api.auth.AuthenticationService
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import androidx.work.Configuration as WorkConfiguration

@HiltAndroidApp
class VectorApplication :
        Application(),
        WorkConfiguration.Provider {

    lateinit var appContext: Context
    @Inject lateinit var authenticationService: AuthenticationService
    @Inject lateinit var vectorConfiguration: VectorConfiguration
    @Inject lateinit var emojiCompatFontProvider: EmojiCompatFontProvider
    @Inject lateinit var emojiCompatWrapper: EmojiCompatWrapper
    @Inject lateinit var roomTopicRenderer: im.vector.app.features.home.room.detail.timeline.tools.RoomTopicRenderer
    @Inject lateinit var twemojiProvider: im.vector.app.features.emoji.TwemojiProvider
    @Inject lateinit var vectorUncaughtExceptionHandler: VectorUncaughtExceptionHandler
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    @Inject lateinit var ensureSessionSyncingUseCase: EnsureSessionSyncingUseCase
    @Inject lateinit var notificationDrawerManager: NotificationDrawerManager
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var versionProvider: VersionProvider
    @Inject lateinit var notificationUtils: NotificationUtils
    @Inject lateinit var spaceStateHandler: SpaceStateHandler
    @Inject lateinit var popupAlertManager: PopupAlertManager
    @Inject lateinit var pinLocker: PinLocker
    @Inject lateinit var homeserverMirrorRefresher: HomeserverMirrorRefresher
    @Inject lateinit var invitesAcceptor: InvitesAcceptor
    @Inject lateinit var vectorFileLogger: VectorFileLogger
    @Inject lateinit var matrix: Matrix
    @Inject lateinit var fcmHelper: FcmHelper
    @Inject lateinit var buildMeta: BuildMeta
    @Inject lateinit var leakDetector: LeakDetector
    @Inject lateinit var vectorLocale: VectorLocale

    private val powerKeyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF &&
                    vectorPreferences.useFlagPinCode()) {
                pinLocker.screenIsOff()
            }
        }
    }

    override fun onCreate() {
        if (MultiDexLoader.isLoaderProcess(this)) {
            // Loader process: skip Hilt and all app init (its dexes/components aren't available here).
            return
        }
        enableStrictModeIfNeeded()
        // Pre-Lollipop can't inflate <vector> drawables natively (android:src/setImageResource); let
        // AppCompat load them through VectorDrawableCompat instead. Must run before any inflation.
        androidx.appcompat.app.AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        super.onCreate()
        // Hilt has injected vectorPreferences by now. Seed perf flag immediately so we can
        // time the rest of onCreate.
        im.vector.app.core.utils.PerfTrace.isEnabled = vectorPreferences.isPerfLoggingEnabled()
        org.matrix.android.sdk.api.util.MatrixPerf.isEnabled = im.vector.app.core.utils.PerfTrace.isEnabled
        // Seed the hardware-derived Performance-mode default and mirror it for the hot render paths.
        vectorPreferences.seedPerformanceModeDefaultIfNeeded()
        vectorPreferences.applyPerformanceModeConstraints()
        im.vector.app.core.ui.PerformanceMode.enabled = vectorPreferences.isPerformanceModeEnabled()
        im.vector.app.core.utils.FrameJankWatcher.startIfEnabled()
        val perfMarker = im.vector.app.core.utils.PerfTrace.mark("app.onCreate")
        de.spiritcroc.matrixsdk.StaticScSdkHelper.scSdkPreferenceProvider = vectorPreferences
        appContext = this
        invitesAcceptor.initialize()
        vectorUncaughtExceptionHandler.activate()

        if (buildMeta.isDebug) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(vectorFileLogger)

        logInfo()
        LazyThreeTen.init(this)
        Mavericks.initialize(debugMode = false)

        configureEpoxy()

        registerActivityLifecycleCallbacks(VectorActivityLifecycleCallbacks(popupAlertManager))
        vectorLocale.init()
        ThemeUtils.init(this)
        vectorConfiguration.applyToApplicationContext()

        // Shared entry point for emoji rendering across all message-text surfaces (see prepareForDisplay()).
        im.vector.app.features.home.room.detail.timeline.tools.messageEmojiSpanify = emojiCompatWrapper
        org.billcarsonfr.jsonviewer.jsonViewerEmojiSpanify = emojiCompatWrapper::spanify
        // Shared entry point for rich topic rendering across the non-DI topic display surfaces (see formatTopic()).
        im.vector.app.features.home.room.detail.timeline.tools.messageTopicRenderer = roomTopicRenderer
        if (twemojiProvider.enabled) {
            // Twemoji draws emoji from bundled sprites (forced below KitKat, opt-in above) and bypasses
            // EmojiCompat, so don't init it or load its 10MB font. The reaction picker uses the sprites too.
            im.vector.app.features.reactions.EmojiDrawView.twemojiResolver = twemojiProvider::bitmapForEmoji
            im.vector.app.features.reactions.EmojiDrawView.twemojiSpanify = twemojiProvider::spanify
            Thread { twemojiProvider.warmUp() }.start()
        } else {
            emojiCompatWrapper.init()
            // Feed the bundled emoji Typeface to the font provider so the emoji picker / reactions render
            // emoji without Google Play Services (the old downloadable FontRequest is dead on F-Droid).
            // The bundled font is a CBDT colour font the platform only rasterises on API 21+, and
            // EmojiCompat no-ops below 19, so this path only makes sense on KitKat+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                emojiCompatFontProvider.typeface = emojiCompatWrapper.emojiTypeface
            }
        }

        notificationUtils.createNotificationChannels()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                Timber.i("App entered foreground")
                fcmHelper.onEnterForeground(activeSessionHolder)
                activeSessionHolder.getSafeActiveSessionAsync {
                    it?.syncService()?.stopAnyBackgroundSync()
                    ensureSessionSyncingUseCase.execute()
                }
            }

            override fun onPause(owner: LifecycleOwner) {
                Timber.i("App entered background")
                fcmHelper.onEnterBackground(activeSessionHolder)
                GlideMemoryTrimmer.onAppBackgrounded(this@VectorApplication)
            }
        })
        ProcessLifecycleOwner.get().lifecycle.addObserver(spaceStateHandler)
        ProcessLifecycleOwner.get().lifecycle.addObserver(pinLocker)
        ProcessLifecycleOwner.get().lifecycle.addObserver(homeserverMirrorRefresher)
        // This should be done as early as possible
        // initKnownEmojiHashSet(appContext)
        ContextCompat.registerReceiver(
                applicationContext,
                powerKeyReceiver,
                IntentFilter().apply {
                    // Looks like i cannot receive OFF, if i don't have both ON and OFF
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Initialize MapLibre before inflating mapViews. Its native lib is API 21+; on KitKat maps
        // are never shown (locations render as a text notice), so skip init to avoid loading it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MapLibre.getInstance(this)
        }

        initMemoryLeakAnalysis()
        perfMarker.end()
    }

    private fun configureEpoxy() {
        EpoxyController.defaultDiffingHandler = EpoxyAsyncUtil.getAsyncBackgroundHandler()
        EpoxyController.defaultModelBuildingHandler = EpoxyAsyncUtil.getAsyncBackgroundHandler()
        Carousel.setDefaultGlobalSnapHelperFactory(object : Carousel.SnapHelperFactory() {
            override fun buildSnapHelper(context: Context?): SnapHelper {
                return GravitySnapHelper(Gravity.START)
            }
        })
    }

    private fun enableStrictModeIfNeeded() {
        if (Config.ENABLE_STRICT_MODE_LOGS) {
            StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                            .detectAll()
                            .penaltyLog()
                            .build()
            )
        }
    }

    override fun getWorkManagerConfiguration(): WorkConfiguration {
        return WorkConfiguration.Builder()
                .setWorkerFactory(matrix.getWorkerFactory())
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(Executors.newCachedThreadPool())
                .build()
    }

    private fun logInfo() {
        val appVersion = versionProvider.getVersion(longFormat = true)
        val sdkVersion = Matrix.getSdkVersion()
        val date = SimpleDateFormat("MM-dd HH:mm:ss.SSSZ", Locale.US).format(Date())

        Timber.d("----------------------------------------------------------------")
        Timber.d("----------------------------------------------------------------")
        Timber.d(" Application version: $appVersion")
        Timber.d(" SDK version: $sdkVersion")
        Timber.d(" Local time: $date")
        Timber.d("----------------------------------------------------------------")
        Timber.d("----------------------------------------------------------------\n\n\n\n")
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (MultiDexLoader.installOrDelegate(this)) {
            // We are the throwaway ":multidex" loader process — secondary dexes aren't loaded, so
            // don't touch anything that lives in one (Conscrypt below, Hilt in onCreate).
            return
        }
        // Pre-Lollipop stock JCE providers lack a working AES/GCM (BouncyCastle rejects GCMParameterSpec)
        // and don't enable TLS 1.2 by default. Conscrypt backfills both; install it first so secure
        // storage and OkHttp pick it up. Harmless to skip on API 21+ where the platform is fine.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            runCatching { java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1) }
                    .onFailure { Log.e("VectorApplication", "Failed to install Conscrypt", it) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The :multidex loader process (and any process where onCreate returned early) never ran Hilt,
        // so the injected fields are unset — don't touch them on a config change there.
        if (!::vectorConfiguration.isInitialized) return
        vectorConfiguration.onConfigurationChanged()
    }

    private fun initMemoryLeakAnalysis() {
        leakDetector.enable(vectorPreferences.isMemoryLeakAnalysisEnabled())
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Timber.i("onTrimMemory level=$level")
    }
}
