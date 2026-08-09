package ceui.pixiv.actionqueue

/**
 * 时间源。抽出来是为了让节流和退避能在单测里用虚拟时间跑完 ——
 * 否则测「撞 429 后冷却 30 秒」就真得睡 30 秒。
 */
public fun interface Clock {

    public fun nowMs(): Long

    public companion object {
        /** 墙上时钟。 */
        public val SYSTEM: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
