package ceui.lisa.core

import ceui.lisa.interfaces.ListShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * legacy 列表 repo 基类：一个「首页 + next_url 翻页」的远端列表，响应形如 [ListShow]。
 *
 * 协程契约（二期去 Rx 后）：
 * - [initApi] / [initNextApi]：发请求拿原始响应（suspend，Retrofit 自己切 IO）；`initNextApi`
 *   返回 null 表示没有翻页（如 SelectTagRepo）。
 * - [loadFirst] / [loadNext]：取数后在 IO 上过一遍 [mapper]（屏蔽过滤 / 自动勾选等，可能读 DB），
 *   消费方一般用这两个。
 * - [mapper] 惰性创建并缓存一次。旧基类在构造器里调它（早于子类属性初始化）；子类 mapper 里
 *   用到的状态（如 SelectTagRepo.listTag）都是在 apply 时才读的，惰性化不改变行为。
 */
abstract class RemoteRepo<Response : ListShow<*>> : BaseRepo() {

    private val mapperFn: ResponseMapper<Response> by lazy { mapper() }

    var nextUrl: String = ""

    abstract suspend fun initApi(): Response?

    abstract suspend fun initNextApi(): Response?

    open fun mapper(): ResponseMapper<Response> = Mapper()

    suspend fun loadFirst(): Response? = initApi()?.let { applyMapper(it) }

    suspend fun loadNext(): Response? = initNextApi()?.let { applyMapper(it) }

    private suspend fun applyMapper(resp: Response): Response =
        withContext(Dispatchers.IO) { mapperFn.apply(resp) }

    open fun hasEffectiveUserFollowStatus(): Boolean = true
}
