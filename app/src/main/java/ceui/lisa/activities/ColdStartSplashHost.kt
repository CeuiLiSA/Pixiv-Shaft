package ceui.lisa.activities

/**
 * 冷启动开屏的放行信号。宿主（[MainActivity]）用自己的实例字段记「是否已裁决」，
 * 首页推荐插画 tab 在本地优先裁决出结果后回调 [markSplashResolved]。
 *
 * 以前是一个进程级 object（ColdStartSplashGate），MainActivity 重建时还得手动 reset——
 * 「第二个实例要 reset 旧状态」本身就说明它是 Activity 的状态而不是进程的。
 */
interface ColdStartSplashHost {
    fun markSplashResolved()
}
