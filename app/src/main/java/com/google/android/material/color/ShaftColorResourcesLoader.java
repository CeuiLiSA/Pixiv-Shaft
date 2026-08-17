package com.google.android.material.color;

import android.content.Context;
import android.content.res.loader.ResourcesLoader;
import android.os.Build.VERSION_CODES;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.Map;

/**
 * 借道 Material 的 ARSC 生成器，把「运行时把某个 color 资源换成任意色值」这件事拿出来给 app 用。
 *
 * <p>为什么必须这么绕：主题色最终要落到 {@code ?attr/colorPrimary}（全仓 200+ 处 XML 引用它，
 * 连 {@link ceui.pixiv.witstudio.theme.V3Palette} 都是从这个 attr 解出来的）。theme attr 的值在编译期就烤进
 * style 里了，运行时改不了 —— 唯一的官方口子是 API 30 的 {@code ResourcesLoader}：让
 * {@code AppTheme.Custom} 的 colorPrimary 指向 {@code @color/custom_theme_primary}，再用一张
 * 运行时生成的 ARSC 把这个 color 资源覆盖掉。
 *
 * <p>生成 ARSC 那段（{@link ColorResourcesTableCreator}，几百行手写资源表二进制）Material 已经
 * 写好了，但它连同 {@link ColorResourcesLoaderCreator} 一起是 package-private，外部够不着；
 * Material 自己的公开入口 {@code DynamicColors} 只吃 M3 seed color，还会顺手糊一层
 * {@code ThemeOverlay.Material3.PersonalizedColors}——本 app 主题是 QMUI/AppCompat 系，套 M3
 * overlay 会把 colorPrimary 解成 M3 baseline tone（见 fragment_plaza_post_detail.xml 的注释）。
 * 所以这里放一个同包类，只借生成器，不要 overlay。
 *
 * <p>与 Material 的耦合是编译期的：升级 Material 时若这两个类改名/改签名，构建会直接红，
 * 不会静默失效。
 */
@RequiresApi(VERSION_CODES.R)
public final class ShaftColorResourcesLoader {

    private ShaftColorResourcesLoader() {
    }

    /**
     * @param colorMapping color 资源 id → 要覆盖成的 ARGB 色值
     * @return 可以塞给 {@code Resources#addLoaders} 的 loader；生成失败返回 null
     */
    @Nullable
    public static ResourcesLoader create(
            @NonNull Context context, @NonNull Map<Integer, Integer> colorMapping) {
        return ColorResourcesLoaderCreator.create(context, colorMapping);
    }
}
