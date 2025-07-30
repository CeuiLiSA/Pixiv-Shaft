package ceui.pixiv.paging

abstract class PagingAPIRepository<ObjectT> :
    PagingRepository<ObjectT>(),
    ProtoToHolder<ObjectT>
