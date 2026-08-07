# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Do NOT blanket-keep the whole app. A `-keep ... { *; }` pins every class (including all the Kotlin
# lambda/synthetic classes) against R8's class-merging + inlining, so our own loaded-class count only
# grows as features are added — which is what overflows ICS's fixed 8MB Dalvik LinearAlloc (class/method
# metadata) at the home screen. Instead let R8 optimise features.** and keep only the reflective entry
# points that R8 can't see through:
#  - Mavericks rebuilds a ViewState from Parcelable fragment args and finds the ViewModel factory (the
#    companion) by reflection.
#  - the FragmentManager, preference screens and settings re-instantiate fragments/preferences by name.
# (AGP's default -optimize rules keep manifest components, custom Views and Parcelable CREATORs; Epoxy,
# Moshi, Glide and Hilt ship their own consumer rules.)
-keep class * implements com.airbnb.mvrx.MavericksState { *; }
-keep class * implements com.airbnb.mvrx.MavericksViewModelFactory { *; }
-keepclassmembers class * extends com.airbnb.mvrx.MavericksViewModel {
    <init>(...);
}
-keep class * extends androidx.fragment.app.Fragment {
    <init>(...);
}
-keep class * extends androidx.preference.Preference {
    <init>(...);
}

## print all the rules in a file
# -printconfiguration ../proguard_files/full-r8-config.txt

# WebRTC

-keep class org.webrtc.** { *; }
-dontwarn org.chromium.build.BuildHooksAndroid

# Jitsi (else callbacks are not called)

-keep class org.jitsi.meet.** { *; }
-keep class org.jitsi.meet.sdk.** { *; }

# React Native

# Keep our interfaces so they can be used by other ProGuard rules.
# See http://sourceforge.net/p/proguard/bugs/466/
-keep,allowobfuscation @interface com.facebook.proguard.annotations.DoNotStrip
-keep,allowobfuscation @interface com.facebook.proguard.annotations.KeepGettersAndSetters
-keep,allowobfuscation @interface com.facebook.common.internal.DoNotStrip

# Do not strip any method/class that is annotated with @DoNotStrip
-keep @com.facebook.proguard.annotations.DoNotStrip class *
-keep @com.facebook.common.internal.DoNotStrip class *
-keepclassmembers class * {
    @com.facebook.proguard.annotations.DoNotStrip *;
    @com.facebook.common.internal.DoNotStrip *;
}

-keepclassmembers @com.facebook.proguard.annotations.KeepGettersAndSetters class * {
  void set*(***);
  *** get*();
}

-keep class * extends com.facebook.react.bridge.JavaScriptModule { *; }
-keep class * extends com.facebook.react.bridge.NativeModule { *; }
-keepclassmembers,includedescriptorclasses class * { native <methods>; }
-keepclassmembers class *  { @com.facebook.react.uimanager.UIProp <fields>; }
-keepclassmembers class *  { @com.facebook.react.uimanager.annotations.ReactProp <methods>; }
-keepclassmembers class *  { @com.facebook.react.uimanager.annotations.ReactPropGroup <methods>; }

-dontwarn com.facebook.react.**
-keep,includedescriptorclasses class com.facebook.react.bridge.** { *; }

-keepattributes InnerClasses
# Keep generic signatures so Moshi (and other reflection) can read parameterized types.
-keepattributes Signature, EnclosingMethod

# Retrofit 2.6.4 predates the R8 full-mode keep rules; full mode strips the generic signatures of
# types not explicitly kept, so suspend functions lose their Continuation<? super T> signature and
# Retrofit throws "Class cannot be cast to ParameterizedType". Keep the types whose signatures it reads.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# JWT dependencies
-keep class io.jsonwebtoken.** { *; }
-keepnames class io.jsonwebtoken.* { *; }
-keepnames interface io.jsonwebtoken.* { *; }

-keep class org.bouncycastle.** { *; }
-keepnames class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# JNA
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# New
-dontwarn com.google.appengine.api.urlfetch.**
-dontwarn com.google.common.io.LimitInputStream
-dontwarn com.google.firebase.analytics.connector.AnalyticsConnector
-dontwarn com.google.javascript.jscomp.**
-dontwarn com.likethesalad.android.templates.provider.api.TemplatesProvider
-dontwarn com.yahoo.platform.yui.compressor.**
-dontwarn java.awt.**
-dontwarn org.apache.velocity.**
-dontwarn org.commonmark.ext.gfm.strikethrough.Strikethrough
-dontwarn org.mozilla.javascript.**
-dontwarn org.slf4j.**
-dontwarn org.jspecify.annotations.NullMarked

# Conscrypt references platform SSLParametersImpl variants that only exist on specific OS versions.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl

# Shaded Guava (inside checkerframework) references compile-only j2objc annotations not on the runtime classpath.
-dontwarn org.checkerframework.com.google.j2objc.annotations.RetainedWith
-dontwarn org.checkerframework.com.google.j2objc.annotations.Weak

# JLaTeXMath resolves macros and atom classes by name from its bundled XML assets
# (Class.forName / getDeclaredMethod / getDeclaredField), so shrinking removes them with no
# static reference to spot. It fails silently — the plugin swallows the Throwable and maths
# renders as nothing — so this has to be a keep, not a dontwarn.
-keep class org.scilab.forge.jlatexmath.** { *; }
