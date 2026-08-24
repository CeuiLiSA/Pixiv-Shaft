# Keep enough debug metadata for Crashlytics/Retrace while still obfuscating source paths.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin/Java DTO fields in these packages are the JSON wire schema. The legacy models predate
# @SerializedName and Gson therefore reads their source field names reflectively. Keep only fields
# of live classes: unused classes and methods can still be removed, class names can still be
# obfuscated, and method bodies remain optimizable. Do not add allowoptimization here: R8 can
# otherwise prove that Java code never reads a reflection-only field and delete it before Gson
# runs (for example ListIllust.illusts and ListIllust.next_url).
-keepclassmembers class ceui.lisa.models.** { <fields>; }
-keepclassmembers class ceui.lisa.model.** { <fields>; }
-keepclassmembers class ceui.loxia.** { <fields>; }
-keepclassmembers class ceui.lisa.network.** { <fields>; }
-keepclassmembers class ceui.lisa.update.** { <fields>; }
-keepclassmembers class ceui.pixiv.chat.api.** { <fields>; }
-keepclassmembers class ceui.pixiv.events.** { <fields>; }
-keepclassmembers class ceui.pixiv.download.config.** { <fields>; }
-keepclassmembers class ceui.pixiv.download.header.** { <fields>; }

# Retrofit also reflects generic response types. In full mode, keeping fields alone is not enough:
# R8 may merge/remove a DTO class and rewrite Observable<Dto> or Continuation<Dto> to Object,
# which makes every otherwise-successful response fail with ClassCastException. Preserve only the
# class identities of the wire-model packages; names may still be obfuscated, while methods keep
# their normal shrinking/optimization behavior and the field rules above protect the JSON schema.
-keep,allowobfuscation class ceui.lisa.models.**
-keep,allowobfuscation class ceui.lisa.model.**
-keep,allowobfuscation class ceui.loxia.*
-keep,allowobfuscation class ceui.lisa.network.**
-keep,allowobfuscation class ceui.lisa.update.**
-keep,allowobfuscation class ceui.pixiv.chat.api.**
-keep,allowobfuscation class ceui.pixiv.events.**

# Persisted/imported JSON models outside the network model packages. Their field names are an
# on-disk compatibility contract across app upgrades, so preserving them also protects old backups.
-keepclassmembers class ceui.lisa.utils.Settings { <fields>; }
-keepclassmembers class ceui.lisa.utils.BackupUtils$BackupEntity { <fields>; }
-keepclassmembers class ceui.lisa.database.** { <fields>; }
-keepclassmembers class ceui.lisa.feature.FeatureEntity { <fields>; }
-keepclassmembers class ceui.pixiv.db.** { <fields>; }
-keepclassmembers class ceui.pixiv.download.StageStore$Manifest { <fields>; }
-keepclassmembers class ceui.pixiv.ui.synonym.SynonymDictBackup$*Json { <fields>; }
-keepclassmembers class ceui.pixiv.ui.history.BrowseHistoryBackup$Payload { <fields>; }
-keepclassmembers class ceui.pixiv.ui.history.BrowseHistoryBackup$RawBackup { <fields>; }
-keepclassmembers class ceui.pixiv.ui.pinned.PreviewRoot { <fields>; }
-keepclassmembers class ceui.pixiv.ui.pinned.PreviewTag { <fields>; }
-keepclassmembers class ceui.pixiv.ui.pinned.PreviewResp { <fields>; }

# These classes use private AndroidX fields by exact string name. Keep only the reflected fields;
# DrawerLayoutHelper already degrades safely if an AndroidX release removes them altogether.
-keepclassmembers class androidx.drawerlayout.widget.DrawerLayout {
    private androidx.customview.widget.ViewDragHelper mLeftDragger;
    private *** mLeftCallback;
}
-keepclassmembers class androidx.customview.widget.ViewDragHelper {
    private int mEdgeSize;
}
-keepclassmembers class androidx.drawerlayout.widget.DrawerLayout$ViewDragCallback {
    private java.lang.Runnable mPeekRunnable;
}

# WebView invokes these methods from JavaScript, outside the Java call graph.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# JNI exported symbol names contain this Kotlin object's binary class/method names.
-keepclasseswithmembernames,includedescriptorclasses class ceui.pixiv.shaftapi.ShaftHmac {
    native <methods>;
}

# Android test dependencies that also exist on the app runtime classpath are loaded from the
# target APK. The release runner calls this before test discovery, so it must survive target R8.
-keep class androidx.tracing.Trace { *; }
-keep class kotlin.LazyKt { *; }

# AgentWeb intentionally probes these optional integrations and catches their absence. They are
# not app features; suppress only the exact optional API references instead of hiding a package.
-dontwarn com.alipay.sdk.app.H5PayCallback
-dontwarn com.alipay.sdk.app.PayTask
-dontwarn com.download.library.DownloadImpl
-dontwarn com.download.library.DownloadListenerAdapter
-dontwarn com.download.library.DownloadTask
-dontwarn com.download.library.ResourceRequest
