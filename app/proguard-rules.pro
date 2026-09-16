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
-keepclassmembers class ceui.pixiv.api.** { <fields>; }
-keepclassmembers class ceui.pixiv.shaftapi.** { <fields>; }
-keepclassmembers class ceui.lisa.network.** { <fields>; }
-keepclassmembers class ceui.lisa.update.** { <fields>; }
-keepclassmembers class ceui.pixiv.chat.api.** { <fields>; }
-keepclassmembers class ceui.pixiv.events.** { <fields>; }
-keepclassmembers class ceui.pixiv.download.config.** { <fields>; }
-keepclassmembers class ceui.pixiv.download.header.** { <fields>; }

# Wire models that live outside the model packages (referenced by fully-qualified name from the
# Retrofit interfaces). Same contract as above: their source field names are the JSON schema.
-keepclassmembers class ceui.pixiv.ui.user.UserRequestPlansResponse { <fields>; }
-keepclassmembers class ceui.pixiv.ui.user.RequestPlanUserProfile { <fields>; }
-keepclassmembers class ceui.pixiv.ui.user.RequestPlan { <fields>; }
-keepclassmembers class ceui.pixiv.ui.user.RequestPlanAcceptFlags { <fields>; }
-keepclassmembers class ceui.pixiv.ui.user.RequestPlanText { <fields>; }
-keepclassmembers class ceui.pixiv.ui.user.RequestPlanImageUrls { <fields>; }
-keepclassmembers class ceui.lisa.http.CloudFlareDNSResponse { <fields>; }
-keepclassmembers class ceui.lisa.http.CloudFlareDNSResponse$* { <fields>; }

# Retrofit also reflects generic response types. In full mode, keeping fields alone is not enough:
# R8 may merge/remove a DTO class and rewrite Observable<Dto> or Continuation<Dto> to Object,
# which makes every otherwise-successful response fail with ClassCastException. Preserve only the
# class identities of the wire-model packages; names may still be obfuscated, while methods keep
# their normal shrinking/optimization behavior and the field rules above protect the JSON schema.
-keep,allowobfuscation class ceui.lisa.models.**
-keep,allowobfuscation class ceui.lisa.model.**
-keep,allowobfuscation class ceui.loxia.*
-keep,allowobfuscation class ceui.pixiv.api.**
-keep,allowobfuscation class ceui.pixiv.shaftapi.**
-keep,allowobfuscation class ceui.lisa.network.**
-keep,allowobfuscation class ceui.lisa.update.**
-keep,allowobfuscation class ceui.pixiv.chat.api.**
-keep,allowobfuscation class ceui.pixiv.events.**
-keep,allowobfuscation class ceui.pixiv.ui.user.UserRequestPlansResponse
-keep,allowobfuscation class ceui.pixiv.ui.user.RequestPlanUserProfile
-keep,allowobfuscation class ceui.pixiv.ui.user.RequestPlan
-keep,allowobfuscation class ceui.pixiv.ui.user.RequestPlanAcceptFlags
-keep,allowobfuscation class ceui.pixiv.ui.user.RequestPlanText
-keep,allowobfuscation class ceui.pixiv.ui.user.RequestPlanImageUrls
-keep,allowobfuscation class ceui.lisa.http.CloudFlareDNSResponse
-keep,allowobfuscation class ceui.lisa.http.CloudFlareDNSResponse$*

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
-keepclassmembers class ceui.lisa.core.DownloadItem { <fields>; }
-keepclassmembers class ceui.pixiv.actions.BookmarkPayload { <fields>; }
-keepclassmembers class ceui.pixiv.actions.FollowPayload { <fields>; }
-keepclassmembers class ceui.pixiv.actions.Nana7miSearchTelemetry$Payload { <fields>; }
-keepclassmembers class ceui.pixiv.actions.Nana7miSearchTelemetry$BatchPayload { <fields>; }
-keepclassmembers class ceui.pixiv.snapshot.SnapshotManifest { <fields>; }
-keepclassmembers class ceui.pixiv.snapshot.SnapshotAssets { <fields>; }
-keepclassmembers class ceui.pixiv.snapshot.SnapshotComments { <fields>; }
-keepclassmembers class ceui.pixiv.snapshot.SnapshotCommentThread { <fields>; }
-keepclassmembers class ceui.pixiv.snapshot.AutoSnapshotManifest { <fields>; }
-keepclassmembers class ceui.pixiv.snapshot.AutoSnapshotBehaviorRecord { <fields>; }
-keepclassmembers class ceui.pixiv.snapshot.AutoSnapshotDwellSample { <fields>; }
-keepclassmembers class ceui.pixiv.ui.prime.PrimeTagIndexItem { <fields>; }
-keepclassmembers class ceui.pixiv.ui.account.EmailBackupV3ViewModel$ErrorBody { <fields>; }
-keepclassmembers class ceui.pixiv.ui.debug.PopularTagExportViewModel$Envelope { <fields>; }

# Field rules alone do NOT protect a class that only Gson ever instantiates: R8 full mode marks it
# uninstantiated, deletes its "dead" instance fields despite -keepclassmembers, and folds reads to
# null (verified in the release mapping — PreviewRoot/RawBackup lost every field while the
# identity-kept model packages above survived). Every reflection-only type in this file therefore
# also pins its class identity here; renaming the class stays allowed, field names stay original.
-keep,allowobfuscation class ceui.lisa.core.DownloadItem
-keep,allowobfuscation class ceui.lisa.utils.Settings
-keep,allowobfuscation class ceui.lisa.utils.BackupUtils$BackupEntity
-keep,allowobfuscation class ceui.lisa.feature.FeatureEntity
-keep,allowobfuscation class ceui.pixiv.actions.BookmarkPayload
-keep,allowobfuscation class ceui.pixiv.actions.FollowPayload
-keep,allowobfuscation class ceui.pixiv.actions.Nana7miSearchTelemetry$Payload
-keep,allowobfuscation class ceui.pixiv.actions.Nana7miSearchTelemetry$BatchPayload
-keep,allowobfuscation class ceui.pixiv.download.StageStore$Manifest
-keep,allowobfuscation class ceui.pixiv.snapshot.SnapshotManifest
-keep,allowobfuscation class ceui.pixiv.snapshot.SnapshotAssets
-keep,allowobfuscation class ceui.pixiv.snapshot.SnapshotComments
-keep,allowobfuscation class ceui.pixiv.snapshot.SnapshotCommentThread
-keep,allowobfuscation class ceui.pixiv.snapshot.AutoSnapshotManifest
-keep,allowobfuscation class ceui.pixiv.snapshot.AutoSnapshotBehaviorRecord
-keep,allowobfuscation class ceui.pixiv.snapshot.AutoSnapshotDwellSample
-keep,allowobfuscation class ceui.pixiv.ui.synonym.SynonymDictBackup$*Json
-keep,allowobfuscation class ceui.pixiv.ui.history.BrowseHistoryBackup$Payload
-keep,allowobfuscation class ceui.pixiv.ui.history.BrowseHistoryBackup$RawBackup
-keep,allowobfuscation class ceui.pixiv.ui.pinned.PreviewRoot
-keep,allowobfuscation class ceui.pixiv.ui.pinned.PreviewTag
-keep,allowobfuscation class ceui.pixiv.ui.pinned.PreviewResp
-keep,allowobfuscation class ceui.pixiv.ui.prime.PrimeTagIndexItem
-keep,allowobfuscation class ceui.pixiv.ui.account.EmailBackupV3ViewModel$ErrorBody
-keep,allowobfuscation class ceui.pixiv.ui.debug.PopularTagExportViewModel$Envelope

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

# onnxruntime-android ships no consumer rules, and libonnxruntime4j_jni.so resolves ai.onnxruntime.*
# by name (FindClass/GetMethodID/GetFieldID on OrtException, TensorInfo, NodeInfo, OnnxTensor, ...).
# Verified in the release mapping: OrtException was renamed to b.c and TensorInfo removed, so every
# ORT error path and getInputInfo() would throw NoClassDefFoundError in the minified APK.
-keep class ai.onnxruntime.** { *; }

# Shaft.hasStackFrame() suppresses known library crashes by matching source class names on the
# stack. Keep those names so the workarounds still fire after obfuscation.
-keepnames class com.bumptech.glide.load.resource.gif.GifDrawable
-keepnames class com.google.android.gms.common.internal.BaseGmsClient
-keepnames class com.google.android.gms.common.internal.BaseGmsClient$*

# Retrofit wire type outside the model packages (API.kt getSearchOptions). It currently survives only
# through Gson's own @SerializedName rule; pin it the same way as RequestPlan* so an un-annotated
# field added later cannot be stripped.
-keep,allowobfuscation class ceui.pixiv.ui.search.v3.SearchOptionsResponse
-keep,allowobfuscation class ceui.pixiv.ui.search.v3.SearchOptionsScope
-keep,allowobfuscation class ceui.pixiv.ui.search.v3.BookmarkRange
-keep,allowobfuscation class ceui.pixiv.ui.search.v3.ToolOptions
-keep,allowobfuscation class ceui.pixiv.ui.search.v3.GenreOptions
-keep,allowobfuscation class ceui.pixiv.ui.search.v3.GenreOption
-keep,allowobfuscation class ceui.pixiv.ui.search.v3.LangOptions
-keep,allowobfuscation class ceui.pixiv.ui.search.v3.LangOption
-keepclassmembers class ceui.pixiv.ui.search.v3.SearchOptionsResponse { <fields>; }
-keepclassmembers class ceui.pixiv.ui.search.v3.SearchOptionsScope { <fields>; }
-keepclassmembers class ceui.pixiv.ui.search.v3.BookmarkRange { <fields>; }
-keepclassmembers class ceui.pixiv.ui.search.v3.ToolOptions { <fields>; }
-keepclassmembers class ceui.pixiv.ui.search.v3.GenreOptions { <fields>; }
-keepclassmembers class ceui.pixiv.ui.search.v3.GenreOption { <fields>; }
-keepclassmembers class ceui.pixiv.ui.search.v3.LangOptions { <fields>; }
-keepclassmembers class ceui.pixiv.ui.search.v3.LangOption { <fields>; }

# Persisted download/header config models: the field rules above only hold while Java code keeps
# instantiating these classes. Pin identity so a refactor to Gson-only construction stays safe.
-keep,allowobfuscation class ceui.pixiv.download.config.**
-keep,allowobfuscation class ceui.pixiv.download.header.**
