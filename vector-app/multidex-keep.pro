# Legacy multidex (minSdk 19): the Application class and its full superclass chain must live in the
# primary dex, otherwise Dalvik fails to link it at startup before MultiDex.install() can run.
-keep class im.vector.app.VectorApplication
-keep class im.vector.app.Hilt_VectorApplication
-keep class androidx.multidex.MultiDexApplication
-keep class androidx.multidex.MultiDex { *; }
