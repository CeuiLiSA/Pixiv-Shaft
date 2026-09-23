package ceui.pixiv.witstudio.widget

import androidx.annotation.DrawableRes

/** [key] 是稳定身份；同文案、不同业务类型的条目应使用不同 key。 */
public data class WitTagItem @JvmOverloads public constructor(
    public val key: String,
    public val name: String,
    public val translation: String? = null,
    @DrawableRes public val leadingIcon: Int = 0,
    /** 非空时展示独立、可由读屏操作的删除按钮。文案由宿主本地化。 */
    public val removeDescription: String? = null,
)
