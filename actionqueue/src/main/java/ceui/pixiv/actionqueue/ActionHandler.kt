package ceui.pixiv.actionqueue

/**
 * 某一类动作的执行器。由调用方实现并在构造 [ActionQueue] 时按 type 注册。
 *
 * ## 幂等是硬契约
 *
 * 进程可能在请求已经发出、响应还没回来的瞬间被系统杀掉。下次启动时
 * [ActionStore.resurrectRunning] 会把这条 RUNNING 复位成 PENDING 重新执行 ——
 * 也就是说**同一个动作可能被执行两次**。
 *
 * 收藏 / 取消收藏 / 关注 / 取关天然满足幂等（pixiv 对重复收藏返回成功）。
 * 将来若要接入非幂等的动作（发评论、发私信），必须先在 payload 里带上业务侧的
 * 去重 id 并由服务端兜底，不能直接塞进这个队列。
 *
 * ## 不要在 execute 里做 UI
 *
 * execute 跑在队列自己的 IO scope 上。UI 反馈请订阅 [ActionQueue.events]。
 */
public fun interface ActionHandler {

    /**
     * 执行一条动作。
     *
     * 抛异常等价于返回 [ActionOutcome.Retry]（队列会兜住），但**强烈建议自己 catch
     * 并显式分类**：只有你知道 404 是「作品被删了，别再试」而 429 是「慢点，等会儿再试」。
     */
    public suspend fun execute(action: PendingAction): ActionOutcome
}
