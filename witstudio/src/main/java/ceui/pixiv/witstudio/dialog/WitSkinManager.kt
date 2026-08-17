package ceui.pixiv.witstudio.dialog

import android.content.Context

/**
 * `QMUISkinManager` 的**临时空壳**。
 *
 * QMUI 的皮肤系统在本项目里从头到尾是死代码：123 处
 * `setSkinManager(QMUISkinManager.defaultInstance(ctx))`，但仓库里没有一份 skin XML、
 * 没有 Application 级初始化、也没有任何一次 `changeSkin` —— 日夜切换全靠 `values-night`。
 *
 * 保留这个空壳只为让「改类名」那一步是纯 token 交换，diff 上不掺别的东西。
 * 迁移完成后会分两步删掉：先删 123 处调用，再删本文件。
 */
@Deprecated("皮肤系统从未启用，迁移完成后删除")
public class WitSkinManager private constructor() {

    public companion object {
        private val INSTANCE = WitSkinManager()

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        public fun defaultInstance(context: Context): WitSkinManager = INSTANCE
    }
}
