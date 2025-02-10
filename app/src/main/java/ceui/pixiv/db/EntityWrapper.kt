package ceui.pixiv.db

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import timber.log.Timber

object EntityWrapper {

    fun visitIllust(context: Context, illust: Illust) {
        MainScope().launch(Dispatchers.IO) {
            try {
                val json = Shaft.sGson.toJson(illust)
                val entity = GeneralEntity(illust.id, json, EntityType.ILLUST, RecordType.VIEW_ILLUST_HISTORY)
                AppDatabase.getAppDatabase(context).generalDao().insert(entity)
                Timber.d("EntityWrapper visitIllust done ${illust.title}")
            } catch (ex: Exception) {
                Timber.e(ex)
            }
        }
    }

    fun visitNovel(context: Context, novel: Novel) {
        MainScope().launch(Dispatchers.IO) {
            try {
                val json = Shaft.sGson.toJson(novel)
                val entity = GeneralEntity(novel.id, json, EntityType.NOVEL, RecordType.VIEW_NOVEL_HISTORY)
                AppDatabase.getAppDatabase(context).generalDao().insert(entity)
                Timber.d("EntityWrapper visitNovel done ${novel.title}")
            } catch (ex: Exception) {
                Timber.e(ex)
            }
        }
    }

    fun visitUser(context: Context, user: User) {
        MainScope().launch(Dispatchers.IO) {
            try {
                val json = Shaft.sGson.toJson(user)
                val entity = GeneralEntity(user.id, json, EntityType.USER, RecordType.VIEW_USER_HISTORY)
                AppDatabase.getAppDatabase(context).generalDao().insert(entity)
                Timber.d("EntityWrapper visitUser done ${user.name}")
            } catch (ex: Exception) {
                Timber.e(ex)
            }
        }
    }
}