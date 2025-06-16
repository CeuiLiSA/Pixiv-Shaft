package ceui.pixiv.ui.common.repo

class RemoteRepository<ValueT>(
    private val loader: suspend () -> ValueT
) : Repository<ValueT>() {

    override suspend fun load(): LoadResult<ValueT> {
        return LoadResult.REMOTE(loader())
    }
}