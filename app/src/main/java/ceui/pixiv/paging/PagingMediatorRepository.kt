package ceui.pixiv.paging

import ceui.pixiv.db.GeneralEntity

abstract class PagingMediatorRepository<ObjectT> :
    PagingRepository<ObjectT>(),
    ProtoToHolder<GeneralEntity> {

    abstract val recordType: Int
}
