package ceui.pixiv.ui.user

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.RecordType

class MineProfileVM(private val db: AppDatabase) : ViewModel() {

    val historyCount: LiveData<Int> = db.generalDao().getCountByRecordTypes(
        listOf(
            RecordType.VIEW_ILLUST_HISTORY,
            RecordType.VIEW_NOVEL_HISTORY,
            RecordType.VIEW_USER_HISTORY
        )
    )

    val favoriteUserCount: LiveData<Int> = db.generalDao().getCountByRecordTypes(
        listOf(
            RecordType.FAVORITE_USER,
        )
    )
}