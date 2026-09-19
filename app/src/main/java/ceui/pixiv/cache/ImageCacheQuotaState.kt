package ceui.pixiv.cache

/**
 * 「Glide 本次进程真正吃到的图片缓存上限」——只活在内存里，不落盘。
 *
 * 存在的唯一理由：设置页那一行要在用户改完、但还没重启的时候把数值标成「未生效」。
 * 判断依据不是「有没有点过确定」，而是「设置里存的值 != 本次 Glide 初始化读到的值」：
 * 把 250 拖到 300 再拖回 250 并确定，后缀会正确地消失（正在跑的就是 250）；重启之后
 * 两边自然相等，后缀也不会出现。
 *
 * 与 ImageCacheQuota 分开：那个是纯数学、无状态、可单测；这个带可变状态，不该混进去。
 */
object ImageCacheQuotaState {

    /** Glide 初始化时读到的上限（MB）；null 表示还没有创建缓存配置。 */
    @Volatile
    private var appliedLimitMb: Int? = null

    /** 由 GlideConfiguration.applyOptions 在 Glide 初始化时调用，记下真正生效的值。 */
    @JvmStatic
    fun markApplied(limitMb: Int) {
        appliedLimitMb = ImageCacheQuota.clampLimitMb(limitMb)
    }

    /** 设置里的值还没生效（改过但没重启）。两边都先 clamp，避免拿未规范化的值去比。 */
    @JvmStatic
    fun isPendingRestart(currentSettingMb: Int): Boolean {
        // 首次加载图片才初始化 Glide；此前改设置会直接被首次初始化读取，无需重启。
        val applied = appliedLimitMb ?: return false
        return ImageCacheQuota.clampLimitMb(currentSettingMb) != applied
    }
}
